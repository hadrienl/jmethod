import Foundation

/// Ce que la session attend d'un client Pronote (remplaçable dans les tests).
protocol PronoteSessionClient: Actor {
    func login(mode: PronoteClient.LoginMode, persist: (@Sendable (PronoteCredentials) async throws -> Void)?) async throws -> PronoteAccount
    func timetable(days: Int, childId: String?, now: Date) async throws -> [PronoteLesson]
    func credentialSnapshot() -> PronoteCredentials
}

/// Client du protocole mobile Pronote, strictement en lecture seule.
/// Clé, IV, numéro d'ordre et ressources appartiennent à une session : ils sont réinitialisés à chaque authentification.
actor PronoteClient: PronoteSessionClient {
    enum LoginMode: Sendable { case qr, token }

    /// Liste blanche explicite des fonctions réseau. Ne pas l'étendre pour contourner une erreur.
    static let readFunctions: Set<String> = [
        "FonctionParametres", "Identification", "Authentification", "ParametresUtilisateur",
        "PageInfosPerso", "Navigation", "PageEmploiDuTemps",
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

    init(credentials: PronoteCredentials, transport: (any PronoteTransport)? = nil) throws {
        let url = try PronoteURL.validated(credentials.url)
        self.credentials = credentials
        self.root = url.deletingLastPathComponent()
        self.transport = transport ?? URLSessionPronoteTransport(origin: url)
    }

    /// Déchiffre le QR officiel avec le code temporaire. L'UUID d'application est stable pour ce compte.
    static func credentials(qr: PronoteQRData, pin: String, uuid: String = UUID().uuidString) throws -> PronoteCredentials {
        let url = try PronoteURL.validated(qr.url)
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
        if let actionText = ProtocolJSON.unwrap(authenticated["actionsDoubleAuth"]) as? String,
           let actionData = actionText.data(using: .utf8),
           let actions = try? JSONSerialization.jsonObject(with: actionData) as? [Int],
           actions.contains(3) || actions.contains(5) {
            throw PronoteFailure.invalid("mfa", "Pronote demande une vérification de sécurité : reconnecte-toi pour la terminer.")
        }
        try Task.checkCancellation()

        let user = try await call("ParametresUtilisateur")
        userResource = ProtocolJSON.object(user["ressource"])
        childResources = ProtocolJSON.list(userResource["listeRessources"])
        selectedResource = childResources.first ?? userResource

        // Compte sécurisé : une seconde connexion par jeton est attendue après une connexion par QR.
        if mode == .qr {
            let personal = try await call("PageInfosPerso", tab: 49)
            if ProtocolJSON.integer(ProtocolJSON.object(personal["securisation"])["mode"]) != 0 {
                return try await login(mode: .token, persist: persist)
            }
        }
        return .init(
            name: ProtocolJSON.string(userResource["L"]),
            children: childResources.map { .init(id: ProtocolJSON.string($0["N"]), name: ProtocolJSON.string($0["L"])) }
        )
    }

    /// Lit les cours des `days` prochains jours (aujourd'hui compris) au calendrier de l'établissement.
    func timetable(days: Int = maximumDays, childId: String? = nil, now: Date = Date()) async throws -> [PronoteLesson] {
        if let childId, !childResources.isEmpty {
            guard let child = childResources.first(where: { ProtocolJSON.string($0["N"]) == childId }) else {
                throw PronoteFailure.invalid("child", "Cet enfant n’est plus rattaché au compte.")
            }
            selectedResource = child
        }
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
                if let lesson = Self.lesson(raw, grid: grid), String(lesson.start.prefix(10)) >= firstDay, String(lesson.start.prefix(10)) <= lastDay {
                    result.append(lesson)
                }
            }
        }
        return result.sorted { $0.start == $1.start ? $0.id < $1.id : $0.start < $1.start }
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
        guard Self.readFunctions.contains(function) else {
            throw PronoteFailure.invalid("readonly", "Opération refusée : J-Method lit uniquement les données scolaires.")
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
            case "25": "Pronote bloque temporairement les connexions après trop de tentatives. Réessaie plus tard."
            default: "Pronote a refusé la requête. Réessaie plus tard sans multiplier les tentatives."
            }
            throw PronoteFailure.invalid("pronote-\(code)", message)
        }
        if let changeIV { iv = changeIV }
        let decoded = try ProtocolCodec.responseDataSec(json["dataSec"], compress: compress, encrypt: encrypt, key: key, iv: iv)
        return ProtocolJSON.object(decoded["data"] ?? decoded)
    }
}
