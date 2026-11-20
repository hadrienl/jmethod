import XCTest
@testable import JMethod

/// Réviser sans quitter Aujourd'hui (`native/shared/notebook-to-course.md` §8) : enchaînement, fiche, documents proches.
@MainActor
final class RevisionSessionTests: XCTestCase {
    private let today = "2026-11-18"

    private func notebookCourse(_ id: String, date: String, subject: String = "maths", label: String = "MATHEMATIQUES",
                                ordinal: Int = 1, documents: [String] = []) -> Course {
        var course = Course(id: id, subjectId: subject, chapter: "Chapitre 3", courseNumber: 1, title: "Titre \(id)", date: date, createdAt: date)
        course.source = CourseSource(kind: CourseSource.notebookKind, sessionKey: NotebookCourses.sessionKey(date: date, subject: label, ordinal: ordinal),
                                     subjectLabel: label, date: date, ordinal: ordinal)
        course.documents = documents.isEmpty ? nil : documents.enumerated().map {
            CourseDocument(id: "doc-\(id)-\($0.offset)", name: $0.element, mime: "application/pdf", size: 10,
                           file: "documents/\(id)/doc-\(id)-\($0.offset).pdf", extractedTitle: "Titre lu \($0.offset)")
        }
        return course
    }

    private func session(_ id: String, date: String, subject: String = "MATHEMATIQUES", files: [String] = [], links: [String] = []) -> NotebookSession {
        NotebookSession(id: id, lessonId: "l-\(id)", date: date, subject: subject, contents: [
            NotebookContent(title: "Contenu \(id)", description: "Texte.", links: [], category: "Cours",
                            attachments: files.enumerated().map { NotebookAttachment(kind: .file, id: "pj-\(id)-\($0.offset)", name: $0.element, fileName: $0.element) }
                                + links.map { NotebookAttachment(kind: .link, id: "lien-\(id)", name: "Lien", url: $0) }),
        ])
    }

    // MARK: Enchaîner (§8.2)

    func testNextDueFollowsTodayOrder() throws {
        var document = try Fixture.document(today: today)
        let due = TodayScreen(today: today, courses: document.courses, revisions: document.revisions, subjects: document.subjects,
                              pronoteEvents: []).groups.flatMap(\.rows).filter(\.canValidate).map(\.revision.id)
        XCTAssertEqual(due.count, 3, "J7 du cours 1, J0 et J1 du cours 2")
        let first = try XCTUnwrap(due.first)
        XCTAssertEqual(RevisionSession.nextDue(revisions: document.revisions, courses: document.courses, subjects: document.subjects,
                                               today: today, excluding: first)?.id, due[1], "séance suivante dans l'ordre affiché")
        // Toutes faites : programme terminé.
        document.revisions = document.revisions.map { revision in
            var done = revision
            if due.contains(revision.id) { done.status = .completed; done.completedDate = today; done.score = 60 }
            return done
        }
        XCTAssertNil(RevisionSession.nextDue(revisions: document.revisions, courses: document.courses, subjects: document.subjects,
                                             today: today, excluding: nil))
        let next = try XCTUnwrap(document.revisions.first { $0.id == due[1] })
        XCTAssertEqual(RevisionSession.nextLabel(next, courses: document.courses, subjects: document.subjects),
                       "Séance suivante · Français — Cours test 2")
    }

    func testNextOfCourseAndLateLabel() throws {
        let document = try Fixture.document(today: today)
        let next = try XCTUnwrap(RevisionSession.nextOfCourse("course-test-1", revisions: document.revisions))
        XCTAssertEqual(next.stepLabel, "J7", "première séance non faite dans l'ordre des J")
        XCTAssertNil(RevisionSession.nextOfCourse("absent", revisions: document.revisions))

        var late = next
        late.scheduledDate = "2026-11-17"
        XCTAssertEqual(RevisionSession.lateLabel(late, today: today), "En retard de 2 jours", "par rapport au jour préparé (19)")
        late.scheduledDate = "2026-11-18"
        XCTAssertEqual(RevisionSession.lateLabel(late, today: today), "En retard de 1 jour")
        late.scheduledDate = "2026-11-19"
        XCTAssertNil(RevisionSession.lateLabel(late, today: today))
        late.scheduledDate = "2026-11-10"
        late.status = .completed
        XCTAssertNil(RevisionSession.lateLabel(late, today: today), "séance faite : jamais en retard")
    }

    // MARK: Aujourd'hui (§8.3)

