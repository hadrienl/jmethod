import Foundation
import Observation

/// Connexion Pronote : formulaire de connexion, page officielle masquée (affichée seulement si nécessaire),
/// reprise par jeton, reconnexion silencieuse avec les identifiants enregistrés (« Rester connecté »),
/// actualisation en arrière-plan, synchronisation des 14 prochains jours, choix de l'enfant, déconnexion.
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

    enum BackgroundResult: Equatable, Sendable {
        case synced
        case noConnection
        /// Une connexion est déjà en cours au premier plan.
        case busy
        /// Jeton refusé : reconnexion silencieuse marquée pour le prochain premier plan.
        case tokenRefused
        case failed
    }

    nonisolated static let foregroundSyncInterval: TimeInterval = 6 * 60 * 60

    private(set) var phase: Phase = .idle
    private(set) var access: Access
    private(set) var errorMessage: String?
    private(set) var account: PronoteAccount?
    private(set) var selectedChildId: String?
    private(set) var hasSavedConnection = false
    /// Reconnexion silencieuse en cours (bandeau discret, sans écran de connexion).
    private(set) var silentRelogin = false
    /// Préremplissage du formulaire : établissement, identifiant et, si « Rester connecté », mot de passe.
    private(set) var school: PronoteVault.School?
    private(set) var savedLogin: PronoteVault.SavedLogin?
    /// Page officielle en cours d'utilisation (masquée ou affichée).
    private(set) var webActive = false
    /// Page officielle affichée (ENT, vérification de sécurité).
    private(set) var webVisible = false
    /// Raison pour laquelle la page officielle est affichée.
    private(set) var webMessage: String?

    /// WebView réelle, présente seulement quand aucun parcours de test n'est injecté.
    @ObservationIgnored let web: PronoteWebFlow?
    @ObservationIgnored private let page: LoginPageDriver
    @ObservationIgnored private let vault: PronoteVault
    @ObservationIgnored private let makeClient: (PronoteCredentials) throws -> any PronoteSessionClient
    @ObservationIgnored private let clearWebData: () async -> Void
    @ObservationIgnored private weak var app: AppModel?
    @ObservationIgnored private var client: (any PronoteSessionClient)?
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()
    /// Incrémenté à la déconnexion : un jeton renouvelé par une connexion antérieure n'est plus enregistré.
    @ObservationIgnored private var epoch = 0
    @ObservationIgnored private var resumed = false
    /// Une connexion par jeton est en vol (premier plan ou arrière-plan) : aucune autre n'est lancée en parallèle.
    @ObservationIgnored private var tokenLoginInFlight = false
    /// Une seule reconnexion silencieuse par jeton refusé ; remis à zéro quand un nouveau jeton est obtenu.
    @ObservationIgnored private var silentAttemptUsed = false
    /// Identifiants à enregistrer après une connexion réussie (« Rester connecté »), puis effacés de la mémoire.
    @ObservationIgnored private var pendingRemember: (school: PronoteVault.School, login: PronoteVault.SavedLogin)?
    @ObservationIgnored private var rememberLogin = true

    init(app: AppModel, vault: PronoteVault = .live, page: LoginPageDriver? = nil,
         clearWebData: @escaping () async -> Void = { await PronoteWebFlow.clearWebsiteData() },
         makeClient: ((PronoteCredentials) throws -> any PronoteSessionClient)? = nil) {
        self.app = app
        self.vault = vault
        self.clearWebData = clearWebData
        if let page {
            self.page = page
            self.web = nil
        } else {
            let flow = PronoteWebFlow()
            self.page = flow
            self.web = flow
        }
        self.makeClient = makeClient ?? { try PronoteClient(credentials: $0) }
        let saved = (try? vault.loadCredentials()) != nil
        hasSavedConnection = saved
        let canRelogin = vault.needsSilentRelogin && vault.autoReloginEnabled && vault.loadLogin()?.password != nil
        access = saved || canRelogin ? .resuming : .signedOut
        school = vault.loadSchool()
        savedLogin = vault.loadLogin()
        selectedChildId = vault.loadSelectedChild()
        self.page.onEvent = { [weak self] event in self?.handle(event) }
    }

    var isWorking: Bool { phase.isWorking }

    /// Un échec de connexion par jeton n'impose une nouvelle connexion que si Pronote refuse explicitement le jeton
    /// ou demande une vérification ; sinon (réseau, serveur, appareil verrouillé) la connexion est conservée.
    nonisolated static func classifyResumeFailure(_ error: Error) -> ResumeFailure {
        if let failure = error as? PronoteFailure, failure.code == "login" || failure.code == "mfa" { return .signInRequired }
        return .unavailable
    }

    /// Synchronisation au retour au premier plan si la dernière date de six heures ou plus.
    nonisolated static func shouldSyncOnForeground(lastSync: Date?, now: Date) -> Bool {
        guard let lastSync else { return true }
        return now.timeIntervalSince(lastSync) >= foregroundSyncInterval
    }

    // MARK: Premier plan

    /// Lancement : reconnexion différée, sinon reprise par jeton, une seule fois.
    func resumeAtLaunch() {
        guard !resumed else { return }
        resumed = true
        if vault.needsSilentRelogin && vault.autoReloginEnabled {
            handleRefusedToken()
        } else if hasSavedConnection {
            resumeSavedConnection()
        } else {
            access = .signedOut
        }
    }

    /// Retour au premier plan : reconnexion différée (jeton refusé en arrière-plan) ou synchronisation après 6 h.
    func foregroundActivated(now: Date = Date()) {
        guard resumed, !isWorking, !tokenLoginInFlight, !webActive else { return }
        if vault.needsSilentRelogin && vault.autoReloginEnabled {
            handleRefusedToken()
        } else if access == .connected, Self.shouldSyncOnForeground(lastSync: app?.lastPronoteSync, now: now) {
            synchronize()
        }
    }

    /// Reprise par jeton (lancement, bouton Réessayer du mode hors ligne) ; sans jeton, reconnexion avec les identifiants enregistrés.
    func resumeSavedConnection() {
        do {
            guard let saved = try vault.loadCredentials() else {
                // Sans jeton valide : nouvelle reconnexion silencieuse demandée par l'utilisateur (bouton Réessayer).
                hasSavedConnection = false
                handleRefusedToken(userInitiated: true)
                return
            }
            connect(saved, mode: .token)
        } catch {
            fail(error)
            if access == .resuming { access = .offline }
        }
    }

    #if DEBUG
    /// Vérification visuelle sur simulateur (argument de lancement `-JMethodOfflinePreview`, builds Debug seulement) :
    /// ouvre l'app hors ligne sur les données locales, sans connexion ni requête Pronote.
    func enterOfflinePreview() {
        resumed = true
        access = .offline
    }
    #endif

    /// Ouvre l'app sur les données locales sans attendre la fin de la reprise, qui continue en arrière-plan
    /// (elle n'est pas annulée, pour ne pas perdre un jeton déjà renouvelé par Pronote).
    func openWithoutWaiting() {
        if access == .resuming { access = .offline }
    }

    /// Formulaire de connexion : la page officielle reste masquée et reçoit les identifiants une seule fois.
    /// Avec « Rester connecté », établissement, identifiant et mot de passe sont enregistrés après la réussite.
    func signIn(school input: String, account type: PronoteAccountType, username: String, password: String, remember: Bool) {
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
            let remembered = PronoteVault.School(input: input.trimmingCharacters(in: .whitespacesAndNewlines), account: resolved.account)
            school = remembered
            vault.saveSchool(remembered)
            rememberLogin = remember
            pendingRemember = remember ? (remembered, .init(username: user, password: password)) : nil
            silentRelogin = false
            webActive = true
            webVisible = false
            page.start(url: url, username: user, password: password)
        } catch {
            fail(error)
        }
    }

    func synchronize() {
        guard !isWorking, !tokenLoginInFlight else { return }
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
        let epoch = self.epoch
        task = Task { [weak self] in
            do {
                let lessons: [PronoteLesson]
                do {
                    lessons = try await client.timetable(days: PronoteClient.maximumDays, childId: child, now: Date())
                } catch let failure as PronoteFailure where failure.isExpiredSession {
                    // Session expirée : une seule reconnexion par jeton, puis nouvelle lecture.
                    guard let self, let saved = try self.vault.loadCredentials() else { throw failure }
                    let fresh = try self.makeClient(saved)
                    _ = try await fresh.login(mode: .token, persist: self.tokenPersister(epoch: epoch))
                    self.client = fresh
                    lessons = try await fresh.timetable(days: PronoteClient.maximumDays, childId: child, now: Date())
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
        vault.saveSelectedChild(id)
        synchronize()
    }

    /// Annule le parcours de la page officielle (bouton Annuler) ; une connexion par jeton en vol n'est pas interrompue.
    func cancel() {
        generation = UUID()
        if !tokenLoginInFlight {
            task?.cancel()
            task = nil
        }
        page.close()
        pendingRemember = nil
        let wasSilent = silentRelogin
        silentRelogin = false
        webActive = false
        webVisible = false
        webMessage = nil
        if phase.isWorking && !tokenLoginInFlight { phase = account == nil ? .idle : .ready }
        if wasSilent { silentFailedWithoutRejection() }
    }

    func dismissError() {
        errorMessage = nil
        if phase == .failed { phase = account == nil ? .idle : .ready }
    }

    /// Déconnexion : jeton, enfant suivi et reconnexion différée retirés du trousseau, données WebKit et créneaux Pronote
    /// effacés. Les cours et révisions restent. Établissement, type de compte, identifiant et mot de passe restent pour
    /// préremplir le formulaire, sauf avec `forgetLogin`.
    func disconnect(forgetLogin: Bool) async {
        epoch += 1
        silentRelogin = false
        cancel()
        task?.cancel()
        task = nil
        vault.removeConnection(forgetLogin: forgetLogin)
        BackgroundRefresh.cancel()
        await clearWebData()
        app?.clearPronoteData()
        client = nil
        account = nil
        selectedChildId = nil
        hasSavedConnection = false
        silentAttemptUsed = false
        school = vault.loadSchool()
        savedLogin = vault.loadLogin()
        errorMessage = nil
        phase = .idle
        access = .signedOut
    }

    // MARK: Arrière-plan

    /// Actualisation en arrière-plan : connexion par jeton en HTTPS natif uniquement (aucune page web),
    /// jeton renouvelé enregistré immédiatement, synchronisation des 14 jours. Jeton refusé : rien d'autre n'est tenté,
    /// la reconnexion silencieuse est marquée pour le prochain premier plan.
    func backgroundRefresh(now: Date = Date()) async -> BackgroundResult {
        guard !isWorking, !tokenLoginInFlight, !webActive else { return .busy }
        guard let saved = try? vault.loadCredentials() else { return .noConnection }
        await app?.ensureLoaded()
        guard app?.loadState == .ready else { return .failed }
        tokenLoginInFlight = true
        defer { tokenLoginInFlight = false }
        let epoch = self.epoch
        do {
            let client = try makeClient(saved)
            let account = try await client.login(mode: .token, persist: tokenPersister(epoch: epoch))
            guard epoch == self.epoch else { return .failed }
            try vault.saveCredentials(await client.credentialSnapshot())
            adopt(client: client, account: account)
            let lessons = try await client.timetable(days: PronoteClient.maximumDays, childId: selectedChildId, now: now)
            guard epoch == self.epoch else { return .failed }
            app?.applyPronote(lessons: lessons, syncedAt: now, announce: false)
            errorMessage = nil
            phase = .ready
            access = .connected
            return .synced
        } catch {
            guard epoch == self.epoch else { return .failed }
            if Self.classifyResumeFailure(error) == .signInRequired {
                vault.setNeedsSilentRelogin(true)
                return .tokenRefused
            }
            return .failed
        }
    }

    // MARK: Flux

    private func handle(_ event: PronoteWebFlow.Event) {
        switch event {
        case .phase(let next):
            phase = next
        case .reveal(let message):
            webMessage = message
            webVisible = true
            page.setPageVisible(true)
        case .conceal:
            webVisible = false
            webMessage = nil
            page.setPageVisible(false)
        case .rejected(let message):
            closePage()
            if silentRelogin {
                // Mot de passe enregistré refusé : il est effacé, l'écran de connexion reprend la main.
                silentRelogin = false
                vault.removePassword()
                signOut(message: "Ton identifiant ou ton mot de passe Pronote a changé. Reconnecte-toi.")
            } else {
                // Mot de passe saisi refusé : celui éventuellement enregistré est aussi effacé.
                pendingRemember = nil
                vault.removePassword()
                savedLogin = vault.loadLogin()
                errorMessage = message
                phase = .failed
            }
        case .failed(let message):
            closePage()
            pendingRemember = nil
            if silentRelogin {
                silentRelogin = false
                errorMessage = message
                silentFailedWithoutRejection()
            } else {
                errorMessage = message
                phase = .failed
            }
        case .qr(let qr, let pin):
            do {
                connect(try PronoteClient.credentials(qr: qr, pin: pin, uuid: stableUUID()), mode: .qr)
            } catch {
                connectionFailed(error, mode: .qr)
            }
        }
    }

    /// Jeton refusé (ou absent) : reconnexion silencieuse avec les identifiants enregistrés, une seule fois
    /// automatiquement par jeton refusé ; le bouton Réessayer en relance une. Sans mot de passe : écran de connexion.
    private func handleRefusedToken(userInitiated: Bool = false) {
        vault.removeCredentials()
        hasSavedConnection = false
        client = nil
        guard vault.autoReloginEnabled, let login = vault.loadLogin(), let password = login.password, let school = vault.loadSchool() else {
            signOut(message: "Ta session Pronote a expiré. Reconnecte-toi.")
            return
        }
        // Marque conservée jusqu'à la réussite : le prochain lancement retentera une fois.
        vault.setNeedsSilentRelogin(true)
        guard userInitiated || !silentAttemptUsed else {
            if access == .resuming { access = .offline }
            return
        }
        silentAttemptUsed = true
        startSilentRelogin(school: school, username: login.username, password: password)
    }

    /// Échec de la reconnexion silencieuse pour une autre raison qu'un mot de passe refusé (réseau, délai, serveur,
    /// annulation) : l'app s'ouvre hors ligne sur les données locales, avec le bandeau et « Réessayer ».
    private func silentFailedWithoutRejection() {
        silentRelogin = false
        phase = .failed
        access = .offline
    }

    /// Parcours masqué (page officielle + identifiants + QR) sans écran de connexion :
    /// écran de chargement au lancement, bandeau discret dans l'app. ENT et vérification affichent la page.
    private func startSilentRelogin(school: PronoteVault.School, username: String, password: String) {
        let resolved = PronoteURL.resolve(school.input, account: school.account)
        guard let url = try? PronoteURL.validated(resolved.address) else {
            return signOut(message: "Reconnecte-toi à Pronote.")
        }
        silentRelogin = true
        rememberLogin = true
        pendingRemember = (school, .init(username: username, password: password))
        errorMessage = nil
        webActive = true
        webVisible = false
        if access == .signedOut { access = .resuming }
        page.start(url: url, username: username, password: password)
    }

    /// Conserve l'UUID d'application déjà associé au même compte lors d'une reconnexion.
    private func stableUUID() -> String {
        (try? vault.loadCredentials())?.uuid ?? UUID().uuidString
    }

    private func connect(_ credentials: PronoteCredentials, mode: PronoteClient.LoginMode) {
        if mode == .token && tokenLoginInFlight { return }
        task?.cancel()
        let attempt = UUID()
        generation = attempt
        errorMessage = nil
        phase = .nativeAuthentication
        let epoch = self.epoch
        if mode == .token { tokenLoginInFlight = true }
        task = Task { [weak self] in
            guard let self else { return }
            defer { if mode == .token { self.tokenLoginInFlight = false } }
            do {
                let client = try self.makeClient(credentials)
                let persist = mode == .token ? self.tokenPersister(epoch: epoch) : self.newLoginPersister(attempt: attempt, epoch: epoch)
                let account = try await client.login(mode: mode, persist: persist)
                guard epoch == self.epoch else { return }
                if mode == .qr { try Task.checkCancellation(); guard self.generation == attempt else { return } }
                try self.vault.saveCredentials(await client.credentialSnapshot())
                self.adopt(client: client, account: account)
                self.phase = .synchronizing
                let lessons = try await client.timetable(days: PronoteClient.maximumDays, childId: self.selectedChildId, now: Date())
                guard epoch == self.epoch else { return }
                self.finish(lessons: lessons)
            } catch {
                guard epoch == self.epoch, !(error is CancellationError) || mode == .token else { return }
                if mode == .qr, self.generation != attempt { return }
                self.connectionFailed(error, mode: mode)
            }
        }
    }

    /// Connexion réussie : nouveau jeton enregistré, identifiants mémorisés selon « Rester connecté », enfant suivi.
    private func adopt(client: any PronoteSessionClient, account: PronoteAccount) {
        hasSavedConnection = true
        silentAttemptUsed = false
        vault.setNeedsSilentRelogin(false)
        vault.setAutoReloginEnabled(true)
        if let remember = pendingRemember {
            vault.saveSchool(remember.school)
            vault.saveLogin(remember.login)
            savedLogin = remember.login
        } else if !rememberLogin {
            // « Rester connecté » désactivé : le mot de passe enregistré est toujours effacé.
            vault.removePassword()
            savedLogin = vault.loadLogin()
        }
        pendingRemember = nil
        silentRelogin = false
        self.client = client
        self.account = account
        if account.children.isEmpty {
            selectedChildId = nil
        } else if !account.children.contains(where: { $0.id == selectedChildId }) {
            selectedChildId = account.children.first?.id
            vault.saveSelectedChild(selectedChildId)
        }
    }

    /// Jeton renouvelé d'une connexion par jeton : toujours enregistré (l'ancien est déjà invalide), même si la tâche
    /// est annulée, sauf après une déconnexion.
    private func tokenPersister(epoch: Int) -> @Sendable (PronoteCredentials) async throws -> Void {
        let vault = self.vault
        return { [weak self] credentials in
            guard let self, await self.currentEpoch() == epoch else { throw CancellationError() }
            try vault.saveCredentials(credentials)
        }
    }

    private func currentEpoch() -> Int { epoch }

    /// Jeton d'une nouvelle connexion (QR) : enregistré seulement pour la tentative courante et non annulée.
    private func newLoginPersister(attempt: UUID, epoch: Int) -> @Sendable (PronoteCredentials) async throws -> Void {
        return { [weak self] credentials in
            guard let self else { throw CancellationError() }
            try await self.storeNewLoginToken(credentials, attempt: attempt, epoch: epoch)
        }
    }

    private func storeNewLoginToken(_ credentials: PronoteCredentials, attempt: UUID, epoch: Int) throws {
        guard generation == attempt, self.epoch == epoch, !Task.isCancelled else { throw CancellationError() }
        try vault.saveCredentials(credentials)
        hasSavedConnection = true
    }

    private func finish(lessons: [PronoteLesson]) {
        app?.applyPronote(lessons: lessons, syncedAt: Date())
        closePage()
        errorMessage = nil
        phase = .ready
        access = .connected
    }

    private func connectionFailed(_ error: Error, mode: PronoteClient.LoginMode) {
        let wasSilent = silentRelogin
        silentRelogin = false
        pendingRemember = nil
        fail(error)
        if mode == .token, Self.classifyResumeFailure(error) == .signInRequired {
            handleRefusedToken()
            return
        }
        if wasSilent {
            // Reconnexion silencieuse sans succès (hors mot de passe refusé) : hors ligne, pas de nouvel essai automatique.
            silentFailedWithoutRejection()
            return
        }
        if account != nil {
            // Connexion établie mais lecture impossible : l'app reste ouverte, l'erreur est visible dans Réglages.
            access = .connected
            return
        }
        if mode == .token, access == .resuming { access = .offline }
    }

    /// Écran de connexion, prérempli avec l'établissement et les identifiants encore enregistrés.
    private func signOut(message: String?) {
        vault.removeCredentials()
        vault.setNeedsSilentRelogin(false)
        hasSavedConnection = false
        client = nil
        account = nil
        school = vault.loadSchool()
        savedLogin = vault.loadLogin()
        errorMessage = message
        phase = message == nil ? .idle : .failed
        access = .signedOut
    }

    private func closePage() {
        page.close()
        webActive = false
        webVisible = false
        webMessage = nil
    }

    private func fail(_ failure: Error) {
        if failure is CancellationError { return }
        errorMessage = (failure as? PronoteFailure)?.message
            ?? ((failure as? URLError).map { _ in "Pronote est injoignable. Vérifie ta connexion internet." })
            ?? "La connexion n’a pas abouti. Réessaie."
        phase = .failed
        closePage()
    }
}
