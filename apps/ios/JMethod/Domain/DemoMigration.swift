import Foundation

/// Migration unique des installations qui avaient reçu le jeu de démonstration du jalon 1.
/// Retire les cours de démonstration (identifiants connus), toutes leurs révisions (rattrapages compris)
/// et les créneaux d'exemple ; les créneaux issus d'une vraie synchronisation Pronote sont conservés.
/// La transformation est idempotente : l'appliquer deux fois ne change rien de plus.
enum DemoMigration {
    static let demoCourseIds: Set<String> = [
        "course-maths-1", "course-maths-2", "course-maths-3", "course-maths-5", "course-maths-14",
        "course-pc-1", "course-pc-2", "course-pc-4", "course-fr-3",
    ]
    /// Créneaux de l'emploi du temps d'exemple (`pronote-demo-1` à `pronote-demo-5`).
    static let demoEventPrefix = "pronote-demo-"

    static func removingDemoData(from document: AppDocument) -> AppDocument {
        var result = document
        result.courses.removeAll { demoCourseIds.contains($0.id) }
        result.revisions.removeAll { demoCourseIds.contains($0.courseId) }
        result.pronoteEvents.removeAll { $0.id.hasPrefix(demoEventPrefix) }
        return result
    }

    static func containsDemoData(_ document: AppDocument) -> Bool {
        removingDemoData(from: document) != document
    }
}