    func testCourseForTomorrowSlot() {
        let subjects = Constants.defaultSubjects
        let manual = Course(id: "manual", subjectId: "maths", chapter: "C", courseNumber: 1, title: "Manuel", date: "2026-11-19", createdAt: "2026-11-18")
        let imported = notebookCourse("imported", date: "2026-11-19")
        var event = PronoteEvent(id: "e1", subjectName: "MATHEMATIQUES", matchedSubjectId: "maths", date: "2026-11-19", startTime: "08:00",
                                 endTime: "09:00", room: nil, teacher: nil, isCancelled: false)
        XCTAssertEqual(RevisionSession.courseForSlot(event, courses: [manual, imported], subjects: subjects)?.id, "imported", "cours du cahier en priorité")
        XCTAssertEqual(RevisionSession.courseForSlot(event, courses: [manual], subjects: subjects)?.id, "manual")
        XCTAssertNil(RevisionSession.courseForSlot(event, courses: [notebookCourse("other-day", date: "2026-11-20")], subjects: subjects))
        event.isCancelled = true
        XCTAssertNil(RevisionSession.courseForSlot(event, courses: [imported], subjects: subjects), "créneau annulé : « Ajouter » masqué comme avant")
        XCTAssertFalse(RevisionSession.hasDocuments(manual))
        XCTAssertTrue(RevisionSession.hasDocuments(notebookCourse("d", date: "2026-11-19", documents: ["Cours.pdf"])))
    }

    // MARK: Dans le cahier de textes (§8.1, point 3)

    func testNotebookPartUsesStableKey() {
        let course = notebookCourse("c1", date: "2026-11-17", documents: ["Cours.pdf"])
        // Nouvelle session Pronote : autres `N`, même clé stable.
        let snapshot = NotebookSnapshot(from: "2026-11-04", to: "2026-12-01", sessions: NotebookCourses.assigningOrdinals([
            session("N-autre", date: "2026-11-17", files: ["Cours.pdf", "Annexe.pdf"], links: ["https://exemple.fr/video"]),
        ]), homework: [], skippedEntries: 0, attachmentCount: 3)
        let part = RevisionSession.notebookPart(for: course, snapshot: snapshot)
        XCTAssertEqual(part?.sourceLine, "Séance du 17 novembre · Pronote")
        XCTAssertEqual(part?.contents?.first?.attachments.map(\.name), ["Annexe.pdf", "Lien"], "pièce jointe déjà importée retirée")
        let outside = notebookCourse("c2", date: "2026-11-01")
        XCTAssertEqual(RevisionSession.notebookPart(for: outside, snapshot: snapshot)?.sourceLine, "Séance du 1er novembre · Pronote")
        XCTAssertNil(RevisionSession.notebookPart(for: outside, snapshot: snapshot)?.contents, "hors de la fenêtre lue : ligne seule")
        let manual = Course(id: "m", subjectId: "maths", chapter: "C", courseNumber: 1, title: "T", date: "2026-11-17", createdAt: "2026-11-17")
        XCTAssertNil(RevisionSession.notebookPart(for: manual, snapshot: snapshot))
    }

    // MARK: Documents proches (§8.5)

    func testNearbyDocuments() {
        // Cas réel : le support des séances du 4 et du 5 novembre est joint à la seconde seulement.
        let first = notebookCourse("nov-4", date: "2026-11-04")
        let second = notebookCourse("nov-5", date: "2026-11-05", documents: ["cours 3e 4 et 5 nov.odt"])
        let far = notebookCourse("nov-20", date: "2026-11-20", documents: ["Loin.pdf"])
        let french = notebookCourse("fr", date: "2026-11-05", subject: "francais", label: "FRANCAIS", documents: ["Français.pdf"])
        let snapshot = NotebookSnapshot(from: "2026-10-21", to: "2026-11-18", sessions: NotebookCourses.assigningOrdinals([
            session("s5", date: "2026-11-05", files: ["cours 3e 4 et 5 nov.odt"]),
            session("s4", date: "2026-11-04", files: []),
            session("s3", date: "2026-11-03", files: ["Exercices.pdf"], links: ["https://exemple.fr"]),
            session("s1", date: "2026-10-25", files: ["Ancien.pdf"]),
            session("fr3", date: "2026-11-03", subject: "FRANCAIS", files: ["Poème.pdf"]),
        ]), homework: [], skippedEntries: 0, attachmentCount: 4)
        let nearby = RevisionSession.nearbyDocuments(for: first, courses: [second, far, french, first], snapshot: snapshot,
                                                     subjects: Constants.defaultSubjects)
        XCTAssertEqual(nearby.map(\.name), ["cours 3e 4 et 5 nov.odt", "Exercices.pdf", "Ancien.pdf"],
                       "même matière, ±10 jours, postérieure avant antérieure, doublon du cahier retiré, liens exclus")
        XCTAssertEqual(nearby.first?.dateLine, "Séance du 5 novembre")
        XCTAssertEqual(nearby.first?.extractedTitle, "Titre lu 0")
        if case .course(let courseId, _)? = nearby.first?.origin { XCTAssertEqual(courseId, "nov-5") } else { XCTFail("document du cours d'abord") }
        if case .notebook(let attachment)? = nearby.dropFirst().first?.origin { XCTAssertEqual(attachment.id, "pj-s3-0") } else { XCTFail("pièce jointe du cahier") }

        // Documents déjà joints à ce cours : retirés.
        var attached = first
        attached.documents = [CourseDocument(id: "x", name: "Exercices.pdf", mime: "application/pdf", size: 1, file: "documents/nov-4/x.pdf", extractedTitle: nil)]
        XCTAssertEqual(RevisionSession.nearbyDocuments(for: attached, courses: [second], snapshot: snapshot, subjects: Constants.defaultSubjects).map(\.name),
                       ["cours 3e 4 et 5 nov.odt", "Ancien.pdf"])
        XCTAssertEqual(RevisionSession.nearbyVisible, 5)
        XCTAssertEqual(RevisionSession.nearbyDocuments(for: first, courses: [second], snapshot: nil, subjects: Constants.defaultSubjects,
                                                       fileExists: { _ in false }).map(\.name), [], "fichier absent : non proposé")
    }

