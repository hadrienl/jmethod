import XCTest
@testable import JMethod

final class TodayWidgetTests: XCTestCase {
    func testSharedVectors() throws {
        let data = try SharedVectors.object("today-widget-vectors.json", subdirectory: "domain")
        let day = try XCTUnwrap(data["day"] as? String)
        for row in try XCTUnwrap(data["cases"] as? [[String: Any]]) {
            func ids(_ key: String) -> [String] { row[key] as? [String] ?? [] }
            func revisions(_ key: String) -> [Revision] {
                ids(key).map { Revision(id: $0, courseId: "c1", stepIndex: 0, stepLabel: "J0", scheduledDate: day,
                                       status: key == "done" ? .completed : .pending, phase: .encodage, courseIds: ["c1", "c2"]) }
            }
            let label = try XCTUnwrap(row["name"] as? String)
            var visit = TodayWidgetVisit(lastVisit: day)
            visit.returnDay = row["returning"] as? Bool == true ? day : nil
            visit.selection = ids("selection")
            let widget = TodayWidgetState(day: day, due: revisions("due"), completed: revisions("done"),
                                          overdue: revisions("overdue"), finishedIds: Set(ids("done")), visit: visit,
                                          flameEarned: row["flameEarned"] as? Bool == true)
            XCTAssertEqual(widget.mode.rawValue, row["state"] as? String, label)
            XCTAssertEqual(widget.next?.id, row["next"] as? String, label)
            XCTAssertEqual(widget.remaining, row["remaining"] as? Int ?? ids("due").count, label)
            if let outside = row["outside"] as? Int { XCTAssertEqual(widget.outsidePlan, outside, label) }
        }
    }

    func testVisitAndSelectionRoundTrip() throws {
        var visit = TodayWidgetVisit(lastVisit: "2026-11-21")
        let document = AppDocument(settings: Constants.defaultSettings, subjects: [], courses: [], revisions: [],
                                   pronoteEvents: [], initialized: true)
        let programme = EveningProgramme(revisions: [], courses: [], today: "2026-11-24")
        visit.open("2026-11-24", programme: programme, document: document)
        XCTAssertEqual(visit.returnDay, "2026-11-24")
        visit.open("2026-11-24", programme: programme, document: document)
        XCTAssertEqual(visit.returnDay, "2026-11-24")
        visit.selection = ["a", "b"]
        XCTAssertEqual(try JSONDecoder().decode(TodayWidgetVisit.self, from: JSONEncoder().encode(visit)), visit)
        visit.open("2026-11-25", programme: programme, document: document)
        XCTAssertNil(visit.returnDay)
        XCTAssertTrue(visit.selection.isEmpty)
    }
}
