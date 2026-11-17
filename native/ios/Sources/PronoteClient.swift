import Foundation

struct PronoteHTTPResponse: Sendable {
    var data: Data
    var status: Int
}

protocol PronoteTransport: Sendable {
    func request(url: URL, method: String, body: Data?, headers: [String: String]) async throws -> PronoteHTTPResponse
}

final class SameOriginRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let origin: URL
    init(origin: URL) { self.origin = origin }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        let permitted = request.url.map { PronoteURL.sameOrigin($0, origin) } ?? false
        completionHandler(permitted ? request : nil)
    }
}

actor URLSessionPronoteTransport: PronoteTransport {
    private let session: URLSession
    private let origin: URL

    init(origin: URL) {
        self.origin = origin
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 25
        configuration.timeoutIntervalForResource = 45
        configuration.httpShouldSetCookies = true
        self.session = URLSession(configuration: configuration, delegate: SameOriginRedirectDelegate(origin: origin), delegateQueue: nil)
    }

    func request(url: URL, method: String, body: Data?, headers: [String: String]) async throws -> PronoteHTTPResponse {
        guard PronoteURL.sameOrigin(url, origin) else { throw PronoteFailure.invalid("origin", "Une requête hors de l’établissement a été refusée.") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw PronoteFailure.invalid("http", "Réponse réseau inattendue.") }
        guard data.count <= 20 * 1024 * 1024 else { throw PronoteFailure.invalid("size", "Réponse Pronote trop volumineuse.") }
        return .init(data: data, status: http.statusCode)
    }
}

enum PronoteURL {
    /// Accepte une saisie approximative (sans https, sans page, avec #/?…) et la ramène à l’espace web officiel.
    static func normalized(_ input: String, defaultPage: String = "eleve.html") -> String {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return text }
        if !text.lowercased().hasPrefix("https://") && !text.lowercased().hasPrefix("http://") { text = "https://" + text }
        if text.lowercased().hasPrefix("http://") { text = "https://" + text.dropFirst("http://".count) }
        guard var components = URLComponents(string: text) else { return text }
        components.query = nil; components.fragment = nil
        var path = components.path
        let last = (path as NSString).lastPathComponent.lowercased()
        if last.hasSuffix(".html") {
            if last.hasPrefix("mobile.") { path = (path as NSString).deletingLastPathComponent + "/" + String(last.dropFirst("mobile.".count)) }
        } else {
            if !path.lowercased().contains("/pronote") { path = "/pronote" }
            path = path.hasSuffix("/") ? path + defaultPage : path + "/" + defaultPage
        }
        components.path = path
        return components.string ?? text
    }

    static func validated(_ string: String) throws -> URL {
        guard let url = URL(string: string.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme == "https", let host = url.host?.lowercased(),
              host.hasSuffix(".index-education.net"), url.user == nil, url.password == nil,
              url.port == nil || url.port == 443,
              ["eleve.html", "parent.html", "mobile.eleve.html", "mobile.parent.html"].contains(url.lastPathComponent) else {
            throw PronoteFailure.invalid("url", "Utilise l’adresse HTTPS de ton espace Élèves ou Parents, hébergé sur index-education.net.")
        }
        return url
    }

    static func sameOrigin(_ lhs: URL, _ rhs: URL) -> Bool {
        lhs.scheme == rhs.scheme && lhs.host?.lowercased() == rhs.host?.lowercased() && (lhs.port ?? 443) == (rhs.port ?? 443)
    }

    static func origin(_ url: URL) -> String { "https://" + (url.host ?? "") + (url.port.map { $0 == 443 ? "" : ":\($0)" } ?? "") }

    static func mobile(_ input: URL) throws -> URL {
        var url = input
        if !url.lastPathComponent.hasPrefix("mobile.") {
            let name = "mobile." + url.lastPathComponent
            url.deleteLastPathComponent(); url.appendPathComponent(name)
        }
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { throw PronoteFailure.invalid("url", "Adresse de connexion invalide.") }
        components.queryItems = [URLQueryItem(name: "fd", value: "1"), URLQueryItem(name: "bydlg", value: "A6ABB224-12DD-4E31-AD3E-8A39A1C2C335"), URLQueryItem(name: "login", value: "true")]
        components.fragment = nil
        guard let result = components.url else { throw PronoteFailure.invalid("url", "Adresse de connexion invalide.") }
        return result
    }
}

