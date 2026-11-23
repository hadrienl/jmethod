import Foundation

enum RevisionDayStatus: String, Codable, Equatable, Sendable {
    case pending, earned, rest, missed
}

struct RevisionDay: Codable, Equatable, Sendable {
    var date: String
    var requiredRevisionIds: [String]
    var completedRevisionIds: [String]
    var frozenAt: String
    var finalizedAt: String?
    var status: RevisionDayStatus
}

struct StreakState: Codable, Equatable, Sendable {
    var trackingStartDate: String
    var lastReconciledDate: String
    var currentCount = 0
    var bestCount = 0
    var days: [RevisionDay]
}

/// Un journal commun à Aujourd'hui et aux statistiques ; aucune reconstruction d'une ancienne série à partir des scores.
enum RevisionStreak {
    struct Failure: LocalizedError { var errorDescription: String? { "Le journal des journées de révision est incohérent." } }

    static func requiredIds(_ document: AppDocument, date: String) throws -> [String] {
        guard let preparation = SchoolDay.adding(1, to: date) else { throw Failure() }
        let courses = Set(document.courses.map(\.id))
        let eligible = document.revisions.enumerated().filter { _, revision in
            courses.contains(revision.courseId) && (
                revision.status == .completed && revision.completedDate == date ||
                revision.scheduledDate <= preparation && (revision.status != .completed || revision.completedDate.map { $0 > date } == true)
            )
        }.sorted { a, b in
            a.element.scheduledDate != b.element.scheduledDate ? a.element.scheduledDate < b.element.scheduledDate : a.offset < b.offset
        }
        var seen = Set<String>()
        return eligible.map(\.element.id).filter { seen.insert($0).inserted }
    }

    private static func freeze(_ document: AppDocument, date: String, stamp: String, close: Bool) throws -> RevisionDay {
        let required = try requiredIds(document, date: date)
        let done = Set(document.revisions.filter { $0.status == .completed && $0.completedDate == date }.map(\.id))
        let completed = required.filter { done.contains($0) }
        let status: RevisionDayStatus
        if required.isEmpty { status = .rest }
        else if completed.count == required.count { status = .earned }
        else { status = close ? .missed : .pending }
        return RevisionDay(date: date, requiredRevisionIds: required, completedRevisionIds: completed, frozenAt: stamp,
                           finalizedAt: status == .pending ? nil : stamp, status: status)
    }

    static func derived(_ state: StreakState) -> StreakState {
        var current = 0, best = 0
        for day in state.days.sorted(by: { $0.date < $1.date }) {
            switch day.status {
            case .earned: current += 1; best = max(best, current)
            case .missed: current = 0
            case .pending, .rest: break
            }
        }
        var result = state; result.currentCount = current; result.bestCount = best
        return result
    }

    static func validate(_ state: StreakState) throws {
        func require(_ condition: Bool) throws { if !condition { throw Failure() } }
        try require(!state.days.isEmpty && SchoolDay.isValid(state.trackingStartDate) && SchoolDay.isValid(state.lastReconciledDate))
        try require(state.days.first?.date == state.trackingStartDate && state.days.last?.date == state.lastReconciledDate)
        try require(zip(state.days, state.days.dropFirst()).allSatisfy { SchoolDay.adding(1, to: $0.date) == $1.date })
        let fractional = ISO8601DateFormatter(); fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter()
        func instant(_ value: String) -> Bool { fractional.date(from: value) != nil || plain.date(from: value) != nil }
        for day in state.days {
            try require(SchoolDay.isValid(day.date) && instant(day.frozenAt))
            if let finalized = day.finalizedAt { try require(instant(finalized)) }
            try require(day.requiredRevisionIds.allSatisfy { !$0.isEmpty } && Set(day.requiredRevisionIds).count == day.requiredRevisionIds.count)
            try require(Set(day.completedRevisionIds).count == day.completedRevisionIds.count && Set(day.completedRevisionIds).isSubset(of: Set(day.requiredRevisionIds)))
            let allDone = !day.requiredRevisionIds.isEmpty && day.completedRevisionIds.count == day.requiredRevisionIds.count
            switch day.status {
            case .rest: try require(day.requiredRevisionIds.isEmpty && day.completedRevisionIds.isEmpty && day.finalizedAt != nil)
            case .earned: try require(allDone && day.finalizedAt != nil)
            case .missed: try require(!day.requiredRevisionIds.isEmpty && !allDone && day.finalizedAt != nil)
            case .pending: try require(!day.requiredRevisionIds.isEmpty && !allDone && day.finalizedAt == nil && day.date == state.lastReconciledDate)
            }
        }
    }

