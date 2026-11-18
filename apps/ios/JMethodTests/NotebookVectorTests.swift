import XCTest
@testable import JMethod

/// Rejoue intégralement `native/shared/notebook-vectors.json` (jalon 5, cahier de textes en lecture seule).
final class NotebookVectorTests: XCTestCase {
    private var vectors: [String: Any] = [:]
    private var problems: [String] = []

    override func setUpWithError() throws {
        vectors = try SharedVectors.object("notebook-vectors.json")
        check(vectors["schemaVersion"] as? Int, 1, "schemaVersion")
        let provenance = try XCTUnwrap(vectors["provenance"] as? [String: Any])
        check(provenance["containsRealCredentials"] as? Bool, false, "provenance")
        check(provenance["containsPersonalData"] as? Bool, false, "provenance")
    }

    override func tearDown() {
        if !problems.isEmpty {
            XCTFail("\(problems.count) écart(s) :\n" + problems.prefix(25).joined(separator: "\n"))
        }
        problems = []
        super.tearDown()
    }

    private func check<T: Equatable>(_ actual: T, _ expected: T, _ label: String) {
        guard actual != expected else { return }
        problems.append("\(label) : \(String(describing: actual).prefix(300)) ≠ \(String(describing: expected).prefix(300))")
    }

    private func checkJSON(_ actual: Any, _ expected: Any, _ label: String) {
        if let mismatch = jsonMismatch(actual, expected) { problems.append("\(label) : \(mismatch)") }
    }

    private func section<T>(_ name: String, as type: T.Type = [String: Any].self) throws -> T {
        try XCTUnwrap(vectors[name] as? T, "section \(name) absente")
    }

    // MARK: Fenêtre et requêtes

    func testWindowAndWeeks() throws {
        let window = try section("window")
        let current = try XCTUnwrap(NotebookRules.window(today: try XCTUnwrap(window["today"] as? String), firstMonday: "2026-08-31"))
        checkJSON(Self.json(current), window, "window")
        let weeks = try section("weeks", as: [[String: Any]].self)
        for item in weeks {
            let today = try XCTUnwrap(item["today"] as? String)
            let monday = try XCTUnwrap(item["premierLundi"] as? String)
            let computed = try XCTUnwrap(NotebookRules.window(today: today, firstMonday: monday))
            checkJSON(Self.json(computed), try XCTUnwrap(item["expected"]), "weeks \(today)")
            // Numéros de pronotepy (sans le plancher natif à 1).
            check(NotebookRules.week(computed.from, firstMonday: monday), item["pronotepyFirstWeek"] as? Int, "pronotepy \(today)")
            check(NotebookRules.week(computed.to, firstMonday: monday), item["pronotepyLastWeek"] as? Int, "pronotepy \(today)")
        }
        check(weeks.count, 5, "weeks")
    }

    private static func json(_ window: NotebookRules.Window) -> [String: Any] {
        [
            "today": window.today, "from": window.from, "to": window.to, "days": (SchoolDay.daysBetween(window.from, window.to) ?? 0) + 1,
            "firstWeek": window.firstWeek, "lastWeek": window.lastWeek, "domaine": ["_T": 8, "V": window.domaine],
        ]
    }

    func testRequestsArePageCahierDeTexte89Then88() throws {
        let requests = try section("requests")
        let window = try XCTUnwrap(NotebookRules.window(today: "2026-11-18", firstMonday: "2026-08-31"))
        func inner(tab: Int, child: String?) -> [String: Any] {
            let resource: [String: Any] = child.map { ["N": $0] } ?? [:]
            return ["Signature": PronoteClient.signature(tab: tab, selectedResource: resource, hasChildren: child != nil),
                    "data": PronoteClient.notebookData(window)]
        }
        let student = try XCTUnwrap(requests["student"] as? [[String: Any]])
        check(student.map { $0["id"] as? String }, ["PageCahierDeTexte", "PageCahierDeTexte"], "student ids")
        checkJSON(student.map { $0["inner"] ?? NSNull() }, [inner(tab: 89, child: nil), inner(tab: 88, child: nil)], "student")
        let parent = try XCTUnwrap(requests["parent"] as? [String: Any])
        let child = try XCTUnwrap(parent["selectedChildId"] as? String)
        let calls = try XCTUnwrap(parent["calls"] as? [[String: Any]])
        checkJSON(calls.map { $0["inner"] ?? NSNull() }, [inner(tab: 89, child: child), inner(tab: 88, child: child)], "parent")
        for call in student + calls {
            let payload = try XCTUnwrap(call["inner"] as? [String: Any])
            let tab = try XCTUnwrap((payload["Signature"] as? [String: Any])?["onglet"] as? Int)
            XCTAssertNoThrow(try PronoteClient.checkCall("PageCahierDeTexte", tab: tab, data: payload["data"] as? [String: Any]))
        }
        check(requests["unauthorizedOngletIsRefusedLocally"] as? Bool, true, "onglet")
    }

