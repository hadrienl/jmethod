import XCTest
@testable import JMethod

final class StorageTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-tests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func sampleDocument() throws -> AppDocument {
        try Fixture.document(today: "2026-11-17")
    }

    func testAtomicRoundTripKeepsWebKeysAndOmitsNulls() async throws {
        let store = DocumentStore(url: directory.appendingPathComponent("data.json"))
        let document = try sampleDocument()
        try await store.save(document, version: 1)
        let loaded = try await store.load()
        XCTAssertEqual(loaded, document)

        let raw = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: store.url)) as? [String: Any])
        XCTAssertEqual(Set(raw.keys), ["settings", "subjects", "courses", "revisions", "pronoteEvents", "initialized"])
        let pending = try XCTUnwrap((raw["revisions"] as? [[String: Any]])?.first { $0["status"] as? String == "pending" })
        XCTAssertNil(pending["completedDate"], "les valeurs absentes sont omises comme JSON.stringify")
        XCTAssertNil(pending["score"])
        XCTAssertFalse(String(decoding: try Data(contentsOf: store.url), as: UTF8.self).contains("null"))
    }

    func testMissingFileReturnsNilAndOlderSaveIsIgnored() async throws {
        let store = DocumentStore(url: directory.appendingPathComponent("data.json"))
        let empty = try await store.load()
        XCTAssertNil(empty)
        var newer = try sampleDocument()
        newer.courses.removeAll()
        try await store.save(newer, version: 2)
        try await store.save(try sampleDocument(), version: 1)
        let loaded = try await store.load()
        XCTAssertEqual(loaded?.courses, [])
    }

    func testUnreadableFileIsReportedNotOverwritten() async throws {
        let url = directory.appendingPathComponent("data.json")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("{ abîmé".utf8).write(to: url)
        let store = DocumentStore(url: url)
        do {
            _ = try await store.load()
            XCTFail("un fichier illisible doit lever une erreur")
        } catch {
            XCTAssertEqual(error as? DocumentStore.Failure, .unreadable)
        }
        XCTAssertEqual(try Data(contentsOf: url), Data("{ abîmé".utf8))
        try await store.archiveUnreadable()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        let archived = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        XCTAssertEqual(archived.count, 1)
        XCTAssertTrue(archived[0].hasPrefix("jmethod-data-illisible-"))
    }

    func testPartialWebSettingsTakeDefaults() throws {
        let json = #"{"courses":[],"revisions":[],"settings":{"sensitivity":0.7,"lastPronoteSync":"2026-11-17T18:00:00.000Z"},"version":"1.0","exportDate":"2026-11-17T18:00:00.000Z"}"#
        let document = try DocumentStore.decode(Data(json.utf8))
        XCTAssertEqual(document.settings.sensitivity, 0.7)
        XCTAssertEqual(document.settings.intervals, Constants.defaultIntervals)
        XCTAssertEqual(document.settings.emergencyThreshold, 30)
        XCTAssertEqual(document.settings.lastPronoteSync, "2026-11-17T18:00:00.000Z")
        XCTAssertEqual(document.subjects, Constants.defaultSubjects)
        XCTAssertEqual(document.pronoteEvents, [])
        XCTAssertTrue(document.initialized)
    }
}

@MainActor
final class AppModelTests: XCTestCase {
    private final class Clock: @unchecked Sendable {
        // Utilisé uniquement depuis le MainActor du test.
        var now: Date
        init(_ now: Date) { self.now = now }
    }

    private func date(_ iso: String) -> Date { ISO8601DateFormatter().date(from: iso)! }

    private func makeModel(at iso: String, url: URL? = nil, preferences: UserDefaults? = nil) -> (AppModel, Clock, URL, UserDefaults) {
        let file = url ?? FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-model-\(UUID().uuidString).json")
        addTeardownBlock { try? FileManager.default.removeItem(at: file) }
        let defaults: UserDefaults
        if let preferences { defaults = preferences } else {
            let suite = "jmethod-tests-\(UUID().uuidString)"
            addTeardownBlock { UserDefaults().removePersistentDomain(forName: suite) }
            defaults = UserDefaults(suiteName: suite)!
        }
        let clock = Clock(date(iso))
        return (AppModel(store: DocumentStore(url: file), now: { clock.now }, preferences: defaults), clock, file, defaults)
    }

    /// Modèle chargé sur un document de test déjà enregistré.
    private func loadedModel(at iso: String, today: String) async throws -> (AppModel, Clock, URL) {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-model-\(UUID().uuidString).json")
        try await DocumentStore(url: file).save(try Fixture.document(today: today), version: 1)
        let (model, clock, url, _) = makeModel(at: iso, url: file)
        await model.load()
        return (model, clock, url)
    }

