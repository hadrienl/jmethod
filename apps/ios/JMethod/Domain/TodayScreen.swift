import Foundation

/// Modèle de l'écran Aujourd'hui, porté de la logique en ligne de `DashboardView.tsx`, `App.tsx` et `Navigation.tsx`.
/// Regroupement par matière seulement (ordre de la liste des matières) ; l'étape n'est qu'un badge.
struct TodayScreen: Equatable {
    struct Hero: Equatable {
        var title: String
        var revision: Revision?
        var course: Course?
        /// Nom de la matière, ou « ton cours » si elle n'existe plus.
        var subjectLabel: String?
    }

    struct Row: Equatable, Identifiable {
        var revision: Revision
        var course: Course
        var badge: String
        var courseLine: String
        var title: String
        var done: Bool
        var doneLabel: String?
        var canValidate: Bool
        var id: String { revision.id }
    }

    struct Group: Equatable, Identifiable {
        var subject: Subject
        var countLabel: String
        var rows: [Row]
        var id: String { subject.id }
    }

    struct TomorrowEvent: Equatable, Identifiable {
        var event: PronoteEvent
        var timeRange: String
        var subjectName: String
        var cancelled: Bool
        /// « Annulé », ou la salle (éventuellement absente).
        var trailing: String?
        var teacher: String?
        var canAddCourse: Bool
        var id: String { event.id }
    }

    var programme: EveningProgramme
    var headerDate: String
    var tomorrowDate: String
    var pendingCountToday: Int
    var overdueCount: Int
    var badgeCount: Int
    var total: Int
    var progress: Int
    var progressSuffix: String
    var progressMessage: String
    var allDoneBanner: Bool
    var hero: Hero
    var dueLabel: String
    var overdueBanner: String?
    var emptyProgramme: Bool
    var groups: [Group]
    var tomorrowEvents: [TomorrowEvent]

    init(today: String, courses: [Course], revisions: [Revision], subjects: [Subject], pronoteEvents: [PronoteEvent]) {
        let programme = EveningProgramme(revisions: revisions, courses: courses, today: today)
        self.programme = programme
        let courseMap = Dictionary(courses.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let subjectMap = Dictionary(subjects.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })

        headerDate = SchoolDay.longLabel(today)
        tomorrowDate = SchoolDay.longLabel(programme.preparationDate)
        pendingCountToday = programme.pendingCount
        overdueCount = programme.overdue.count
        badgeCount = pendingCountToday + overdueCount

        let completed = programme.completed.count
        total = programme.total
        progress = programme.progressPercent
        let plural = total > 1 ? "s" : ""
        progressSuffix = "/ \(total) séance\(plural) terminée\(plural)"
        progressMessage = completed > 0 ? "Chaque séance compte. Continue comme ça !"
            : total > 0 ? "La première séance, c’est déjà un progrès."
            : "Aucune séance à préparer pour demain."
        allDoneBanner = programme.due.isEmpty && completed > 0

        if let next = programme.due.first, let course = courseMap[next.courseId] {
            hero = Hero(title: "Une séance à la fois.", revision: next, course: course, subjectLabel: subjectMap[course.subjectId]?.name ?? "ton cours")
        } else {
            hero = Hero(title: "Tu peux souffler !")
        }

        dueLabel = "\(programme.due.count) à faire"
        let overdue = programme.overdue.count
        overdueBanner = overdue > 0 ? "\(overdue) séance\(overdue > 1 ? "s" : "") à reprendre. Pas de pression : avance une séance à la fois." : nil
        emptyProgramme = programme.visible.isEmpty

        groups = subjects.compactMap { subject in
            let rows: [Row] = programme.visible.compactMap { revision in
                guard let course = courseMap[revision.courseId], course.subjectId == subject.id else { return nil }
                let done = revision.status == .completed
                return Row(
                    revision: revision,
                    course: course,
                    badge: "\(Constants.todayPhase(revision.phase).label) · \(revision.stepLabel)",
                    courseLine: revision.programmeId == nil ? "\(course.chapter) · \(course.displayReference)" : RevisionProgrammes.references(revision, courses: courses),
                    title: course.title,
                    done: done,
                    doneLabel: done ? "Fait · \(revision.score.map(String.init) ?? "")/100" : nil,
                    canValidate: !done
                )
            }
            guard !rows.isEmpty else { return nil }
            return Group(subject: subject, countLabel: "\(rows.count) séance\(rows.count > 1 ? "s" : "") au programme", rows: rows)
        }

        tomorrowEvents = pronoteEvents.filter { $0.date == programme.preparationDate }.map { event in
            TomorrowEvent(
                event: event,
                timeRange: "\(event.startTime) – \(event.endTime)",
                subjectName: event.subjectName,
                cancelled: event.isCancelled,
                trailing: event.isCancelled ? "Annulé" : event.room,
                teacher: event.teacher.flatMap { $0.isEmpty ? nil : $0 },
                canAddCourse: !event.isCancelled
            )
        }
    }
}

extension TodayScreen.Hero {
    init(title: String) { self.init(title: title, revision: nil, course: nil, subjectLabel: nil) }
}
