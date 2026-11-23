import XCTest
@testable import JMethod

final class RevisionStreakTests: XCTestCase {
    private func revision(_ id: String, _ date: String) -> Revision {
        Revision(id: id, courseId: "c1", stepIndex: 0, stepLabel: "J0", scheduledDate: date, status: .pending, phase: .encodage,
                 programmeId: "p", courseIds: ["c1","c2"])
    }
    private func document(_ revisions: [Revision]) -> AppDocument {
        var result = AppDocument(settings: Constants.defaultSettings, subjects: Constants.defaultSubjects, courses: [
            Course(id: "c1", subjectId: "maths", chapter: "", courseNumber: 1, title: "C1", date: "2026-11-01", createdAt: "2026-11-01"),
            Course(id: "c2", subjectId: "maths", chapter: "", courseNumber: 2, title: "C2", date: "2026-11-01", createdAt: "2026-11-01")],
            revisions: revisions, pronoteEvents: [], initialized: true)
        result.schemaVersion = 2
        return result
    }

    func testSharedEventVectors() throws {
        let fixture = try SharedVectors.object("streak-vectors.json", subdirectory: "domain")
        for value in try XCTUnwrap(fixture["cases"] as? [[String: Any]]) {
            let name = try XCTUnwrap(value["name"] as? String)
            var doc = document(try XCTUnwrap(value["revisions"] as? [[String: String]]).map { revision($0["id"]!, $0["date"]!) })
            for (index,event) in try XCTUnwrap(value["events"] as? [[String: Any]]).enumerated() {
                let label = "\(name) / \(index)"
                let now = try XCTUnwrap(ISO8601DateFormatter().date(from: try XCTUnwrap(event["at"] as? String)))
                let day = SchoolDay.today(now: now)
                let frozen = Dictionary(uniqueKeysWithValues: (doc.streakState?.days ?? []).map { ($0.date, $0.frozenAt) })
                let id = event["id"] as? String
                switch event["op"] as? String {
                case "open": doc = try RevisionStreak.reconcile(doc, now: now, openCurrent: true)
                case "complete":
                    doc = try RevisionStreak.reconcile(doc, now: now, openCurrent: true)
                    doc.revisions = doc.revisions.map { original in
                        var r = original
                        if r.id == id && r.status != .completed { r.status = .completed; r.completedDate = day; r.score = 0 }
                        return r
                    }
                    doc = try RevisionStreak.completed(doc, revisionId: XCTUnwrap(id), now: now)
                case "add":
                    doc = try RevisionStreak.reconcile(doc, now: now)
                    doc.revisions.append(revision(try XCTUnwrap(id), try XCTUnwrap(event["date"] as? String)))
                case "delete":
                    doc = try RevisionStreak.reconcile(doc, now: now)
                    doc.revisions.removeAll { $0.id == id }
                case "move":
                    doc = try RevisionStreak.reconcile(doc, now: now)
                    let date = try XCTUnwrap(event["date"] as? String)
                    doc.revisions = doc.revisions.map { original in var r = original; if r.id == id { r.scheduledDate = date }; return r }
                default: XCTFail("Événement inconnu"); continue
                }
                let state = try XCTUnwrap(doc.streakState)
                try RevisionStreak.validate(state)
                XCTAssertEqual(state.currentCount, event["count"] as? Int, label)
                XCTAssertEqual(state.bestCount, event["best"] as? Int, label)
                XCTAssertEqual(state.days.first { $0.date == day }?.status.rawValue, event["status"] as? String, label)
                if let required = event["required"] as? [String] { XCTAssertEqual(state.days.first { $0.date == day }?.requiredRevisionIds, required, label) }
                for (date,status) in event["past"] as? [String: String] ?? [:] {
                    XCTAssertEqual(state.days.first { $0.date == date }?.status.rawValue, status, label)
                }
                for entry in state.days { if let original = frozen[entry.date] { XCTAssertEqual(entry.frozenAt, original, label) } }
                XCTAssertEqual(doc, try RevisionStreak.reconcile(doc, now: now, openCurrent: true), label)
            }
        }
    }

    func testMigrationAndBackgroundDoNotInventPastFlames() throws {
        var historical = revision("old", "2026-11-01"); historical.status = .completed; historical.completedDate = "2026-11-01"; historical.score = 100
        let doc = document([historical])
        let now = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-11-23T18:00:00Z"))
        XCTAssertEqual(doc, try RevisionStreak.reconcile(doc, now: now))
        let state = try XCTUnwrap(RevisionStreak.reconcile(doc, now: now, openCurrent: true).streakState)
        XCTAssertEqual(state.trackingStartDate, "2026-11-23")
        XCTAssertEqual(state.currentCount, 0); XCTAssertEqual(state.bestCount, 0)
        XCTAssertEqual(state.days.count, 1); XCTAssertEqual(state.days.first?.status, .rest)
    }

    /// Export puis restauration : journal et record repris, jours écoulés clôturés ; un ancien export n'invente rien.
    func testExportRestoreKeepsJournalAndRecord() throws {
        func day(_ date: String, _ status: RevisionDayStatus) -> RevisionDay {
            RevisionDay(date: date, requiredRevisionIds: ["r-\(date)"], completedRevisionIds: status == .earned ? ["r-\(date)"] : [],
                        frozenAt: "\(date)T17:00:00.000Z", finalizedAt: "\(date)T22:00:00.000Z", status: status)
        }
        let state = RevisionStreak.derived(StreakState(trackingStartDate: "2026-11-13", lastReconciledDate: "2026-11-17", days: [
            day("2026-11-13", .earned), day("2026-11-14", .earned), day("2026-11-15", .missed), day("2026-11-16", .earned), day("2026-11-17", .earned)]))
        var doc = document([]); doc.schemaVersion = 3; doc.streakState = state
        XCTAssertEqual(try JSONDecoder().decode(AppDocument.self, from: DocumentStore.encode(doc)).streakState, state)

        let iso = ISO8601DateFormatter()
        let exported = Backup.export(doc, now: try XCTUnwrap(iso.date(from: "2026-11-17T20:00:00Z")))
        guard case .accepted(let accepted) = Backup.parse(Data(exported.utf8)) else { return XCTFail("sauvegarde v3 refusée") }
        XCTAssertEqual(accepted.streakState, state)
        let now = try XCTUnwrap(iso.date(from: "2026-11-19T18:00:00Z"))
        let restored = try XCTUnwrap(RevisionStreak.reconcile(accepted.apply(to: document([])), now: now, openCurrent: true).streakState)
        XCTAssertEqual(restored.currentCount, 2); XCTAssertEqual(restored.bestCount, 2)
        XCTAssertEqual(restored.days.suffix(2).map(\.date), ["2026-11-18", "2026-11-19"])
        XCTAssertEqual(restored.days.suffix(2).map(\.status), [.rest, .rest])

        var old = doc; old.schemaVersion = 2; old.streakState = nil
        guard case .accepted(let legacy) = Backup.parse(Data(Backup.export(old, now: now).utf8)) else { return XCTFail("sauvegarde v2 refusée") }
        XCTAssertNil(legacy.streakState)
        let fresh = try XCTUnwrap(RevisionStreak.reconcile(legacy.apply(to: doc), now: now, openCurrent: true).streakState)
        XCTAssertEqual(fresh.trackingStartDate, "2026-11-19"); XCTAssertEqual(fresh.bestCount, 0); XCTAssertEqual(fresh.days.count, 1)
    }
}