    private func waitForSaved(_ url: URL, until condition: (AppDocument) -> Bool) async throws -> AppDocument? {
        var saved: AppDocument?
        for _ in 0..<50 {
            saved = try? await DocumentStore(url: url).load()
            if let saved, condition(saved) { return saved }
            try await Task.sleep(for: .milliseconds(20))
        }
        return saved
    }

    func testFirstLaunchCreatesEmptyDocumentWithDefaultConfiguration() async throws {
        // 23:30 UTC le 17 = 00:30 le 18 à Paris.
        let (model, _, url, defaults) = makeModel(at: "2026-11-17T23:30:00Z")
        await model.load()
        XCTAssertEqual(model.loadState, .ready)
        XCTAssertEqual(model.today, "2026-11-18")
        XCTAssertEqual(model.document, AppModel.emptyDocument)
        XCTAssertEqual(model.document.subjects, Constants.defaultSubjects)
        XCTAssertEqual(model.document.settings, Constants.defaultSettings)
        XCTAssertTrue(model.document.courses.isEmpty && model.document.revisions.isEmpty && model.document.pronoteEvents.isEmpty)
        XCTAssertTrue(defaults.bool(forKey: AppModel.demoMigrationKey))
        let saved = try await waitForSaved(url) { _ in true }
        XCTAssertEqual(saved, AppModel.emptyDocument)
        let screen = TodayScreen(today: model.today, courses: [], revisions: [], subjects: model.document.subjects, pronoteEvents: [])
        XCTAssertTrue(screen.emptyProgramme)
        XCTAssertEqual(screen.hero.title, "Tu peux souffler !")
    }

    func testExistingDemoInstallationIsMigratedOnceAndRealEventsKept() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-model-\(UUID().uuidString).json")
        var installed = try Fixture.document(today: "2026-11-17")
        let demoCourse = Course(id: "course-maths-1", subjectId: "maths", chapter: "Arithmétique", courseNumber: 1, title: "Démo", date: "2026-10-23", createdAt: "2026-10-23")
        installed.courses.append(demoCourse)
        installed.revisions += try AdaptiveAlgorithm.initialRevisions(for: demoCourse, intervals: Constants.defaultIntervals)
        installed.revisions.append(Revision(id: "course-maths-1-emerg-1794942000000", courseId: "course-maths-1", stepIndex: 3, stepLabel: "J4* (Rattrapage)", scheduledDate: "2026-11-19", status: .pending, phase: .consolidation, adjustedIntervalDays: 1, isEmergencySession: true))
        installed.pronoteEvents.append(PronoteEvent(id: "pronote-demo-1", subjectName: "Mathématiques", matchedSubjectId: "maths", date: "2026-11-18", startTime: "08:30", endTime: "09:30", isCancelled: false, isCourseCreated: false))
        try await DocumentStore(url: url).save(installed, version: 1)

        let (model, _, _, defaults) = makeModel(at: "2026-11-17T19:00:00Z", url: url)
        await model.load()
        let expected = try Fixture.document(today: "2026-11-17")
        XCTAssertEqual(model.document, expected)
        XCTAssertTrue(defaults.bool(forKey: AppModel.demoMigrationKey))
        let saved = try await DocumentStore(url: url).load()
        XCTAssertEqual(saved, expected, "la migration est enregistrée avant d'être marquée faite")

