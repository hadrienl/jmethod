import Foundation

struct PronoteHTTPResponse: Sendable {
    var data: Data
    var status: Int
}

protocol PronoteTransport: Sendable {
    func request(url: URL, method: String, body: Data?, headers: [String: String]) async throws -> PronoteHTTPResponse
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

    init(origin: URL) {
        self.origin = origin
        let configuration = URLSessionConfiguration.ephemeral
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
}
