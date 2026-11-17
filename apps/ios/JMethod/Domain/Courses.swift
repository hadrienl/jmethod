import Foundation

/// Liste de Mes cours (`CoursesView.tsx`, suite `coursesList`).
/// Écart D9 : la recherche est nettoyée (`trim`) et insensible à la casse et aux accents ; « c1 » reste une sous-chaîne (C10 à C19 inclus).
struct CoursesList: Equatable {
    struct Card: Equatable, Identifiable {
        struct Next: Equatable {
            var label: String
            var date: String
        }

        var course: Course
        var badge: String
        var dateLine: String
        var lastScoreLine: String?
        var counter: String
        var progress: Int
        var next: Next?
        var id: String { course.id }
    }

    struct Chapter: Equatable, Identifiable {
        var chapter: String
        var cards: [Card]
        var id: String { chapter }
    }

    struct Group: Equatable, Identifiable {
        var subject: Subject
        var countLabel: String
        var chapters: [Chapter]
        var id: String { subject.id }
    }

    var header: String
    var empty: Bool
    var groups: [Group]

    init(courses: [Course], revisions: [Revision], subjects: [Subject], search: String = "", subjectId: String? = nil) {
        let query = Self.searchKey(search.trimmingCharacters(in: .whitespacesAndNewlines))
        let filtered = courses.filter { course in
            if let subjectId, course.subjectId != subjectId { return false }
            guard !query.isEmpty else { return true }
            return [course.title, course.chapter, "c\(course.courseNumber)"].contains { Self.searchKey($0).range(of: query, options: .literal) != nil }
        }
        let revisionsByCourse = Dictionary(grouping: revisions, by: \.courseId)

        groups = subjects.compactMap { subject in
            let subjectCourses = filtered.filter { $0.subjectId == subject.id }
            guard !subjectCourses.isEmpty else { return nil }
            // Chapitres dans l'ordre de première apparition, regroupés à l'identique de la chaîne (casse comprise).
            var order: [String] = []
            var byChapter: [String: [(offset: Int, course: Course)]] = [:]
            for (offset, course) in subjectCourses.enumerated() {
                if byChapter[course.chapter] == nil { order.append(course.chapter) }
                byChapter[course.chapter, default: []].append((offset, course))
            }
            let chapters = order.map { chapter in
                let sorted = (byChapter[chapter] ?? [])
                    .sorted { $0.course.courseNumber != $1.course.courseNumber ? $0.course.courseNumber < $1.course.courseNumber : $0.offset < $1.offset }
                    .map(\.course)
                return Chapter(chapter: chapter, cards: sorted.map { Self.card($0, revisions: revisionsByCourse[$0.id] ?? []) })
            }
            let count = chapters.reduce(0) { $0 + $1.cards.count }
            return Group(subject: subject, countLabel: "(\(count) cours)", chapters: chapters)
        }
        // L'en-tête compte tous les cours, sans filtre, matière supprimée comprise.
        header = "Mes cours (\(courses.count))"
        empty = groups.isEmpty
    }

    private static func card(_ course: Course, revisions: [Revision]) -> Card {
        let completed = revisions.filter { $0.status == .completed }.count
        let progress = revisions.isEmpty ? 0 : jsRound(Double(completed) / Double(revisions.count) * 100)
        // « Prochain » et « Dernier score » suivent l'ordre du tableau, pas les dates.
        let next = revisions.first { $0.status == .pending }
        let lastScore = revisions.last { $0.score != nil }?.score
        return Card(
            course: course,
            badge: "C\(course.courseNumber)",
            dateLine: "Cours du \(FrenchDate.format(course.date, "dd/MM/yyyy"))",
            lastScoreLine: lastScore.map { "Dernier score : \($0)/100" },
            counter: "\(completed) / \(revisions.count) J",
            progress: progress,
            next: next.map { .init(label: "Prochain : \($0.stepLabel)", date: FrenchDate.format($0.scheduledDate, "dd/MM")) }
        )
    }

    /// Minuscules, NFD puis retrait des diacritiques U+0300–U+036F (même normalisation que l'appariement des matières).
    static func searchKey(_ value: String) -> String { SubjectMatcher.normalize(value) }
}

