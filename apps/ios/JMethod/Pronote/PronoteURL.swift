import Foundation

/// Espace Pronote choisi dans le formulaire.
enum PronoteAccountType: String, Codable, Sendable, CaseIterable {
    case student, parent

    var page: String { self == .student ? "eleve.html" : "parent.html" }
    var label: String { self == .student ? "Élève" : "Parent" }
}

enum PronoteURL {
    static let trustedPages: Set<String> = ["eleve.html", "parent.html"]

    /// Adresse officielle à partir de la saisie du formulaire :
    /// - un code seul (`abcd1234`, sans point, casse et espaces ignorés) devient `https://abcd1234.index-education.net/pronote/<page>` ;
    /// - un hôte seul reçoit la page du type de compte choisi ;
    /// - une adresse complète garde sa page (`eleve.html` ou `parent.html`), qui prime sur le choix et le met à jour.
    static func resolve(_ input: String, account: PronoteAccountType) -> (address: String, account: PronoteAccountType) {
        let compact = input.filter { !$0.isWhitespace }.lowercased()
        guard !compact.isEmpty else { return ("", account) }
        if !compact.contains("."), !compact.contains("/"), !compact.contains(":"),
           compact.allSatisfy({ ($0.isASCII && ($0.isLetter || $0.isNumber)) || $0 == "-" }) {
            return ("https://\(compact).index-education.net/pronote/\(account.page)", account)
        }
        let address = normalized(input, defaultPage: account.page)
        let page = URL(string: address)?.lastPathComponent.lowercased()
        let resolved: PronoteAccountType = page == "parent.html" ? .parent : page == "eleve.html" ? .student : account
        return (address, resolved)
    }

    /// Accepte une saisie approximative (hôte seul, sans https, page mobile, avec ?…#…)
    /// et la ramène à l'espace web officiel : `xxxx.index-education.net` devient
    /// `https://xxxx.index-education.net/pronote/eleve.html`.
    static func normalized(_ input: String, defaultPage: String = "eleve.html") -> String {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return text }
        let lowered = text.lowercased()
        if lowered.hasPrefix("http://") { text = "https://" + text.dropFirst("http://".count) }
        else if !lowered.hasPrefix("https://") { text = "https://" + text }
        guard var components = URLComponents(string: text) else { return text }
        components.query = nil
        components.fragment = nil
        components.scheme = "https"
        components.host = components.host?.lowercased()
        var path = components.path
        let last = (path as NSString).lastPathComponent.lowercased()
        if last.hasSuffix(".html") {
            let page = last.hasPrefix("mobile.") ? String(last.dropFirst("mobile.".count)) : last
            path = (path as NSString).deletingLastPathComponent
            path = (path.hasSuffix("/") ? path : path + "/") + page
        } else {
            if !path.lowercased().contains("/pronote") { path = "/pronote" }
            path = path.hasSuffix("/") ? path + defaultPage : path + "/" + defaultPage
        }
        components.path = path
        return components.string ?? text
    }

    /// Page de connexion directe Pronote (`?login=true`) : quand l'établissement passe par un ENT, l'adresse nue
    /// redirige vers le CAS ; le formulaire natif utilise les identifiants Pronote et vise donc cette page.
    static func directLogin(_ url: URL) -> URL {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        components.queryItems = [URLQueryItem(name: "login", value: "true")]
        components.fragment = nil
        return components.url ?? url
    }

    static func validated(_ string: String) throws -> URL {
        guard let url = URL(string: string.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme == "https", let host = url.host?.lowercased(),
              host.hasSuffix(".index-education.net"), url.user == nil, url.password == nil,
              url.port == nil || url.port == 443,
              ["eleve.html", "parent.html", "mobile.eleve.html", "mobile.parent.html"].contains(url.lastPathComponent.lowercased()) else {
            throw PronoteFailure.invalid("url", "Indique l’adresse de ton espace Pronote Élèves ou Parents, hébergé sur index-education.net.")
        }
        return url
    }

    static func sameOrigin(_ lhs: URL, _ rhs: URL) -> Bool {
        lhs.scheme == rhs.scheme && lhs.host?.lowercased() == rhs.host?.lowercased() && (lhs.port ?? 443) == (rhs.port ?? 443)
    }

    static func origin(_ url: URL) -> String {
        "https://" + (url.host?.lowercased() ?? "") + (url.port.map { $0 == 443 ? "" : ":\($0)" } ?? "")
    }

    /// Page web officielle correspondant à une adresse mobile enregistrée (pour pré-remplir le formulaire).
    static func webPage(fromMobile string: String) -> String {
        guard var components = URLComponents(string: string) else { return string }
        components.query = nil
        components.fragment = nil
        let last = (components.path as NSString).lastPathComponent
        if last.lowercased().hasPrefix("mobile.") {
            components.path = (components.path as NSString).deletingLastPathComponent + "/" + String(last.dropFirst("mobile.".count))
        }
        return components.string ?? string
    }

    static func mobile(_ input: URL) throws -> URL {
        var url = input
        if !url.lastPathComponent.lowercased().hasPrefix("mobile.") {
            let name = "mobile." + url.lastPathComponent
            url.deleteLastPathComponent()
            url.appendPathComponent(name)
        }
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { throw PronoteFailure.invalid("url", "Adresse de connexion invalide.") }
        components.queryItems = [
            URLQueryItem(name: "fd", value: "1"),
            URLQueryItem(name: "bydlg", value: "A6ABB224-12DD-4E31-AD3E-8A39A1C2C335"),
            URLQueryItem(name: "login", value: "true"),
        ]
        components.fragment = nil
        guard let result = components.url else { throw PronoteFailure.invalid("url", "Adresse de connexion invalide.") }
        return result
    }
}
