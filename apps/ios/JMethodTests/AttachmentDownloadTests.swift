import XCTest
@testable import JMethod

/// Pièces jointes du cahier de textes : adresse identique à Android avec l'adresse mobile réellement enregistrée
/// (`?fd=1&bydlg=…&login=true`), page HTML jamais gardée comme document, réparation des fichiers déjà enregistrés.
final class AttachmentDownloadTests: XCTestCase {
    private var problems: [String] = []

    override func tearDown() {
        if !problems.isEmpty { XCTFail("\(problems.count) écart(s) :\n" + problems.prefix(20).joined(separator: "\n")) }
        problems = []
        super.tearDown()
    }

    private func check<T: Equatable>(_ actual: T, _ expected: T, _ label: String) {
        if actual != expected { problems.append("\(label) : \(actual) ≠ \(expected)") }
    }

    private func downloadVectors() throws -> [String: Any] {
        try XCTUnwrap(try SharedVectors.object("notebook-vectors.json")["download"] as? [String: Any])
    }

    /// Algorithme Android (`PronoteClient.kt`) : `credentials.url.substringBefore('?').substringBeforeLast('/')`.
    private func androidRoot(_ url: String) -> String {
        let bare = String(url.prefix { $0 != "?" })
        return bare.lastIndex(of: "/").map { String(bare[..<$0]) } ?? bare
    }

    // MARK: Racine

    func testRootDropsQueryLikeAndroid() throws {
        let download = try downloadVectors()
        let root = try XCTUnwrap(download["pronoteRoot"] as? String)
        let stored = try XCTUnwrap(download["storedPageURL"] as? String)
        let plain = try XCTUnwrap(download["pageURL"] as? String)
        for page in [stored, plain] {
            check(PronoteURL.root(try XCTUnwrap(URL(string: page))), root, "racine \(page)")
            check(androidRoot(page), root, "racine Android \(page)")
        }
        // L'adresse vraiment enregistrée après une connexion (élève et parent).
        for page in ["eleve.html", "parent.html"] {
            let mobile = try PronoteURL.mobile(try XCTUnwrap(URL(string: "https://school.index-education.net/pronote/\(page)")))
            XCTAssertNotNil(mobile.query, "l'adresse mobile garde sa requête")
            check(PronoteURL.root(mobile), root, "racine mobile \(page)")
            check(PronoteURL.root(mobile), androidRoot(mobile.absoluteString), "racine iOS = Android \(page)")
        }
        check(PronoteURL.root(try XCTUnwrap(URL(string: "https://school.index-education.net/pronote/eleve.html#x"))), root, "fragment")
    }

    /// Vecteur : avec l'adresse enregistrée, l'URL complète est celle d'Android (`expectedURL` des vecteurs partagés).
    func testDownloadURLFromStoredPageMatchesVectors() throws {
        let download = try downloadVectors()
        let key = try ProtocolCrypto.unhex(try XCTUnwrap(download["sessionKeyHex"] as? String))
        let iv = try ProtocolCrypto.unhex(try XCTUnwrap(download["ivHex"] as? String))
        let session = try XCTUnwrap(download["sessionH"] as? Int)
        let stored = try XCTUnwrap(download["storedPageURL"] as? String)
        let root = PronoteURL.root(try XCTUnwrap(URL(string: stored)))
        for item in try XCTUnwrap(download["cases"] as? [[String: Any]]) {
            let attachment = try XCTUnwrap(item["attachment"] as? [String: Any])
            let id = try XCTUnwrap(attachment["N"] as? String)
            let url = NotebookRules.downloadURL(root: root, encryptedId: try NotebookRules.encryptedId(id, key: key, iv: iv),
                                                name: attachment["L"] as? String ?? "", session: session)
            check(url, item["expectedURL"] as? String ?? "", "URL \(item["name"] ?? "")")
            check(URL(string: url)?.query, "Session=\(session)", "requête \(item["name"] ?? "")")
        }
    }

    /// Client réel, connexion par jeton sur l'adresse enregistrée : `FichiersExternes` et `appelfonction` sans la requête
    /// de la page, identifiant chiffré avec la clé et l'IV de la session (comme Android).
    func testClientBuildsAndroidURLAfterLogin() async throws {
        let stored = try XCTUnwrap(try downloadVectors()["storedPageURL"] as? String)
        let transport = RecordingTransport(body: Data("%PDF-1.4\n%fictif\n".utf8))
        let client = try PronoteClient(credentials: PronoteCredentials(url: stored, username: "u", password: "old-token", uuid: "uuid-test"),
                                       transport: transport)
        _ = try await client.login(mode: .token)
        let name = "LearnEnglish-Magazine sujet élève.pdf"
        let target = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-pj-\(UUID().uuidString).pdf")
        addTeardownBlock { try? FileManager.default.removeItem(at: target) }
        try await client.download(attachmentId: "pj-file-01", fileName: name, session: await client.currentSession(), to: target)

        let recordedIV = await transport.temporaryIV
        let ivSeed = try XCTUnwrap(recordedIV)
        let expected = androidRoot(stored) + "/FichiersExternes/"
            + (try NotebookRules.encryptedId("pj-file-01", key: ProtocolCrypto.md5(Data([1, 2, 3])), iv: ProtocolCrypto.md5(ivSeed)))
            + "/LearnEnglish-Magazine%20sujet%20%C3%A9l%C3%A8ve.pdf?Session=424242"
        let downloads = await transport.downloads
        let posts = await transport.posts
        check(downloads.map(\.absoluteString), [expected], "URL de téléchargement")
        for url in posts where url.query != nil || !url.absoluteString.hasPrefix("https://school.index-education.net/pronote/appelfonction/3/424242/") {
            problems.append("appelfonction inattendu : \(url.path)")
        }
        check(FileManager.default.fileExists(atPath: target.path), true, "document gardé")
    }

