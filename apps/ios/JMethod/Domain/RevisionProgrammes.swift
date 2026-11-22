import Foundation

struct RhythmStep: Codable, Equatable, Hashable, Sendable, Identifiable {
    var id: String
    var offsetDays: Int
    var phase: Phase
}

struct RevisionRhythm: Codable, Equatable, Hashable, Sendable {
    var id: String
    var name: String
    var revision: Int
    var steps: [RhythmStep]
    static let standard = RevisionRhythm(id: "default", name: "Rythme des J", revision: 1,
        steps: [0, 1, 3, 7, 14, 25, 45, 60, 100].map { day in
            RhythmStep(id: "day-\(day)", offsetDays: day, phase: day <= 3 ? .encodage : day <= 25 ? .consolidation : .recuperation)
        })
}

struct RevisionProgramme: Codable, Equatable, Sendable, Identifiable {
    var id: String
    var subjectId: String
    var courseIds: [String]
    var startDate: String
    var rhythmSnapshot: RevisionRhythm
    var adaptiveEnabled: Bool
    var needsReview: Bool = false
}

enum RevisionProgrammes {
    struct Failure: LocalizedError {
        var message: String
        var errorDescription: String? { message }
    }
    static func validate(_ rhythm: RevisionRhythm) throws {
        guard !rhythm.id.isEmpty, !rhythm.name.isEmpty, rhythm.revision > 0, rhythm.steps.allSatisfy({ !$0.id.isEmpty }), !rhythm.steps.isEmpty, rhythm.steps.first?.offsetDays == 0 else { throw Failure(message: "Le rythme doit commencer à J0.") }
        guard rhythm.steps.allSatisfy({ (0...3650).contains($0.offsetDays) }),
              zip(rhythm.steps, rhythm.steps.dropFirst()).allSatisfy({ $0.offsetDays < $1.offsetDays }),
              Set(rhythm.steps.map(\.id)).count == rhythm.steps.count else {
            throw Failure(message: "Les jours J doivent être uniques, croissants et compris entre 0 et 3650.")
        }
    }
    static func validateDocument(_ document: AppDocument, checkSubjects: Bool = true) throws {
        func require(_ condition: Bool) throws { if !condition { throw Failure(message: "Les feuilles, programmes ou rythmes sont incohérents.") } }
        try require(document.schemaVersion == 2)
        try require(Set(document.courses.map(\.id)).count == document.courses.count)
        try require(Set(document.revisions.map(\.id)).count == document.revisions.count)
        try require(Set(document.programmes.map(\.id)).count == document.programmes.count)
        if let rhythm = document.settings.rhythm { try validate(rhythm) }
        for programme in document.programmes {
            try require(!programme.courseIds.isEmpty && Set(programme.courseIds).count == programme.courseIds.count)
            try require(!checkSubjects || document.subjects.contains { $0.id == programme.subjectId })
            try require(programme.courseIds.allSatisfy { id in document.courses.contains { $0.id == id && $0.subjectId == programme.subjectId } })
            try require(SchoolDay.isValid(programme.startDate))
            if !programme.needsReview { try validate(programme.rhythmSnapshot) }
        }
        for revision in document.revisions {
            guard let programme = document.programmes.first(where: { $0.id == revision.programmeId }) else { throw Failure(message: "Une séance n’a plus de programme.") }
            try require(revision.courseIds == programme.courseIds && revision.courseId == programme.courseIds.first)
            try require(revision.score.map { (0...100).contains($0) } ?? true)
            try require(SchoolDay.isValid(revision.scheduledDate) && (revision.completedDate.map(SchoolDay.isValid) ?? true))
            if !programme.needsReview && revision.status != .completed { try require(revision.nominalOffsetDays != nil && revision.rhythmStepId != nil) }
        }
    }
    static func references(_ revision: Revision, courses: [Course]) -> String {
        (revision.courseIds ?? [revision.courseId]).compactMap { id in courses.first { $0.id == id }?.displayReference }.joined(separator: " + ")
    }
    static func start(_ document: AppDocument, courseIds: [String], startDate: String, id: String = "programme-\(UUID().uuidString)") throws -> AppDocument {
        let courses = courseIds.compactMap { id in document.courses.first { $0.id == id } }
        guard !courseIds.isEmpty, Set(courseIds).count == courseIds.count, courses.count == courseIds.count,
              Set(courses.map(\.subjectId)).count == 1, SchoolDay.isValid(startDate), !document.programmes.contains(where: { $0.id == id }) else {
            throw Failure(message: "Sélectionne des feuilles existantes de la même matière et une date valide.")
        }
        guard courses.allSatisfy({ c in !document.revisions.contains { $0.applies(to: c.id) && $0.status != .completed } }) else {
            throw Failure(message: "Une feuille a déjà un programme en cours. Termine-le avant d’en commencer un autre.")
        }
        let rhythm = document.settings.rhythm ?? .standard
        try validate(rhythm)
        let programme = RevisionProgramme(id: id, subjectId: courses[0].subjectId, courseIds: courseIds, startDate: startDate,
                                          rhythmSnapshot: rhythm, adaptiveEnabled: document.settings.adaptiveEnabled)
        var result = document
        result.programmes.append(programme)
        result.schemaVersion = 2
        result.revisions += try rhythm.steps.enumerated().map { index, step in
            guard let date = SchoolDay.adding(step.offsetDays, to: startDate) else { throw Failure(message: "Date invalide.") }
            return Revision(id: "\(id)-\(step.id)", courseId: courseIds[0], stepIndex: index, stepLabel: "J\(step.offsetDays)",
                            scheduledDate: date, status: .pending, phase: step.phase,
                            adjustedIntervalDays: index == 0 ? 0 : step.offsetDays - rhythm.steps[index-1].offsetDays,
                            programmeId: id, courseIds: courseIds, rhythmStepId: step.id, nominalOffsetDays: step.offsetDays)
        }
        return result
    }
    static func reapply(_ document: AppDocument, programmeIds: Set<String>, rhythm: RevisionRhythm) throws -> AppDocument {
        try validate(rhythm)
        var result = document
        for programme in document.programmes where programmeIds.contains(programme.id) {
            let sessions = result.revisions.filter { $0.programmeId == programme.id }
            guard sessions.contains(where: { $0.status != .completed }) else { continue }
            let retained = sessions.filter { $0.status == .completed || $0.isEmergencySession == true }
            let doneDays = Set(retained.filter { $0.status == .completed && $0.isEmergencySession != true }.compactMap(\.nominalOffsetDays))
            let pending = try rhythm.steps.enumerated().compactMap { index, step -> Revision? in
                if doneDays.contains(step.offsetDays) { return nil }
                guard let date = SchoolDay.adding(step.offsetDays, to: programme.startDate) else { throw Failure(message: "Date invalide.") }
                return Revision(id: "\(programme.id)-rhythm-\(rhythm.revision)-\(step.id)", courseId: programme.courseIds[0], stepIndex: index,
                    stepLabel: "J\(step.offsetDays)", scheduledDate: date, status: .pending, phase: step.phase,
                    programmeId: programme.id, courseIds: programme.courseIds, rhythmStepId: step.id, nominalOffsetDays: step.offsetDays)
            }
            result.revisions = result.revisions.filter { $0.programmeId != programme.id } + retained + pending
            if let index = result.programmes.firstIndex(where: { $0.id == programme.id }) {
                result.programmes[index].rhythmSnapshot = rhythm
                result.programmes[index].needsReview = retained.contains { $0.status != .completed && $0.nominalOffsetDays == nil }
            }
        }
        return result
    }
    static func migrate(_ document: AppDocument) -> AppDocument {
        guard document.schemaVersion < 2 else { return document }
        var result = document
        for course in document.courses {
            let legacy = document.revisions.filter { $0.courseId == course.id && $0.programmeId == nil }
            guard !legacy.isEmpty else { continue }
            var seen = Set<String>()
            let steps = legacy.filter { $0.isEmergencySession != true }.sorted { $0.stepIndex < $1.stepIndex }.compactMap { r -> RhythmStep? in
                let id = "legacy-\(r.stepIndex)"
                guard seen.insert(id).inserted else { return nil }
                return RhythmStep(id: id, offsetDays: nominalDay(r.stepLabel) ?? 0, phase: r.phase)
            }
            let rhythm = RevisionRhythm(id: "legacy-\(course.id)", name: "Rythme historique", revision: 1, steps: steps)
            let id = "programme-legacy-\(course.id)"
            result.programmes.append(RevisionProgramme(id: id, subjectId: course.subjectId, courseIds: [course.id], startDate: course.date,
                rhythmSnapshot: rhythm, adaptiveEnabled: true, needsReview: true))
            do { try validate(rhythm); result.programmes[result.programmes.count-1].needsReview = false }
            catch { result.programmes[result.programmes.count-1].needsReview = true }
            for index in result.revisions.indices where legacy.contains(where: { $0.id == result.revisions[index].id }) {
                result.revisions[index].programmeId = id
                result.revisions[index].courseIds = [course.id]
                result.revisions[index].rhythmStepId = "legacy-\(result.revisions[index].stepIndex)"
                result.revisions[index].nominalOffsetDays = nominalDay(result.revisions[index].stepLabel)
            }
        }
        if result.settings.rhythm == nil {
            result.settings.rhythm = result.settings.intervals == Constants.defaultIntervals ? .standard
                : RevisionRhythm(id: "custom-legacy", name: "Rythme personnalisé", revision: 1,
                    steps: result.settings.intervals.enumerated().map { index, day in RhythmStep(id: "day-\(day)", offsetDays: day, phase: AdaptiveAlgorithm.phase(forStep: index)) })
        }
        result.schemaVersion = 2
        return result
    }
    static func nominalDay(_ label: String) -> Int? {
        guard label.hasPrefix("J") else { return nil }
        return Int(label.dropFirst().prefix(while: { $0.isNumber }))
    }
}

// Les libellés historiques restent compatibles avec les sauvegardes web.
extension Revision {
    var phaseLabel: String { programmeId == nil ? Constants.phaseConfig(phase).label : phase.rhythmLabel }
}