        // Migration unique : un document chargé plus tard n'est plus filtré.
        try await DocumentStore(url: url).save(installed, version: 1)
        let (again, _, _, _) = makeModel(at: "2026-11-17T19:00:00Z", url: url, preferences: defaults)
        await again.load()
        XCTAssertEqual(again.document, installed)
    }

    func testDayRefreshesAtMidnight() async throws {
        let (model, clock, _) = try await loadedModel(at: "2026-11-17T22:59:00Z", today: "2026-11-17")
        XCTAssertEqual(model.today, "2026-11-17")
        clock.now = date("2026-11-17T23:01:00Z")
        model.refreshDay()
        XCTAssertEqual(model.today, "2026-11-18")
        XCTAssertEqual(model.programme.preparationDate, "2026-11-19")
    }

    func testValidationReschedulesFromPreparedDayAndPersists() async throws {
        let (model, _, url) = try await loadedModel(at: "2026-11-17T19:00:00Z", today: "2026-11-17")
        // J0 du cours du jour (17) : sa date cible est passée, il est dû ce soir.
        let target = try XCTUnwrap(model.programme.due.first { $0.id == "course-test-2-rev-0" })
        let explanation = try XCTUnwrap(model.validate(revisionId: target.id, score: 50))
        XCTAssertEqual(explanation.nextDate, "2026-11-19", "J1 posé depuis le jour préparé (18)")
        let done = try XCTUnwrap(model.document.revisions.first { $0.id == target.id })
        XCTAssertEqual(done.completedDate, "2026-11-17", "historique à la date réelle")
        XCTAssertEqual(done.score, 50)
        XCTAssertEqual(model.toast?.title, "Révision validée (50/100)")
        XCTAssertNil(model.validate(revisionId: target.id, score: 80), "une séance terminée n'est pas revalidée")
        let saved = try await waitForSaved(url) { $0.revisions.first { $0.id == target.id }?.status == .completed }
        XCTAssertEqual(saved?.revisions.first { $0.id == target.id }?.status, .completed)
    }

    func testCompletingTheEveningCelebrates() async throws {
        let (model, _, _) = try await loadedModel(at: "2026-11-17T19:00:00Z", today: "2026-11-17")
        XCTAssertFalse(model.programme.due.isEmpty)
        var guardCount = 0
        while let next = model.programme.due.first, guardCount < 50 {
            XCTAssertEqual(model.celebration, 0)
            model.validate(revisionId: next.id, score: 60)
            guardCount += 1
        }
        XCTAssertTrue(model.programme.due.isEmpty)
        XCTAssertEqual(model.celebration, 1)
    }

    func testPronoteSyncReplacesEventsAndDisconnectKeepsCourses() async throws {
        let (model, _, _) = try await loadedModel(at: "2026-11-17T19:00:00Z", today: "2026-11-17")
        let lesson = PronoteLesson(id: "x-2026-11-18T08:00:00-0", subject: "S.V.T.", start: "2026-11-18T08:00:00", end: "2026-11-18T09:00:00", teacher: "", room: "", cancelled: false)
        model.applyPronote(lessons: [lesson], syncedAt: date("2026-11-17T19:00:00Z"))
        XCTAssertEqual(model.document.pronoteEvents.map(\.matchedSubjectId), ["svt"])
        XCTAssertEqual(model.document.settings.lastPronoteSync, "2026-11-17T19:00:00.000Z")
        let courses = model.document.courses, revisions = model.document.revisions
        model.clearPronoteData()
        XCTAssertEqual(model.document.pronoteEvents, [])
        XCTAssertNil(model.document.settings.lastPronoteSync)
        XCTAssertEqual(model.document.courses, courses, "les cours restent après la déconnexion")
        XCTAssertEqual(model.document.revisions, revisions)
    }
}

final class AccessTests: XCTestCase {
    func testRouting() {
        XCTAssertEqual(AppRoute.resolve(load: .loading, access: .signedOut), .loading)
        XCTAssertEqual(AppRoute.resolve(load: .loading, access: .resuming), .resuming, "pas d'écran de connexion pendant la reprise")
        XCTAssertEqual(AppRoute.resolve(load: .ready, access: .resuming), .resuming)
        XCTAssertEqual(AppRoute.resolve(load: .ready, access: .signedOut), .signIn)
        XCTAssertEqual(AppRoute.resolve(load: .ready, access: .offline), .app(offline: true))
        XCTAssertEqual(AppRoute.resolve(load: .ready, access: .connected), .app(offline: false))
        XCTAssertEqual(AppRoute.resolve(load: .failed("x"), access: .connected), .loadFailure("x"))
    }

    func testResumeFailureClassification() {
        XCTAssertEqual(PronoteSession.classifyResumeFailure(PronoteFailure.invalid("login", "")), .signInRequired)
        XCTAssertEqual(PronoteSession.classifyResumeFailure(PronoteFailure.invalid("mfa", "")), .signInRequired)
        XCTAssertEqual(PronoteSession.classifyResumeFailure(URLError(.notConnectedToInternet)), .unavailable)
        XCTAssertEqual(PronoteSession.classifyResumeFailure(URLError(.timedOut)), .unavailable)
        XCTAssertEqual(PronoteSession.classifyResumeFailure(PronoteFailure.invalid("http-503", "")), .unavailable)
        XCTAssertEqual(PronoteSession.classifyResumeFailure(PronoteFailure.invalid("pronote-25", "")), .unavailable)
        XCTAssertEqual(PronoteSession.classifyResumeFailure(PronoteFailure.invalid("keychain", "")), .unavailable)
    }
}