    func testSafetyWhitelistAndLocalRefusals() throws {
        let safety = try section("safety")
        check(PronoteClient.readFunctions, Set(try XCTUnwrap(safety["readOnlyFunctionIds"] as? [String])), "liste blanche")
        for function in try XCTUnwrap(safety["rejectedFunctionExamples"] as? [String]) {
            if (try? PronoteClient.checkCall(function, tab: 88, data: nil)) != nil { problems.append("\(function) accepté") }
        }
        for prefix in try XCTUnwrap(safety["rejectedFunctionPrefixes"] as? [String]) {
            if (try? PronoteClient.checkCall(prefix + "Quelconque", tab: nil, data: nil)) != nil { problems.append("\(prefix)… accepté") }
        }
        let page = try XCTUnwrap(safety["pageCahierDeTexte"] as? [String: Any])
        check(Set(try XCTUnwrap(page["allowedOnglets"] as? [Int])), [NotebookRules.homeworkTab, NotebookRules.contentsTab], "onglets")
        check(page["maxWeekSpan"] as? Int, NotebookRules.maxWeekSpan, "span")
        let valid: [String: Any] = ["domaine": ["_T": 8, "V": "[10..14]"]]
        let refused: [(Int?, [String: Any]?)] = [
            (16, valid), (nil, valid), (89, nil),
            (89, ["domaine": ["_T": 8, "V": "[10..14]"], "ressource": [:] as [String: Any]]),
            (89, ["domaine": ["_T": 7, "V": "[10..14]"]]),
            (89, ["domaine": ["_T": 8, "V": "[10..16]"]]),
            (89, ["domaine": ["_T": 8, "V": "[0..3]"]]),
            (89, ["domaine": ["_T": 8, "V": "[5..4]"]]),
            (89, ["domaine": ["_T": 8, "V": "10..14"]]),
        ]
        for (index, item) in refused.enumerated() where (try? PronoteClient.checkCall("PageCahierDeTexte", tab: item.0, data: item.1)) != nil {
            problems.append("PageCahierDeTexte non conforme accepté (\(index))")
        }
        XCTAssertNoThrow(try PronoteClient.checkCall("PageCahierDeTexte", tab: 89, data: ["domaine": ["_T": 8, "V": "[10..15]"]]))
        let download = try XCTUnwrap(safety["download"] as? [String: Any])
        check(download["maxBytes"] as? Int, NotebookRules.maxDownloadBytes, "maxBytes")
        check(download["pathPrefix"] as? String, "/FichiersExternes/", "pathPrefix")
        // `listeOnglets` aplati comme pronotepy.
        check(PronoteClient.flattenTabs([["G": 89, "Onglet": [["G": 88]]], "7", ["x": [16]]] as [Any]), [89, 88, 7, 16], "listeOnglets")
    }

    // MARK: Dates, HTML, liens

    func testDates() throws {
        for item in try section("dates", as: [[String: Any]].self) {
            check(NotebookRules.parseDate(item["raw"] as? String), item["expectedDate"] as? String, "date \(String(describing: item["raw"]))")
        }
    }

    func testHTMLToSafeText() throws {
        let html = try section("html")
        let table = try XCTUnwrap(html["entityTable"] as? [String: Int])
        check(HTMLEntities.table.count, table.count, "entités")
        for (name, value) in table where HTMLEntities.table[name] != UInt32(value) { problems.append("entité \(name)") }
        let cases = try XCTUnwrap(html["cases"] as? [[String: Any]])
        for item in cases {
            let name = item["name"] as? String ?? ""
            let result = NotebookRules.safeText(item["html"] as? String)
            let actual: [String: Any] = ["text": result.text, "links": result.links.map { ["label": $0.label, "url": $0.url] }]
            checkJSON(actual, try XCTUnwrap(item["expected"]), "html \(name)")
        }
        check(cases.count, 21, "html cases")
    }

    func testLinkURLs() throws {
        for item in try section("linkUrls", as: [[String: Any]].self) {
            check(NotebookRules.validLinkURL(item["url"] as? String), item["accepted"] as? String, "lien \(item["url"] ?? "")")
        }
    }

    // MARK: Modèle

