import Foundation

/// Ce que la session attend d'un client Pronote (remplaçable dans les tests).
protocol PronoteSessionClient: Actor {
    func login(mode: PronoteClient.LoginMode, persist: (@Sendable (PronoteCredentials) async throws -> Void)?) async throws -> PronoteAccount
    func timetable(days: Int, childId: String?, now: Date) async throws -> [PronoteLesson]
    func credentialSnapshot() -> PronoteCredentials
    func notebook(today: String, childId: String?) async throws -> NotebookRead
    /// - Parameter fileName: `L` brut de la pièce jointe (même vide).
    func download(attachmentId: String, fileName: String, session: UUID, to destination: URL) async throws
    /// Déconnexion : plus aucun téléchargement de cette session.
    func invalidate()
}

/// Lecture du cahier de textes et session qui l'a produite : un téléchargement n'est permis que dans cette session.
struct NotebookRead: Sendable {
    var snapshot: NotebookSnapshot
    var session: UUID
}

extension PronoteSessionClient {
    func notebook(today: String, childId: String?) async throws -> NotebookRead {
        throw PronoteFailure.invalid("notebook-unavailable", NotebookRules.unavailable)
    }

    func download(attachmentId: String, fileName: String, session: UUID, to destination: URL) async throws {
        throw PronoteFailure.invalid("download", NotebookRules.downloadUnavailable)
    }

    func invalidate() {}
}

