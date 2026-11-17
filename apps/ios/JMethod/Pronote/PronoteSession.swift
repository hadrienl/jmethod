import Foundation
import Observation

/// Connexion Pronote : formulaire de connexion, page officielle masquée (affichée seulement si nécessaire),
/// reprise par jeton au lancement, synchronisation des 14 prochains jours, choix de l'enfant, déconnexion.
/// L'accès à l'app dépend de `access`.
@Observable
@MainActor
final class PronoteSession {
    enum Phase: Equatable, Sendable {
        case idle, signingIn, authenticated, qr, nativeAuthentication, synchronizing, ready, failed

        var isWorking: Bool {
            switch self {
            case .signingIn, .authenticated, .qr, .nativeAuthentication, .synchronizing: true
            case .idle, .ready, .failed: false
            }
        }

        /// Libellé simple affiché pendant une connexion.
        var progressLabel: String {
            self == .synchronizing ? "Lecture de ton emploi du temps…" : "Connexion à Pronote…"
        }
    }

    /// Accès à l'app.
    enum Access: Equatable, Sendable {
        /// Reprise de la connexion enregistrée au lancement (écran de chargement).
        case resuming
        /// Aucune connexion active : écran de connexion plein écran.
        case signedOut
        /// Connexion enregistrée mais Pronote injoignable : l'app s'ouvre sur les données locales.
        case offline
        case connected
    }

    /// Issue d'un échec de reprise par jeton.
    enum ResumeFailure: Equatable, Sendable {
        /// Pronote refuse la connexion enregistrée : il faut se reconnecter.
        case signInRequired
        /// Réseau ou serveur indisponible : la connexion est conservée.
        case unavailable
    }

    private(set) var phase: Phase = .idle
    private(set) var access: Access
    private(set) var errorMessage: String?
    private(set) var account: PronoteAccount?
    private(set) var selectedChildId: String?
    private(set) var hasSavedConnection = false
    /// Établissement et type de compte mémorisés pour préremplir le formulaire (jamais le mot de passe).
    private(set) var school: PronoteKeychain.School?
    /// Page officielle en cours d'utilisation (masquée ou affichée).
    private(set) var webActive = false
    /// Page officielle affichée (ENT, vérification de sécurité).
    private(set) var webVisible = false
    /// Raison pour laquelle la page officielle est affichée.
    private(set) var webMessage: String?

    @ObservationIgnored let web = PronoteWebFlow()
    @ObservationIgnored private weak var app: AppModel?
    @ObservationIgnored private var client: PronoteClient?
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()
    @ObservationIgnored private var resumed = false

    init(app: AppModel) {
        self.app = app
        let saved = (try? PronoteKeychain.loadCredentials()) != nil
        hasSavedConnection = saved
        access = saved ? .resuming : .signedOut
        school = PronoteKeychain.loadSchool()
        selectedChildId = PronoteKeychain.loadSelectedChild()
        web.onEvent = { [weak self] event in self?.handle(event) }
    }

    var isWorking: Bool { phase.isWorking }

    /// Un échec de reprise par jeton n'impose une nouvelle connexion que si Pronote refuse explicitement le jeton
    /// ou demande une vérification ; sinon (réseau, serveur, appareil verrouillé) la connexion est conservée.
    nonisolated static func classifyResumeFailure(_ error: Error) -> ResumeFailure {
        if let failure = error as? PronoteFailure, failure.code == "login" || failure.code == "mfa" { return .signInRequired }
        return .unavailable
    }

    // MARK: Entrées utilisateur

    /// Reprise automatique par jeton, une seule fois par lancement.
    func resumeAtLaunch() {
        guard !resumed else { return }
        resumed = true
        guard hasSavedConnection else {
            access = .signedOut
            return
        }
        resumeSavedConnection()
    }

    /// Reprise par jeton (lancement ou bouton Réessayer du mode hors ligne).
    func resumeSavedConnection() {
        do {
            guard let saved = try PronoteKeychain.loadCredentials() else {
                hasSavedConnection = false
                access = .signedOut
                return
            }
            connect(saved, mode: .token)
        } catch {
            fail(error)
            if access == .resuming { access = .offline }
        }
    }

