import Foundation

struct PronoteHTTPResponse: Sendable {
    var data: Data
    var status: Int
}

protocol PronoteTransport: Sendable {
    func request(url: URL, method: String, body: Data?, headers: [String: String]) async throws -> PronoteHTTPResponse
    /// `GET` d'une pièce jointe vers un fichier privé, avec les cookies de la session.
    /// - Returns: le statut HTTP, ou 413 si `maxBytes` est dépassé (le fichier partiel est alors effacé).
    /// - Throws: `http-html` si un 200 renvoie une page HTML à la place d'un fichier non HTML (fichier effacé).
    func download(url: URL, headers: [String: String], maxBytes: Int, to destination: URL) async throws -> Int
}

extension PronoteTransport {
    func download(url: URL, headers: [String: String], maxBytes: Int, to destination: URL) async throws -> Int { 404 }
}

/// Refuse toute redirection hors de l'origine de l'établissement.
/// `@unchecked Sendable` : seule propriété, `origin`, immuable.
final class SameOriginRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let origin: URL
    init(origin: URL) { self.origin = origin }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        let permitted = request.url.map { PronoteURL.sameOrigin($0, origin) } ?? false
        completionHandler(permitted ? request : nil)
    }
}

/// Transport HTTPS direct, session éphémère (cookies en mémoire seulement), taille de réponse bornée.
actor URLSessionPronoteTransport: PronoteTransport {
    /// Réponses `appelfonction` et page de connexion : 16 Mo au plus (les pièces jointes ont leur propre limite).
    static let maximumResponseBytes = 16 * 1024 * 1024
    private let session: URLSession
    private let origin: URL

    /// - Parameter protocolClasses: tests seulement (réponses simulées), jamais de proxy ni de serveur tiers.
    init(origin: URL, protocolClasses: [AnyClass]? = nil) {
        self.origin = origin
        let configuration = URLSessionConfiguration.ephemeral
        if let protocolClasses { configuration.protocolClasses = protocolClasses }
        configuration.timeoutIntervalForRequest = 25
        configuration.timeoutIntervalForResource = 45
        configuration.httpShouldSetCookies = true
        configuration.urlCache = nil
        self.session = URLSession(configuration: configuration, delegate: SameOriginRedirectDelegate(origin: origin), delegateQueue: nil)
    }

    deinit { session.invalidateAndCancel() }

    func request(url: URL, method: String, body: Data?, headers: [String: String]) async throws -> PronoteHTTPResponse {
        guard PronoteURL.sameOrigin(url, origin) else { throw PronoteFailure.invalid("origin", "Une requête hors de l’établissement a été refusée.") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw PronoteFailure.invalid("http", "Réponse réseau inattendue.") }
        if http.expectedContentLength > Int64(Self.maximumResponseBytes) { throw PronoteFailure.invalid("size", "Réponse Pronote trop volumineuse.") }
        var data = Data()
        for try await byte in bytes {
            data.append(byte)
            if data.count > Self.maximumResponseBytes { throw PronoteFailure.invalid("size", "Réponse Pronote trop volumineuse.") }
        }
        return .init(data: data, status: http.statusCode)
    }

    /// Même session (cookies), même origine ; lecture en flux bornée, écrite directement dans le cache privé.
    func download(url: URL, headers: [String: String], maxBytes: Int, to destination: URL) async throws -> Int {
        guard url.scheme == "https", PronoteURL.sameOrigin(url, origin) else {
            throw PronoteFailure.invalid("origin", "Une requête hors de l’établissement a été refusée.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { return 0 }
        guard http.statusCode == 200 else { return http.statusCode }
        if http.expectedContentLength > Int64(maxBytes) { return 413 }
        // Le nom demandé est le dernier segment de l'URL : seul un `.html`/`.htm` peut légitimement être une page.
        let expectsHTML = NotebookRules.isHTMLName(url.lastPathComponent)
        let notDocument = PronoteFailure.invalid(NotebookRules.notDocumentCode, NotebookRules.downloadNotDocument)
        let contentType = (http.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
        if !expectsHTML, contentType.hasPrefix("text/html") {
            Self.diagnose(status: http.statusCode, contentType: contentType, bytes: 0, html: true)
            throw notDocument
        }
        let manager = FileManager.default
        try manager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard manager.createFile(atPath: destination.path, contents: nil, attributes: [.protectionKey: FileProtectionType.complete]) else {
            throw PronoteFailure.invalid("download-file", "Le document n’a pas pu être enregistré sur l’iPhone.")
        }
        let handle = try FileHandle(forWritingTo: destination)
        var total = 0
        var buffer = Data()
        var head = Data()
        buffer.reserveCapacity(64 * 1024)
        do {
            for try await byte in bytes {
                buffer.append(byte)
                if head.count < 512 { head.append(byte) }
                total += 1
                if total > maxBytes {
                    try? handle.close()
                    try? manager.removeItem(at: destination)
                    return 413
                }
                if buffer.count >= 64 * 1024 {
                    try handle.write(contentsOf: buffer)
                    buffer.removeAll(keepingCapacity: true)
                }
            }
            try handle.write(contentsOf: buffer)
            try handle.close()
        } catch {
            try? handle.close()
            try? manager.removeItem(at: destination)
            throw error
        }
        // Pronote répond 200 avec sa page d'accueil quand l'adresse n'est pas celle d'un document : jamais conservée.
        let html = !expectsHTML && NotebookRules.looksLikeHTML(head)
        Self.diagnose(status: http.statusCode, contentType: contentType, bytes: total, html: html)
        if html {
            try? manager.removeItem(at: destination)
            throw notDocument
        }
        return 200
    }

    /// Diagnostic de développement seulement : statut, type MIME, taille et indicateur HTML. Jamais l'URL,
    /// l'identifiant, le nom du fichier, les cookies ni le contenu.
    private static func diagnose(status: Int, contentType: String, bytes: Int, html: Bool) {
        #if DEBUG
        let type = contentType.split(separator: ";").first.map { String($0).trimmingCharacters(in: .whitespaces) } ?? ""
        print("[JMethod] FichiersExternes status=\(status) type=\(type.isEmpty ? "-" : type) bytes=\(bytes) html=\(html)")
        #endif
    }
}