/// Fiche d'un cours (`CourseDetailModal.tsx`, suite `courseDetail`).
/// Écarts : D1, 0 % sans révision au lieu de « NaN% » ; D2, « Créé le » lit `createdAt` et la date du cours devient « Cours du » ;
/// D12, une séance terminée sans score affiche « —/100 » au lieu de « 50/100 ».
struct CourseDetail: Equatable {
    enum State: String { case completed, overdue, pending }

    struct Item: Equatable, Identifiable {
        var revision: Revision
        var phaseLabel: String
        var emergencyBadge: Bool
        var state: State
        var plannedLine: String
        var doneLine: String?
        /// Score (« 60/100 ») pour une séance terminée, sinon « Valider ».
        var right: String
        var id: String { revision.id }
    }

    var course: Course
    var subject: Subject?
    var subjectLabel: String
    var createdLine: String
    var courseDateLine: String
    var averageLine: String?
    var notes: String?
    var progressLine: String
    var progressPercent: Int
    var timeline: [Item]
    var deleteConfirm: String

    /// `revisions` : celles du cours, dans l'ordre du document.
    init(course: Course, subjects: [Subject], revisions: [Revision]) {
        self.course = course
        subject = subjects.first { $0.id == course.subjectId }
        subjectLabel = subject?.name ?? "Matière"
        let sorted = revisions.enumerated()
            .sorted { $0.element.stepIndex != $1.element.stepIndex ? $0.element.stepIndex < $1.element.stepIndex : $0.offset < $1.offset }
            .map(\.element)
        let completed = sorted.filter { $0.status == .completed }.count
        progressPercent = sorted.isEmpty ? 0 : jsRound(Double(completed) / Double(sorted.count) * 100)
        let scores = sorted.compactMap(\.score)
        let average = scores.isEmpty ? nil : jsRound(Double(scores.reduce(0, +)) / Double(scores.count))
        createdLine = "Créé le \(FrenchDate.format(course.createdAt, "dd MMMM yyyy"))"
        courseDateLine = "Cours du \(FrenchDate.format(course.date, "dd/MM/yyyy"))"
        averageLine = average.map { "Moyenne des séances : \($0)/100" }
        notes = course.description
        progressLine = "\(completed) / \(sorted.count) effectuées (\(progressPercent)%)"
        timeline = sorted.map { revision in
            let done = revision.status == .completed
            return Item(
                revision: revision,
                phaseLabel: Constants.phaseConfig(revision.phase).label,
                emergencyBadge: revision.isEmergencySession == true,
                state: done ? .completed : revision.status == .overdue ? .overdue : .pending,
                plannedLine: "Prévu : \(FrenchDate.format(revision.scheduledDate, "dd MMM yyyy"))",
                doneLine: revision.completedDate.map { "• Fait le \(FrenchDate.format($0, "dd/MM"))" },
                right: done ? "\(revision.score.map(String.init) ?? "—")/100" : "Valider"
            )
        }
        deleteConfirm = "Es-tu sûr de vouloir supprimer définitivement le cours C\(course.courseNumber) : \(course.title) ?"
    }
}

/// Formulaire de création (`AddCourseModal.tsx`) et ajout (`App.handleAddCourse`), suites `courseCreate` et `courseCreatePrefill`.
struct CourseDraft: Equatable, Sendable {
    var subjectId: String?
    var chapter: String
    var title: String
    /// `yyyy-MM-dd`, date du cours (J0).
    var date: String
    var description: String

    init(subjectId: String? = nil, chapter: String = "", title: String = "", date: String, description: String = "") {
        self.subjectId = subjectId
        self.chapter = chapter
        self.title = title
        self.date = date
        self.description = description
    }
}

enum CourseCreation {
    struct Prefill: Equatable {
        var subjectId: String?
        var date: String
        var title: String
    }

    struct Toast: Equatable {
        var title: String
        var message: String
    }

    struct Outcome: Equatable {
        var course: Course
        var newRevisions: [Revision]
        var courses: [Course]
        var revisions: [Revision]
        var toast: Toast
    }