    /// Ouvre l'app sur les données locales sans attendre la fin de la reprise, qui continue en arrière-plan
    /// (elle n'est pas annulée, pour ne pas perdre un jeton déjà renouvelé par Pronote).
    func openWithoutWaiting() {
        if access == .resuming { access = .offline }
    }

    /// Formulaire de connexion : la page officielle reste masquée et reçoit les identifiants une seule fois.
    func signIn(school input: String, account type: PronoteAccountType, username: String, password: String) {
        cancel()
        errorMessage = nil
        let user = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolved = PronoteURL.resolve(input, account: type)
        guard !resolved.address.isEmpty else {
            return fail(PronoteFailure.invalid("url", "Indique le code ou l’adresse de ton établissement."))
        }
        guard !user.isEmpty, !password.isEmpty else {
            return fail(PronoteFailure.invalid("form", "Indique ton identifiant et ton mot de passe."))
        }
        do {
            let url = try PronoteURL.validated(resolved.address)
            guard PronoteURL.trustedPages.contains(url.lastPathComponent.lowercased()) else {
                throw PronoteFailure.invalid("url", "Cette adresse ne correspond pas à un espace Élève ou Parent.")
            }
            let remembered = PronoteKeychain.School(input: input.trimmingCharacters(in: .whitespacesAndNewlines), account: resolved.account)
            school = remembered
            PronoteKeychain.saveSchool(remembered)
            webActive = true
            webVisible = false
            web.start(url: url, username: user, password: password)
        } catch {
            fail(error)
        }
    }

    func synchronize() {
        guard !isWorking else { return }
        guard let client else {
            if hasSavedConnection { resumeSavedConnection() }
            return
        }
        let attempt = UUID()
        generation = attempt
        task?.cancel()
        errorMessage = nil
        phase = .synchronizing
        let child = selectedChildId
        task = Task { [weak self] in
            do {
                let lessons: [PronoteLesson]
                do {
                    lessons = try await client.timetable(childId: child)
                } catch let failure as PronoteFailure where failure.isExpiredSession {
                    // Session expirée : une seule reconnexion par jeton, puis nouvelle lecture.
                    guard let self, let saved = try PronoteKeychain.loadCredentials() else { throw failure }
                    let fresh = try PronoteClient(credentials: saved)
                    _ = try await fresh.login(mode: .token, persist: self.persister(for: attempt))
                    self.client = fresh
                    lessons = try await fresh.timetable(childId: child)
                }
                try Task.checkCancellation()
                guard let self, self.generation == attempt else { return }
                self.finish(lessons: lessons)
            } catch {
                guard let self, self.generation == attempt, !Task.isCancelled else { return }
                self.connectionFailed(error, mode: .token)
            }
        }
    }

    func selectChild(_ id: String) {
        guard id != selectedChildId, account?.children.contains(where: { $0.id == id }) == true else { return }
        selectedChildId = id
        PronoteKeychain.saveSelectedChild(id)
        synchronize()
    }

    /// Annule la tentative en cours (bouton Annuler de la page officielle) ; rien n'est enregistré après l'annulation.
    func cancel() {
        generation = UUID()
        task?.cancel()
        task = nil
        web.close()
        webActive = false
        webVisible = false
        webMessage = nil
        if phase.isWorking { phase = account == nil ? .idle : .ready }
    }

    func dismissError() {
        errorMessage = nil
        if phase == .failed { phase = account == nil ? .idle : .ready }
    }

    /// Déconnexion : jeton et choix d'enfant retirés du trousseau, données WebKit et créneaux Pronote effacés.
    /// Les cours et révisions restent ; l'établissement mémorisé sert à préremplir le formulaire.
    func disconnect() async {
        cancel()
        PronoteKeychain.removeConnection()
        await PronoteWebFlow.clearWebsiteData()
        app?.clearPronoteData()
        client = nil
        account = nil
        selectedChildId = nil
        hasSavedConnection = false
        errorMessage = nil
        phase = .idle
        access = .signedOut
    }

    // MARK: Flux

    private func handle(_ event: PronoteWebFlow.Event) {
        switch event {
        case .phase(let next):
            phase = next
        case .reveal(let message):
            webMessage = message
            webVisible = true
            web.setPageVisible(true)
        case .conceal:
            webVisible = false
            webMessage = nil
            web.setPageVisible(false)
        case .failed(let message), .rejected(let message):
            web.close()
            webActive = false
            webVisible = false
            webMessage = nil
            errorMessage = message
            phase = .failed
        case .qr(let qr, let pin):
            do {
                connect(try PronoteClient.credentials(qr: qr, pin: pin, uuid: stableUUID()), mode: .qr)
            } catch {
                fail(error)
            }
        }
    }