    /// En arrière-plan, clôture le passé sans geler une nouvelle journée avant l'ouverture fonctionnelle.
    static func reconcile(_ document: AppDocument, now: Date, openCurrent: Bool = false) throws -> AppDocument {
        let today = SchoolDay.today(now: now), stamp = Backup.isoMillis(now)
        var result = document
        guard let existing = document.streakState else {
            guard openCurrent else { return document }
            let first = try freeze(document, date: today, stamp: stamp, close: false)
            result.schemaVersion = 3
            result.streakState = derived(StreakState(trackingStartDate: today, lastReconciledDate: today, days: [first]))
            return result
        }
        try validate(existing)
        if today < existing.lastReconciledDate {
            result.schemaVersion = 3; result.streakState = derived(existing); return result
        }
        var days = existing.days
        var date = existing.lastReconciledDate
        while date < today {
            if let index = days.lastIndex(where: { $0.date == date }) {
                if days[index].status == .pending { days[index].status = .missed; days[index].finalizedAt = stamp }
            } else { days.append(try freeze(document, date: date, stamp: stamp, close: true)) }
            guard let next = SchoolDay.adding(1, to: date) else { throw Failure() }
            date = next
        }
        if openCurrent && !days.contains(where: { $0.date == today }) { days.append(try freeze(document, date: today, stamp: stamp, close: false)) }
        var state = existing; state.days = days; state.lastReconciledDate = days.last!.date
        result.schemaVersion = 3; result.streakState = derived(state)
        return result
    }

    /// La validation et cette progression sont persistées ensemble par l'appelant.
    static func completed(_ document: AppDocument, revisionId: String, now: Date) throws -> AppDocument {
        var result = try reconcile(document, now: now)
        guard var state = result.streakState else { return result }
        let today = SchoolDay.today(now: now)
        guard let index = state.days.lastIndex(where: { $0.date == today }), state.days[index].status == .pending,
              state.days[index].requiredRevisionIds.contains(revisionId),
              document.revisions.contains(where: { $0.id == revisionId && $0.status == .completed && $0.completedDate == today }) else { return result }
        var entry = state.days[index]
        let done = Set(entry.completedRevisionIds + [revisionId])
        entry.completedRevisionIds = entry.requiredRevisionIds.filter { done.contains($0) }
        if entry.completedRevisionIds.count == entry.requiredRevisionIds.count { entry.status = .earned; entry.finalizedAt = Backup.isoMillis(now) }
        state.days[index] = entry
        result.streakState = derived(state)
        return result
    }

