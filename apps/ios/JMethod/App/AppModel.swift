import Foundation
import Observation

/// État principal de l'app (document, jour courant, retours visuels). Toute écriture passe par `DocumentStore`.
@Observable
@MainActor
final class AppModel {
    enum LoadState: Equatable {
        case loading
        case ready
        case failed(String)
    }

    struct Toast: Identifiable, Equatable {
        enum Kind { case success, info, warning }
        let id = UUID()
        var title: String
        var message: String
        var kind: Kind
    }

    private(set) var loadState: LoadState = .loading
    private(set) var document: AppDocument
    /// Jour réel au fuseau Europe/Paris, rafraîchi au changement de jour.
    private(set) var today: String
    /// Incrémenté quand la dernière séance du programme est validée (feu d'artifice).
    private(set) var celebration = 0
    /// Incrémenté pour une séance validée avec au moins 75/100 (petite gerbe, comme le web).
    private(set) var confetti = 0
    var toast: Toast?
    /// Feuille ouverte (une seule à la fois) : autoévaluation, détail de séance, fiche de cours, création de cours.
    var presented: Sheet?
    /// État du formulaire de création, conservé d'une ouverture à l'autre comme sur le web : le chapitre reste,
    /// la matière et la date ne changent que par un préremplissage, le titre et les notes sont vidés à chaque ouverture.
    var courseDraft: CourseDraft

    enum Sheet: Identifiable, Equatable {
        /// Fiche de séance (§8.1) ouverte directement sur « Faire le point ».
        case score(revisionId: String)
        /// Fiche de séance (§8.1).
        case revision(revisionId: String)
        /// Fiche de séance ouverte sur le document principal du cours (« Revoir le cours »).
        case review(revisionId: String)
        case course(courseId: String)
        case addCourse
        /// Cahier de textes ; une matière J-Method le filtre (ouverture depuis la fiche d'un cours).
        case notebook(subjectId: String?)

        var id: String {
            switch self {
            case .score(let id): "score-\(id)"
            case .revision(let id): "revision-\(id)"
            case .review(let id): "review-\(id)"
            case .course(let id): "course-\(id)"
            case .addCourse: "add-course"
            case .notebook(let id): "notebook-\(id ?? "all")"
            }
        }
    }

    @ObservationIgnored private let store: DocumentStore?
    /// Fichiers des cours créés depuis le cahier de textes (Application Support, hors sauvegarde).
    @ObservationIgnored let documentStore: CourseDocumentStore
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let preferences: UserDefaults
    @ObservationIgnored private var version = 0
    @ObservationIgnored private var loading: Task<Void, Never>?

    /// Indicateur (non sensible) de la migration qui retire le jeu de démonstration du jalon 1.
    static let demoMigrationKey = "fr.jmethod.migration.demoDataRemoved"

    /// Premier lancement : réglages et matières par défaut (configuration), aucun cours, révision ni créneau.
    static var emptyDocument: AppDocument {
        AppDocument(settings: Constants.defaultSettings, subjects: Constants.defaultSubjects, courses: [], revisions: [], pronoteEvents: [], initialized: true)
    }

    /// Séance du cahier de textes en cours de transformation en cours (formulaire prérempli ouvert).
    var notebookDraft: NotebookCourseDraft?

    init(store: DocumentStore?, now: @escaping () -> Date = Date.init, preferences: UserDefaults = .standard,
         documentStore: CourseDocumentStore = .live) {
        self.store = store
        self.documentStore = documentStore
        self.now = now
        self.preferences = preferences
        let launchDay = SchoolDay.today(now: now())
        self.today = launchDay
        self.document = Self.emptyDocument
        // Date initiale du formulaire : le jour du lancement de l'app, comme le web.
        self.courseDraft = CourseDraft(date: launchDay)
    }

    static func live() -> AppModel {
        AppModel(store: (try? DocumentStore.defaultURL()).map(DocumentStore.init(url:)))
    }

    var programme: EveningProgramme {
        EveningProgramme(revisions: document.revisions, courses: document.courses, today: today)
    }

    func course(id: String) -> Course? { document.courses.first { $0.id == id } }
    func subject(id: String) -> Subject? { document.subjects.first { $0.id == id } }

    // MARK: Chargement

