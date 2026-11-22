import Foundation

/// Modèle du Planning, porté de la logique en ligne de `PlanningView.tsx` (suites `planning` et `planningNavigate`).
/// Écarts volontaires (`native/shared/domain/DEVIATIONS.md`) : D10, libellés d'étape Découvrir / S'entraîner / Se tester ;
/// D11, chaînes en minuscules, la majuscule initiale étant posée à l'affichage seulement.
struct PlanningScreen: Equatable {
    enum Mode: String, CaseIterable, Identifiable, Sendable {
        case day, week, month
        var id: String { rawValue }
        var label: String {
            switch self {
            case .day: "Jour"
            case .week: "Semaine"
            case .month: "Mois"
            }
        }
    }

    struct Event: Equatable, Identifiable {
        var event: PronoteEvent
        /// `HH:mm · <matière>` suivi de ` · Annulé`.
        var label: String
        /// Matière de la couleur, seulement si elle existe encore.
        var subjectId: String?
        var canAddCourse: Bool
        var id: String { event.id }
    }

    struct Tile: Equatable, Identifiable {
        var revision: Revision
        var course: Course
        var subject: Subject
        var phaseLine: String
        var done: Bool
        var footer: String
        var accessibilityLabel: String
        var id: String { revision.id }
    }

    struct Day: Equatable, Identifiable {
        var date: String
        var heading: String
        var countLabel: String
        var isToday: Bool
        /// Mois seulement.
        var outsideMonth: Bool?
        var timetableSummary: String?
        var events: [Event]
        var tiles: [Tile]
        var empty: String?
        var id: String { date }
    }

    var period: String
    var days: [Day]

    init(mode: Mode, date: String, subjectId: String? = nil, phase: Phase? = nil, today: String,
         courses: [Course], revisions: [Revision], subjects: [Subject], pronoteEvents: [PronoteEvent]) {
        let courseMap = Dictionary(courses.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let subjectMap = Dictionary(subjects.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        // Un cours dont la matière a disparu n'apparaît pas (contrairement à Aujourd'hui).
        let filtered: [(offset: Int, revision: Revision, course: Course, subject: Subject)] = revisions.enumerated().compactMap { offset, revision in
            guard let course = courseMap[revision.courseId], let subject = subjectMap[course.subjectId],
                  subjectId == nil || course.subjectId == subjectId,
                  phase == nil || revision.phase == phase else { return nil }
            return (offset, revision, course, subject)
        }

        let weekStart = SchoolDay.startOfWeek(date) ?? date
        let dayList: [String]
        switch mode {
        case .day:
            dayList = [date]
        case .week:
            dayList = (0..<7).compactMap { SchoolDay.adding($0, to: weekStart) }
        case .month:
            dayList = Self.monthGrid(date)
        }

        switch mode {
        case .month: period = FrenchDate.format(date, "MMMM yyyy")
        case .day: period = FrenchDate.format(date, "EEEE d MMMM yyyy")
        case .week:
            let end = SchoolDay.adding(6, to: weekStart) ?? weekStart
            period = "\(FrenchDate.format(weekStart, "d MMM")) – \(FrenchDate.format(end, "d MMM yyyy"))"
        }

        let anchorMonth = date.prefix(7)
        days = dayList.map { key in
            // Non terminées d'abord, puis terminées ; par étape croissante ; ordre du tableau à égalité (tri stable).
            let dayItems = filtered.filter { $0.revision.scheduledDate == key }.sorted { lhs, rhs in
                let a = lhs.revision.status == .completed ? 1 : 0, b = rhs.revision.status == .completed ? 1 : 0
                if a != b { return a < b }
                if lhs.revision.stepIndex != rhs.revision.stepIndex { return lhs.revision.stepIndex < rhs.revision.stepIndex }
                return lhs.offset < rhs.offset
            }
            // Le filtre d'étape ne s'applique pas aux créneaux ; un créneau non apparié disparaît dès qu'une matière est choisie.
            let events = pronoteEvents.filter { $0.date == key && (subjectId == nil || $0.matchedSubjectId == subjectId) }
            let isToday = key == today
            let count = dayItems.count
            return Day(
                date: key,
                heading: FrenchDate.format(key, mode == .month ? "EEE d" : "EEEE d MMM"),
                countLabel: "\(count) séance\(count > 1 ? "s" : "")\(isToday ? " · Aujourd’hui" : "")",
                isToday: isToday,
                outsideMonth: mode == .month ? key.prefix(7) != anchorMonth : nil,
                timetableSummary: events.isEmpty ? nil : "Emploi du temps · \(events.count) cours",
                events: events.map { event in
                    Event(
                        event: event,
                        label: "\(event.startTime) · \(event.subjectName)\(event.isCancelled ? " · Annulé" : "")",
                        subjectId: event.matchedSubjectId.flatMap { subjectMap[$0] == nil ? nil : $0 },
                        canAddCourse: !event.isCancelled
                    )
                },
                tiles: dayItems.map { item in
                    let done = item.revision.status == .completed
                    let phaseLabel = item.revision.phaseLabel
                    let number = RevisionProgrammes.references(item.revision, courses: courses)
                    return Tile(
                        revision: item.revision,
                        course: item.course,
                        subject: item.subject,
                        phaseLine: "\(phaseLabel) · \(number)",
                        done: done,
                        footer: done ? "Terminée · \(item.revision.score.map(String.init) ?? "—")/100" : "Voir la séance →",
                        accessibilityLabel: "\(item.subject.name), \(item.revision.stepLabel), \(phaseLabel), \(number) \(item.course.title)\(done ? ", terminée" : "")"
                    )
                },
                empty: dayItems.isEmpty ? "Aucune séance prévue." : nil
            )
        }
    }

    /// Du lundi de la semaine du 1er au dimanche de la semaine du dernier jour (35 ou 42 jours).
    static func monthGrid(_ date: String) -> [String] {
        guard let parts = SchoolDay.components(date) else { return [] }
        let first = SchoolDay.format(year: parts.year, month: parts.month, day: 1)
        let last = SchoolDay.format(year: parts.year, month: parts.month, day: SchoolDay.daysInMonth(year: parts.year, month: parts.month))
        guard let start = SchoolDay.startOfWeek(first), let lastWeek = SchoolDay.startOfWeek(last),
              let end = SchoolDay.adding(6, to: lastWeek), let count = SchoolDay.daysBetween(start, end) else { return [] }
        return (0...count).compactMap { SchoolDay.adding($0, to: start) }
    }

    /// Précédent / suivant : ±1 jour, semaine ou mois en arithmétique calendaire.
    static func navigate(mode: Mode, date: String, direction: Int) -> String {
        switch mode {
        case .day: SchoolDay.adding(direction, to: date) ?? date
        case .week: SchoolDay.adding(7 * direction, to: date) ?? date
        case .month: SchoolDay.addingMonths(direction, to: date) ?? date
        }
    }
}
