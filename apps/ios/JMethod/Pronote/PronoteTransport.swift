import Foundation

struct PronoteHTTPResponse: Sendable {
    var data: Data
    var status: Int
}

protocol PronoteTransport: Sendable {
    func request(url: URL, method: String, body: Data?, headers: [String: String]) async throws -> PronoteHTTPResponse
    /// `GET` d'une pièce jointe vers un fichier privé, avec les cookies de la session.
    /// - Returns: le statut HTTP, ou 413 si `maxBytes` est dépassé (le fichier partiel est alors effacé).
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
    static let maximumResponseBytes = 20 * 1024 * 1024
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
        let manager = FileManager.default
        try manager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard manager.createFile(atPath: destination.path, contents: nil, attributes: [.protectionKey: FileProtectionType.complete]) else {
            throw PronoteFailure.invalid("download-file", "Le document n’a pas pu être enregistré sur l’iPhone.")
        }
        let handle = try FileHandle(forWritingTo: destination)
        var total = 0
        var buffer = Data()
        buffer.reserveCapacity(64 * 1024)
        do {
            for try await byte in bytes {
                buffer.append(byte)
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
        return 200
    }
}