    /// Charge le document une seule fois, même si le premier plan et une actualisation en arrière-plan le demandent ensemble.
    func load() async {
        if let loading { return await loading.value }
        let work = Task { await performLoad() }
        loading = work
        await work.value
        loading = nil
    }

    /// Utilisé par l'actualisation en arrière-plan, qui peut démarrer avant l'interface.
    func ensureLoaded() async {
        if loadState != .ready { await load() }
    }

    private func performLoad() async {
        refreshDay()
        guard let store else {
            loadState = .failed("Le dossier de l’app est inaccessible.")
            return
        }
        do {
            if let saved = try await store.load() {
                document = saved
                await migrateDemoDataIfNeeded(store: store)
                // Clés du cahier de textes stables entre sessions Pronote (migration unique, idempotente).
                let migrated = NotebookCourses.migratingKeys(document)
                if migrated != document {
                    document = migrated
                    persist()
                }
                await repairHTMLDocuments()
            } else {
                document = Self.emptyDocument
                persist()
                preferences.set(true, forKey: Self.demoMigrationKey)
            }
            loadState = .ready
        } catch {
            loadState = .failed((error as? LocalizedError)?.errorDescription ?? "Tes données n’ont pas pu être chargées.")
        }
    }

    /// Migration unique : retire les données de démonstration puis l'enregistre avant de la marquer faite.
    private func migrateDemoDataIfNeeded(store: DocumentStore) async {
        guard !preferences.bool(forKey: Self.demoMigrationKey) else { return }
        let cleaned = DemoMigration.removingDemoData(from: document)
        if cleaned != document {
            document = cleaned
            version += 1
            do {
                try await store.save(cleaned, version: version)
            } catch {
                // Réessayée au prochain lancement ; les données restent utilisables en mémoire.
                toast = .init(title: "Enregistrement impossible", message: "Le nettoyage des données d’exemple sera refait au prochain lancement.", kind: .warning)
                return
            }
        }
        preferences.set(true, forKey: Self.demoMigrationKey)
    }

    /// Documents enregistrés comme page HTML de Pronote (ancien défaut de l'adresse de téléchargement iOS) : fichier
    /// effacé et titre lu oublié, la fiche du cours propose alors « Retélécharger ». Idempotent, à chaque chargement ;
    /// aucun cours n'est supprimé ni renommé.
    private func repairHTMLDocuments() async {
        let courses = document.courses
        guard courses.contains(where: { !($0.documents ?? []).isEmpty }) else { return }
        let store = documentStore
        let repaired = await Task.detached(priority: .utility) { store.removeHTMLImpostors(in: courses) }.value
        guard !repaired.isEmpty else { return }
        for index in document.courses.indices {
            let courseId = document.courses[index].id
            guard var documents = document.courses[index].documents else { continue }
            for position in documents.indices where repaired.contains("\(courseId)/\(documents[position].id)") {
                documents[position].extractedTitle = nil
            }
            document.courses[index].documents = documents
        }
        persist()
    }

    /// Met de côté un fichier illisible (sans le supprimer) puis repart d'un document vide.
    func startOverAfterUnreadableFile() async {
        guard let store else { return }
        do {
            try await store.archiveUnreadable()
            loadState = .loading
            await load()
        } catch {
            loadState = .failed("Le fichier illisible n’a pas pu être mis de côté.")
        }
    }

    func refreshDay() {
        let current = SchoolDay.today(now: now())
        if current != today { today = current }
    }

    // MARK: Séances

    /// Résultat d'une validation pour la fiche de séance (§8.2).
    struct Validation {
        var explanation: AdaptiveAlgorithm.Explanation
        var title: String
        var message: String
        /// Rattrapage programmé (note sous le seuil) : présentation d'avertissement (ambre).
        var warning: Bool
        /// Première séance due dans l'ordre d'Aujourd'hui ; nil si le programme du soir est vide.
        var next: Revision?
        /// Séances encore dues ce soir, après celle-ci.
        var remaining: Int
    }

    /// Validation d'une séance du programme : autoévaluation puis replanification adaptative depuis le jour préparé.
    @discardableResult
    func validate(revisionId: String, score: Int) -> AdaptiveAlgorithm.Explanation? {
        submitScore(revisionId: revisionId, score: score, showsResult: false)?.explanation
    }

