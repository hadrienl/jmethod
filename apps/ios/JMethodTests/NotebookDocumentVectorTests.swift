import XCTest
@testable import JMethod

/// Rejoue intégralement `native/shared/documents/document-title-vectors.json` (28 documents) et
/// `native/shared/notebook-course-vectors.json` (préremplissage des 13 séances, 5 scénarios).
/// Les documents synthétiques sont lus dans le dossier partagé, jamais copiés.
final class NotebookDocumentVectorTests: XCTestCase {
    private var problems: [String] = []

    override func tearDown() {
        if !problems.isEmpty {
            print("ECARTS \(name)\n" + problems.prefix(40).joined(separator: "\n"))
            XCTFail("\(problems.count) écart(s) :\n" + problems.prefix(25).joined(separator: "\n"))
        }
        problems = []
        super.tearDown()
    }

    private func check<T: Equatable>(_ actual: T, _ expected: T, _ label: String) {
        guard actual != expected else { return }
        problems.append("\(label) : \(String(describing: actual).prefix(400)) ≠ \(String(describing: expected).prefix(400))")
    }

    private func checkJSON(_ actual: Any, _ expected: Any, _ label: String) {
        if let mismatch = jsonMismatch(actual, expected) { problems.append("\(label) : \(mismatch)") }
    }

    /// `relative` est relatif à `native/shared/documents` (« files/… »).
    private func readFixture(_ relative: String) throws -> Data {
        let files = try SharedVectors.documentsDirectory()
        return try Data(contentsOf: files.deletingLastPathComponent().appendingPathComponent(relative))
    }

    private static func json(_ candidates: [CourseTitleRules.Candidate]) -> [[String: Any]] {
        candidates.map { ["source": $0.source.rawValue, "raw": $0.raw, "value": $0.value, "accepted": $0.accepted, "reason": ($0.rejection?.rawValue).json] }
    }

    /// Candidats attendus sans le champ informatif `detail`.
    private static func comparable(_ candidates: Any?) -> [[String: Any]] {
        ((candidates as? [[String: Any]]) ?? []).map { candidate in
            var copy = candidate
            copy.removeValue(forKey: "detail")
            return copy
        }
    }

    // MARK: Documents

    func testDocumentTitleVectors() throws {
        let vectors = try SharedVectors.object("document-title-vectors.json", subdirectory: "documents")
        let cases = try XCTUnwrap(vectors["cases"] as? [[String: Any]])
        for item in cases {
            let id = item["id"] as? String ?? ""
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let name = try XCTUnwrap(input["fileName"] as? String)
            let data = try readFixture(try XCTUnwrap(item["file"] as? String))
            check(data.count, item["size"] as? Int, "\(id) taille du fichier")
            let extraction = DocumentText.extract(name: name, data: data)
            check(extraction.kind?.rawValue, item["detectedType"] as? String, "\(id) type")
            check(extraction.detectedBy?.rawValue, item["detectedBy"] as? String, "\(id) détecté par")
            check(DocumentText.mime(name: name, kind: extraction.kind), item["mime"] as? String, "\(id) mime")
            let expectedExtraction = try XCTUnwrap(item["extraction"] as? [String: Any])
            check(extraction.error, expectedExtraction["error"] as? String, "\(id) erreur")
            check(extraction.metadata, expectedExtraction["metadata"] as? String, "\(id) métadonnées")
            check(extraction.metadataDetail, expectedExtraction["metadataDetail"] as? String, "\(id) origine des métadonnées")
            if let lines = expectedExtraction["pdfLines"] {
                checkJSON((extraction.pdfLines ?? []).map { ["text": $0.text, "size": $0.size] as [String: Any] }, lines, "\(id) lignes PDF")
            }
            let names = [try XCTUnwrap(input["subjectLabel"] as? String), try XCTUnwrap(input["subjectName"] as? String)]
            let principal: (name: String, extraction: DocumentExtraction)? = extraction.kind == nil ? nil : (name, extraction)
            let pipeline = CourseTitleRules.pipeline(principal: principal, contentTitle: input["contentTitle"] as? String,
                                                     subjectNames: names, sessionDate: try XCTUnwrap(input["sessionDate"] as? String))
            checkJSON(Self.json(pipeline.candidates), Self.comparable(item["candidates"]), "\(id) candidats")
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            let actual: [String: Any] = [
                "title": pipeline.choice.title.json, "chapter": pipeline.choice.chapter,
                "titleSource": pipeline.choice.titleFrom.map { pipeline.candidates[$0].source.rawValue }.json,
                "chapterSource": pipeline.choice.chapterFrom.map { pipeline.candidates[$0].source.rawValue }.json,
                "extractedTitle": (extraction.kind == nil ? nil : CourseTitleRules.extractedTitle(extraction, name: name, subjectNames: names)).json,
            ]
            checkJSON(actual, expected, "\(id) attendu")
            let reading: Any = [.odt, .odp, .docx, .pptx].contains(extraction.kind)
                ? (extraction.reading ?? []).map { block -> [String: Any] in
                    var object: [String: Any] = ["kind": block.kind.rawValue, "text": block.text]
                    if let level = block.level { object["level"] = level }
                    return object
                }
                : NSNull()
            checkJSON(reading, item["readingView"] ?? NSNull(), "\(id) vue Lecture")
        }
        check(cases.count, 28, "nombre de cas")
    }