    /// Séances figées que la mutation `before` → `after`, décidée par l'app (adaptation après une validation, rythme
    /// réappliqué aux programmes en cours), a sorties du programme du jour : elles ne conditionnent plus la flamme.
    /// Une séance recréée sous un autre identifiant (même programme, même étape) et encore due reste exigée sous ce
    /// nouvel identifiant. Ce qui manquait déjà avant la mutation (suppression ou report de l'élève) reste bloquant.
    static func releaseMovedByApp(before: AppDocument, after: AppDocument, now: Date) throws -> AppDocument {
        guard var state = after.streakState else { return after }
        let today = SchoolDay.today(now: now)
        guard let index = state.days.lastIndex(where: { $0.date == today }), state.days[index].status == .pending else { return after }
        guard let preparation = SchoolDay.adding(1, to: today) else { throw Failure() }
        func due(_ revision: Revision?) -> Bool {
            guard let revision else { return false }
            return revision.status == .completed || revision.scheduledDate <= preparation
        }
        func step(_ revision: Revision) -> String? {
            guard revision.isEmergencySession != true, let programme = revision.programmeId, let step = revision.rhythmStepId else { return nil }
            return programme + "\u{1F}" + step
        }
        let previous = Dictionary(before.revisions.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let current = Dictionary(after.revisions.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let created = after.revisions.filter { previous[$0.id] == nil }
        var entry = state.days[index]
        var seen = Set<String>()
        let required = entry.requiredRevisionIds.compactMap { id -> String? in
            let old = previous[id]
            // Absente ou repoussée avant cette mutation : décision de l'élève, reste bloquante.
            if !due(old) { return id }
            if due(current[id]) { return id }
            if current[id] != nil { return nil } // Repoussée par l'app.
            guard let old, let key = step(old), let replacement = created.first(where: { step($0) == key }), due(replacement) else { return nil }
            return replacement.id
        }.filter { seen.insert($0).inserted }
        guard required != entry.requiredRevisionIds else { return after }
        let completed = entry.completedRevisionIds.filter { required.contains($0) }
        entry.requiredRevisionIds = required
        entry.completedRevisionIds = completed
        if required.isEmpty { entry.completedRevisionIds = []; entry.status = .rest; entry.finalizedAt = Backup.isoMillis(now) }
        else if completed.count == required.count { entry.status = .earned; entry.finalizedAt = Backup.isoMillis(now) }
        state.days[index] = entry
        var result = after
        result.streakState = derived(state)
        return result
    }

    /// « 0 journée », « 1 journée », « 2 journées » : accord français, 0 et 1 au singulier.
    static func days(_ count: Int) -> String { "\(count) journée\(count > 1 ? "s" : "")" }

    struct Summary: Equatable {
        var count: Int
        var record: Int
        var status: String
        var title: String
        var message: String
        var remaining: Int
    }

    static func summary(_ value: StreakState?, date: String) -> Summary {
        let state = value.map(derived), count = state?.currentCount ?? 0, record = state?.bestCount ?? 0
        guard let entry = state?.days.first(where: { $0.date == date }) else {
            return Summary(count: count, record: record, status: "waiting", title: "\(days(count)) de révision", message: "Le suivi reprendra à la prochaine journée de révision.", remaining: 0)
        }
        let remaining = entry.requiredRevisionIds.filter { !entry.completedRevisionIds.contains($0) }.count
        // Les pauses ne masquent pas une interruption : on regarde la dernière journée qui comptait.
        let interrupted = state?.days.last(where: { $0.date < date && $0.status != .rest })?.status == .missed
        switch entry.status {
        case .earned:
            return Summary(count: count, record: record, status: "earned", title: "Flamme gagnée !", message: "Toutes tes révisions sont terminées : série de \(days(count)) !", remaining: 0)
        case .rest where interrupted:
            return Summary(count: count, record: record, status: "rest", title: "Série interrompue", message: "Aucun J aujourd’hui. Ta prochaine journée de révision lancera une nouvelle série.", remaining: 0)
        case .rest:
            return Summary(count: count, record: record, status: "rest", title: "Flamme en pause", message: "Aucun J aujourd’hui, ta série est conservée.", remaining: 0)
        case .missed:
            return Summary(count: count, record: record, status: "missed", title: "Série interrompue", message: "Une journée de révision est restée incomplète. Tu peux recommencer une nouvelle série.", remaining: remaining)
        case .pending:
            return Summary(count: count, record: record, status: "pending", title: interrupted ? "Une nouvelle série commence ici" : "À compléter", message: "Encore \(remaining) révision\(remaining > 1 ? "s" : "") pour gagner la flamme du jour.", remaining: remaining)
        }
    }
}
