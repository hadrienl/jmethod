import Foundation
import Observation

/// Du cahier de textes aux cours (`notebook-to-course.md`) : préparation d'une séance (téléchargement des fichiers,
/// extraction du titre et du chapitre), formulaire prérempli, création automatique si le réglage est actif.
/// Téléchargements et extraction se font au premier plan uniquement.
@Observable
@MainActor
final class NotebookCourseImporter {
    /// Proposition faite à l'activation : séances des 14 jours précédents.
    struct BackfillOffer: Equatable {
        var proposal: NotebookCourses.Backfill
        fileprivate var read: NotebookRead
        var count: Int { proposal.count }

        static func == (lhs: BackfillOffer, rhs: BackfillOffer) -> Bool {
            lhs.proposal == rhs.proposal && lhs.read.session == rhs.read.session
        }
    }

    /// Séance en cours de préparation (indicateur de progression sur son bouton).
    private(set) var preparingSessionId: String?
    private(set) var running = false
    var backfillOffer: BackfillOffer?

    @ObservationIgnored private let app: AppModel
    @ObservationIgnored private let pronote: PronoteSession
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private var lastAutomaticRead: Date?

    init(app: AppModel, pronote: PronoteSession, now: @escaping () -> Date = Date.init) {
        self.app = app
        self.pronote = pronote
        self.now = now
    }

    // MARK: Création manuelle

    /// « Créer un cours » : fichiers téléchargés et titre extrait avant l'ouverture du formulaire.
    func prepare(_ session: NotebookSession, readSession: UUID) {
        guard preparingSessionId == nil else { return }
        preparingSessionId = session.id
        Task {
            let draft = await stage(session, readSession: readSession)
            preparingSessionId = nil
            app.openAddCourse(fromNotebook: draft)
        }
    }

    // MARK: Création automatique

    /// Après une lecture réussie du cahier au premier plan (ouverture de l'écran, actualisation).
    func afterNotebookRead(_ read: NotebookRead) {
        guard app.document.settings.autoCreatesCourses else { return }
        lastAutomaticRead = now()
        Task { await createAutomatically(read) }
    }

    /// Après une synchronisation réussie au premier plan : au plus une lecture du cahier toutes les 2 h pour ce besoin.
    func afterForegroundSync() async {
        guard app.document.settings.autoCreatesCourses, !running else { return }
        if let last = lastAutomaticRead, now().timeIntervalSince(last) < NotebookCourses.autoReadInterval { return }
        lastAutomaticRead = now()
        guard let read = try? await pronote.readNotebook() else { return }
        await createAutomatically(read)
    }

    /// Activation du réglage : à partir d'aujourd'hui, puis proposition d'importer les 14 derniers jours.
    func activate() async {
        app.setNotebookAutoCreate(true)
        lastAutomaticRead = now()
        let read: NotebookRead
        do {
            read = try await pronote.readNotebook()
        } catch {
            app.showToast("Création automatique activée", "Le cahier de textes n’a pas pu être lu pour l’instant : les cours seront créés à la prochaine synchronisation.", warning: true)
            return
        }
        let proposal = NotebookCourses.backfill(read.snapshot.sessions, imports: app.document.notebookImports,
                                                courses: app.document.courses, subjects: app.document.subjects, today: app.today)
        if proposal.count == 0 {
            await createAutomatically(read)
        } else {
            backfillOffer = BackfillOffer(proposal: proposal, read: read)
        }
    }

    /// Réponse à « Importer aussi les N séances des 14 derniers jours ? » : « Importer » fait commencer la création
    /// automatique 14 jours plus tôt, puis la règle d'éligibilité ordinaire s'applique.
    func answerBackfill(importOlder: Bool) {
        guard let offer = backfillOffer else { return }
        backfillOffer = nil
        if importOlder { app.setNotebookAutoCreateSince(offer.proposal.acceptSetsSince) }
        Task { await createAutomatically(offer.read) }
    }

    func deactivate() {
        app.setNotebookAutoCreate(false)
        backfillOffer = nil
    }

    /// Un cours par séance éligible, par date croissante ; les numéros se suivent dans le lot.
    private func createAutomatically(_ read: NotebookRead) async {
        guard app.document.settings.autoCreatesCourses, !running else { return }
        running = true
        defer { running = false }
        let eligible = NotebookCourses.eligible(read.snapshot.sessions, settings: app.document.settings,
                                                imports: app.document.notebookImports, courses: app.document.courses,
                                                subjects: app.document.subjects, today: app.today)
        var created = 0
        for session in eligible {
            // Revérifié à chaque séance : un import manuel ou une désactivation a pu avoir lieu entre-temps.
            guard NotebookCourses.skipReason(session, settings: app.document.settings, imports: app.document.notebookImports,
                                             courses: app.document.courses, subjects: app.document.subjects, today: app.today) == nil else { continue }
            let draft = await stage(session, readSession: read.session)
            defer { draft.removeStagedFiles() }
            guard draft.form.subjectId != nil, app.createCourseFromNotebook(draft) != nil else { continue }
            created += 1
        }
        if let message = NotebookCourses.toast(created) {
            app.showToast(created > 1 ? "\(created) cours créés" : "1 cours créé", message)
        }
    }