    // MARK: Séances et scénarios

    private struct Fixtures {
        var sessions: [NotebookSession]
        /// Pièce jointe → fichier « téléchargé » ou erreur de téléchargement.
        var downloads: [String: NotebookCourses.Download]
        var subjects: [Subject]
    }

    /// Séances des vecteurs, déjà dans l'ordre du modèle ; rangs attribués comme pour un instantané Pronote.
    private func sessions(_ value: Any?, into downloads: inout [String: NotebookCourses.Download]) throws -> [NotebookSession] {
        var sessions: [NotebookSession] = []
        for raw in try XCTUnwrap(value as? [[String: Any]]) {
            var contents: [NotebookContent] = []
            for content in try XCTUnwrap(raw["contents"] as? [[String: Any]]) {
                var attachments: [NotebookAttachment] = []
                for attachment in (content["attachments"] as? [[String: Any]]) ?? [] {
                    let id = try XCTUnwrap(attachment["id"] as? String)
                    let name = try XCTUnwrap(attachment["name"] as? String)
                    let kind = NotebookAttachment.Kind(rawValue: attachment["kind"] as? String ?? "file") ?? .file
                    attachments.append(NotebookAttachment(kind: kind, id: id, name: name, url: attachment["url"] as? String, fileName: name))
                    if let fixture = attachment["fixture"] as? String {
                        downloads[id] = .data(try readFixture(fixture), url: nil)
                    } else if let error = attachment["downloadError"] as? String {
                        downloads[id] = .failed(error)
                    }
                }
                contents.append(NotebookContent(title: content["title"] as? String ?? "", description: content["description"] as? String ?? "",
                                                links: [], category: content["category"] as? String ?? "", attachments: attachments))
            }
            sessions.append(NotebookSession(id: try XCTUnwrap(raw["id"] as? String), lessonId: raw["lessonId"] as? String ?? "",
                                            date: try XCTUnwrap(raw["date"] as? String), subject: try XCTUnwrap(raw["subject"] as? String),
                                            contents: contents))
        }
        return NotebookCourses.assigningOrdinals(sessions)
    }

    private func fixtures(_ value: Any?, subjects: [Subject]) throws -> Fixtures {
        var downloads: [String: NotebookCourses.Download] = [:]
        let list = try sessions(value, into: &downloads)
        return Fixtures(sessions: list, downloads: downloads, subjects: subjects)
    }

    /// Cours des vecteurs (champs de comparaison retirés).
    private func courses(_ value: Any?) throws -> [Course] {
        try ((value as? [[String: Any]]) ?? []).map { raw in
            var object = raw
            object["createdAt"] = object["createdAt"] ?? object["date"] ?? "2026-11-18"
            object.removeValue(forKey: "sessionKey")
            object.removeValue(forKey: "titleSource")
            object.removeValue(forKey: "documents")
            return try decodeJSON(Course.self, object)
        }
    }

    private func imports(_ value: Any?) throws -> [String: NotebookImport] {
        try ((value as? [String: [String: Any]]) ?? [:]).mapValues { raw in
            var object = raw
            object["at"] = object["at"] ?? "2026-11-18T19:00:00Z"
            return try decodeJSON(NotebookImport.self, object)
        }
    }

    private static func json(_ source: CourseSource?) -> Any {
        guard let source else { return NSNull() }
        var object: [String: Any] = ["kind": source.kind, "sessionKey": source.sessionKey, "subjectLabel": source.subjectLabel, "date": source.date]
        if let ordinal = source.ordinal { object["ordinal"] = ordinal }
        return object
    }