    /// Conserve l'UUID d'application déjà associé au même compte lors d'une reconnexion.
    private func stableUUID() -> String {
        (try? PronoteKeychain.loadCredentials())?.uuid ?? UUID().uuidString
    }

    private func connect(_ credentials: PronoteCredentials, mode: PronoteClient.LoginMode) {
        task?.cancel()
        let attempt = UUID()
        generation = attempt
        errorMessage = nil
        phase = .nativeAuthentication
        task = Task { [weak self] in
            do {
                guard let self else { return }
                let client = try PronoteClient(credentials: credentials)
                let account = try await client.login(mode: mode, persist: self.persister(for: attempt))
                try Task.checkCancellation()
                guard self.generation == attempt else { return }
                let snapshot = await client.credentialSnapshot()
                try Task.checkCancellation()
                guard self.generation == attempt else { return }
                try PronoteKeychain.saveCredentials(snapshot)
                self.hasSavedConnection = true
                self.client = client
                self.account = account
                if account.children.isEmpty {
                    self.selectedChildId = nil
                } else if !account.children.contains(where: { $0.id == self.selectedChildId }) {
                    self.selectedChildId = account.children.first?.id
                    PronoteKeychain.saveSelectedChild(self.selectedChildId)
                }
                self.phase = .synchronizing
                let lessons = try await client.timetable(childId: self.selectedChildId)
                try Task.checkCancellation()
                guard self.generation == attempt else { return }
                self.finish(lessons: lessons)
            } catch {
                guard let self, self.generation == attempt, !Task.isCancelled else { return }
                self.connectionFailed(error, mode: mode)
            }
        }
    }

    /// Enregistrement immédiat du jeton rotatif, seulement pour la tentative courante et non annulée.
    private func persister(for attempt: UUID) -> @Sendable (PronoteCredentials) async throws -> Void {
        return { [weak self] credentials in
            guard let self else { throw CancellationError() }
            try await self.storeRotatedToken(credentials, attempt: attempt)
        }
    }

    /// Exécuté dans la tâche de connexion : l'annulation et la génération sont vérifiées juste avant l'écriture.
    private func storeRotatedToken(_ credentials: PronoteCredentials, attempt: UUID) throws {
        guard generation == attempt, !Task.isCancelled else { throw CancellationError() }
        try PronoteKeychain.saveCredentials(credentials)
        hasSavedConnection = true
    }

    private func finish(lessons: [PronoteLesson]) {
        app?.applyPronote(lessons: lessons, syncedAt: Date())
        web.close()
        webActive = false
        webVisible = false
        webMessage = nil
        errorMessage = nil
        phase = .ready
        access = .connected
    }

    private func connectionFailed(_ error: Error, mode: PronoteClient.LoginMode) {
        fail(error)
        if account != nil {
            // Connexion établie mais lecture impossible : l'app reste ouverte, l'erreur est visible dans Réglages.
            if mode == .token, Self.classifyResumeFailure(error) == .signInRequired {
                signOutAfterRejection()
            } else {
                access = .connected
            }
            return
        }
        guard mode == .token else { return }
        switch Self.classifyResumeFailure(error) {
        case .signInRequired:
            signOutAfterRejection()
        case .unavailable:
            if access == .resuming { access = .offline }
        }
    }

    /// Jeton refusé : il est retiré, l'écran de connexion s'affiche avec un message simple.
    private func signOutAfterRejection() {
        PronoteKeychain.removeCredentials()
        hasSavedConnection = false
        client = nil
        account = nil
        errorMessage = "Ta session Pronote a expiré. Reconnecte-toi."
        access = .signedOut
    }

    private func fail(_ failure: Error) {
        if failure is CancellationError { return }
        errorMessage = (failure as? PronoteFailure)?.message
            ?? ((failure as? URLError).map { _ in "Pronote est injoignable. Vérifie ta connexion internet." })
            ?? "La connexion n’a pas abouti. Réessaie."
        phase = .failed
        web.close()
        webActive = false
        webVisible = false
        webMessage = nil
    }
}
