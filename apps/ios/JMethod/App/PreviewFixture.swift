#if DEBUG
import Foundation

/// Vérification visuelle et tests UI (Debug seulement, argument `-JMethodPreviewFixture`) : document fictif
/// calculé autour du jour réel, écrit dans un fichier temporaire distinct. Le document de l'utilisateur,
/// ses préférences et Pronote ne sont jamais lus ni modifiés.
enum PreviewFixture {
    static let argument = "-JMethodPreviewFixture"

    static var isEnabled: Bool { ProcessInfo.processInfo.arguments.contains(argument) }

    /// Modèle branché sur un fichier temporaire réécrit à chaque lancement.
    @MainActor
    static func makeModel(now: Date = Date()) -> AppModel {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-preview-fixture.json", isDirectory: false)
        if let data = try? DocumentStore.encode(document(today: SchoolDay.today(now: now))) {
            try? data.write(to: url, options: .atomic)
        }
        let suite = UserDefaults(suiteName: "fr.jmethod.preview") ?? .standard
        suite.set(true, forKey: AppModel.demoMigrationKey)
        return AppModel(store: DocumentStore(url: url), preferences: suite, documentStore: documentStore())
    }

    /// Fichiers fictifs des cours, dans un dossier temporaire distinct (jamais le stockage de l'utilisateur).
    private static let documentsRoot = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-preview-documents", isDirectory: true)

    /// Fiche fictive présente ; le second document est absent pour montrer l'état « À retélécharger ».
    private static let notebookDocuments: [CourseDocument] = [
        CourseDocument(id: "fixture-doc-fiche", name: "Fiche - Fonctions affines.md", mime: "text/markdown", size: 0,
                       file: "documents/fixture-maths-1/fixture-doc-fiche.md", extractedTitle: "Fonctions affines"),
        CourseDocument(id: "fixture-doc-exercices", name: "Exercices.pdf", mime: "application/pdf", size: 48_213,
                       file: "documents/fixture-maths-1/fixture-doc-exercices.pdf", extractedTitle: nil),
    ]

    private static let notebookSheet = """
    # Fonctions affines

    ## Définition

    Une fonction affine s'écrit f(x) = ax + b.

    - a est le coefficient directeur ;
    - b est l'ordonnée à l'origine.

    ## Lecture graphique

    La représentation graphique d'une fonction affine est une droite.
    """