    private func prefill(_ session: NotebookSession, _ fixtures: Fixtures, courses: [Course]) -> NotebookCourses.Prefill {
        NotebookCourses.prefill(session: session, subjects: fixtures.subjects, courses: courses) { fixtures.downloads[$0.id] ?? .failed("unavailable") }
    }

    private static func json(documents: [NotebookCourses.PreparedDocument]) -> [[String: Any]] {
        documents.map { ["name": $0.attachment.name, "mime": $0.mime, "size": $0.size, "extractedTitle": $0.extractedTitle.json] }
    }

    private static func keyJSON(_ session: NotebookSession) -> [String: Any] {
        ["id": session.id, "date": session.date, "subject": session.subject, "normalizedSubject": NotebookCourses.subjectKey(session.subject),
         "ordinal": session.ordinal, "sessionKey": NotebookCourses.sessionKey(session)]
    }

    func testSubjectNormalizationAndKeys() throws {
        let vectors = try SharedVectors.object("notebook-course-vectors.json")
        check(vectors["schemaVersion"] as? Int, 2, "version des vecteurs")
        let cases = try XCTUnwrap(vectors["subjectNormalization"] as? [[String: Any]])
        for item in cases {
            let input = try XCTUnwrap(item["input"] as? String)
            check(NotebookCourses.subjectKey(input), try XCTUnwrap(item["expected"] as? String), "matière « \(input) »")
        }
        check(cases.count, 18, "cas de normalisation")
        let data = try fixtures(vectors["sessions"], subjects: try decodeJSON([Subject].self, vectors["subjects"]))
        checkJSON(data.sessions.map(Self.keyJSON), try XCTUnwrap(vectors["sessionKeys"]), "clés des séances")
    }

    func testPrefillVectors() throws {
        let vectors = try SharedVectors.object("notebook-course-vectors.json")
        let data = try fixtures(vectors["sessions"], subjects: try decodeJSON([Subject].self, vectors["subjects"]))
        let expectedList = try XCTUnwrap(vectors["prefill"] as? [[String: Any]])
        let base = try courses(try XCTUnwrap(vectors["scenarios"] as? [[String: Any]]).first?["courses"])
        check(expectedList.count, data.sessions.count, "nombre de séances")
        for (session, expected) in zip(data.sessions, expectedList) {
            let result = prefill(session, data, courses: base)
            var actual: [String: Any] = [
                "sessionKey": result.sessionKey, "ordinal": session.ordinal, "notebookId": session.id,
                "subjectId": result.subjectId.json, "date": result.date, "chapter": result.chapter,
                "title": result.title, "titleSource": (result.titleSource?.rawValue).json, "candidates": Self.json(result.candidates),
                "description": result.description, "principalDocument": result.principalDocument.json,
                "documents": Self.json(documents: result.documents),
                "skippedAttachments": result.skipped.map { ["id": $0.id, "name": $0.name, "reason": $0.reason] },
            ]
            if let number = result.courseNumber { actual["courseNumber"] = number }
            var comparable = expected
            comparable["candidates"] = Self.comparable(expected["candidates"])
            checkJSON(actual, comparable, "préremplissage \(session.id)")
        }
    }

