import XCTest
@testable import JMethod

/// Création, préremplissage, suppression et validation depuis le Planning, au niveau du modèle de l'app.
@MainActor
final class CourseFlowTests: XCTestCase {
    private final class Clock: @unchecked Sendable {
        // Utilisé uniquement depuis le MainActor du test.
        var now: Date
        init(_ now: Date) { self.now = now }
    }

    private func date(_ iso: String) -> Date { ISO8601DateFormatter().date(from: iso)! }

    private func model(at iso: String, document: AppDocument? = nil) async throws -> (AppModel, Clock, URL) {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-flow-\(UUID().uuidString).json")
        addTeardownBlock { try? FileManager.default.removeItem(at: file) }
        if let document { try await DocumentStore(url: file).save(document, version: 1) }
        let suite = "jmethod-flow-\(UUID().uuidString)"
        addTeardownBlock { UserDefaults().removePersistentDomain(forName: suite) }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.set(true, forKey: AppModel.demoMigrationKey)
        let clock = Clock(date(iso))
        let model = AppModel(store: DocumentStore(url: file), now: { clock.now }, preferences: defaults)
        await model.load()
        return (model, clock, file)
    }

    private func saved(_ url: URL, until condition: (AppDocument) -> Bool) async throws -> AppDocument? {
        var document: AppDocument?
        for _ in 0..<50 {
            document = try? await DocumentStore(url: url).load()
            if let document, condition(document) { return document }
            try await Task.sleep(for: .milliseconds(20))
        }
        return document
    }

    func testFirstCourseIsCreatedWithItsRevisionsAndPersisted() async throws {
        let (app, _, url) = try await model(at: "2026-11-17T17:30:00Z")
        XCTAssertTrue(app.document.courses.isEmpty)
        app.openAddCourse()
        XCTAssertEqual(app.presented, .addCourse)
        XCTAssertEqual(app.courseDraft.subjectId, "maths", "première matière de la liste")
        XCTAssertEqual(app.courseDraft.date, "2026-11-17", "date du lancement de l'app")
        XCTAssertFalse(app.createCourse(), "refusé sans chapitre ni titre")
        app.courseDraft.chapter = "  Statistiques "
        app.courseDraft.title = " Moyenne "
        XCTAssertTrue(app.createCourse())
        let course = try XCTUnwrap(app.document.courses.first)
        XCTAssertEqual(course.id, "course-1794936600000")
        XCTAssertEqual(course.chapter, "Statistiques")
        XCTAssertEqual(course.title, "Moyenne")
        XCTAssertNil(course.description)
        XCTAssertEqual(course.createdAt, "2026-11-17")
        XCTAssertEqual(app.document.revisions.map(\.id), (0..<10).map { "course-1794936600000-rev-\($0)" })
        XCTAssertEqual(app.toast?.title, "Nouveau cours créé !")
        XCTAssertEqual(app.toast?.message, "C1 « Moyenne » ajouté avec ses 10 échéances de révision.")
        // Le soir du 17 prépare le 18 : le J0 (17) et le J1 (18) sont au programme.
        XCTAssertEqual(app.programme.due.map(\.id), ["course-1794936600000-rev-0", "course-1794936600000-rev-1"])
        let stored = try await saved(url) { !$0.courses.isEmpty }
        XCTAssertEqual(stored?.courses, app.document.courses)
        XCTAssertEqual(stored?.revisions, app.document.revisions)
    }

