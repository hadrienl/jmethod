import XCTest
@testable import JMethod

final class SuccessSoundTests: XCTestCase {
    func testSharedPriorityVectors() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "success-sound-vectors", withExtension: "json"))
        let cases = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [[String: Any]])
        let day = "2026-11-25"
        let priorDay = "2026-11-24"
        for item in cases {
            func flag(_ key: String) -> Bool { item[key] as? Bool == true }
            func entry(_ date: String, earned: Bool) -> RevisionDay {
                RevisionDay(date: date, requiredRevisionIds: ["r"], completedRevisionIds: earned ? ["r"] : [],
                            frozenAt: "\(date)T12:00:00Z", status: earned ? .earned : .pending)
            }
            let prior = flag("prior") ? [entry(priorDay, earned: true)] : []
            let revision = Revision(id: "r", courseId: "c", stepIndex: 0, stepLabel: "J0", scheduledDate: day,
                                    status: flag("duplicate") ? .completed : .pending, phase: .encodage,
                                    programmeId: flag("programme") ? "p" : nil)
            var before = AppDocument(settings: Constants.defaultSettings, subjects: [], courses: [],
                                     revisions: [revision], pronoteEvents: [], initialized: true)
            before.settings.soundEnabled = !flag("muted")
            before.streakState = StreakState(trackingStartDate: prior.first?.date ?? day, lastReconciledDate: day,
                                             days: prior + [entry(day, earned: false)])
            if flag("resume") {
                var visit = TodayWidgetVisit(lastVisit: day)
                visit.selection = [revision.id]
                before.todayWidgetVisit = visit
            }
            var after = before
            if !flag("unchanged") { after.revisions[0].status = .completed }
            if flag("recovery") {
                var recovery = revision
                recovery.id = "recovery"
                recovery.isEmergencySession = true
                after.revisions.append(recovery)
            }
            after.streakState?.days = prior + [entry(day, earned: flag("flame"))]
            let actual = SuccessSound.afterRevision(before: before, after: after, id: revision.id, day: day, eveningDone: flag("evening"))
            XCTAssertEqual(actual?.rawValue, item["expected"] as? String, item["case"] as? String ?? "")
        }
    }
}