/// Client du protocole mobile Pronote, strictement en lecture seule.
/// Clé, IV, numéro d'ordre et ressources appartiennent à une session : ils sont réinitialisés à chaque authentification.
actor PronoteClient: PronoteSessionClient {
    enum LoginMode: Sendable { case qr, token }

    /// Liste blanche explicite des fonctions réseau. Ne pas l'étendre pour contourner une erreur.
    /// `PageCahierDeTexte` (jalon 5) est limitée par `checkCall` aux onglets 88/89 et à `domaine` sur six semaines.
    static let readFunctions: Set<String> = [
        "FonctionParametres", "Identification", "Authentification", "ParametresUtilisateur",
        "PageInfosPerso", "Navigation", "PageEmploiDuTemps", "PageCahierDeTexte",
    ]
    static let maximumDays = 14

    private let transport: any PronoteTransport
    private var credentials: PronoteCredentials
    private var attributes: [String: Any] = [:]
    private var key = ProtocolCrypto.md5(Data())
    private var iv = ProtocolCrypto.zeroIV
    private var order = 1
    private var compress = false
    private var encrypt = false
    private var root: URL
    private var parameters: [String: Any] = [:]
    private var userResource: [String: Any] = [:]
    private var selectedResource: [String: Any] = [:]
    private var childResources: [[String: Any]] = []
    private var firstMonday = ""
    /// Onglets autorisés (`listeOnglets` aplati) ; nil si Pronote ne les fournit pas.
    private(set) var authorizedTabs: Set<Int>?
    /// Matière des cours de l'emploi du temps lu, par identifiant `N` (secours du cahier de textes).
    private var lessonSubjects: [String: String] = [:]
    /// Identifiant de la session authentifiée : renouvelé à chaque connexion.
    private var sessionID = UUID()
    /// Les appels sont sérialisés : le numéro d'ordre avance de deux à chaque requête, même entre deux suspensions.
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(credentials: PronoteCredentials, transport: (any PronoteTransport)? = nil) throws {
        let url = try PronoteURL.validated(credentials.url)
        self.credentials = credentials
        self.root = url.deletingLastPathComponent()
        self.transport = transport ?? URLSessionPronoteTransport(origin: url)
    }

    /// Déchiffre le QR officiel avec le code temporaire. L'UUID d'application est stable pour ce compte.
    /// - Parameter expectedOrigin: origine de la tentative (page officielle ouverte) ; un QR d'une autre origine est refusé.
    static func credentials(qr: PronoteQRData, pin: String, uuid: String = UUID().uuidString, expectedOrigin: String? = nil) throws -> PronoteCredentials {
        let url = try PronoteURL.validated(qr.url)
        if let expectedOrigin, PronoteURL.origin(url) != expectedOrigin {
            throw PronoteFailure.invalid("qr-origin", "La connexion n’a pas pu être finalisée. Réessaie.")
        }
        guard pin.count == 4, pin.allSatisfy({ $0.isASCII && $0.isNumber }) else {
            throw PronoteFailure.invalid("pin", "La connexion n’a pas pu être finalisée. Réessaie.")
        }
        let key = ProtocolCrypto.md5(Data(pin.utf8))
        let login = try ProtocolCrypto.decrypt(ProtocolCrypto.unhex(qr.login), key: key, iv: ProtocolCrypto.zeroIV)
        let token = try ProtocolCrypto.decrypt(ProtocolCrypto.unhex(qr.jeton), key: key, iv: ProtocolCrypto.zeroIV)
        guard let username = String(data: login, encoding: .utf8), let password = String(data: token, encoding: .utf8) else {
            throw PronoteFailure.invalid("qr", "La connexion n’a pas pu être finalisée. Réessaie.")
        }
        return .init(url: try PronoteURL.mobile(url).absoluteString, username: username, password: password, uuid: uuid, clientIdentifier: nil)
    }

    func credentialSnapshot() -> PronoteCredentials { credentials }

    /// - Parameter persist: appelé immédiatement après chaque rotation du jeton, avant les requêtes suivantes.
    func login(mode: LoginMode, persist: (@Sendable (PronoteCredentials) async throws -> Void)? = nil) async throws -> PronoteAccount {
        await acquire()
        defer { release() }
        return try await performLogin(mode: mode, persist: persist)
    }

    private func performLogin(mode: LoginMode, persist: (@Sendable (PronoteCredentials) async throws -> Void)?) async throws -> PronoteAccount {
        resetSession()
        let url = try PronoteURL.validated(credentials.url)
        let page = try await request(url: url, method: "GET")
        guard let html = String(data: page, encoding: .utf8) else { throw PronoteFailure.invalid("html", "La page Pronote est illisible.") }
        attributes = try ProtocolJSON.parseStart(html)
        root = url.deletingLastPathComponent()
        compress = ProtocolJSON.bool(attributes["CoA"])
        encrypt = ProtocolJSON.bool(attributes["CrA"])

        let temporaryIV = try ProtocolCrypto.randomBytes(16)
        let initialization = ProtocolJSON.bool(attributes["http"]) ? try ProtocolCrypto.encryptInitializationIV(temporaryIV) : temporaryIV
        let initialData: [String: Any] = ["Uuid": initialization.base64EncodedString(), "identifiantNav": credentials.clientIdentifier as Any? ?? NSNull()]
        parameters = try await call("FonctionParametres", data: initialData, changeIV: ProtocolCrypto.md5(temporaryIV))
        let browserIdentifier = ProtocolJSON.string(parameters["identifiantNav"])
        if !browserIdentifier.isEmpty { credentials.clientIdentifier = browserIdentifier }
        firstMonday = String(ProtocolJSON.dateString(ProtocolJSON.object(parameters["General"])["PremierLundi"]).prefix(10))
        try Task.checkCancellation()

        let space = ProtocolJSON.integer(attributes["a"])
        let identification = try await call("Identification", data: [
            "genreConnexion": 0, "genreEspace": space, "identifiant": credentials.username,
            "pourENT": false, "enConnexionAuto": false, "demandeConnexionAuto": false,
            "demandeConnexionAppliMobile": mode == .qr, "demandeConnexionAppliMobileJeton": mode == .qr,
            "enConnexionAppliMobile": mode == .token, "uuidAppliMobile": credentials.uuid,
            "loginTokenSAV": "",
        ])
        let authenticationKey = ProtocolCodec.authenticationKey(
            username: credentials.username, password: credentials.password, alea: ProtocolJSON.string(identification["alea"]),
            lowercaseUsername: ProtocolJSON.bool(identification["modeCompLog"]), lowercasePassword: ProtocolJSON.bool(identification["modeCompMdp"])
        )
        let challenge = try ProtocolCodec.challengeResponse(ProtocolJSON.string(identification["challenge"]), key: authenticationKey, iv: iv)
        // Dernier point d'annulation : une fois l'Authentification envoyée, Pronote fait tourner le jeton. La suite va
        // jusqu'à l'enregistrement du jeton renouvelé, même si la tâche appelante est annulée entre-temps
        // (« Ouvrir sans attendre », nouvelle tentative, fin de l'actualisation en arrière-plan).
        try Task.checkCancellation()
        let doubleAuthentication = try await Task {
            try await self.authenticate(challenge: challenge, space: space, authenticationKey: authenticationKey, mode: mode, persist: persist)
        }.value
        if doubleAuthentication {
            throw PronoteFailure.invalid("mfa", "Pronote demande une vérification de sécurité : reconnecte-toi pour la terminer.")
        }
        try Task.checkCancellation()

        let user = try await call("ParametresUtilisateur")
        userResource = ProtocolJSON.object(user["ressource"])
        authorizedTabs = user["listeOnglets"].map(Self.flattenTabs)
        childResources = ProtocolJSON.list(userResource["listeRessources"])
        selectedResource = childResources.first ?? userResource

        // Compte sécurisé : une seconde connexion par jeton est attendue après une connexion par QR.
        if mode == .qr {
            let personal = try await call("PageInfosPerso", tab: 49)
            if ProtocolJSON.integer(ProtocolJSON.object(personal["securisation"])["mode"]) != 0 {
                return try await performLogin(mode: .token, persist: persist)
            }
        }
        return .init(
            name: ProtocolJSON.string(userResource["L"]),
            children: childResources.map { .init(id: ProtocolJSON.string($0["N"]), name: ProtocolJSON.string($0["L"])) }
        )
    }

    /// Authentification, clé de session et enregistrement immédiat du jeton renouvelé, sans point d'annulation.
    /// - Returns: vrai si Pronote exige une double authentification (actions 3 ou 5).
    private func authenticate(challenge: String, space: Int, authenticationKey: Data, mode: LoginMode,
                              persist: (@Sendable (PronoteCredentials) async throws -> Void)?) async throws -> Bool {
        let authenticated = try await call("Authentification", data: ["connexion": 0, "challenge": challenge, "espace": space])
        guard let encryptedKey = authenticated["cle"] as? String else {
            throw PronoteFailure.invalid("login", mode == .token
                ? "Pronote demande de te reconnecter."
                : "Pronote a refusé la connexion. Réessaie.")
        }
        key = try ProtocolCodec.sessionKey(encryptedCle: encryptedKey, authenticationKey: authenticationKey, iv: iv)
        // Le jeton mobile tourne : il est enregistré avant toute autre requête susceptible d'échouer.
        if let token = authenticated["jetonConnexionAppliMobile"] as? String, !token.isEmpty {
            credentials.password = token
            try await persist?(credentials)
        }
        guard let actionText = ProtocolJSON.unwrap(authenticated["actionsDoubleAuth"]) as? String,
              let actionData = actionText.data(using: .utf8),
              let actions = try? JSONSerialization.jsonObject(with: actionData) as? [Int] else { return false }
        return actions.contains(3) || actions.contains(5)
    }

    /// Déconnexion : les téléchargements en attente de ce client sont refusés (identifiant de session renouvelé).
    func invalidate() {
        sessionID = UUID()
    }

    /// Lit les cours des `days` prochains jours (aujourd'hui compris) au calendrier de l'établissement.
    func timetable(days: Int = maximumDays, childId: String? = nil, now: Date = Date()) async throws -> [PronoteLesson] {
        await acquire()
        defer { release() }
        try select(childId: childId)
        let firstDay = SchoolDay.today(now: now)
        guard let lastDay = SchoolDay.adding(min(Self.maximumDays, max(1, days)) - 1, to: firstDay),
              let computedFirstWeek = TimetableMath.weekNumber(day: firstDay, firstMonday: firstMonday),
              let lastWeek = TimetableMath.weekNumber(day: lastDay, firstMonday: firstMonday),
              computedFirstWeek <= lastWeek, lastWeek - computedFirstWeek < 5 else {
            throw PronoteFailure.invalid("date", "Le calendrier scolaire de Pronote est illisible.")
        }
        // Avant la rentrée (semaine 1), il n'existe pas encore d'emploi du temps à lire.
        guard lastWeek >= 1 else { return [] }
        let firstWeek = max(1, computedFirstWeek)
        let grid = ProtocolJSON.list(ProtocolJSON.object(parameters["General"])["ListeHeuresFin"])
        var result: [PronoteLesson] = []
        for number in firstWeek...lastWeek {
            try Task.checkCancellation()
            let data = try await call("PageEmploiDuTemps", data: [
                "ressource": selectedResource, "Ressource": selectedResource, "avecAbsencesEleve": false,
                "avecConseilDeClasse": true, "estEDTPermanence": false, "avecAbsencesRessource": true,
                "avecDisponibilites": true, "avecInfosPrefsGrille": true, "NumeroSemaine": number, "numeroSemaine": number,
            ], tab: 16)
            for raw in ProtocolJSON.list(data["ListeCours"]) {
                let lessonId = ProtocolJSON.string(raw["N"])
                if !lessonId.isEmpty {
                    let label = ProtocolJSON.list(raw["ListeContenus"]).first { ProtocolJSON.integer($0["G"]) == 16 }.map { ProtocolJSON.string($0["L"]) } ?? ""
                    if !label.isEmpty { lessonSubjects[lessonId] = label }
                }
                if let lesson = Self.lesson(raw, grid: grid), String(lesson.start.prefix(10)) >= firstDay, String(lesson.start.prefix(10)) <= lastDay {
                    result.append(lesson)
                }
            }
        }
        return result.sorted { $0.start == $1.start ? $0.id < $1.id : $0.start < $1.start }
    }

    private func select(childId: String?) throws {
        guard let childId, !childResources.isEmpty else { return }
        guard let child = childResources.first(where: { ProtocolJSON.string($0["N"]) == childId }) else {
            throw PronoteFailure.invalid("child", "Cet enfant n’est plus rattaché au compte.")
        }
        selectedResource = child
    }

    // MARK: Cahier de textes

    /// Cahier de textes en lecture seule sur 28 jours : contenus (onglet 89) puis, s'il est autorisé, travail à faire
    /// (onglet 88). Ne crée et n'écrit rien dans Pronote.
    func notebook(today: String, childId: String?) async throws -> NotebookRead {
        await acquire()
        defer { release() }
        try select(childId: childId)
        guard let window = NotebookRules.window(today: today, firstMonday: firstMonday) else {
            throw PronoteFailure.invalid("date", "Le calendrier scolaire de Pronote est illisible.")
        }
        if let tabs = authorizedTabs, !tabs.contains(NotebookRules.contentsTab) {
            throw PronoteFailure.invalid("notebook-unavailable", NotebookRules.unavailable)
        }
        let contents = try await call("PageCahierDeTexte", data: Self.notebookData(window), tab: NotebookRules.contentsTab)
        try Task.checkCancellation()
        var homework: [String: Any]?
        if authorizedTabs?.contains(NotebookRules.homeworkTab) ?? true {
            homework = try await call("PageCahierDeTexte", data: Self.notebookData(window), tab: NotebookRules.homeworkTab)
        }
        let snapshot = NotebookRules.parse(window: window, contents: contents, homework: homework, timetableSubjects: lessonSubjects)
        return NotebookRead(snapshot: snapshot, session: sessionID)
    }

    static func notebookData(_ window: NotebookRules.Window) -> [String: Any] {
        ["domaine": ["_T": 8, "V": window.domaine]]
    }

    /// Télécharge une pièce jointe (`FichiersExternes`) avec la session et les cookies courants. L'URL est construite ici,
    /// utilisée une fois puis oubliée : elle n'est ni renvoyée, ni journalisée, ni conservée.
    func download(attachmentId: String, fileName: String, session: UUID, to destination: URL) async throws {
        await acquire()
        defer { release() }
        guard session == sessionID, NotebookRules.isDownloadableId(attachmentId) else {
            throw PronoteFailure.invalid("download", NotebookRules.downloadUnavailable)
        }
        let rootText = root.absoluteString.hasSuffix("/") ? String(root.absoluteString.dropLast()) : root.absoluteString
        let encrypted = try NotebookRules.encryptedId(attachmentId, key: key, iv: iv)
        guard let url = URL(string: NotebookRules.downloadURL(root: rootText, encryptedId: encrypted, name: fileName, session: ProtocolJSON.integer(attributes["h"]))) else {
            throw PronoteFailure.invalid("download", NotebookRules.downloadUnavailable)
        }
        let status: Int
        do {
            status = try await transport.download(url: url, headers: ["User-Agent": "iPhone"], maxBytes: NotebookRules.maxDownloadBytes, to: destination)
        } catch let failure as PronoteFailure {
            throw failure
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            // Aucun détail : la description d'une erreur réseau peut contenir l'URL.
            throw PronoteFailure.invalid("download-network", "Pronote est injoignable. Vérifie ta connexion internet, puis réessaie.")
        }
        switch status {
        case 200: return
        case 413: throw PronoteFailure.invalid("download-size", NotebookRules.downloadTooLarge)
        default: throw PronoteFailure.invalid("download", NotebookRules.downloadUnavailable)
        }
    }

    // MARK: Lecture seule

    /// Contrôle local avant tout envoi : liste blanche, aucune fonction `Saisie…`, et pour `PageCahierDeTexte`
    /// onglet 88 ou 89 avec `data` réduit à `domaine` de type 8 sur six semaines au plus.
    static func checkCall(_ function: String, tab: Int?, data: [String: Any]?) throws {
        guard readFunctions.contains(function), !function.hasPrefix("Saisie") else {
            throw PronoteFailure.invalid("readonly", "Opération refusée : J-Method lit uniquement les données scolaires.")
        }
        guard function == "PageCahierDeTexte" else { return }
        let refused = PronoteFailure.invalid("readonly", "Cette lecture du cahier de textes n’est pas autorisée.")
        guard let tab, tab == NotebookRules.homeworkTab || tab == NotebookRules.contentsTab,
              let data, data.count == 1, let domaine = data["domaine"] as? [String: Any], domaine.count == 2,
              let type = domaine["_T"] as? Int, type == 8, let range = domaine["V"] as? String else { throw refused }
        let bounds = range.dropFirst().dropLast().components(separatedBy: "..")
        guard range.hasPrefix("["), range.hasSuffix("]"), bounds.count == 2,
              bounds.allSatisfy({ (1...3).contains($0.count) && $0.allSatisfy { $0.isASCII && $0.isNumber } }),
              let first = Int(bounds[0]), let last = Int(bounds[1]),
              first >= 1, last >= first, last - first <= NotebookRules.maxWeekSpan else { throw refused }
    }

    /// `listeOnglets` aplati comme `_prepare_onglets` de pronotepy (toutes les valeurs, récursivement).
    static func flattenTabs(_ raw: Any) -> Set<Int> {
        var result = Set<Int>()
        func walk(_ value: Any) {
            switch value {
            case let list as [Any]: list.forEach(walk)
            case let object as [String: Any]: object.values.forEach(walk)
            case let number as NSNumber where CFGetTypeID(number) != CFBooleanGetTypeID(): result.insert(number.intValue)
            case let text as String: if let number = Int(text) { result.insert(number) }
            default: break
            }
        }
        walk(raw)
        return result
    }

    private func acquire() async {
        guard busy else {
            busy = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func release() {
        if waiters.isEmpty { busy = false } else { waiters.removeFirst().resume() }
    }

    /// Conversion d'un cours brut. `P` distingue les variantes d'un même créneau et fait partie de l'identifiant.
    static func lesson(_ raw: [String: Any], grid: [[String: Any]]) -> PronoteLesson? {
        let start = ProtocolJSON.dateString(raw["DateDuCours"])
        guard !start.isEmpty else { return nil }
        let contents = ProtocolJSON.list(raw["ListeContenus"])
        let labels: (Int) -> String = { type in
            contents.filter { ProtocolJSON.integer($0["G"]) == type }.map { ProtocolJSON.string($0["L"]) }.filter { !$0.isEmpty }.joined(separator: ", ")
        }
        var end = ProtocolJSON.dateString(raw["DateDuCoursFin"])
        if end.isEmpty, let time = TimetableMath.endTime(place: ProtocolJSON.integer(raw["place"]), duration: ProtocolJSON.integer(raw["duree"]), grid: grid) {
            end = String(start.prefix(10)) + "T" + time
        }
        let subject = labels(16)
        return PronoteLesson(
            id: "\(ProtocolJSON.string(raw["N"]))-\(start)-\(ProtocolJSON.integer(raw["P"]))",
            subject: subject.isEmpty ? "Cours" : subject,
            start: start,
            end: end,
            teacher: labels(3),
            room: labels(17),
            cancelled: ProtocolJSON.bool(raw["estAnnule"])
        )
    }

    /// Signature d'un appel lié à un onglet ; pour un parent, elle porte l'enfant sélectionné.
    static func signature(tab: Int, selectedResource: [String: Any], hasChildren: Bool) -> [String: Any] {
        var signature: [String: Any] = ["onglet": tab]
        if hasChildren { signature["membre"] = ["N": selectedResource["N"] ?? "", "G": 4] }
        return signature
    }

    private func resetSession() {
        order = 1
        key = ProtocolCrypto.md5(Data())
        iv = ProtocolCrypto.zeroIV
        attributes = [:]
        parameters = [:]
        userResource = [:]
        selectedResource = [:]
        childResources = []
        firstMonday = ""
        authorizedTabs = nil
        lessonSubjects = [:]
        sessionID = UUID()
    }

    private func request(url: URL, method: String, body: Data? = nil) async throws -> Data {
        let response = try await transport.request(url: url, method: method, body: body, headers: [
            "User-Agent": "iPhone",
            "Accept": method == "GET" ? "text/html" : "application/json",
            "Content-Type": "application/json",
        ])
        guard (200..<300).contains(response.status) else {
            throw PronoteFailure.invalid("http-\(response.status)", "Pronote ne répond pas correctement pour le moment (\(response.status)). Réessaie plus tard.")
        }
        return response.data
    }

    private func call(_ function: String, data: [String: Any]? = nil, tab: Int? = nil, changeIV: Data? = nil) async throws -> [String: Any] {
        try Self.checkCall(function, tab: tab, data: data)
        if function == "PageCahierDeTexte", let tab, let tabs = authorizedTabs, !tabs.contains(tab) {
            throw PronoteFailure.invalid("notebook-unavailable", NotebookRules.unavailable)
        }
        var secured: [String: Any] = [:]
        if let tab { secured["Signature"] = Self.signature(tab: tab, selectedResource: selectedResource, hasChildren: !childResources.isEmpty) }
        if let data { secured["data"] = data }
        let serialized = try JSONSerialization.data(withJSONObject: secured)
        let payload = try ProtocolCodec.requestDataSec(serialized, compress: compress, encrypt: encrypt, key: key, iv: iv)
        let number = try ProtocolCodec.orderHex(order, key: key, iv: iv)
        let body: [String: Any] = ["session": ProtocolJSON.integer(attributes["h"]), "no": number, "id": function, "dataSec": payload]
        let url = root.appendingPathComponent("appelfonction/\(ProtocolJSON.string(attributes["a"]))/\(ProtocolJSON.string(attributes["h"]))/\(number)")
        let response = try await request(url: url, method: "POST", body: JSONSerialization.data(withJSONObject: body))
        order += 2
        guard let json = try? JSONSerialization.jsonObject(with: response) as? [String: Any] else {
            throw PronoteFailure.invalid("json", "Réponse Pronote invalide.")
        }
        if let error = json["Erreur"] as? [String: Any] {
            let code = ProtocolJSON.string(error["G"])
            let message = switch code {
            case "10", "22": "La session Pronote a expiré."
            case "25": PronoteThrottle.blockedMessage
            default: "Pronote a refusé la requête. Réessaie plus tard sans multiplier les tentatives."
            }
            throw PronoteFailure.invalid("pronote-\(code)", message)
        }
        if let changeIV { iv = changeIV }
        let decoded = try ProtocolCodec.responseDataSec(json["dataSec"], compress: compress, encrypt: encrypt, key: key, iv: iv)
        return ProtocolJSON.object(decoded["data"] ?? decoded)
    }
}
