import Foundation

/// Détail d'une séance (`RevisionDetailModal.tsx`, suite `revisionDetail`).
/// Écart D10 : libellés d'étape Découvrir / S'entraîner / Se tester au lieu de Encodage / Consolidation / Récupération.
struct RevisionDetail: Equatable {
    struct HistoryItem: Equatable, Identifiable {
        var revision: Revision
        var label: String
        var detail: String
        var id: String { revision.id }
    }

    var revision: Revision
    var course: Course
    var subject: Subject
    var title: String
    var chapterLine: String
    var phaseLabel: String
    var plannedLine: String
    var description: String?
    var historyTitle: String
    var history: [HistoryItem]
    var historyEmpty: String?
    var completedLine: String?
    var canValidate: Bool

    /// nil (le détail ne s'ouvre pas) si la séance, son cours ou sa matière n'existe plus.
    init?(revisionId: String, revisions: [Revision], courses: [Course], subjects: [Subject]) {
        guard let revision = revisions.first(where: { $0.id == revisionId }),
              let course = courses.first(where: { $0.id == revision.courseId }),
              let subject = subjects.first(where: { $0.id == course.subjectId }) else { return nil }
        self.revision = revision
        self.course = course
        self.subject = subject
        title = "C\(course.courseNumber) · \(course.title)"
        chapterLine = "Chapitre : \(course.chapter)"
        phaseLabel = Constants.phaseConfig(revision.phase).label
        plannedLine = "Prévue le \(FrenchDate.format(revision.scheduledDate, "d MMMM yyyy"))"
        description = course.description
        let items = Self.history(of: revision, courseId: course.id, in: revisions)
        historyTitle = "Sessions précédentes (\(items.count))"
        history = items.map { item in
            HistoryItem(
                revision: item,
                label: "\(item.stepLabel) · \(Constants.phaseConfig(item.phase).label)",
                detail: "\(FrenchDate.format(item.completedDate ?? item.scheduledDate, "dd/MM/yyyy")) · \(item.score.map { "\($0)/100" } ?? "Sans note")"
            )
        }
        historyEmpty = items.isEmpty ? "Aucune session terminée avant celle-ci." : nil
        if revision.status == .completed {
            let when = revision.completedDate.map { " le \(FrenchDate.format($0, "dd/MM/yyyy"))" } ?? ""
            completedLine = "Séance terminée\(when) · \(revision.score.map(String.init) ?? "—")/100"
        } else {
            completedLine = nil
        }
        canValidate = revision.status != .completed
    }

    /// Séances terminées du même cours, hors celle-ci ; si elle est terminée, seulement celles dont la date est inférieure ou égale.
    /// Tri par date décroissante, puis étape décroissante, puis ordre du tableau.
    static func history(of revision: Revision, courseId: String, in revisions: [Revision]) -> [Revision] {
        let reference = revision.completedDate ?? revision.scheduledDate
        return revisions.enumerated()
            .filter { _, item in
                item.courseId == courseId && item.id != revision.id && item.status == .completed
                    && (revision.status != .completed || (item.completedDate ?? item.scheduledDate) <= reference)
            }
            .sorted { lhs, rhs in
                let a = lhs.element.completedDate ?? lhs.element.scheduledDate
                let b = rhs.element.completedDate ?? rhs.element.scheduledDate
                if a != b { return a > b }
                if lhs.element.stepIndex != rhs.element.stepIndex { return lhs.element.stepIndex > rhs.element.stepIndex }
                return lhs.offset < rhs.offset
            }
            .map(\.element)
    }
}
