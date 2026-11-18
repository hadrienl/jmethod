import Foundation

/// Aperçu de l'autoévaluation, porté de `ScoreModal.tsx`.
struct ScorePreview: Equatable, Sendable {
    enum Tone: String, Sendable { case emergency, shortened, exact, expanded }
    enum Band: String, Sendable { case rose, amber, sky, emerald, indigo }

    var tone: Tone
    var title: String
    var description: String
    var band: Band
    var confettiOnSubmit: Bool

    init(score: Int, sensitivity: Double, emergencyThreshold: Double) {
        let factor = 1 + (Double(score) - 50) / 50 * sensitivity
        if Double(score) < emergencyThreshold {
            tone = .emergency
            title = "Rattrapage nécessaire"
            description = "Oubli important : une session de rattrapage immédiate sera programmée pour demain (J+1)."
        } else if score < 50 {
            tone = .shortened
            title = "Révision rapprochée"
            description = "Difficultés constatées : les prochaines révisions seront rapprochées de -\(jsRound((1 - factor) * 100))%."
        } else if score > 50 {
            tone = .expanded
            title = "Révision espacée"
            description = "Très bonne maîtrise : les prochaines révisions seront espacées de +\(jsRound((factor - 1) * 100))%."
        } else {
            tone = .exact
            title = "On garde le même rythme"
            description = "Tu retrouves les idées principales. Ta prochaine séance garde le même espacement."
        }
        band = score < 30 ? .rose : score < 50 ? .amber : score == 50 ? .sky : score < 85 ? .emerald : .indigo
        confettiOnSubmit = score >= 75
    }
}

/// Validation d'une séance (`App.handleScoreSubmit`) : replanification depuis le jour préparé,
/// nouvel ordre global (autres cours puis ce cours trié par étape), feu d'artifice et message.
enum ScoreSubmission {
    struct Toast: Equatable, Sendable {
        enum Kind: String, Sendable { case success, warning }
        var title: String
        var message: String
        var kind: Kind
    }

    struct Outcome: Equatable, Sendable {
        var today: String
        var preparationDate: String?
        var ignored: Bool
        var revisions: [Revision]
        var celebrate: Bool
        var toast: Toast?
        var explanation: AdaptiveAlgorithm.Explanation?
    }

    static func submit(revisions: [Revision], courses: [Course], settings: AppSettings, revisionId: String, score: Int, now: Date) throws -> Outcome {
        let today = SchoolDay.today(now: now)
        guard let target = revisions.first(where: { $0.id == revisionId }), target.status != .completed else {
            return Outcome(today: today, preparationDate: nil, ignored: true, revisions: revisions, celebrate: false, toast: nil, explanation: nil)
        }
        let before = EveningProgramme(revisions: revisions, courses: courses, today: today)
        let result = try AdaptiveAlgorithm.applyReview(
            courseRevisions: revisions.filter { $0.courseId == target.courseId }, revisionId: revisionId, score: score,
            settings: settings, actualCompletionDate: today, schedulingReferenceDate: before.preparationDate, now: now
        )
        let updated = revisions.filter { $0.courseId != target.courseId } + result.updatedRevisions
        let after = EveningProgramme(revisions: updated, courses: courses, today: today)
        let emergency = result.explanation.actionType == .emergency
        return Outcome(
            today: today,
            preparationDate: before.preparationDate,
            ignored: false,
            revisions: updated,
            celebrate: !before.due.isEmpty && after.due.isEmpty,
            toast: Toast(title: emergency ? "Rattrapage programmé" : "Révision validée (\(score)/100)", message: result.explanation.message, kind: emergency ? .warning : .success),
            explanation: result.explanation
        )
    }
}