    /// « Valider » dans la fiche de séance. S'il reste une séance au programme et que la fiche affiche le résultat
    /// (`showsResult`), ni message ni confettis par-dessus (comme Android) ; sinon la fiche se ferme et ils prennent
    /// le relais (feu d'artifice et « Programme terminé » quand la dernière séance du soir est validée).
    func submitScore(revisionId: String, score: Int, showsResult: Bool) -> Validation? {
        refreshDay()
        let outcome: ScoreSubmission.Outcome
        do {
            outcome = try ScoreSubmission.submit(
                revisions: document.revisions, courses: document.courses, settings: document.settings,
                revisionId: revisionId, score: max(0, min(100, score)), now: now()
            )
        } catch {
            toast = .init(title: "Séance non enregistrée", message: "Les dates de ce cours sont invalides.", kind: .warning)
            return nil
        }
        today = outcome.today
        guard !outcome.ignored, let explanation = outcome.explanation else { return nil }
        document.revisions = outcome.revisions
        persist()
        let next = RevisionSession.nextDue(revisions: document.revisions, courses: document.courses,
                                           subjects: document.subjects, today: today, excluding: revisionId)
        let warning = outcome.toast?.kind == .warning
        let title = outcome.toast?.title ?? "Révision validée (\(score)/100)"
        let message = outcome.toast?.message ?? explanation.message
        if !(showsResult && next != nil) {
            if outcome.celebrate {
                celebration += 1
            } else if score >= 75 {
                confetti += 1
            }
            if outcome.toast != nil {
                toast = .init(title: outcome.celebrate ? "Programme terminé" : title, message: message, kind: warning ? .warning : .success)
            }
        }
        return Validation(explanation: explanation, title: title, message: message, warning: warning, next: next, remaining: programme.due.count)
    }

    /// « Encore N séances au programme ce soir. » (Android `JMethodApp.kt`) ; nil s'il n'en reste aucune.
    nonisolated static func remainingLabel(_ count: Int) -> String? {
        count > 0 ? "Encore \(count) séance\(count > 1 ? "s" : "") au programme ce soir." : nil
    }

    // MARK: Cours

    /// Ouvre le formulaire de création ; un créneau Pronote préremplit la matière, la date et le titre.
    func openAddCourse(from event: PronoteEvent? = nil) {
        discardNotebookDraft()
        if let event {
            let prefill = CourseCreation.prefill(event: event, subjects: document.subjects)
            if let subjectId = prefill.subjectId { courseDraft.subjectId = subjectId }
            courseDraft.date = prefill.date
            courseDraft.title = prefill.title
        } else {
            courseDraft.title = ""
        }
        courseDraft.description = ""
        if courseDraft.subjectId.map({ id in !document.subjects.contains { $0.id == id } }) ?? true {
            courseDraft.subjectId = document.subjects.first?.id
        }
        presented = .addCourse
    }

    /// Crée le cours du formulaire avec ses révisions initiales. Retourne false si le formulaire est refusé.
    @discardableResult
    func createCourse() -> Bool {
        refreshDay()
        let pending = notebookDraft
        let created: (outcome: CourseCreation.Outcome, course: Course)?
        do {
            created = try insertCourse(courseDraft, notebook: pending, documents: pending?.documents.filter(\.selected) ?? [])
        } catch {
            toast = .init(title: "Cours non créé", message: "La date du cours est invalide.", kind: .warning)
            return false
        }
        guard let created else { return false }
        if pending != nil { discardNotebookDraft() }
        toast = .init(title: created.outcome.toast.title, message: created.outcome.toast.message, kind: .success)
        return true
    }

    /// Création effective : cours, révisions initiales et, depuis une séance, source, documents et suivi d'import.
    private func insertCourse(_ draft: CourseDraft, notebook: NotebookCourseDraft?, documents staged: [StagedDocument]) throws
        -> (outcome: CourseCreation.Outcome, course: Course)? {
        // Identifiant `course-<ms>` unique même pour plusieurs créations dans la même milliseconde.
        var at = now()
        while document.courses.contains(where: { $0.id == "course-\(AdaptiveAlgorithm.epochMilliseconds(at))" }) {
            at = at.addingTimeInterval(0.001)
        }
        guard var outcome = try CourseCreation.create(draft, courses: document.courses, revisions: document.revisions,
                                                      settings: document.settings, now: at) else { return nil }
        var course = outcome.course
        if let notebook {
            course.source = notebook.source
            var stored: [CourseDocument] = []
            for item in staged.prefix(CourseDocumentStore.maximumDocuments) {
                if let document = try? documentStore.store(item.url, name: item.name, mime: item.mime, courseId: course.id, extractedTitle: item.extractedTitle) {
                    stored.append(document)
                }
            }
            course.documents = stored.isEmpty ? nil : stored
            outcome.courses[0] = course
            outcome.course = course
            document.notebookImports[notebook.source.sessionKey] = NotebookImport(status: .created, courseId: course.id, at: Self.isoString(now()))
        }
        document.courses = outcome.courses
        document.revisions = outcome.revisions
        persist()
        return (outcome, course)
    }

