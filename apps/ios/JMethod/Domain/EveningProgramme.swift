import Foundation

/// Portage de `src/lib/eveningProgramme.ts` : le soir prépare le jour calendaire suivant,
/// l'historique garde la date réelle de validation.
struct EveningProgramme: Equatable, Sendable {
    var preparationDate: String
    var due: [Revision]
    var completed: [Revision]
    var overdue: [Revision]
    var visible: [Revision]

    init(revisions: [Revision], courses: [Course], today: String) {
        preparationDate = SchoolDay.adding(1, to: today) ?? today
        let courseIds = Set(courses.map(\.id))
        let valid = revisions.filter { courseIds.contains($0.courseId) }
        let preparation = preparationDate
        // Tri stable par date cible, comme Array.prototype.sort + localeCompare sur des dates ISO.
        due = valid.enumerated()
            .filter { $0.element.status != .completed && $0.element.scheduledDate <= preparation }
            .sorted { $0.element.scheduledDate != $1.element.scheduledDate ? $0.element.scheduledDate < $1.element.scheduledDate : $0.offset < $1.offset }
            .map(\.element)
        completed = valid.filter { $0.status == .completed && $0.completedDate == today }
        overdue = due.filter { $0.scheduledDate < preparation }
        visible = due + completed
    }

    /// Séances prévues pour le jour préparé (hors retards) : compteur du web `pendingCountToday`.
    var pendingCount: Int { due.count - overdue.count }
    var total: Int { due.count + completed.count }
    /// Pourcentage arrondi comme `Math.round(completed / total * 100)`.
    var progressPercent: Int { total == 0 ? 0 : jsRound(Double(completed.count) / Double(total) * 100) }
}