    func testAttachNearbyDocumentCopiesFile() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-attach-\(UUID().uuidString)", isDirectory: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        let suite = "jmethod-attach-\(UUID().uuidString)"
        addTeardownBlock { UserDefaults().removePersistentDomain(forName: suite) }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.set(true, forKey: AppModel.demoMigrationKey)
        let store = CourseDocumentStore(root: root)
        let source = root.appendingPathComponent("source.odt")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data("support".utf8).write(to: source)
        let stored = try store.store(source, name: "cours 3e 4 et 5 nov.odt", courseId: "nov-5", extractedTitle: "Les fonctions", documentId: "doc-orig")
        var second = notebookCourse("nov-5", date: "2026-11-05")
        second.documents = [stored]
        let first = notebookCourse("nov-4", date: "2026-11-04")
        var saved = AppModel.emptyDocument
        saved.courses = [second, first]
        let dataURL = root.appendingPathComponent("data.json")
        try DocumentStore.encode(saved).write(to: dataURL)
        let app = AppModel(store: DocumentStore(url: dataURL), preferences: defaults, documentStore: store)
        await app.load()
        XCTAssertEqual(app.document.courses.count, 2)

        let url = try XCTUnwrap(app.documentURL(stored))
        try app.attachDocument(from: url, name: stored.name, mime: stored.mime, extractedTitle: stored.extractedTitle, to: "nov-4")
        let copy = try XCTUnwrap(app.course(id: "nov-4")?.documents?.first)
        XCTAssertNotEqual(copy.id, stored.id, "nouvel identifiant")
        XCTAssertEqual(copy.name, stored.name)
        XCTAssertEqual(copy.extractedTitle, "Les fonctions", "titre lu conservé")
        XCTAssertTrue(copy.file.hasPrefix("documents/nov-4/"))
        XCTAssertTrue(app.documentExists(copy))
        XCTAssertEqual(app.course(id: "nov-4")?.title, "Titre nov-4", "titre du cours inchangé")
        XCTAssertEqual(app.course(id: "nov-5")?.documents, [stored], "l'autre cours n'est pas modifié")
        XCTAssertThrowsError(try app.attachDocument(from: url, name: stored.name, mime: nil, extractedTitle: nil, to: "nov-4")) {
            XCTAssertEqual($0 as? AppModel.AttachFailure, .alreadyAttached)
        }
        XCTAssertThrowsError(try app.attachDocument(from: url, name: "x.odt", mime: nil, extractedTitle: nil, to: "absent")) {
            XCTAssertEqual($0 as? AppModel.AttachFailure, .courseMissing)
        }
        for index in 0..<9 {
            try app.attachDocument(from: url, name: "copie-\(index).odt", mime: nil, extractedTitle: nil, to: "nov-4")
        }
        XCTAssertThrowsError(try app.attachDocument(from: url, name: "onzieme.odt", mime: nil, extractedTitle: nil, to: "nov-4")) {
            XCTAssertEqual($0 as? AppModel.AttachFailure, .full, "10 documents au plus par cours")
        }
    }
}