    private static func documentStore() -> CourseDocumentStore {
        let store = CourseDocumentStore(root: documentsRoot)
        try? FileManager.default.removeItem(at: documentsRoot)
        if let fiche = store.url(for: notebookDocuments[0]) {
            try? FileManager.default.createDirectory(at: fiche.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? Data(notebookSheet.utf8).write(to: fiche, options: .atomic)
        }
        return store
    }

    private struct Seed {
        let id: String
        let subjectId: String
        let chapter: String
        let number: Int
        let title: String
        /// Jour du cours par rapport à aujourd'hui.
        let offset: Int
        /// Notes des séances passées, dans l'ordre ; les séances sans note restent à faire.
        let scores: [Int]
        /// La séance d'aujourd'hui est déjà faite (avec la note suivante de `scores`).
        var doneToday = false
        var notes: String?
    }

    private static let seeds: [Seed] = [
        Seed(id: "fixture-maths-1", subjectId: "maths", chapter: "Fonctions", number: 1, title: "Fonctions affines", offset: -6,
             scores: [70, 85, 75], notes: "Coefficient directeur, ordonnée à l’origine, lecture graphique."),
        Seed(id: "fixture-maths-2", subjectId: "maths", chapter: "Fonctions", number: 2, title: "Fonctions linéaires et proportionnalité",
             offset: -1, scores: [80]),
        Seed(id: "fixture-francais-1", subjectId: "francais", chapter: "Grammaire", number: 1, title: "La proposition subordonnée relative",
             offset: 0, scores: [60], doneToday: true),
        Seed(id: "fixture-pc-1", subjectId: "physique-chimie", chapter: "Organisation de la matière", number: 1,
             title: "Les états de la matière", offset: -3, scores: [65, 40, 80]),
        Seed(id: "fixture-hg-1", subjectId: "histoire-geo", chapter: "Le monde depuis 1945", number: 1, title: "La guerre froide",
             offset: -9, scores: [45, 50, 55, 40]),
        Seed(id: "fixture-anglais-1", subjectId: "anglais", chapter: "Grammar", number: 1, title: "Present perfect et prétérit",
             offset: -15, scores: [35, 45, 40, 30, 45, 40]),
        Seed(id: "fixture-svt-1", subjectId: "svt", chapter: "Le vivant", number: 1, title: "La cellule, unité du vivant", offset: 2, scores: []),
    ]

    static func document(today: String) -> AppDocument {
        var courses: [Course] = []
        var revisions: [Revision] = []
        for seed in seeds {
            let day = SchoolDay.adding(seed.offset, to: today) ?? today
            var course = Course(id: seed.id, subjectId: seed.subjectId, chapter: seed.chapter, courseNumber: seed.number, title: seed.title,
                                date: day, description: seed.notes, createdAt: day)
            if seed.id == "fixture-maths-1" {
                // Cours créé depuis le cahier de textes (fictif) : origine et documents dans la fiche.
                let key = NotebookCourses.sessionKey(date: day, subject: "MATHEMATIQUES", ordinal: 1)
                course.source = CourseSource(kind: CourseSource.notebookKind, sessionKey: key, subjectLabel: "MATHEMATIQUES", date: day, ordinal: 1)
                var documents = notebookDocuments
                documents[0].size = notebookSheet.utf8.count
                course.documents = documents
            }
            courses.append(course)
            var scores = seed.scores[...]
            let initial = (try? AdaptiveAlgorithm.initialRevisions(for: course, intervals: Constants.defaultIntervals)) ?? []
            for var revision in initial {
                let isPast = revision.scheduledDate < today
                let isToday = revision.scheduledDate == today
                if (isPast || (isToday && seed.doneToday)), let score = scores.popFirst() {
                    revision.status = .completed
                    revision.score = score
                    revision.completedDate = isToday ? today : revision.scheduledDate
                }
                revisions.append(revision)
            }
        }
        var document = AppDocument(settings: settings(today: today), subjects: Constants.defaultSubjects, courses: courses,
                                   revisions: revisions, pronoteEvents: events(today: today), initialized: true)
        if let key = courses.first(where: { $0.id == "fixture-maths-1" })?.source?.sessionKey {
            document.notebookImports[key] = NotebookImport(status: .created, courseId: "fixture-maths-1", at: AppModel.isoString(Date()))
        }
        return document
    }

    private static func settings(today: String) -> AppSettings {
        var settings = Constants.defaultSettings
        settings.lastPronoteSync = SchoolDay.displayDate(today).map { AppModel.isoString($0.addingTimeInterval(6 * 3600)) }
        return settings
    }

    /// Emploi du temps fictif sur trois semaines, du lundi au vendredi (mercredi matin seulement).
    private static func events(today: String) -> [PronoteEvent] {
        typealias Slot = (start: String, end: String, subject: String, room: String, teacher: String)
        let full: [Slot] = [
            ("08:00", "09:00", "MATHEMATIQUES", "B12", "M. MARTIN"),
            ("09:00", "10:00", "FRANCAIS", "A04", "Mme DURAND"),
            ("10:15", "11:15", "ANGLAIS LV1", "C21", "Mme PETIT"),
            ("13:30", "14:30", "PHYSIQUE-CHIMIE", "Labo 2", "M. BERNARD"),
            ("14:30", "15:30", "HISTOIRE-GEOGRAPHIE", "A10", "M. LEROY"),
            ("15:45", "17:45", "ED.PHYSIQUE & SPORT.", "Gymnase", "Mme MOREAU"),
        ]
        var events: [PronoteEvent] = []
        let calendar = SchoolDay.parisCalendar
        for offset in -7...20 {
            guard let day = SchoolDay.adding(offset, to: today), let date = SchoolDay.displayDate(day) else { continue }
            let weekday = calendar.component(.weekday, from: date)
            guard (2...6).contains(weekday) else { continue }
            let slots = weekday == 4 ? Array(full.prefix(3)) : full
            for (index, slot) in slots.enumerated() {
                let subjects = Constants.defaultSubjects
                events.append(PronoteEvent(
                    id: "fixture-\(day)-\(index)", subjectName: slot.subject,
                    matchedSubjectId: SubjectMatcher.match(slot.subject, subjects: subjects)?.id,
                    date: day, startTime: slot.start, endTime: slot.end, room: slot.room, teacher: slot.teacher,
                    isCancelled: offset == 1 && index == 2, isCourseCreated: nil
                ))
            }
        }
        return events
    }
}
#endif
