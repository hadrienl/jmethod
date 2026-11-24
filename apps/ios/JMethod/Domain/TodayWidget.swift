import Foundation

struct TodayWidgetVisit: Codable, Equatable, Sendable {
    var lastVisit: String
    var returnDay: String?
    var selection: [String] = []
    var removedCount: Int = 0

    private enum CodingKeys: String, CodingKey { case lastVisit, returnDay, selection, removedCount }

    init(lastVisit: String) { self.lastVisit = lastVisit }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        lastVisit = try values.decode(String.self, forKey: .lastVisit)
        returnDay = try values.decodeIfPresent(String.self, forKey: .returnDay)
        selection = try values.decodeIfPresent([String].self, forKey: .selection) ?? []
        removedCount = try values.decodeIfPresent(Int.self, forKey: .removedCount) ?? 0
    }

    mutating func open(_ day: String, programme: EveningProgramme, document: AppDocument) {
        let advancing = day > lastVisit
        if advancing {
            returnDay = SchoolDay.adding(3, to: lastVisit).map { $0 <= day } == true ? day : nil
            lastVisit = day
        }
        let eligible = Set(programme.due.map(\.id))
        if advancing && !selection.contains(where: eligible.contains) {
            selection = []
            removedCount = 0
        }
        reconcile(programme: programme, document: document)
    }

    mutating func reconcile(programme: EveningProgramme, document: AppDocument) {
        let eligible = Set(programme.due.map(\.id))
        let courses = Set(document.courses.map(\.id))
        let completed = document.revisions.filter { $0.status == .completed && courses.contains($0.courseId) }.map(\.id)
        let valid = eligible.union(completed)
        var seen = Set<String>()
        let unique = selection.filter { seen.insert($0).inserted }
        selection = unique.filter(valid.contains)
        removedCount = max(0, removedCount) + unique.count - selection.count
    }
}

struct TodayWidgetState {
    enum Mode: String {
        case rest, ready, focus, progress, done, returned, resume, plan, planActive, planDone, newWork

        var art: String {
            switch self {
            case .rest: "rest"
            case .ready, .returned: "ready"
            case .focus, .resume, .plan, .newWork: "focus"
            case .progress, .planActive: "motion"
            case .done, .planDone: "joy"
            }
        }
    }

    var mode: Mode
    var next: Revision?
    var remaining: Int
    var completed: Int
    var overdue: Int
    var outsidePlan: Int
    var removedFromPlan: Int

    init(day: String, programme: EveningProgramme, document: AppDocument) {
        self.init(day: day, due: programme.due, completed: programme.completed, overdue: programme.overdue,
                  finishedIds: Set(document.revisions.filter { $0.status == .completed }.map(\.id)),
                  visit: document.todayWidgetVisit,
                  flameEarned: RevisionStreak.summary(document.streakState, date: day).status == "earned")
    }

    init(day: String, due: [Revision], completed: [Revision], overdue: [Revision],
         finishedIds: Set<String>, visit: TodayWidgetVisit?, flameEarned: Bool) {
        var seen = Set<String>()
        let ids = (visit?.selection ?? []).filter { seen.insert($0).inserted }
        let selected = Self.orderedSelection(ids, due: due)
        let selectedComplete = ids.filter(finishedIds.contains).count
        let outside = due.count - selected.count
        let returned = visit?.returnDay == day
        if !selected.isEmpty { mode = .planActive }
        else if selectedComplete > 0 && outside > 0 { mode = .planDone }
        else if due.isEmpty && !completed.isEmpty { mode = .done }
        else if flameEarned && !due.isEmpty { mode = .newWork }
        else if returned && overdue.count >= 4 { mode = .plan }
        else if returned && !overdue.isEmpty { mode = .resume }
        else if returned { mode = .returned }
        else if due.isEmpty { mode = .rest }
        else if !completed.isEmpty { mode = .progress }
        else if due.count < 4 { mode = .ready }
        else { mode = .focus }
        switch mode {
        case .planActive: next = selected.first
        case .resume: next = overdue.first
        case .plan, .planDone: next = nil
        default: next = due.first
        }
        remaining = mode == .planActive ? selected.count : due.count
        self.completed = mode == .planActive || mode == .planDone ? selectedComplete : completed.count
        self.overdue = overdue.count
        outsidePlan = outside
        removedFromPlan = max(0, visit?.removedCount ?? 0)
    }

    static func orderedSelection(_ ids: [String], due: [Revision]) -> [Revision] {
        let byID = Dictionary(due.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var seen = Set<String>()
        return ids.filter { seen.insert($0).inserted }.compactMap { byID[$0] }
    }

    static func suggested(_ programme: EveningProgramme) -> [String] {
        var seen = Set<String>()
        return Array((programme.overdue + programme.due).map(\.id).filter { seen.insert($0).inserted }.prefix(3))
    }
}