    func testNotebookModel() throws {
        let notebook = try section("notebook")
        let responses = try XCTUnwrap(notebook["responses"] as? [String: Any])
        func data(_ key: String) throws -> [String: Any] {
            try XCTUnwrap(((responses[key] as? [String: Any])?["dataSec"] as? [String: Any])?["data"] as? [String: Any])
        }
        let window = try XCTUnwrap(NotebookRules.window(today: try XCTUnwrap(notebook["today"] as? String), firstMonday: "2026-08-31"))
        let snapshot = NotebookRules.parse(window: window, contents: try data("PageCahierDeTexte89"), homework: try data("PageCahierDeTexte88"),
                                           timetableSubjects: try XCTUnwrap(notebook["timetableSubjects"] as? [String: String]))
        checkJSON(Self.json(snapshot), try XCTUnwrap(notebook["expected"]), "notebook")
        XCTAssertTrue(snapshot.homeworkAvailable)
        // Sans onglet 88 : contenus seuls.
        let contentsOnly = NotebookRules.parse(window: window, contents: try data("PageCahierDeTexte89"), homework: nil)
        XCTAssertFalse(contentsOnly.homeworkAvailable)
        XCTAssertTrue(contentsOnly.homework.isEmpty)
        check(contentsOnly.sessions.first { $0.id == "cdt-06" }?.subject, "Cours", "matière de secours sans emploi du temps")
    }

    private static func json(_ snapshot: NotebookSnapshot) -> [String: Any] {
        func attachments(_ items: [NotebookAttachment]) -> [[String: Any]] {
            items.map { item in
                var object: [String: Any] = ["kind": item.kind.rawValue, "id": item.id, "name": item.name]
                if let url = item.url { object["url"] = url }
                return object
            }
        }
        func links(_ items: [NotebookLink]) -> [[String: Any]] { items.map { ["label": $0.label, "url": $0.url] } }
        return [
            "from": snapshot.from, "to": snapshot.to, "skippedEntries": snapshot.skippedEntries, "attachmentCount": snapshot.attachmentCount,
            "sessions": snapshot.sessions.map { session in
                [
                    "id": session.id, "lessonId": session.lessonId, "date": session.date, "subject": session.subject,
                    "contents": session.contents.map { content in
                        ["title": content.title, "description": content.description, "links": links(content.links),
                         "category": content.category, "attachments": attachments(content.attachments)] as [String: Any]
                    },
                ] as [String: Any]
            },
            "homework": snapshot.homework.map { item in
                ["id": item.id, "dueDate": item.dueDate, "subject": item.subject, "description": item.description,
                 "links": links(item.links), "done": item.done, "attachments": attachments(item.attachments)] as [String: Any]
            },
        ]
    }

    // MARK: Téléchargement

    func testDownloadIdentifierURLAndNames() throws {
        let download = try section("download")
        let key = try ProtocolCrypto.unhex(try XCTUnwrap(download["sessionKeyHex"] as? String))
        let iv = try ProtocolCrypto.unhex(try XCTUnwrap(download["ivHex"] as? String))
        let root = try XCTUnwrap(download["pronoteRoot"] as? String)
        let session = try XCTUnwrap(download["sessionH"] as? Int)
        let pageURL = try XCTUnwrap(URL(string: try XCTUnwrap(download["pageURL"] as? String)))
        check(PronoteURL.root(pageURL), root, "racine")
        // Adresse réellement enregistrée (`PronoteURL.mobile`) : sa requête n'entre pas dans la racine.
        check(PronoteURL.root(try XCTUnwrap(URL(string: try XCTUnwrap(download["storedPageURL"] as? String)))), root, "racine enregistrée")
        for item in try XCTUnwrap(download["cases"] as? [[String: Any]]) {
            let name = item["name"] as? String ?? ""
            let attachment = try XCTUnwrap(item["attachment"] as? [String: Any])
            let id = try XCTUnwrap(attachment["N"] as? String)
            let label = attachment["L"] as? String ?? ""
            check(String(decoding: NotebookRules.plaintext(id), as: UTF8.self), item["plaintextUtf8"] as? String, "\(name) clair")
            check(ProtocolCrypto.hex(NotebookRules.pkcs7(NotebookRules.plaintext(id))), item["padded1Hex"] as? String, "\(name) pad1")
            check(ProtocolCrypto.hex(NotebookRules.pkcs7(NotebookRules.pkcs7(NotebookRules.plaintext(id)))), item["padded2Hex"] as? String, "\(name) pad2")
            let encrypted = try NotebookRules.encryptedId(id, key: key, iv: iv)
            check(encrypted, item["ciphertextHex"] as? String, "\(name) chiffré")
            check(try NotebookRules.encryptedId(id, key: key, iv: iv, doublePadding: false), item["singlePaddingVariantCiphertextHex"] as? String, "\(name) simple")
            check(NotebookRules.encodeURIComponent(label), item["encodedName"] as? String, "\(name) nom encodé")
            check(NotebookRules.downloadURL(root: root, encryptedId: encrypted, name: label, session: session), item["expectedURL"] as? String, "\(name) URL")
            // Le nom affiché d'un fichier sans nom est « Document » (modèle) ; c'est lui qui sert à l'enregistrement.
            check(NotebookRules.saveAsName(label.isEmpty ? "Document" : label), item["saveAsName"] as? String, "\(name) enregistrement")
            XCTAssertTrue(NotebookRules.isDownloadableId(id))
        }
        for id in try XCTUnwrap(download["rejectedIds"] as? [String]) where NotebookRules.isDownloadableId(id) {
            problems.append("identifiant accepté : \(id.debugDescription)")
        }
        for item in try XCTUnwrap(download["saveAsNames"] as? [[String: String]]) {
            check(NotebookRules.saveAsName(item["name"] ?? ""), item["expected"], "saveAs \(item["name"] ?? "")")
        }
        check(download["maxBytes"] as? Int, NotebookRules.maxDownloadBytes, "maxBytes")
    }