    /// Le client refuse aussi une page HTML renvoyée par n'importe quel transport, fichier effacé.
    func testClientDeletesHTMLHomePage() async throws {
        let page = Data("\u{FEFF}\n  <!DOCTYPE html><html><head><title>PRONOTE</title></head></html>".utf8)
        let transport = RecordingTransport(body: page)
        let client = try PronoteClient(credentials: PronoteCredentials(url: "https://school.index-education.net/pronote/mobile.eleve.html",
                                                                       username: "u", password: "old-token", uuid: "uuid-test"), transport: transport)
        _ = try await client.login(mode: .token)
        let target = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-pj-\(UUID().uuidString).odt")
        do {
            try await client.download(attachmentId: "pj-file-02", fileName: "Cours.odt", session: await client.currentSession(), to: target)
            problems.append("page HTML acceptée")
        } catch {
            check((error as? PronoteFailure)?.code, NotebookRules.notDocumentCode, "code")
            check((error as? PronoteFailure)?.message, "Pronote n’a pas renvoyé le document. Réessaie depuis le cahier de textes.", "message")
            check(PronoteSession.isSessionLost(error), true, "reconnexion puis un nouvel essai")
        }
        check(FileManager.default.fileExists(atPath: target.path), false, "fichier effacé")
        // Un vrai fichier HTML reste accepté.
        try await client.download(attachmentId: "pj-file-03", fileName: "page.HTML", session: await client.currentSession(), to: target)
        check(FileManager.default.fileExists(atPath: target.path), true, "fichier .html gardé")
        try? FileManager.default.removeItem(at: target)
    }

    // MARK: Transport

    func testTransportRefusesHTMLInsteadOfDocument() async throws {
        let origin = try XCTUnwrap(URL(string: "https://school.index-education.net/pronote/mobile.eleve.html"))
        let transport = URLSessionPronoteTransport(origin: origin, protocolClasses: [StubPageProtocol.self])
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-html-\(UUID().uuidString)")
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        // (nom, corps, Content-Type, refusé)
        let cases: [(String, String, String, Bool)] = [
            ("a.pdf", "html", "text/html; charset=utf-8", true),
            ("a.pdf", "bom", "application/octet-stream", true),
            ("a.odt", "upper", "", true),
            ("a.pdf", "pdf", "application/pdf", false),
            ("a.pdf", "text", "text/plain", false),
            ("page.html", "html", "text/html", false),
            ("page.htm", "bom", "text/html", false),
        ]
        for (index, item) in cases.enumerated() {
            let target = directory.appendingPathComponent("\(index)")
            let url = try XCTUnwrap(URL(string: "https://school.index-education.net/pronote/FichiersExternes/00/\(item.0)?body=\(item.1)&type=\(item.2.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")"))
            do {
                let status = try await transport.download(url: url, headers: [:], maxBytes: NotebookRules.maxDownloadBytes, to: target)
                check(status, 200, "statut \(item)")
                check(item.3, false, "refus attendu \(item)")
            } catch {
                check(item.3, true, "accepté attendu \(item)")
                check((error as? PronoteFailure)?.code, NotebookRules.notDocumentCode, "code \(item)")
            }
            check(FileManager.default.fileExists(atPath: target.path), !item.3, "fichier \(item)")
        }
    }

    func testLooksLikeHTML() {
        let yes = ["<!DOCTYPE html>", "<!doctype HTML>", "\u{FEFF}<html>", "\n\t  <HTML lang=\"fr\">", "\r\n<!DocType html>"]
        let no = ["%PDF-1.7", "PK\u{3}\u{4}", "", "   ", "<?xml version=\"1.0\"?>", "Bonjour <html>", "<htm"]
        for text in yes where !NotebookRules.looksLikeHTML(Data(text.utf8)) { problems.append("non détecté : \(text.debugDescription)") }
        for text in no where NotebookRules.looksLikeHTML(Data(text.utf8)) { problems.append("faux positif : \(text.debugDescription)") }
        check(NotebookRules.isHTMLName("Page.HTM"), true, "htm")
        check(NotebookRules.isHTMLName("cours.html.pdf"), false, "pdf")
    }
}

// MARK: Réparation

@MainActor
final class HTMLDocumentRepairTests: XCTestCase {
    func testLoadRemovesStoredHTMLPagesAndOffersRedownload() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-repair-\(UUID().uuidString)", isDirectory: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        let suite = "jmethod-repair-\(UUID().uuidString)"
        addTeardownBlock { UserDefaults().removePersistentDomain(forName: suite) }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.set(true, forKey: AppModel.demoMigrationKey)