    /// 1 si le chapitre est vide ou nouveau ; sinon le plus grand numéro + 1 parmi les cours de la matière dont le chapitre
    /// est égal après `trim` et minuscules (sans repli des accents). Les trous ne sont pas comblés.
    static func courseNumber(subjectId: String?, chapter: String, courses: [Course]) -> Int {
        let key = chapter.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !key.isEmpty else { return 1 }
        let matches = courses.filter { $0.subjectId == subjectId && $0.chapter.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == key }
        guard !matches.isEmpty else { return 1 }
        return max(matches.map(\.courseNumber).max() ?? 0, 0) + 1
    }

    /// Chapitres distincts de la matière dans l'ordre des cours, au plus trois, seulement quand le champ est vide.
    static func chapterSuggestions(subjectId: String?, chapter: String, courses: [Course]) -> [String] {
        guard chapter.isEmpty else { return [] }
        var seen = Set<String>()
        var result: [String] = []
        for course in courses where course.subjectId == subjectId && seen.insert(course.chapter).inserted {
            result.append(course.chapter)
        }
        return Array(result.prefix(3))
    }

    static func canSubmit(_ draft: CourseDraft) -> Bool {
        draft.subjectId != nil && !trimmed(draft.title).isEmpty && !trimmed(draft.chapter).isEmpty && SchoolDay.isValid(draft.date)
    }

    /// nil si le formulaire est refusé (titre ou chapitre vide après `trim`). Identifiant `course-<epochMs>`, `createdAt` au jour local de `now`.
    /// Le cours est placé en tête de la liste et ses révisions à la fin.
    static func create(_ draft: CourseDraft, courses: [Course], revisions: [Revision], settings: AppSettings, now: Date) throws -> Outcome? {
        guard canSubmit(draft), let subjectId = draft.subjectId else { return nil }
        let description = trimmed(draft.description)
        let course = Course(
            id: "course-\(AdaptiveAlgorithm.epochMilliseconds(now))",
            subjectId: subjectId,
            chapter: trimmed(draft.chapter),
            courseNumber: courseNumber(subjectId: subjectId, chapter: draft.chapter, courses: courses),
            title: trimmed(draft.title),
            date: draft.date,
            description: description.isEmpty ? nil : description,
            createdAt: SchoolDay.today(now: now)
        )
        let newRevisions = try AdaptiveAlgorithm.initialRevisions(for: course, intervals: settings.intervals)
        return Outcome(
            course: course,
            newRevisions: newRevisions,
            courses: [course] + courses,
            revisions: revisions + newRevisions,
            toast: Toast(title: "Nouveau cours créé !", message: "C\(course.courseNumber) « \(course.title) » ajouté avec \(milestonesPhrase(newRevisions.count)).")
        )
    }

    /// Écart D8 (nombre réel d'échéances) et D7 (accord) : « ses 10 échéances de révision », « son échéance de révision ».
    static func milestonesPhrase(_ count: Int) -> String {
        switch count {
        case 0: "aucune échéance de révision"
        case 1: "son échéance de révision"
        default: "ses \(count) échéances de révision"
        }
    }

    /// Préremplissage depuis un créneau Pronote : matière appariée, sinon la première matière ; date et libellé du créneau.
    static func prefill(event: PronoteEvent, subjects: [Subject]) -> Prefill {
        let matched = event.matchedSubjectId.flatMap { $0.isEmpty ? nil : $0 }
        return Prefill(subjectId: matched ?? subjects.first?.id, date: event.date, title: event.subjectName)
    }

    private static func trimmed(_ value: String) -> String { value.trimmingCharacters(in: .whitespacesAndNewlines) }
}

/// Suppression d'un cours et de toutes ses révisions (`App.handleDeleteCourse`, suite `courseDelete`). Les créneaux Pronote ne changent pas.
enum CourseDeletion {
    static let toastTitle = "Cours supprimé"
    static let toastMessage = "Le cours et son calendrier ont été supprimés."

    static func apply(courseId: String, courses: [Course], revisions: [Revision]) -> (courses: [Course], revisions: [Revision]) {
        (courses.filter { $0.id != courseId }, revisions.filter { $0.courseId != courseId })
    }
}