    /// Limite de 20 Mo en flux (20 971 520 acceptés, 20 971 521 refusés), statut transmis, fichier partiel effacé.
    func testTransportDownloadLimitAndStatus() async throws {
        let sizes = try XCTUnwrap((try section("download"))["sizeCases"] as? [[String: Any]])
        let origin = try XCTUnwrap(URL(string: "https://school.index-education.net/pronote/mobile.eleve.html"))
        let transport = URLSessionPronoteTransport(origin: origin, protocolClasses: [StubDownloadProtocol.self])
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-download-\(UUID().uuidString)")
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        for item in sizes {
            let bytes = try XCTUnwrap(item["bytes"] as? Int)
            let accepted = try XCTUnwrap(item["accepted"] as? Bool)
            let target = directory.appendingPathComponent("\(bytes).bin")
            let url = try XCTUnwrap(URL(string: "https://school.index-education.net/pronote/FichiersExternes/00/a.bin?size=\(bytes)"))
            let status = try await transport.download(url: url, headers: [:], maxBytes: NotebookRules.maxDownloadBytes, to: target)
            check(status, accepted ? 200 : 413, "taille \(bytes)")
            check(FileManager.default.fileExists(atPath: target.path), accepted, "fichier \(bytes)")
            if accepted { check((try? FileManager.default.attributesOfItem(atPath: target.path)[.size] as? Int) ?? 0, bytes, "octets") }
        }
        let missing = try XCTUnwrap(URL(string: "https://school.index-education.net/pronote/FichiersExternes/00/a.bin?status=404"))
        let status = try await transport.download(url: missing, headers: [:], maxBytes: 10, to: directory.appendingPathComponent("missing"))
        check(status, 404, "statut")
        do {
            _ = try await transport.download(url: try XCTUnwrap(URL(string: "https://evil.example/x")), headers: [:], maxBytes: 10, to: directory.appendingPathComponent("x"))
            problems.append("origine étrangère acceptée")
        } catch {}
    }

    /// Un téléchargement est refusé si la session a changé depuis la lecture, sans aucune requête.
    func testDownloadRefusedForAnotherSession() async throws {
        let credentials = PronoteCredentials(url: "https://school.index-education.net/pronote/mobile.eleve.html", username: "u", password: "p", uuid: "uuid")
        let client = try PronoteClient(credentials: credentials, transport: FailingTransport())
        do {
            try await client.download(attachmentId: "pj-file-01", fileName: "a.pdf", session: UUID(), to: FileManager.default.temporaryDirectory.appendingPathComponent("never"))
            XCTFail("téléchargement accepté")
        } catch {
            XCTAssertEqual((error as? PronoteFailure)?.message, NotebookRules.downloadUnavailable)
        }
    }
}

/// Transport qui échoue à toute requête : prouve qu'aucun envoi n'a lieu.
private struct FailingTransport: PronoteTransport {
    func request(url: URL, method: String, body: Data?, headers: [String: String]) async throws -> PronoteHTTPResponse {
        XCTFail("aucune requête attendue")
        throw URLError(.cancelled)
    }

    func download(url: URL, headers: [String: String], maxBytes: Int, to destination: URL) async throws -> Int {
        XCTFail("aucun téléchargement attendu")
        return 0
    }
}

/// Réponses simulées en mémoire (tests seulement) : `?size=` octets à 200, ou `?status=`.
final class StubDownloadProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }
        let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        let status = Int(query["status"] ?? "") ?? 200
        let size = Int(query["size"] ?? "") ?? 0
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: [:])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        var remaining = size
        let chunk = Data(repeating: 0x41, count: 1 << 20)
        while remaining > 0 {
            let count = min(remaining, chunk.count)
            client?.urlProtocol(self, didLoad: chunk.prefix(count))
            remaining -= count
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