enum ProtocolJSON {
    static func object(_ value: Any?) -> [String: Any] { value as? [String: Any] ?? [:] }
    static func unwrap(_ value: Any?) -> Any? { (value as? [String: Any])?["V"] ?? value }
    static func list(_ value: Any?) -> [[String: Any]] { unwrap(value) as? [[String: Any]] ?? [] }
    static func string(_ value: Any?) -> String { value as? String ?? (value as? NSNumber)?.stringValue ?? "" }
    static func integer(_ value: Any?) -> Int { (value as? NSNumber)?.intValue ?? Int(string(value)) ?? 0 }
    static func bool(_ value: Any?) -> Bool { (value as? Bool) ?? ["true", "1"].contains(string(value).lowercased()) }

    static func parseStart(_ html: String) throws -> [String: Any] {
        let expression = try NSRegularExpression(pattern: #"\bStart\s*\(\s*(\{[^}]*\})\s*\)"#)
        let range = NSRange(html.startIndex..., in: html)
        guard let match = expression.firstMatch(in: html, range: range), let bodyRange = Range(match.range(at: 1), in: html) else { throw PronoteFailure.invalid("html", "Cette page demande une connexion officielle ou le format Pronote a changé.") }
        let body = String(html[bodyRange])
        let attribute = try NSRegularExpression(pattern: #"(?:\{|,)\s*['"]?([\w]+)['"]?\s*:\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([^,}]+))"#)
        var result: [String: Any] = [:]
        for match in attribute.matches(in: body, range: NSRange(body.startIndex..., in: body)) {
            guard let keyRange = Range(match.range(at: 1), in: body) else { continue }
            var raw = ""
            for index in 2...4 { if let valueRange = Range(match.range(at: index), in: body) { raw = String(body[valueRange]).trimmingCharacters(in: .whitespacesAndNewlines); break } }
            let key = String(body[keyRange])
            if raw == "true" { result[key] = true }
            else if raw == "false" { result[key] = false }
            else if let number = Int(raw) { result[key] = number }
            else { result[key] = raw.replacingOccurrences(of: #"\'"#, with: "'").replacingOccurrences(of: #"\""#, with: #"""#) }
        }
        guard result["h"] != nil, result["a"] != nil else { throw PronoteFailure.invalid("html", "Identifiant de session Pronote introuvable.") }
        return result
    }

    static func dateString(_ raw: Any?) -> String {
        let value = string(unwrap(raw))
        let parts = value.split(separator: " ", maxSplits: 1).map(String.init)
        let date = (parts.first ?? "").split(separator: "/").map(String.init)
        guard date.count == 3 else { return "" }
        return "\(date[2])-\(date[1])-\(date[0])T\(parts.count > 1 ? parts[1] : "00:00:00")"
    }
}

actor PronoteClient {
    enum LoginMode { case qr, token, demo }
    static let readFunctions: Set<String> = ["FonctionParametres", "Identification", "Authentification", "ParametresUtilisateur", "PageInfosPerso", "Navigation", "PageEmploiDuTemps"]
    private let transport: any PronoteTransport
    private var credentials: PronoteCredentials
    private var attributes: [String: Any] = [:]
    private var key = ProtocolCrypto.md5(Data())
    private var iv = ProtocolCrypto.zeroIV
    private var order = 1
    private var compress = false
    private var encrypt = false
    private var root = URL(string: "https://demo.index-education.net/pronote")!
    private var parameters: [String: Any] = [:]
    private var selectedResource: [String: Any] = [:]
    private var childResources: [[String: Any]] = []
    private var firstMonday = ""

    init(credentials: PronoteCredentials, transport: (any PronoteTransport)? = nil) throws {
        let origin = try PronoteURL.validated(credentials.url)
        self.credentials = credentials
        self.transport = transport ?? URLSessionPronoteTransport(origin: origin)
    }

    static func credentials(qr: PronoteQRData, pin: String) throws -> PronoteCredentials {
        let url = try PronoteURL.validated(qr.url)
        guard pin.count == 4, pin.allSatisfy({ $0.isASCII && $0.isNumber }) else { throw PronoteFailure.invalid("pin", "Le code temporaire doit comporter quatre chiffres.") }
        let key = ProtocolCrypto.md5(Data(pin.utf8))
        let login = try ProtocolCrypto.decrypt(ProtocolCrypto.unhex(qr.login), key: key, iv: ProtocolCrypto.zeroIV)
        let password = try ProtocolCrypto.decrypt(ProtocolCrypto.unhex(qr.jeton), key: key, iv: ProtocolCrypto.zeroIV)
        guard let username = String(data: login, encoding: .utf8), let token = String(data: password, encoding: .utf8) else { throw PronoteFailure.invalid("qr", "Le QR code ne peut pas être lu.") }
        return .init(url: try PronoteURL.mobile(url).absoluteString, username: username, password: token, uuid: UUID().uuidString, clientIdentifier: nil)
    }

    func credentialSnapshot() -> PronoteCredentials { credentials }

    func login(mode: LoginMode, persist: (@Sendable (PronoteCredentials) async throws -> Void)? = nil) async throws -> PronoteAccount {
        order = 1; key = ProtocolCrypto.md5(Data()); iv = ProtocolCrypto.zeroIV
        let url = try PronoteURL.validated(credentials.url)
        let page = try await request(url: url, method: "GET")
        guard let html = String(data: page, encoding: .utf8) else { throw PronoteFailure.invalid("html", "La page Pronote est illisible.") }
        attributes = try ProtocolJSON.parseStart(html)
        root = url.deletingLastPathComponent()
        compress = ProtocolJSON.bool(attributes["CoA"])
        encrypt = ProtocolJSON.bool(attributes["CrA"])
        let temporaryIV = try ProtocolCrypto.randomBytes(16)
        let initialization = ProtocolJSON.bool(attributes["http"]) ? try ProtocolCrypto.encryptInitializationIV(temporaryIV) : temporaryIV
        var initialData: [String: Any] = ["Uuid": initialization.base64EncodedString()]
        initialData["identifiantNav"] = credentials.clientIdentifier as Any? ?? NSNull()
        parameters = try await call("FonctionParametres", data: initialData, changeIV: ProtocolCrypto.md5(temporaryIV))
        credentials.clientIdentifier = ProtocolJSON.string(parameters["identifiantNav"])
        firstMonday = String(ProtocolJSON.dateString(ProtocolJSON.object(parameters["General"])["PremierLundi"]).prefix(10))
        let identification = try await call("Identification", data: [
            "genreConnexion": 0, "genreEspace": ProtocolJSON.integer(attributes["a"]), "identifiant": credentials.username,
            "pourENT": false, "enConnexionAuto": false, "demandeConnexionAuto": false,
            "demandeConnexionAppliMobile": mode == .qr, "demandeConnexionAppliMobileJeton": mode == .qr,
            "enConnexionAppliMobile": mode == .token, "uuidAppliMobile": mode == .demo ? "" : credentials.uuid, "loginTokenSAV": ""
        ])
        let username = ProtocolJSON.bool(identification["modeCompLog"]) ? credentials.username.lowercased() : credentials.username
        let password = ProtocolJSON.bool(identification["modeCompMdp"]) ? credentials.password.lowercased() : credentials.password
        let hashed = ProtocolCrypto.sha256Hex(ProtocolJSON.string(identification["alea"]) + password)
        let authenticationKey = ProtocolCrypto.md5(Data((username + hashed).utf8))
        let challenge = try ProtocolCrypto.encrypt(Data(ProtocolJSON.string(identification["challenge"]).utf8), key: authenticationKey, iv: iv)
        let authenticated = try await call("Authentification", data: ["connexion": 0, "challenge": ProtocolCrypto.hex(challenge), "espace": ProtocolJSON.integer(attributes["a"])])
        guard let encryptedKey = authenticated["cle"] as? String else { throw PronoteFailure.invalid("login", "La connexion Pronote a été refusée.") }
        let keyBytes = try ProtocolCrypto.decrypt(ProtocolCrypto.unhex(encryptedKey), key: authenticationKey, iv: iv)
        guard let keyText = String(data: keyBytes, encoding: .utf8) else { throw PronoteFailure.invalid("crypto", "Clé de session incompatible.") }
        let numbers = keyText.split(separator: ",").compactMap { UInt8($0) }
        guard !numbers.isEmpty else { throw PronoteFailure.invalid("crypto", "Clé de session vide.") }
        key = ProtocolCrypto.md5(Data(numbers))
        if let token = authenticated["jetonConnexionAppliMobile"] as? String { credentials.password = token; try await persist?(credentials) }
        if let actionText = ProtocolJSON.unwrap(authenticated["actionsDoubleAuth"]) as? String,
           let actionData = actionText.data(using: .utf8),
           let actions = try? JSONSerialization.jsonObject(with: actionData) as? [Int],
           actions.contains(3) || actions.contains(5) {
            throw PronoteFailure.invalid("mfa", "Une vérification de sécurité supplémentaire doit être terminée dans Pronote.")
        }
        let user = try await call("ParametresUtilisateur")
        let resource = ProtocolJSON.object(user["ressource"])
        childResources = ProtocolJSON.list(resource["listeRessources"])
        selectedResource = childResources.first ?? resource
        if mode == .qr {
            let personal = try await call("PageInfosPerso", tab: 49)
            if ProtocolJSON.integer(ProtocolJSON.object(personal["securisation"])["mode"]) != 0 {
                return try await login(mode: .token, persist: persist)
            }
        }
        return .init(name: ProtocolJSON.string(resource["L"]), children: childResources.map { .init(id: ProtocolJSON.string($0["N"]), name: ProtocolJSON.string($0["L"])) }, selectedChildId: childResources.first.map { ProtocolJSON.string($0["N"]) })
    }

    func timetable(days: Int = 14, childId: String? = nil) async throws -> [PronoteLesson] {
        if let childId {
            guard let child = childResources.first(where: { ProtocolJSON.string($0["N"]) == childId }) else { throw PronoteFailure.invalid("child", "Enfant introuvable.") }
            selectedResource = child
        }
        let calendar = Self.schoolCalendar()
        let today = calendar.startOfDay(for: Date())
        guard let end = calendar.date(byAdding: .day, value: min(14, max(1, days)) - 1, to: today), let monday = Self.day(firstMonday) else { throw PronoteFailure.invalid("date", "Le calendrier scolaire est illisible.") }
        let week: (Date) -> Int = { 1 + (calendar.dateComponents([.day], from: monday, to: $0).day ?? 0) / 7 }
        let firstWeek = week(today), lastWeek = week(end)
        guard firstWeek <= lastWeek, lastWeek - firstWeek < 5 else { throw PronoteFailure.invalid("date", "Période scolaire invalide.") }
        let formatter = Self.dayFormatter()
        let firstDay = formatter.string(from: today), lastDay = formatter.string(from: end)
        var result: [PronoteLesson] = []
        for number in firstWeek...lastWeek {
            let data = try await call("PageEmploiDuTemps", data: [
                "ressource": selectedResource, "Ressource": selectedResource, "avecAbsencesEleve": false,
                "avecConseilDeClasse": true, "estEDTPermanence": false, "avecAbsencesRessource": true,
                "avecDisponibilites": true, "avecInfosPrefsGrille": true, "NumeroSemaine": number, "numeroSemaine": number
            ], tab: 16)
            for raw in ProtocolJSON.list(data["ListeCours"]) {
                let start = ProtocolJSON.dateString(raw["DateDuCours"])
                guard !start.isEmpty, String(start.prefix(10)) >= firstDay, String(start.prefix(10)) <= lastDay else { continue }
                let contents = ProtocolJSON.list(raw["ListeContenus"])
                let labels: (Int) -> String = { type in contents.filter { ProtocolJSON.integer($0["G"]) == type }.map { ProtocolJSON.string($0["L"]) }.joined(separator: ", ") }
                var ending = ProtocolJSON.dateString(raw["DateDuCoursFin"])
                if ending.isEmpty {
                    let times = ProtocolJSON.list(ProtocolJSON.object(parameters["General"])["ListeHeuresFin"])
                    if times.count > 1 {
                        var place = ProtocolJSON.integer(raw["place"]) % (times.count - 1) + ProtocolJSON.integer(raw["duree"]) - 1
                        if place > times.count { place %= times.count - 1 }
                        if let time = times.first(where: { ProtocolJSON.integer($0["G"]) == place }) {
                            let parts = ProtocolJSON.string(time["L"]).split(separator: "h")
                            if parts.count == 2, let hour = Int(parts[0]), parts[1].count == 2 { ending = String(start.prefix(10)) + "T" + String(format: "%02d", hour) + ":" + parts[1] + ":00" }
                        }
                    }
                }
                result.append(.init(id: "\(ProtocolJSON.string(raw["N"]))-\(start)-\(ProtocolJSON.integer(raw["P"]))", subject: labels(16).isEmpty ? "Cours" : labels(16), start: start, end: ending, teacher: labels(3), room: labels(17), cancelled: ProtocolJSON.bool(raw["estAnnule"])))
            }
        }
        return result.sorted { $0.start < $1.start }
    }

    private func request(url: URL, method: String, body: Data? = nil) async throws -> Data {
        let response = try await transport.request(url: url, method: method, body: body, headers: ["User-Agent": "iPhone", "Accept": method == "GET" ? "text/html" : "application/json", "Content-Type": "application/json"])
        guard (200..<300).contains(response.status) else { throw PronoteFailure.invalid("http", "Pronote a répondu avec le statut \(response.status). Réouvre la page officielle si nécessaire.") }
        return response.data
    }

    private func call(_ function: String, data: [String: Any]? = nil, tab: Int? = nil, changeIV: Data? = nil) async throws -> [String: Any] {
        guard Self.readFunctions.contains(function) else { throw PronoteFailure.invalid("readonly", "Opération refusée : le POC lit uniquement les données scolaires.") }
        var secured: [String: Any] = [:]
        if let tab {
            var signature: [String: Any] = ["onglet": tab]
            if !childResources.isEmpty { signature["membre"] = ["N": selectedResource["N"] ?? "", "G": 4] }
            secured["Signature"] = signature
        }
        if let data { secured["data"] = data }
        var payload: Any = secured
        var serialized = try JSONSerialization.data(withJSONObject: secured)
        if compress {
            serialized = try ProtocolCrypto.deflateRaw(Data(ProtocolCrypto.hex(serialized).utf8))
            payload = ProtocolCrypto.hex(serialized).uppercased()
        }
        if encrypt { payload = ProtocolCrypto.hex(try ProtocolCrypto.encrypt(serialized, key: key, iv: iv)).uppercased() }
        let requestNumber = ProtocolCrypto.hex(try ProtocolCrypto.encrypt(Data(String(order).utf8), key: key, iv: iv))
        let body: [String: Any] = ["session": ProtocolJSON.integer(attributes["h"]), "no": requestNumber, "id": function, "dataSec": payload]
        let url = root.appendingPathComponent("appelfonction/\(ProtocolJSON.string(attributes["a"]))/\(ProtocolJSON.string(attributes["h"]))/\(requestNumber)")
        let response = try await request(url: url, method: "POST", body: JSONSerialization.data(withJSONObject: body))
        order += 2
        guard let json = try JSONSerialization.jsonObject(with: response) as? [String: Any] else { throw PronoteFailure.invalid("json", "Réponse Pronote invalide.") }
        if let error = json["Erreur"] as? [String: Any] { throw PronoteFailure.invalid("pronote-\(ProtocolJSON.string(error["G"]))", "Pronote a refusé la requête. Réouvre la connexion sans multiplier les tentatives.") }
        if let changeIV { iv = changeIV }
        var result: Any? = json["dataSec"]
        if encrypt || compress {
            guard let encrypted = result as? String else { throw PronoteFailure.invalid("json", "Réponse chiffrée inattendue.") }
            var bytes = try ProtocolCrypto.unhex(encrypted)
            if encrypt { bytes = try ProtocolCrypto.decrypt(bytes, key: key, iv: iv) }
            if compress { bytes = try ProtocolCrypto.inflateRaw(bytes) }
            result = try JSONSerialization.jsonObject(with: bytes)
        }
        let wrapper = ProtocolJSON.object(result)
        return ProtocolJSON.object(wrapper["data"] ?? wrapper)
    }

    private static func schoolCalendar() -> Calendar { var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Europe/Paris")!; return calendar }
    private static func dayFormatter() -> DateFormatter { let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = TimeZone(identifier: "Europe/Paris"); formatter.dateFormat = "yyyy-MM-dd"; return formatter }
    private static func day(_ value: String) -> Date? { dayFormatter().date(from: value) }
}