    func testDraftKeepsChapterAndPrefillFromSlotLikeTheWeb() async throws {
        let (app, clock, _) = try await model(at: "2026-11-17T17:30:00Z")
        app.openAddCourse()
        app.courseDraft.subjectId = "francais"
        app.courseDraft.chapter = "Grammaire"
        app.courseDraft.title = "Accords"
        app.courseDraft.description = "Notes"
        XCTAssertTrue(app.createCourse())

        // Réouverture sans préremplissage : chapitre, matière et date gardés ; titre et notes vidés.
        app.openAddCourse()
        XCTAssertEqual(app.courseDraft, CourseDraft(subjectId: "francais", chapter: "Grammaire", title: "", date: "2026-11-17", description: ""))
        XCTAssertEqual(CourseCreation.courseNumber(subjectId: "francais", chapter: app.courseDraft.chapter, courses: app.document.courses), 2)

        // Depuis un créneau Pronote : matière appariée, date et titre du créneau ; le chapitre reste.
        let slot = PronoteEvent(id: "slot-1", subjectName: "Mathématiques", matchedSubjectId: "maths", date: "2026-11-18", startTime: "08:00", endTime: "09:00", isCancelled: false)
        app.openAddCourse(from: slot)
        XCTAssertEqual(app.courseDraft, CourseDraft(subjectId: "maths", chapter: "Grammaire", title: "Mathématiques", date: "2026-11-18", description: ""))
        let unmatched = PronoteEvent(id: "slot-2", subjectName: "LATIN", date: "2026-11-19", startTime: "10:00", endTime: "11:00", isCancelled: false)
        app.openAddCourse(from: unmatched)
        XCTAssertEqual(app.courseDraft.subjectId, "maths", "créneau non apparié : la première matière")
        XCTAssertEqual(app.courseDraft.title, "LATIN")

        clock.now = date("2026-11-17T17:30:05Z")
        app.courseDraft.chapter = "Déclinaisons"
        XCTAssertTrue(app.createCourse())
        XCTAssertEqual(app.document.courses.map(\.id), ["course-1794936605000", "course-1794936600000"], "nouveau cours en tête")
        XCTAssertEqual(app.document.courses.first?.date, "2026-11-19")
    }

    func testDeletingCourseRemovesItsRevisionsButKeepsSlots() async throws {
        let document = try Fixture.document(today: "2026-11-17")
        let (app, _, url) = try await model(at: "2026-11-17T19:00:00Z", document: document)
        app.presented = .course(courseId: "course-test-1")
        app.deleteCourse(id: "course-test-1")
        XCTAssertEqual(app.document.courses.map(\.id), ["course-test-2"])
        XCTAssertFalse(app.document.revisions.contains { $0.courseId == "course-test-1" })
        XCTAssertEqual(app.document.revisions.count, 10)
        XCTAssertEqual(app.document.pronoteEvents, document.pronoteEvents)
        XCTAssertEqual(app.toast?.title, "Cours supprimé")
        XCTAssertEqual(app.toast?.kind, .info)
        let stored = try await saved(url) { $0.courses.count == 1 }
        XCTAssertEqual(stored?.courses.map(\.id), ["course-test-2"])
    }

    func testValidatingFromPlanningUsesEveningReference() async throws {
        let document = try Fixture.document(today: "2026-11-17")
        let (app, _, _) = try await model(at: "2026-11-17T19:00:00Z", document: document)
        let screen = PlanningScreen(mode: .day, date: "2026-11-17", today: app.today, courses: app.document.courses,
                                    revisions: app.document.revisions, subjects: app.document.subjects, pronoteEvents: app.document.pronoteEvents)
        let tile = try XCTUnwrap(screen.days.first?.tiles.first { $0.revision.id == "course-test-2-rev-0" })
        XCTAssertFalse(tile.done)
        let explanation = try XCTUnwrap(app.validate(revisionId: tile.revision.id, score: 50))
        XCTAssertEqual(explanation.nextDate, "2026-11-19", "replanifié depuis le jour préparé (18), comme Aujourd'hui")
        let done = try XCTUnwrap(RevisionDetail(revisionId: tile.revision.id, revisions: app.document.revisions, courses: app.document.courses, subjects: app.document.subjects))
        XCTAssertEqual(done.completedLine, "Séance terminée le 17/11/2026 · 50/100")
        XCTAssertFalse(done.canValidate)
    }
}
