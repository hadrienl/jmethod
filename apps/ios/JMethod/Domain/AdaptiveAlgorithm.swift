import Foundation

/// Portage à l'identique de `src/lib/adaptiveAlgorithm.ts`.
enum AdaptiveAlgorithm {
    enum Failure: Error, Equatable, LocalizedError {
        case revisionNotFound(String)
        case invalidDate(String)

        /// Même texte que l'exception du web.
        var errorDescription: String? {
            switch self {
            case .revisionNotFound(let id): "Revision \(id) non trouvée"
            case .invalidDate(let value): "Date invalide : \(value)"
            }
        }
    }

    enum ActionType: String, Codable, Sendable {
        case emergency, shortened, exact, expanded
    }

    struct Explanation: Equatable, Sendable {
        var score: Int
        var factor: Double
        var message: String
        var actionType: ActionType
        var nextDate: String?
    }

    struct Result: Equatable, Sendable {
        var updatedRevisions: [Revision]
        var explanation: Explanation
    }

    static func phase(forStep stepIndex: Int) -> Phase {
        if stepIndex == 0 { return .encodage }
        if stepIndex <= 3 { return .consolidation }
        return .recuperation
    }

    static func initialRevisions(for course: Course, intervals: [Int]) throws -> [Revision] {
        try intervals.enumerated().map { index, interval in
            guard let scheduled = SchoolDay.adding(interval, to: course.date) else { throw Failure.invalidDate(course.date) }
            return Revision(
                id: "\(course.id)-rev-\(index)",
                courseId: course.id,
                stepIndex: index,
                stepLabel: "J\(interval)",
                scheduledDate: scheduled,
                status: .pending,
                phase: phase(forStep: index),
                adjustedIntervalDays: index == 0 ? 0 : interval - intervals[index - 1]
            )
        }
    }

    /// - Parameters:
    ///   - actualCompletionDate: date réelle de la séance, conservée dans l'historique (par défaut : jour de `now` à Paris).
    ///   - schedulingReferenceDate: jour préparé (le lendemain le soir) qui sert d'ancre aux dates suivantes (par défaut : `actualCompletionDate`).
    ///   - now: horloge injectée ; ses millisecondes forment l'identifiant d'un rattrapage, comme `Date.now()`.
    static func applyReview(
        courseRevisions: [Revision],
        revisionId: String,
        score: Int,
        settings: AppSettings,
        actualCompletionDate: String? = nil,
        schedulingReferenceDate: String? = nil,
        now: Date = Date()
    ) throws -> Result {
        let completionDate = actualCompletionDate ?? SchoolDay.today(now: now)
        let emergencyTimestamp = epochMilliseconds(now)
        // Tri stable par étape, comme Array.prototype.sort.
        var revisions = courseRevisions.enumerated()
            .sorted { $0.element.stepIndex != $1.element.stepIndex ? $0.element.stepIndex < $1.element.stepIndex : $0.offset < $1.offset }
            .map(\.element)
        guard let targetIndex = revisions.firstIndex(where: { $0.id == revisionId }) else { throw Failure.revisionNotFound(revisionId) }

        var current = revisions[targetIndex]
        current.completedDate = completionDate
        current.score = score
        current.status = .completed
        revisions[targetIndex] = current

        let factor = 1 + (Double(score) - 50) / 50 * settings.sensitivity
        let nextPendingIndex = revisions.indices.first { index in
            index > targetIndex && revisions[index].status == .pending && revisions[index].isEmergencySession != true
        }
        let reference = schedulingReferenceDate ?? completionDate
        let interval: (Int) -> Int? = { settings.intervals.indices.contains($0) ? settings.intervals[$0] : nil }

        var actionType = ActionType.exact
        var message = ""
        var nextDate: String?

        if Double(score) < settings.emergencyThreshold {
            actionType = .emergency
            guard let emergencyDate = SchoolDay.adding(1, to: reference) else { throw Failure.invalidDate(reference) }
            nextDate = emergencyDate
            let emergency = Revision(
                id: "\(current.courseId)-emerg-\(emergencyTimestamp)",
                courseId: current.courseId,
                stepIndex: current.stepIndex,
                stepLabel: "\(current.stepLabel)* (Rattrapage)",
                scheduledDate: emergencyDate,
                status: .pending,
                phase: .consolidation,
                adjustedIntervalDays: 1,
                isEmergencySession: true
            )
            revisions.insert(emergency, at: targetIndex + 1)
            message = "Score difficile (\(score)/100) : Une session de rattrapage immédiate est programmée pour demain afin de consolider l'ancrage."
        } else if let nextPendingIndex {
            var next = revisions[nextPendingIndex]
            let currentInterval = interval(current.stepIndex) ?? 0
            let nextInterval = interval(next.stepIndex) ?? currentInterval + 1
            let defaultStepGap = max(1, nextInterval - currentInterval)
            let adaptedGap = max(1, jsRound(Double(defaultStepGap) * factor))
            guard let scheduled = SchoolDay.adding(adaptedGap, to: reference) else { throw Failure.invalidDate(reference) }
            nextDate = scheduled
            next.scheduledDate = scheduled
            next.adjustedIntervalDays = adaptedGap
            revisions[nextPendingIndex] = next

            var lastDate = scheduled
            var index = nextPendingIndex + 1
            while index < revisions.count {
                let stepGap = max(1, (interval(revisions[index].stepIndex) ?? 0) - (interval(revisions[index - 1].stepIndex) ?? 0))
                let scaled = max(1, jsRound(Double(stepGap) * factor))
                guard let date = SchoolDay.adding(scaled, to: lastDate) else { throw Failure.invalidDate(lastDate) }
                lastDate = date
                revisions[index].scheduledDate = date
                revisions[index].adjustedIntervalDays = scaled
                index += 1
            }

            if score == 50 {
                actionType = .exact
                message = "Niveau idéal (50/100) : Intervalle d'origine parfaitement conservé (\(dayCount(adaptedGap))). Prochaine révision le \(scheduled)."
            } else if score > 50 {
                actionType = .expanded
                let percent = jsRound((factor - 1) * 100)
                message = "Excellente maîtrise (\(score)/100) : Intervalle espacé de +\(percent)% (\(dayCount(adaptedGap)) au lieu de \(defaultStepGap)j). Prochaine révision le \(scheduled)."
            } else {
                actionType = .shortened
                let percent = jsRound((1 - factor) * 100)
                message = "Difficultés constatées (\(score)/100) : Intervalle rapproché de -\(percent)% (\(dayCount(adaptedGap)) au lieu de \(defaultStepGap)j). Prochaine révision le \(scheduled)."
            }
        } else {
            message = "Bravo ! Tu as validé le cycle complet de révisions de ce cours avec la note de \(score)/100 !"
        }

        return Result(
            updatedRevisions: revisions,
            explanation: .init(score: score, factor: factor, message: message, actionType: actionType, nextDate: nextDate)
        )
    }

    /// Écart D7 (`DEVIATIONS.md`) : accord du pluriel, « 1 jour » au lieu de « 1 jours » du web.
    static func dayCount(_ days: Int) -> String { "\(days) jour\(abs(days) > 1 ? "s" : "")" }

    static func epochMilliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }
}
