import Foundation

enum SuccessSound: String, Sendable {
    case revision, homework, evening, resume, flame, record, programme

    var asset: String { "success_\(rawValue)" }

    static func afterRevision(before: AppDocument, after: AppDocument, id: String, day: String, eveningDone: Bool) -> Self? {
        guard after.settings.soundEnabled,
              let revision = before.revisions.first(where: { $0.id == id }),
              revision.status != .completed else { return nil }
        let completed = Set(after.revisions.filter { $0.status == .completed }.map(\.id))
        guard completed.contains(id) else { return nil }
        let programmeDone = revision.programmeId.map { programmeId in
            before.revisions.filter { $0.programmeId == programmeId }.allSatisfy { completed.contains($0.id) }
        } ?? false
        let old = RevisionStreak.summary(before.streakState, date: day)
        let new = RevisionStreak.summary(after.streakState, date: day)
        let flame = old.status != "earned" && new.status == "earned"
        let selection = before.todayWidgetVisit?.selection ?? []
        if programmeDone { return .programme }
        if flame && old.record > 0 && new.record > old.record { return .record }
        if eveningDone { return .evening }
        if selection.contains(id) && selection.allSatisfy(completed.contains) { return .resume }
        if flame { return .flame }
        return .revision
    }
}
