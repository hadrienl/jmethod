import Foundation

/// Jeu de démonstration du premier lancement, comme `initializeDefaultDataIfEmpty` du web.
enum DemoData {
    private struct DemoCourse {
        let id: String, subjectId: String, chapter: String, number: Int, title: String, daysAgo: Int, description: String
    }

    private static let courses: [DemoCourse] = [
        .init(id: "course-maths-1", subjectId: "maths", chapter: "Arithmétique", number: 1, title: "Division euclidienne & divisibilité", daysAgo: 25, description: "Définition, quotient, reste et critères de divisibilité par 2, 3, 5, 9."),
        .init(id: "course-maths-2", subjectId: "maths", chapter: "Arithmétique", number: 2, title: "Nombres premiers & décomposition", daysAgo: 14, description: "Liste des nombres premiers < 30, algorithme de décomposition en facteurs."),
        .init(id: "course-maths-3", subjectId: "maths", chapter: "Arithmétique", number: 3, title: "Fractions irréductibles", daysAgo: 7, description: "Simplification de fractions par décomposition en facteurs premiers."),
        .init(id: "course-maths-5", subjectId: "maths", chapter: "Arithmétique", number: 5, title: "Problèmes de PGCD dans la vie réelle", daysAgo: 2, description: "Découpage de dalles et composition de paquets identiques."),
        .init(id: "course-maths-14", subjectId: "maths", chapter: "Arithmétique", number: 14, title: "Algorithme d’Euclide avancé", daysAgo: 0, description: "Méthode des soustractions successives et divisions en chaîne."),
        .init(id: "course-pc-1", subjectId: "physique-chimie", chapter: "Poids et Masse", number: 1, title: "Distinction entre masse et poids", daysAgo: 7, description: "Relation P = m × g, unités kg et Newton."),
        .init(id: "course-pc-2", subjectId: "physique-chimie", chapter: "Poids et Masse", number: 2, title: "Mesure avec dynamomètre", daysAgo: 1, description: "Utilisation du dynamomètre et étalonnage du ressort."),
        .init(id: "course-pc-4", subjectId: "physique-chimie", chapter: "Poids et Masse", number: 4, title: "Gravitation universelle et astres", daysAgo: 0, description: "Comparaison de g sur la Terre et sur la Lune."),
        .init(id: "course-fr-3", subjectId: "francais", chapter: "Grammaire", number: 3, title: "Les propositions subordonnées relatives", daysAgo: 2, description: "Pronoms relatifs simples et composés, antécédent."),
    ]

    private struct SampleSlot {
        let uid: String, summary: String, start: String, end: String, location: String, description: String, cancelled: Bool
    }

    /// Emploi du temps d'exemple du lendemain (`generateSamplePronoteFeed`).
    private static let sampleSlots: [SampleSlot] = [
        .init(uid: "pronote-demo-1", summary: "MATHEMATIQUES", start: "08:30", end: "09:30", location: "Salle 204", description: "M. Dupont - Arithmétique & calcul littéral", cancelled: false),
        .init(uid: "pronote-demo-2", summary: "PHYSIQUE-CHIMIE", start: "09:30", end: "10:30", location: "Labo Sciences 2", description: "Mme Martin - Poids et Masse", cancelled: false),
        .init(uid: "pronote-demo-3", summary: "FRANCAIS", start: "10:45", end: "11:45", location: "Salle 105", description: "Mme Bernard - Grammaire & Analyse", cancelled: false),
        .init(uid: "pronote-demo-4", summary: "HISTOIRE-GEOGRAPHIE", start: "13:30", end: "14:30", location: "Salle 301", description: "M. Lambert - La Première Guerre mondiale", cancelled: false),
        .init(uid: "pronote-demo-5", summary: "[ANNULÉ] ANGLAIS", start: "14:30", end: "15:30", location: "Salle 108", description: "Professeur absent", cancelled: true),
    ]

    /// - Parameter random: équivalent de `Math.random` (valeur dans [0, 1)) ; le score des séances passées vaut `floor(65 + random × 25)`.
    static func document(today: String, random: () -> Double = { Double.random(in: 0..<1) }) throws -> AppDocument {
        let settings = Constants.defaultSettings
        let subjects = Constants.defaultSubjects
        let demoCourses: [Course] = try courses.map { item in
            guard let date = SchoolDay.adding(-item.daysAgo, to: today) else { throw AdaptiveAlgorithm.Failure.invalidDate(today) }
            return Course(id: item.id, subjectId: item.subjectId, chapter: item.chapter, courseNumber: item.number, title: item.title, date: date, description: item.description, createdAt: date)
        }
        var revisions: [Revision] = []
        for course in demoCourses {
            revisions += try AdaptiveAlgorithm.initialRevisions(for: course, intervals: settings.intervals)
        }
        revisions = revisions.map { revision in
            guard revision.scheduledDate < today else { return revision }
            var completed = revision
            completed.status = .completed
            completed.completedDate = revision.scheduledDate
            completed.score = Int((65 + random() * 25).rounded(.down))
            return completed
        }
        return AppDocument(settings: settings, subjects: subjects, courses: demoCourses, revisions: revisions,
                           pronoteEvents: try sampleEvents(today: today, subjects: subjects), initialized: true)
    }

    static func sampleEvents(today: String, subjects: [Subject]) throws -> [PronoteEvent] {
        guard let tomorrow = SchoolDay.adding(1, to: today) else { throw AdaptiveAlgorithm.Failure.invalidDate(today) }
        return sampleSlots.map { slot in
            let matched = SubjectMatcher.match(slot.summary, subjects: subjects)
            return PronoteEvent(
                id: slot.uid,
                subjectName: matched?.name ?? slot.summary.replacingOccurrences(of: "[annulé]", with: "", options: .caseInsensitive).trimmingCharacters(in: .whitespaces),
                matchedSubjectId: matched?.id,
                date: tomorrow,
                startTime: slot.start,
                endTime: slot.end,
                room: slot.location,
                teacher: slot.description,
                isCancelled: slot.cancelled,
                isCourseCreated: false
            )
        }
    }
}