    /// Création automatique (réglage) : un cours par séance, sans formulaire. Retourne l'identifiant du cours créé.
    @discardableResult
    func createCourseFromNotebook(_ draft: NotebookCourseDraft) -> String? {
        try? insertCourse(draft.form, notebook: draft, documents: draft.documents)?.course.id
    }

    /// Formulaire prérempli depuis une séance du cahier de textes (documents déjà téléchargés).
    func openAddCourse(fromNotebook draft: NotebookCourseDraft) {
        discardNotebookDraft()
        courseDraft = draft.form
        notebookDraft = draft
        presented = .addCourse
    }

    /// Formulaire fermé sans création : les fichiers préparés sont effacés.
    func discardNotebookDraft() {
        guard let draft = notebookDraft else { return }
        notebookDraft = nil
        draft.removeStagedFiles()
    }

    func setNotebookDocument(_ id: String, selected: Bool) {
        guard let index = notebookDraft?.documents.firstIndex(where: { $0.id == id }) else { return }
        notebookDraft?.documents[index].selected = selected
    }

    /// Supprime le cours, toutes ses révisions et ses fichiers ; une séance du cahier n'est plus jamais recréée
    /// automatiquement (`dismissed`). Les créneaux Pronote ne changent pas.
    func deleteCourse(id: String) {
        let removed = course(id: id)
        let result = CourseDeletion.apply(courseId: id, courses: document.courses, revisions: document.revisions)
        document.courses = result.courses
        document.revisions = result.revisions
        if let removed {
            documentStore.removeAll(courseId: removed.id)
            if let key = removed.source?.sessionKey {
                document.notebookImports[key] = NotebookImport(status: .dismissed, courseId: removed.id, at: Self.isoString(now()))
            }
        }
        persist()
        toast = .init(title: CourseDeletion.toastTitle, message: CourseDeletion.toastMessage, kind: .info)
    }

    // MARK: Cahier de textes et documents

    func documentURL(_ document: CourseDocument) -> URL? { documentStore.url(for: document) }

    func documentExists(_ document: CourseDocument) -> Bool { documentStore.exists(document) }

    enum AttachFailure: LocalizedError, Equatable {
        case courseMissing, full, alreadyAttached
        var errorDescription: String? {
            switch self {
            case .courseMissing: "Ce cours n’existe plus."
            case .full: "Ce cours a déjà \(CourseDocumentStore.maximumDocuments) documents."
            case .alreadyAttached: "Ce document est déjà joint à ce cours."
            }
        }
    }

    /// Fichier retéléchargé : taille et titre lu mis à jour (le nom, le type et le chemin ne changent pas).
    func updateDocumentFile(courseId: String, documentId: String, size: Int, extractedTitle: String?) {
        guard let index = document.courses.firstIndex(where: { $0.id == courseId }),
              let position = document.courses[index].documents?.firstIndex(where: { $0.id == documentId }) else { return }
        document.courses[index].documents?[position].size = size
        document.courses[index].documents?[position].extractedTitle = extractedTitle
        persist()
    }

    /// « Joindre à ce cours » (§8.5) : copie le fichier dans les documents du cours (nouvel identifiant, même nom,
    /// titre lu conservé). L'autre cours et le titre de ce cours ne changent pas.
    func attachDocument(from source: URL, name: String, mime: String?, extractedTitle: String?, to courseId: String) throws {
        guard let index = document.courses.firstIndex(where: { $0.id == courseId }) else { throw AttachFailure.courseMissing }
        let existing = document.courses[index].documents ?? []
        guard !existing.contains(where: { $0.name == name }) else { throw AttachFailure.alreadyAttached }
        guard existing.count < CourseDocumentStore.maximumDocuments else { throw AttachFailure.full }
        let stored = try documentStore.store(source, name: name, mime: mime, courseId: courseId, extractedTitle: extractedTitle)
        document.courses[index].documents = existing + [stored]
        persist()
        toast = .init(title: "Document joint", message: "« \(name) » est maintenant dans ce cours.", kind: .success)
    }

