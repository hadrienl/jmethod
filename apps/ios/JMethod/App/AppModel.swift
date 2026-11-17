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
        case score(revisionId: String)
        case revision(revisionId: String)
        case course(courseId: String)
        case addCourse

        var id: String {
            switch self {
            case .score(let id): "score-\(id)"
            case .revision(let id): "revision-\(id)"
            case .course(let id): "course-\(id)"
            case .addCourse: "add-course"
            }
        }
    }

    @ObservationIgnored private let store: DocumentStore?
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

    init(store: DocumentStore?, now: @escaping () -> Date = Date.init, preferences: UserDefaults = .standard) {
        self.store = store
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

    /// Validation d'une séance du programme : autoévaluation puis replanification adaptative depuis le jour préparé.
    @discardableResult
    func validate(revisionId: String, score: Int) -> AdaptiveAlgorithm.Explanation? {
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
        guard !outcome.ignored else { return nil }
        document.revisions = outcome.revisions
        persist()
        if outcome.celebrate {
            celebration += 1
        } else if score >= 75 {
            confetti += 1
        }
        if let message = outcome.toast {
            toast = .init(title: message.title, message: message.message, kind: message.kind == .warning ? .warning : .success)
        }
        return outcome.explanation
    }

    // MARK: Cours

    /// Ouvre le formulaire de création ; un créneau Pronote préremplit la matière, la date et le titre.
    func openAddCourse(from event: PronoteEvent? = nil) {
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
        let outcome: CourseCreation.Outcome?
        do {
            outcome = try CourseCreation.create(courseDraft, courses: document.courses, revisions: document.revisions,
                                                settings: document.settings, now: now())
        } catch {
            toast = .init(title: "Cours non créé", message: "La date du cours est invalide.", kind: .warning)
            return false
        }
        guard let outcome else { return false }
        document.courses = outcome.courses
        document.revisions = outcome.revisions
        persist()
        toast = .init(title: outcome.toast.title, message: outcome.toast.message, kind: .success)
        return true
    }

    /// Supprime le cours et toutes ses révisions ; les créneaux Pronote ne changent pas.
    func deleteCourse(id: String) {
        let result = CourseDeletion.apply(courseId: id, courses: document.courses, revisions: document.revisions)
        document.courses = result.courses
        document.revisions = result.revisions
        persist()
        toast = .init(title: CourseDeletion.toastTitle, message: CourseDeletion.toastMessage, kind: .info)
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
    static func isoString(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    static func parseISO(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }
}
