import Foundation
import Observation

/// Cache privé des pièces jointes (Caches, exclu des sauvegardes) : effacé au lancement, à la déconnexion,
/// au changement d'enfant et à chaque nouvelle lecture.
enum NotebookCache {
    static var directory: URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("notebook", isDirectory: true)
    }

    static func clear() {
        try? FileManager.default.removeItem(at: directory)
    }

    /// Un sous-dossier aléatoire par fichier, pour garder le nom d'origine sans collision.
    static func destination(for name: String) -> URL {
        directory.appendingPathComponent(UUID().uuidString, isDirectory: true).appendingPathComponent(NotebookRules.saveAsName(name))
    }
}

/// État de l'aperçu du cahier de textes. Rien n'est persisté : les identifiants de pièces jointes restent en mémoire.
@Observable
@MainActor
final class NotebookModel {
    enum Tab: Hashable { case contents, homework }

    private(set) var snapshot: NotebookSnapshot?
    private(set) var loading = false
    private(set) var error: String?
    var tab: Tab = .contents
    /// Matière J-Method choisie depuis la fiche d'un cours : seuls ses contenus sont affichés.
    var subjectId: String?
    private(set) var downloadingId: String?
    /// Fichier téléchargé à présenter (QuickLook).
    var preview: URL?
    /// Message ponctuel (téléchargement refusé, etc.).
    var message: String?

    @ObservationIgnored private let pronote: PronoteSession
    @ObservationIgnored private var session: UUID?
    /// Lecture réussie au premier plan (création automatique des cours si le réglage est actif).
    @ObservationIgnored var onRead: ((NotebookRead) -> Void)?

    /// Session Pronote de la lecture affichée (préparation d'un cours depuis une séance).
    var readSession: UUID? { session }
    @ObservationIgnored private var scope: Int
    @ObservationIgnored private var reading: Task<Void, Never>?
    @ObservationIgnored private var downloading: Task<Void, Never>?

    init(pronote: PronoteSession) {
        self.pronote = pronote
        self.scope = pronote.notebookScope
        NotebookCache.clear()
    }

    /// Ouverture depuis Mes cours (toutes matières) ou depuis la fiche d'un cours (sa matière).
    func open(subjectId: String?) {
        self.subjectId = subjectId
        tab = .contents
        if snapshot == nil && !loading { refresh() }
    }

    /// Déconnexion, changement d'enfant, nouvelle connexion : l'aperçu et les fichiers sont effacés.
    func scopeChanged() {
        guard pronote.notebookScope != scope else { return }
        scope = pronote.notebookScope
        reading?.cancel()
        reading = nil
        downloading?.cancel()
        downloading = nil
        loading = false
        snapshot = nil
        session = nil
        error = nil
        preview = nil
        downloadingId = nil
        NotebookCache.clear()
    }

    func refresh() {
        guard !loading else { return }
        loading = true
        error = nil
        let expected = scope
        reading = Task { [weak self] in
            guard let self else { return }
            do {
                let read = try await pronote.readNotebook()
                guard !Task.isCancelled, expected == self.scope, expected == pronote.notebookScope else { return }
                // Nouvelle lecture : les fichiers d'une session précédente ne servent plus.
                NotebookCache.clear()
                self.session = read.session
                self.snapshot = read.snapshot
                self.loading = false
                self.onRead?(read)
            } catch {
                guard !Task.isCancelled, expected == self.scope else { return }
                self.loading = false
                self.error = Self.text(for: error, fallback: "Lecture du cahier de textes impossible. Réessaie plus tard.")
            }
        }
    }

    /// Lecture discrète demandée par une fiche (§8.1) : seulement sans aperçu, au plus une tentative toutes les 5 min.
    @ObservationIgnored private var lastEnsure: Date?

    func ensureRead(now: Date = Date()) {
        guard snapshot == nil, !loading else { return }
        if let lastEnsure, now.timeIntervalSince(lastEnsure) < 5 * 60 { return }
        lastEnsure = now
        refresh()
    }