    /// Passe de création automatique avec les règles natives (clé, éligibilité, ordre, numérotation, suivi, boutons).
    private func run(_ scenario: [String: Any], subjects: [Subject]) throws {
        let id = scenario["id"] as? String ?? ""
        let today = try XCTUnwrap(scenario["today"] as? String)
        let settings = try decodeJSON(AppSettings.self, scenario["settings"])
        let data = try fixtures(scenario["sessions"], subjects: subjects)
        var courseList = try courses(scenario["courses"])
        var imports = try imports(scenario["notebookImports"])
        let key = NotebookCourses.sessionKey as (NotebookSession) -> String
        let skipped = data.sessions.compactMap { session in
            NotebookCourses.skipReason(session, settings: settings, imports: imports, courses: courseList, subjects: subjects, today: today)
                .map { ["sessionKey": key(session), "reason": $0.rawValue] }
        }
        let eligible = NotebookCourses.eligible(data.sessions, settings: settings, imports: imports, courses: courseList,
                                                subjects: subjects, today: today)
        var created: [[String: Any]] = []
        for session in eligible {
            let result = prefill(session, data, courses: courseList)
            let courseId = "course-nb-\(session.date)-\(NotebookCourses.subjectKey(session.subject).replacingOccurrences(of: " ", with: "-"))-\(session.ordinal)"
            var course = Course(id: courseId, subjectId: result.subjectId ?? "", chapter: result.chapter,
                                courseNumber: result.courseNumber ?? 1, title: result.title, date: result.date,
                                description: result.description, createdAt: today)
            course.source = NotebookCourses.source(for: session)
            courseList.append(course)
            imports[key(session)] = NotebookImport(status: .created, courseId: course.id, at: "2026-11-18T19:00:00Z")
            created.append([
                "sessionKey": key(session), "titleSource": (result.titleSource?.rawValue).json, "id": course.id,
                "subjectId": course.subjectId, "chapter": course.chapter, "courseNumber": course.courseNumber, "title": course.title,
                "date": course.date, "createdAt": course.createdAt, "description": result.description,
                "source": Self.json(course.source), "documents": Self.json(documents: result.documents),
            ])
        }
        var actual: [String: Any] = [
            "sessionKeys": data.sessions.map(key),
            "eligibleSessionKeys": eligible.map(key),
            "skipped": skipped,
            "createdCourses": created,
            "notebookImportsAfter": imports.mapValues { ["status": $0.status.rawValue, "courseId": $0.courseId.json] },
            "toast": NotebookCourses.toast(created.count).json,
            "buttons": Dictionary(uniqueKeysWithValues: data.sessions.map { session -> (String, String) in
                if case .created = NotebookCourses.state(of: session, imports: imports, courses: courseList) { return (key(session), "viewCourse") }
                return (key(session), "createCourse")
            }),
        ]
        let expected = try XCTUnwrap(scenario["expected"] as? [String: Any])
        if expected["backfillProposal"] != nil {
            let proposal = NotebookCourses.backfill(data.sessions, imports: imports, courses: courseList, subjects: subjects, today: today)
            actual["backfillProposal"] = ["from": proposal.from, "to": proposal.to, "count": proposal.count, "sessionKeys": proposal.sessionKeys,
                                          "message": proposal.message.json, "acceptSetsSince": proposal.acceptSetsSince]
        }
        checkJSON(actual, expected, "scénario \(id)")
    }

    func testScenarioVectors() throws {
        let vectors = try SharedVectors.object("notebook-course-vectors.json")
        let subjects = try decodeJSON([Subject].self, vectors["subjects"])
        let scenarios = try XCTUnwrap(vectors["scenarios"] as? [[String: Any]])
        for scenario in scenarios { try run(scenario, subjects: subjects) }
        check(scenarios.count, 8, "nombre de scénarios")
        check(Set(scenarios.compactMap { $0["id"] as? String }).isSuperset(of: ["new-pronote-session", "modified-sessions", "belt-and-braces"]),
              true, "scénarios de clé stable présents")
    }

    /// Migration des anciennes clés (règles 37 à 40), idempotence, puis passe de création sur le document migré.
    @MainActor
    func testMigrationVectors() throws {
        let vectors = try SharedVectors.object("notebook-course-vectors.json")
        let subjects = try decodeJSON([Subject].self, vectors["subjects"])
        let migration = try XCTUnwrap(vectors["migration"] as? [String: Any])
        let input = try XCTUnwrap(migration["input"] as? [String: Any])
        let expected = try XCTUnwrap(migration["expected"] as? [String: Any])
        var document = AppModel.emptyDocument
        document.courses = try courses(input["courses"])
        document.notebookImports = try imports(input["notebookImports"])
        let migrated = NotebookCourses.migratingKeys(document)
        let expectedCourses = try XCTUnwrap(expected["courses"] as? [[String: Any]])
        checkJSON(migrated.courses.map { ["id": $0.id, "source": Self.json($0.source)] },
                  expectedCourses.map { ["id": $0["id"] ?? NSNull(), "source": $0["source"] ?? NSNull()] }, "cours migrés")
        checkJSON(migrated.notebookImports.mapValues { ["status": $0.status.rawValue, "courseId": $0.courseId.json, "at": $0.at] },
                  try XCTUnwrap(expected["notebookImports"]), "suivi migré")
        if migration["idempotent"] as? Bool == true {
            check(NotebookCourses.migratingKeys(migrated) == migrated, true, "seconde migration sans effet")
        }
        let after = try XCTUnwrap(migration["afterMigration"] as? [String: Any])
        try run(after, subjects: subjects)
    }
}
