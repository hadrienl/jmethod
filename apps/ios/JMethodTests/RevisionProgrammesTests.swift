import XCTest
@testable import JMethod

final class RevisionProgrammesTests: XCTestCase {
    private func sheet(_ number: Int, subject: String = "maths") -> Course {
        var result = Course(id: "c\(number)", subjectId: subject, chapter: "Sans chapitre", courseNumber: number,
                            title: "C\(number)", date: "2026-11-22", createdAt: "2026-11-22")
        result.reference = "C\(number)"
        return result
    }
    private func document() -> AppDocument {
        RevisionProgrammes.migrate(AppDocument(settings: Constants.defaultSettings, subjects: Constants.defaultSubjects,
            courses: [sheet(1),sheet(2),sheet(3)], revisions: [], pronoteEvents: [], initialized: true))
    }
    private func score(_ document: AppDocument, id: String, value: Int = 50) throws -> ScoreSubmission.Outcome {
        try ScoreSubmission.submit(revisions: document.revisions, courses: document.courses, settings: document.settings,
            revisionId: id, score: value, now: ISO8601DateFormatter().date(from: "2026-11-22T19:00:00Z")!, programmes: document.programmes)
    }
    func testSharedCalendarsPhasesAndMembership() throws {
        let vectors = try SharedVectors.object("programme-vectors.json", subdirectory: "domain")
        let rhythm = try XCTUnwrap(vectors["rhythm"] as? [String: Any])
        XCTAssertEqual(RevisionRhythm.standard.steps.map(\.offsetDays), rhythm["days"] as? [Int])
        XCTAssertEqual(RevisionRhythm.standard.steps.map { $0.phase.rawValue }, rhythm["phases"] as? [String])
        for row in try XCTUnwrap(vectors["calendars"] as? [[String: Any]]) {
            let result = try RevisionProgrammes.start(document(), courseIds: ["c1","c2"], startDate: try XCTUnwrap(row["start"] as? String), id: "p")
            XCTAssertEqual(result.revisions.map(\.scheduledDate), row["dates"] as? [String])
            XCTAssertEqual(result.revisions.count, 9)
            XCTAssertTrue(result.revisions.allSatisfy { $0.courseIds == ["c1","c2"] })
            XCTAssertFalse(result.revisions.contains { $0.applies(to: "c3") })
            XCTAssertEqual(RevisionProgrammes.references(result.revisions[0], courses: result.courses), "C1 + C2")
        }
    }
    func testFixedModeSnapshotsAndEveningIdempotence() throws {
        var base = document(); base.settings.adaptiveEnabled = false
        var started = try RevisionProgrammes.start(base, courseIds: ["c1","c2"], startDate: "2026-11-23", id: "p")
        let original = started
        started.settings.intervals = [0,200]; started.settings.adaptiveEnabled = true
        started.settings.rhythm = RevisionRhythm(id: "other", name: "Autre", revision: 1, steps: [.init(id: "zero", offsetDays: 0, phase: .recuperation)])
        let outcome = try score(started, id: started.revisions[0].id, value: 0)
        XCTAssertEqual(outcome.revisions.filter { $0.id != started.revisions[0].id }, Array(original.revisions.dropFirst()))
        XCTAssertEqual(outcome.revisions.first { $0.status == .completed }?.completedDate, "2026-11-22")
        XCTAssertEqual(outcome.revisions.first { $0.stepLabel == "J1" }?.scheduledDate, "2026-11-24")
        started.revisions = outcome.revisions
        XCTAssertTrue(try score(started, id: original.revisions[0].id).ignored)
        var adaptive = try RevisionProgrammes.start(document(), courseIds: ["c1"], startDate: "2026-11-23", id: "a")
        adaptive.settings = started.settings
        let after = try score(adaptive, id: adaptive.revisions[0].id)
        XCTAssertEqual(after.revisions.first { $0.stepLabel == "J1" }?.scheduledDate, "2026-11-24")
        XCTAssertTrue(adaptive.programmes[0].adaptiveEnabled)
    }
    func testReapplyRetainsHistoryAndEmergencyAndNominalGaps() throws {
        var doc = try RevisionProgrammes.start(document(), courseIds: ["c1","c2"], startDate: "2026-11-23", id: "p")
        doc.revisions = try score(doc, id: doc.revisions[0].id, value: 0).revisions
        let retained = doc.revisions.filter { $0.status == .completed || $0.isEmergencySession == true }
        let rhythm = RevisionRhythm(id: "new", name: "Nouveau", revision: 2, steps: [
            .init(id: "zero", offsetDays: 0, phase: .encodage), .init(id: "three", offsetDays: 3, phase: .encodage), .init(id: "ten", offsetDays: 10, phase: .consolidation)])
        let applied = try RevisionProgrammes.reapply(doc, programmeIds: ["p"], rhythm: rhythm)
        XCTAssertTrue(retained.allSatisfy { applied.revisions.contains($0) })
        XCTAssertEqual(applied.revisions.filter { $0.status != .completed && $0.isEmergencySession != true }.map(\.stepLabel), ["J3","J10"])
        XCTAssertEqual(applied, try RevisionProgrammes.reapply(applied, programmeIds: ["p"], rhythm: rhythm))
        let emergency = try XCTUnwrap(applied.revisions.first { $0.isEmergencySession == true })
        let scored = try score(applied, id: emergency.id)
        XCTAssertEqual(scored.revisions.first { $0.stepLabel == "J3" }?.scheduledDate, "2026-11-26")
        XCTAssertEqual(scored.revisions.first { $0.stepLabel == "J10" }?.scheduledDate, "2026-12-03")
    }
    func testMigrationAndProtectedStorage() async throws {
        var course = sheet(1)
        course.documents = [CourseDocument(id: "d", name: "fiche.pdf", mime: "application/pdf", size: 12, file: "documents/c1/d.pdf")]
        var old = AppDocument(settings: Constants.defaultSettings, subjects: Constants.defaultSubjects, courses: [course],
            revisions: try AdaptiveAlgorithm.initialRevisions(for: course, intervals: [0,1,7]), pronoteEvents: [], initialized: true)
        old.revisions[0].status = .completed; old.revisions[0].score = 75; old.revisions[0].completedDate = "2026-11-22"
        let migrated = RevisionProgrammes.migrate(old)
        XCTAssertEqual(old.courses, migrated.courses)
        XCTAssertEqual(old.revisions, migrated.revisions.map { value in
            var r = value; r.programmeId = nil; r.courseIds = nil; r.rhythmStepId = nil; r.nominalOffsetDays = nil; return r
        })
        XCTAssertEqual(migrated, RevisionProgrammes.migrate(migrated))
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("jmethod-data.json")
        let before = try DocumentStore.encode(old); try before.write(to: url)
        let store = DocumentStore(url: url)
        try await store.save(migrated, version: 1)
        XCTAssertEqual(try Data(contentsOf: url), before)
        let loaded = try await store.load()
        XCTAssertEqual(loaded, migrated)
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent("jmethod-data-v2.json").path))
    }
    func testExportRestoreAndInvalidGroup() throws {
        let doc = try RevisionProgrammes.start(document(), courseIds: ["c1","c2"], startDate: "2026-11-23", id: "p")
        let text = Backup.export(doc, now: Date(timeIntervalSince1970: 0))
        let envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
        XCTAssertNil(envelope["courses"], "une ancienne app refuse cette sauvegarde au lieu de perdre les programmes")
        XCTAssertNotNil(envelope["data"])
        guard case .accepted(let accepted) = Backup.parse(Data(text.utf8)) else { return XCTFail("Export refusé") }
        let restored = accepted.apply(to: document())
        XCTAssertEqual(restored.programmes, doc.programmes); XCTAssertEqual(restored.revisions, doc.revisions)
        XCTAssertEqual(restored.courses, doc.courses); XCTAssertEqual(restored.settings, doc.settings)
        guard case .rejected = Backup.parse(Data(text.replacingOccurrences(of: "\"schemaVersion\" : 2", with: "\"schemaVersion\" : 3").utf8)) else { return XCTFail("Version future acceptée") }
        let invalid = text.replacingOccurrences(of: "\"programmeId\" : \"p\"", with: "\"programmeId\" : \"absent\"")
        guard case .rejected = Backup.parse(Data(invalid.utf8)) else { return XCTFail("Groupe orphelin accepté") }
    }
    func testEmptyVersionedExportCanBeRestored() throws {
        var empty = document(); empty.courses = []
        let text = Backup.export(empty, now: Date(timeIntervalSince1970: 0))
        guard case .accepted(let accepted) = Backup.parse(Data(text.utf8)) else { return XCTFail("Export vide refusé") }
        XCTAssertEqual(accepted.apply(to: document()).programmes, [])
        XCTAssertEqual(accepted.apply(to: document()).courses, [])
    }
    func testInvalidSelectionsAndRhythms() throws {
        var doc = document(); doc.courses.append(sheet(4, subject: "fr"))
        XCTAssertThrowsError(try RevisionProgrammes.start(doc, courseIds: ["c1","c4"], startDate: "2026-11-23"))
        XCTAssertThrowsError(try RevisionProgrammes.start(doc, courseIds: ["absent"], startDate: "2026-11-23"))
        let started = try RevisionProgrammes.start(doc, courseIds: ["c1"], startDate: "2026-11-23")
        XCTAssertThrowsError(try RevisionProgrammes.start(started, courseIds: ["c1","c2"], startDate: "2026-11-23"))
        for days in [[1,3],[0,0],[0,-1],[0,3651]] {
            var rhythm = RevisionRhythm.standard
            rhythm.steps = days.enumerated().map { .init(id: "\($0.offset)", offsetDays: $0.element, phase: .encodage) }
            XCTAssertThrowsError(try RevisionProgrammes.validate(rhythm))
        }
    }
}