    // MARK: Documents

    /// Document restauré sans son fichier : relit le cahier et le retélécharge si la séance est encore dans la fenêtre.
    func redownload(_ document: CourseDocument, of course: Course) async throws {
        guard let key = course.source?.sessionKey else { throw Failure.notInNotebook }
        let read = try await pronote.readNotebook()
        let session = read.snapshot.sessions.first { NotebookCourses.sessionKey($0) == key }
            ?? read.snapshot.sessions.first { NotebookCourses.matchingCourse($0, courses: [course]) != nil }
        guard let session,
              let attachment = NotebookCourses.fileAttachments(of: session).first(where: { $0.name == document.name }) else { throw Failure.notInNotebook }
        let directory = NotebookCourseDraft.newStagingDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let destination = directory.appendingPathComponent(NotebookRules.saveAsName(attachment.name))
        try await pronote.downloadAttachment(attachment, session: read.session, to: destination)
        let names = [session.subject] + (app.subject(id: course.subjectId).map { [$0.name] } ?? [])
        let name = attachment.name
        let (size, title) = await Task.detached(priority: .userInitiated) { () -> (Int, String?) in
            guard let data = try? Data(contentsOf: destination) else { return (0, nil) }
            let extraction = DocumentText.extract(name: name, data: data)
            return (data.count, extraction.kind == nil ? nil : CourseTitleRules.extractedTitle(extraction, name: name, subjectNames: names))
        }.value
        try app.documentStore.restore(destination, for: document)
        app.updateDocumentFile(courseId: course.id, documentId: document.id, size: size, extractedTitle: title)
    }

    enum Failure: LocalizedError {
        case notInNotebook
        var errorDescription: String? { "Cette séance n’est plus dans le cahier de textes (28 jours) : le document ne peut pas être retéléchargé." }
    }

    // MARK: Préparation

    /// Télécharge les fichiers de la séance dans l'ordre (10 enregistrés au plus, 20 Mo chacun ; les échecs ne comptent pas),
    /// puis prépare le formulaire hors du fil principal (extraction du titre et du chapitre).
    private func stage(_ session: NotebookSession, readSession: UUID) async -> NotebookCourseDraft {
        enum Outcome: Sendable { case stored(URL), failed(String) }
        let directory = NotebookCourseDraft.newStagingDirectory()
        var outcomes: [String: Outcome] = [:]
        var stored = 0
        for (index, attachment) in NotebookCourses.fileAttachments(of: session).enumerated() where stored < NotebookCourses.maximumFiles {
            let destination = directory.appendingPathComponent("\(index)", isDirectory: true).appendingPathComponent(NotebookRules.saveAsName(attachment.name))
            do {
                try await pronote.downloadAttachment(attachment, session: readSession, to: destination)
                outcomes[attachment.id] = .stored(destination)
                stored += 1
            } catch {
                outcomes[attachment.id] = .failed((error as? PronoteFailure)?.code == "download-size" ? "tooLarge" : "unavailable")
            }
        }
        let subjects = app.document.subjects, courses = app.document.courses, known = outcomes
        let prefill = await Task.detached(priority: .userInitiated) {
            NotebookCourses.prefill(session: session, subjects: subjects, courses: courses) { attachment in
                switch known[attachment.id] {
                case .stored(let url)?:
                    guard let data = try? Data(contentsOf: url) else { return .failed("unavailable") }
                    return .data(data, url: url)
                case .failed(let reason)?: return .failed(reason)
                case nil: return .failed("unavailable")
                }
            }
        }.value
        let documents: [StagedDocument] = prefill.documents.compactMap { document in
            guard let url = document.url else { return nil }
            return StagedDocument(id: document.attachment.id, name: document.attachment.name, url: url, size: document.size,
                                  kind: DocumentKind.detect(name: document.attachment.name, data: nil), mime: document.mime,
                                  extractedTitle: document.extractedTitle)
        }
        return NotebookCourseDraft(
            form: NotebookCourses.form(prefill),
            source: NotebookCourses.source(for: session),
            documents: documents,
            failedFiles: prefill.skipped.filter { $0.reason != "limit" }.map(\.name),
            skippedFiles: prefill.skipped.filter { $0.reason == "limit" }.count,
            stagingDirectory: directory
        )
    }
}