        let files: [(id: String, name: String, contents: String)] = [
            ("doc-bad-pdf", "sujet.pdf", "<!DOCTYPE html><html><head><title>PRONOTE</title></head></html>"),
            ("doc-bad-odt", "cours.odt", "\u{FEFF}<html></html>"),
            ("doc-good", "fiche.pdf", "%PDF-1.4 fictif"),
            ("doc-page", "page.html", "<!DOCTYPE html><p>Vraie page</p>"),
        ]
        var documents: [CourseDocument] = []
        for file in files {
            let relative = "documents/course-1/\(file.id).\(DocumentText.fileExtension(file.name) ?? "")"
            let url = root.appendingPathComponent(relative)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data(file.contents.utf8).write(to: url)
            documents.append(CourseDocument(id: file.id, name: file.name, mime: "application/octet-stream", size: file.contents.utf8.count,
                                            file: relative, extractedTitle: "PRONOTE"))
        }
        var saved = AppModel.emptyDocument
        saved.courses = [Course(id: "course-1", subjectId: saved.subjects[0].id, chapter: "Chapitre", courseNumber: 1, title: "Titre du cours",
                                date: "2026-11-17", createdAt: "2026-11-17T18:00:00Z", documents: documents)]
        let data = root.appendingPathComponent("data.json")
        try await DocumentStore(url: data).save(saved, version: 1)

        let app = AppModel(store: DocumentStore(url: data), preferences: defaults, documentStore: CourseDocumentStore(root: root))
        await app.load()
        XCTAssertEqual(app.loadState, .ready)
        let course = try XCTUnwrap(app.course(id: "course-1"))
        let after = try XCTUnwrap(course.documents)
        var problems: [String] = []
        if course.title != "Titre du cours" || after.count != 4 { problems.append("cours modifié") }
        for document in after {
            let bad = document.id.hasPrefix("doc-bad")
            if app.documentExists(document) == bad { problems.append("\(document.id) : présence \(app.documentExists(document))") }
            if (document.extractedTitle == nil) != bad { problems.append("\(document.id) : titre lu \(document.extractedTitle ?? "nil")") }
        }
        // Idempotent : un second chargement ne change rien.
        await app.load()
        if app.course(id: "course-1")?.documents?.count != 4 { problems.append("second chargement") }
        if !problems.isEmpty { XCTFail(problems.joined(separator: "\n")) }
    }
}

// MARK: Doubles de test

/// Serveur scripté (`ScriptedPronoteServer`) avec téléchargement enregistré : URLs reçues et IV temporaire envoyé.
private actor RecordingTransport: PronoteTransport {
    private let server = ScriptedPronoteServer()
    private let body: Data
    private(set) var downloads: [URL] = []
    private(set) var posts: [URL] = []
    private(set) var temporaryIV: Data?

    init(body: Data) { self.body = body }

    func request(url: URL, method: String, body: Data?, headers: [String: String]) async throws -> PronoteHTTPResponse {
        if method == "POST" {
            posts.append(url)
            if let body, let envelope = try? JSONSerialization.jsonObject(with: body) as? [String: Any], envelope["id"] as? String == "FonctionParametres",
               let uuid = ((envelope["dataSec"] as? [String: Any])?["data"] as? [String: Any])?["Uuid"] as? String {
                temporaryIV = Data(base64Encoded: uuid)
            }
        }
        return try await server.request(url: url, method: method, body: body, headers: headers)
    }

    func download(url: URL, headers: [String: String], maxBytes: Int, to destination: URL) async throws -> Int {
        downloads.append(url)
        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        try body.write(to: destination)
        return 200
    }
}

/// Réponse simulée : `?body=html|bom|upper|pdf|text` avec `?type=` comme `Content-Type`.
final class StubPageProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }
        let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        let body = switch query["body"] {
        case "html": "<!DOCTYPE html><html><head><title>PRONOTE</title></head><body></body></html>"
        case "bom": "\u{FEFF}\r\n   <html><body></body></html>"
        case "upper": "<HTML><BODY></BODY></HTML>"
        case "text": "Texte : <html> plus loin"
        default: "%PDF-1.4\n%fictif\n"
        }
        var fields: [String: String] = [:]
        if let type = query["type"], !type.isEmpty { fields["Content-Type"] = type }
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: fields)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
