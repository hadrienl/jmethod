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
        try DemoData.document(today: "2026-11-17", random: { 0.5 })
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

    private func makeModel(at iso: String) -> (AppModel, Clock, URL) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-model-\(UUID().uuidString).json")
        let clock = Clock(date(iso))
        let model = AppModel(store: DocumentStore(url: url), now: { clock.now })
        return (model, clock, url)
    }

    func testFirstLaunchLoadsDemoDataForParisDay() async throws {
        // 23:30 UTC le 17 = 00:30 le 18 à Paris.
        let (model, _, url) = makeModel(at: "2026-11-17T23:30:00Z")
        defer { try? FileManager.default.removeItem(at: url) }
        await model.load()
        XCTAssertEqual(model.loadState, .ready)
        XCTAssertEqual(model.today, "2026-11-18")
        XCTAssertEqual(model.document.courses.count, 9)
        XCTAssertEqual(model.document.pronoteEvents.first?.date, "2026-11-19")
        XCTAssertTrue(model.document.revisions.filter { $0.scheduledDate < "2026-11-18" }.allSatisfy { $0.status == .completed && (65...89).contains($0.score ?? -1) })
    }

    func testDayRefreshesAtMidnight() async throws {
        let (model, clock, url) = makeModel(at: "2026-11-17T22:59:00Z")
        defer { try? FileManager.default.removeItem(at: url) }
        await model.load()
        XCTAssertEqual(model.today, "2026-11-17")
        clock.now = date("2026-11-17T23:01:00Z")
        model.refreshDay()
        XCTAssertEqual(model.today, "2026-11-18")
        XCTAssertEqual(model.programme.preparationDate, "2026-11-19")
    }

    func testValidationReschedulesFromPreparedDayAndPersists() async throws {
        let (model, _, url) = makeModel(at: "2026-11-17T19:00:00Z")
        defer { try? FileManager.default.removeItem(at: url) }
        await model.load()
        // J0 du cours du jour (17) : sa date cible est passée, il est dû ce soir.
        let target = try XCTUnwrap(model.programme.due.first { $0.id == "course-maths-14-rev-0" })
        let explanation = try XCTUnwrap(model.validate(revisionId: target.id, score: 50))
        XCTAssertEqual(explanation.nextDate, "2026-11-19", "J1 posé depuis le jour préparé (18)")
        let done = try XCTUnwrap(model.document.revisions.first { $0.id == target.id })
        XCTAssertEqual(done.completedDate, "2026-11-17", "historique à la date réelle")
        XCTAssertEqual(done.score, 50)
        XCTAssertEqual(model.toast?.title, "Révision validée (50/100)")
        XCTAssertNil(model.validate(revisionId: target.id, score: 80), "une séance terminée n'est pas revalidée")

        // L'écriture est asynchrone : on attend qu'elle soit visible sur disque.
        var saved: AppDocument?
        for _ in 0..<50 {
            saved = try? await DocumentStore(url: url).load()
            if saved?.revisions.first(where: { $0.id == target.id })?.status == .completed { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertEqual(saved?.revisions.first { $0.id == target.id }?.status, .completed)
    }

    func testCompletingTheEveningCelebrates() async throws {
        let (model, _, url) = makeModel(at: "2026-11-17T19:00:00Z")
        defer { try? FileManager.default.removeItem(at: url) }
        await model.load()
        var guardCount = 0
        while let next = model.programme.due.first, guardCount < 50 {
            XCTAssertEqual(model.celebration, 0)
            model.validate(revisionId: next.id, score: 60)
            guardCount += 1
        }
        XCTAssertTrue(model.programme.due.isEmpty)
        XCTAssertEqual(model.celebration, 1)
    }

    func testPronoteSyncReplacesEventsAndForgetClearsThem() async throws {
        let (model, _, url) = makeModel(at: "2026-11-17T19:00:00Z")
        defer { try? FileManager.default.removeItem(at: url) }
        await model.load()
        let lesson = PronoteLesson(id: "x-2026-11-18T08:00:00-0", subject: "S.V.T.", start: "2026-11-18T08:00:00", end: "2026-11-18T09:00:00", teacher: "", room: "", cancelled: false)
        model.applyPronote(lessons: [lesson], syncedAt: date("2026-11-17T19:00:00Z"))
        XCTAssertEqual(model.document.pronoteEvents.map(\.matchedSubjectId), ["svt"])
        XCTAssertEqual(model.document.settings.lastPronoteSync, "2026-11-17T19:00:00.000Z")
        XCTAssertEqual(TodayScreen(today: model.today, courses: [], revisions: [], subjects: model.document.subjects, pronoteEvents: model.document.pronoteEvents).tomorrowEvents.count, 1)
        model.clearPronoteData()
        XCTAssertEqual(model.document.pronoteEvents, [])
        XCTAssertNil(model.document.settings.lastPronoteSync)
    }
}