    /// « Créer les cours automatiquement » : activation à partir d'aujourd'hui, ou désactivation.
    func setNotebookAutoCreate(_ enabled: Bool) {
        refreshDay()
        document.settings.notebookAutoCreate = enabled
        document.settings.notebookAutoCreateSince = enabled ? today : document.settings.notebookAutoCreateSince
        persist()
    }

    /// Proposition des 14 derniers jours acceptée : les séances éligibles commencent à `since`.
    func setNotebookAutoCreateSince(_ since: String) {
        document.settings.notebookAutoCreateSince = since
        persist()
    }

    // MARK: Réglages et sauvegarde

    /// « Enregistrer » des réglages : appliqués aux prochaines validations et aux nouveaux cours.
    func saveSettings(sensitivity: Double, emergencyThreshold: Double, intervals: [Int]) {
        document.settings = SettingsLogic.save(document.settings, icalUrl: document.settings.pronoteIcalUrl,
                                               sensitivity: sensitivity, emergencyThreshold: emergencyThreshold, intervals: intervals)
        persist()
        toast = .init(title: "Réglages enregistrés", message: "Ils s’appliquent aux prochaines validations et aux nouveaux cours.", kind: .success)
    }

    /// Couleur d'une matière : enregistrée aussitôt, comme sur le web.
    func setSubjectColor(subjectId: String, color: String) {
        guard SettingsLogic.isValidColor(color) else { return }
        document.subjects = SettingsLogic.subjectColor(document.subjects, subjectId: subjectId, color: color)
        persist()
    }

    /// Texte de l'export au format `exportAllData`.
    func exportText() -> String { Backup.export(document, now: now()) }

    var exportFileName: String { Backup.fileName(today: SchoolDay.today(now: now())) }

    /// Restauration confirmée : cours et séances remplacés, matières et réglages s'ils sont fournis, créneaux Pronote conservés.
    func restore(_ accepted: Backup.Accepted) {
        // Une sauvegarde ancienne peut porter des clés du cahier au format périmé.
        document = NotebookCourses.migratingKeys(accepted.apply(to: document))
        presented = nil
        persist()
        toast = .init(title: "Sauvegarde restaurée", message: "\(accepted.courses.count) cours et \(accepted.revisions.count) séance\(accepted.revisions.count > 1 ? "s" : "") importé\(accepted.revisions.count > 1 ? "s" : "").", kind: .success)
    }

    func showToast(_ title: String, _ message: String, warning: Bool = false) {
        toast = .init(title: title, message: message, kind: warning ? .warning : .success)
    }

    // MARK: Pronote

    func applyPronote(lessons: [PronoteLesson], syncedAt: Date, announce: Bool = true) {
        let events = LessonConverter.events(from: lessons, subjects: document.subjects)
        document.pronoteEvents = events
        document.settings.lastPronoteSync = Self.isoString(syncedAt)
        persist()
        guard announce else { return }
        toast = .init(title: "Pronote synchronisé", message: "\(events.count) créneau\(events.count > 1 ? "x" : "") importé\(events.count > 1 ? "s" : "") dans ton planning.", kind: .success)
    }

    /// Oubli du compte : les créneaux importés de Pronote sont retirés du téléphone.
    func clearPronoteData() {
        document.pronoteEvents = []
        document.settings.lastPronoteSync = nil
        persist()
    }

    var lastPronoteSync: Date? {
        document.settings.lastPronoteSync.flatMap(Self.parseISO)
    }

    // MARK: Persistance

    private func persist() {
        version += 1
        let snapshot = document
        let currentVersion = version
        guard let store else { return }
        Task { [weak self] in
            do {
                try await store.save(snapshot, version: currentVersion)
            } catch {
                self?.toast = .init(title: "Enregistrement impossible", message: "Tes dernières modifications n’ont pas pu être enregistrées sur l’iPhone.", kind: .warning)
            }
        }
    }

    /// Comme `Date.prototype.toISOString` : `2026-11-17T18:30:00.000Z`.
    nonisolated static func isoString(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    nonisolated static func parseISO(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }
}