    /// Téléchargement pour une fiche (§8.1, §8.5) : fichier du cache privé rendu à l'appelant (visionneuse de la feuille
    /// ou copie dans un cours), mêmes règles de taille et de session que le cahier.
    func fetch(_ attachment: NotebookAttachment) async throws -> URL {
        guard attachment.kind == .file, let session else { throw PronoteFailure(code: "download-unavailable", message: NotebookRules.downloadUnavailable) }
        let expected = scope
        let destination = NotebookCache.destination(for: attachment.name)
        do {
            try await pronote.downloadAttachment(attachment, session: session, to: destination)
        } catch {
            try? FileManager.default.removeItem(at: destination.deletingLastPathComponent())
            throw error
        }
        guard !Task.isCancelled, expected == scope, expected == pronote.notebookScope else {
            try? FileManager.default.removeItem(at: destination.deletingLastPathComponent())
            throw CancellationError()
        }
        return destination
    }

    func download(_ attachment: NotebookAttachment) {
        guard attachment.kind == .file, downloadingId == nil, let session else { return }
        downloadingId = attachment.id
        let expected = scope
        let destination = NotebookCache.destination(for: attachment.name)
        downloading = Task { [weak self] in
            guard let self else { return }
            defer { if self.scope == expected { self.downloadingId = nil } }
            do {
                try await pronote.downloadAttachment(attachment, session: session, to: destination)
                // Résultat arrivé après une déconnexion, un changement d'enfant ou une annulation : rien n'est ouvert.
                guard !Task.isCancelled, expected == self.scope, expected == pronote.notebookScope else {
                    try? FileManager.default.removeItem(at: destination.deletingLastPathComponent())
                    return
                }
                self.preview = destination
            } catch {
                try? FileManager.default.removeItem(at: destination.deletingLastPathComponent())
                guard !Task.isCancelled, expected == self.scope else { return }
                self.message = Self.text(for: error, fallback: NotebookRules.downloadUnavailable)
            }
        }
    }

    #if DEBUG
    /// Vérification visuelle (Debug, `-JMethodPreviewSheet notebook`) : aperçu fictif, sans session ni téléchargement possible.
    func loadPreview(today: String) {
        let day: (Int) -> String = { SchoolDay.adding($0, to: today) ?? today }
        snapshot = NotebookSnapshot(
            from: day(-14), to: day(13),
            sessions: [
                NotebookSession(id: "preview-1", lessonId: "l1", date: day(-1), subject: "MATHEMATIQUES", contents: [
                    NotebookContent(title: "Fonctions affines", description: "Définition et représentation graphique.\n• Coefficient directeur\n• Ordonnée à l’origine",
                                    links: [], category: "Cours", attachments: [NotebookAttachment(kind: .file, id: "f1", name: "Fiche d’exercices.pdf")]),
                    NotebookContent(title: "Exercices", description: "Ex. 12 et 13 p. 45", links: [NotebookLink(label: "manuel en ligne", url: "https://exemple.fr/manuel")],
                                    category: "Exercices", attachments: []),
                ]),
                NotebookSession(id: "preview-2", lessonId: "l2", date: day(-3), subject: "FRANCAIS", contents: [
                    NotebookContent(title: "La proposition subordonnée", description: "Relire la leçon.", links: [], category: "", attachments: []),
                ]),
            ],
            homework: [
                NotebookHomework(id: "h1", dueDate: day(1), subject: "MATHEMATIQUES", description: "Exercices 12 à 15 p. 45", links: [], done: false,
                                 attachments: [NotebookAttachment(kind: .file, id: "f2", name: "Énoncé.pdf")]),
                NotebookHomework(id: "h2", dueDate: day(3), subject: "ANGLAIS LV1", description: "Écouter le podcast et noter 5 mots.",
                                 links: [NotebookLink(label: "podcast", url: "https://exemple.fr/podcast")], done: true, attachments: []),
            ],
            skippedEntries: 1, attachmentCount: 2
        )
        loading = false
        error = nil
        // Session fictive : « Créer un cours » ouvre le formulaire (téléchargements refusés, signalés).
        session = UUID()
    }
    #endif

    /// Les messages des erreurs Pronote sont fixes et sans donnée de session ; les autres restent génériques.
    static func text(for error: Error, fallback: String) -> String {
        if let failure = error as? PronoteFailure {
            return failure.code.hasPrefix("pronote-") || failure.code.hasPrefix("http") ? fallback : failure.message
        }
        if error is URLError { return "Pronote est injoignable. Vérifie ta connexion internet, puis réessaie." }
        return fallback
    }

    /// La matière Pronote d'une séance correspond-elle à la matière J-Method ?
    static func matches(_ label: String, subjectId: String, subjects: [Subject]) -> Bool {
        SubjectMatcher.match(label, subjects: subjects)?.id == subjectId
    }
}
