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
        /// Erreur 25 récente : aucun essai pendant au moins une heure.
        case blocked
        case failed
    }

    nonisolated static let foregroundSyncInterval: TimeInterval = 6 * 60 * 60
    /// Session inactive depuis plus de 10 minutes : reconnexion par jeton avant toute lecture (comme pronotepy).
    nonisolated static let idleReloginInterval: TimeInterval = 10 * 60

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
    /// Change à chaque déconnexion, changement d'enfant ou nouvelle connexion : l'aperçu du cahier de textes
    /// et ses pièces jointes téléchargées sont alors effacés.
    private(set) var notebookScope = 0

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
    #if DEBUG
    /// Aperçu Debug (`-JMethodOfflinePreview`, `-JMethodPreviewFixture`, `-JMethodPreviewSignIn`) : aucune connexion automatique.
    @ObservationIgnored private var previewOnly = false
    #endif
    /// Une connexion par jeton est en vol (premier plan ou arrière-plan) : aucune autre n'est lancée en parallèle.
    @ObservationIgnored private var tokenLoginInFlight = false
    /// Reconnexion par jeton en cours (session perdue ou inactive) : partagée par toutes les lectures concurrentes,
    /// pour qu'un même jeton ne serve jamais à deux connexions.
    @ObservationIgnored private var relogin: Task<any PronoteSessionClient, Error>?
    /// Instant du dernier appel Pronote réussi sur la session établie (connexion comprise).
    @ObservationIgnored private var lastPronoteSuccess: Date?
    /// Une seule reconnexion silencieuse par jeton refusé ; remis à zéro quand un nouveau jeton est obtenu.
    @ObservationIgnored private var silentAttemptUsed = false
    /// Identifiants à enregistrer après une connexion réussie (« Rester connecté »), puis effacés de la mémoire.
    @ObservationIgnored private var pendingRemember: (school: PronoteVault.School, login: PronoteVault.SavedLogin)?
    @ObservationIgnored private var rememberLogin = true
    /// Origine de la page officielle de la tentative en cours : un QR d'une autre origine est refusé.
    @ObservationIgnored private var attemptOrigin: String?
    @ObservationIgnored private let throttle: PronoteThrottle
    @ObservationIgnored private let now: () -> Date

    init(app: AppModel, vault: PronoteVault = .live, page: LoginPageDriver? = nil,
         clearWebData: @escaping () async -> Void = { await PronoteWebFlow.clearWebsiteData() },
         throttle: PronoteThrottle = .live, now: @escaping () -> Date = Date.init,
         makeClient: ((PronoteCredentials) throws -> any PronoteSessionClient)? = nil) {
        self.app = app
        self.vault = vault
        self.throttle = throttle
        self.now = now
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
        // Trousseau inaccessible pour l'instant (lancement en arrière-plan avant le premier déverrouillage) :
        // la connexion est supposée présente ; rien n'est effacé, la reprise relira le jeton.
        let saved = vault.credentialsState ?? true
        hasSavedConnection = saved
        let canRelogin = vault.needsSilentRelogin && vault.autoReloginEnabled && vault.loadLogin()?.password != nil
        access = saved || canRelogin ? .resuming : .signedOut
        school = vault.loadSchool()
        savedLogin = vault.loadLogin()
        selectedChildId = vault.loadSelectedChild()
        self.page.onEvent = { [weak self] event in self?.handle(event) }
    }

    var isWorking: Bool { phase.isWorking }

    /// Erreur 25 récente : aucune connexion automatique ni « Réessayer » pendant au moins une heure.
    var isBlocked: Bool { throttle.isBlocked(now: now()) }

    /// Blocage affiché sans aucun essai : l'app s'ouvre sur les données locales si une connexion existe.
    private func showBlocked() {
        errorMessage = PronoteThrottle.blockedMessage
        phase = .failed
        silentRelogin = false
        if access == .connected { return }
        let canRelogin = vault.autoReloginEnabled && vault.loadLogin()?.password != nil
        access = hasSavedConnection || canRelogin || account != nil ? .offline : .signedOut
    }

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
        if isBlocked { return showBlocked() }
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
        #if DEBUG
        // Aperçus Debug : aucune connexion automatique au retour au premier plan.
        if previewOnly { return }
        #endif
        guard resumed, !isWorking, !tokenLoginInFlight, relogin == nil, !webActive, !throttle.isBlocked(now: now) else { return }
        if vault.needsSilentRelogin && vault.autoReloginEnabled {
            handleRefusedToken()
        } else if access == .connected, Self.shouldSyncOnForeground(lastSync: app?.lastPronoteSync, now: now) {
            synchronize()
        }
    }

    /// Reprise par jeton (lancement, bouton Réessayer du mode hors ligne) ; sans jeton, reconnexion avec les identifiants enregistrés.
    func resumeSavedConnection() {
        if isBlocked { return showBlocked() }
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
        previewOnly = true
        access = .offline
    }

    /// Vérification visuelle de l'écran de connexion (`-JMethodPreviewSignIn`, Debug) : aucune reprise, et rien
    /// d'enregistré n'est prérempli à l'écran (le trousseau n'est ni lu de nouveau ni modifié).
    func enterSignInPreview() {
        resumed = true
        previewOnly = true
        school = nil
        savedLogin = nil
        access = .signedOut
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
            attemptOrigin = PronoteURL.origin(url)
            page.start(url: url, username: user, password: password)
        } catch {
            fail(error)
        }
    }

    func synchronize() {
        guard !isWorking, !tokenLoginInFlight, relogin == nil else { return }
        if isBlocked { return showBlocked() }
        guard let client else {
            if hasSavedConnection { resumeSavedConnection() }
            return
        }
        let attempt = UUID()
        generation = attempt
        task?.cancel()
        errorMessage = nil
        phase = .synchronizing
        _ = client
        let child = selectedChildId
        let epoch = self.epoch
        task = Task { [weak self] in
            do {
                guard let self else { return }
                let lessons = try await self.withSession { try await $0.timetable(days: PronoteClient.maximumDays, childId: child, now: Date()) }
                try Task.checkCancellation()
                guard self.generation == attempt else { return }
                self.finish(lessons: lessons, epoch: epoch)
            } catch {
                guard let self, self.generation == attempt, !Task.isCancelled else { return }
                self.connectionFailed(error, mode: .token)
            }
        }
    }

    // MARK: Session établie

    /// Erreurs qui signifient que la session Pronote est perdue (`protocol-reference.md`, « Session expiry ») :
    /// toute `Erreur` Pronote sauf 25, un statut HTTP non 2xx, une réponse non JSON, un échec de déchiffrement
    /// ou de décompression. Pas de liste fermée de codes « expirés » (le code 8 est apparu après deux heures).
    nonisolated static func isSessionLost(_ error: Error) -> Bool {
        guard let failure = error as? PronoteFailure, failure.code != PronoteThrottle.blockedCode else { return false }
        return failure.code.hasPrefix("pronote-") || failure.code.hasPrefix("http-") || ["json", "crypto", "compression"].contains(failure.code)
    }

    /// Lecture sur la session établie. Session inactive depuis plus de 10 minutes (ou absente) : reconnexion par jeton
    /// avant l'appel. Session perdue pendant l'appel : une seule reconnexion par jeton, puis l'appel est rejoué une fois ;
    /// un second échec remonte à l'utilisateur. Erreur 25 : blocage d'une heure, aucune reconnexion.
    private func withSession<T: Sendable>(_ operation: (any PronoteSessionClient) async throws -> T) async throws -> T {
        if isBlocked { throw PronoteFailure.invalid(PronoteThrottle.blockedCode, PronoteThrottle.blockedMessage) }
        let epoch = self.epoch
        var current: any PronoteSessionClient
        var reconnected = false
        if let client, let last = lastPronoteSuccess, now().timeIntervalSince(last) <= Self.idleReloginInterval, now() >= last {
            current = client
        } else {
            current = try await reloginByToken(epoch: epoch)
            reconnected = true
        }
        do {
            let value = try await operation(current)
            lastPronoteSuccess = now()
            return value
        } catch {
            throttle.note(error, now: now())
            guard !reconnected, Self.isSessionLost(error), !isBlocked, epoch == self.epoch else { throw error }
            current = try await reloginByToken(epoch: epoch)
            let value = try await operation(current)
            lastPronoteSuccess = now()
            return value
        }
    }

    /// Reconnexion par jeton depuis un état propre : nouveau client (cookies, numéro d'ordre, clé et IV neufs),
    /// dernier jeton du trousseau, jeton renouvelé enregistré dès sa réception (même si l'appelant est annulé).
    /// Une reconnexion en cours est partagée.
    private func reloginByToken(epoch: Int) async throws -> any PronoteSessionClient {
        if let relogin { return try await relogin.value }
        let task = Task { @MainActor [weak self] () throws -> any PronoteSessionClient in
            guard let self else { throw CancellationError() }
            if self.isBlocked { throw PronoteFailure.invalid(PronoteThrottle.blockedCode, PronoteThrottle.blockedMessage) }
            guard let saved = try self.vault.loadCredentials() else {
                throw PronoteFailure.invalid("login", "Ta session Pronote a expiré. Reconnecte-toi.")
            }
            let fresh = try self.makeClient(saved)
            do {
                _ = try await fresh.login(mode: .token, persist: self.tokenPersister(epoch: epoch))
            } catch {
                self.throttle.note(error, now: self.now())
                throw error
            }
            guard epoch == self.epoch else { throw CancellationError() }
            try? self.vault.saveCredentials(await fresh.credentialSnapshot())
            self.client = fresh
            self.lastPronoteSuccess = self.now()
            return fresh
        }
        relogin = task
        defer { relogin = nil }
        return try await task.value
    }

    func selectChild(_ id: String) {
        guard id != selectedChildId, account?.children.contains(where: { $0.id == id }) == true else { return }
        selectedChildId = id
        vault.saveSelectedChild(id)
        notebookScope += 1
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
        notebookScope += 1
        await client?.invalidate()
        client = nil
        lastPronoteSuccess = nil
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

    // MARK: Cahier de textes

    /// Lit le cahier de textes (28 jours) sur la session établie (reconnexion par jeton si elle est perdue ou inactive).
    func readNotebook(now: Date = Date()) async throws -> NotebookRead {
        guard client != nil else {
            throw PronoteFailure.invalid("offline", "Pronote n’est pas connecté. Réessaie la connexion, puis rouvre le cahier de textes.")
        }
        let child = selectedChildId
        let today = SchoolDay.today(now: now)
        return try await withSession { try await $0.notebook(today: today, childId: child) }
    }

    /// Contrôlé juste avant le GET (client actif, aucune déconnexion), puis au retour : un résultat arrivé après une
    /// déconnexion est effacé et rien n'est ouvert.
    func downloadAttachment(_ attachment: NotebookAttachment, session: UUID, to destination: URL) async throws {
        let unavailable = PronoteFailure.invalid("download", NotebookRules.downloadUnavailable)
        guard attachment.kind == .file, access == .connected || access == .offline, let original = client else { throw unavailable }
        let epoch = self.epoch
        do {
            try await withSession { current in
                // Après une reconnexion faite ici (session perdue ou inactive), la nouvelle session sert au téléchargement.
                let stamp = current === original ? session : await current.currentSession()
                try await current.download(attachmentId: attachment.id, fileName: attachment.fileName, session: stamp, to: destination)
            }
        } catch {
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
        guard epoch == self.epoch else {
            try? FileManager.default.removeItem(at: destination)
            throw unavailable
        }
    }

    // MARK: Arrière-plan

    /// Actualisation en arrière-plan : connexion par jeton en HTTPS natif uniquement (aucune page web),
    /// jeton renouvelé enregistré immédiatement, synchronisation des 14 jours. Jeton refusé : rien d'autre n'est tenté,
    /// la reconnexion silencieuse est marquée pour le prochain premier plan.
    func backgroundRefresh(now: Date = Date()) async -> BackgroundResult {
        guard !isWorking, !tokenLoginInFlight, relogin == nil, !webActive else { return .busy }
        guard !throttle.isBlocked(now: now) else { return .blocked }
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
            let child = selectedChildId
            let lessons = try await withSession { try await $0.timetable(days: PronoteClient.maximumDays, childId: child, now: now) }
            guard epoch == self.epoch else { return .failed }
            app?.applyPronote(lessons: lessons, syncedAt: now, announce: false)
            silentAttemptUsed = false
            errorMessage = nil
            phase = .ready
            access = .connected
            return .synced
        } catch {
            if throttle.note(error, now: now) { return .blocked }
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
                // UUID d'application stable ; QR refusé s'il ne vient pas de l'origine de la tentative.
                connect(try PronoteClient.credentials(qr: qr, pin: pin, uuid: vault.applicationUUID(), expectedOrigin: attemptOrigin), mode: .qr)
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
        if isBlocked { return showBlocked() }
        // Automatique : une seule tentative tant qu'aucune synchronisation n'a réussi, et au plus une par heure.
        guard userInitiated || (!silentAttemptUsed && throttle.silentAllowed(now: now())) else {
            if access == .resuming || access == .signedOut { access = .offline }
            return
        }
        silentAttemptUsed = true
        throttle.recordSilentAttempt(now: now())
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
        attemptOrigin = PronoteURL.origin(url)
        page.start(url: url, username: username, password: password)
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
                // Jeton : déjà enregistré par le client ; la génération n'est contrôlée qu'à l'adoption du résultat.
                if mode == .token { try self.vault.saveCredentials(await client.credentialSnapshot()) }
                guard self.generation == attempt else { return }
                if mode == .qr {
                    try Task.checkCancellation()
                    try self.vault.saveCredentials(await client.credentialSnapshot())
                }
                self.adopt(client: client, account: account)
                if mode == .qr {
                    // Le jeton suffit désormais : cookies, stockage et cache de la page officielle sont effacés.
                    self.closePage()
                    await self.clearWebData()
                }
                self.phase = .synchronizing
                let child = self.selectedChildId
                let lessons = try await self.withSession { try await $0.timetable(days: PronoteClient.maximumDays, childId: child, now: Date()) }
                guard epoch == self.epoch, self.generation == attempt else { return }
                self.finish(lessons: lessons, epoch: epoch)
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
        lastPronoteSuccess = now()
        notebookScope += 1
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

    /// Synchronisation réussie : créneaux écrits (sauf déconnexion entre-temps) ; une reconnexion silencieuse
    /// automatique redevient possible.
    private func finish(lessons: [PronoteLesson], epoch: Int) {
        guard epoch == self.epoch else { return }
        app?.applyPronote(lessons: lessons, syncedAt: Date())
        silentAttemptUsed = false
        closePage()
        errorMessage = nil
        phase = .ready
        access = .connected
    }

    private func connectionFailed(_ error: Error, mode: PronoteClient.LoginMode) {
        throttle.note(error, now: now())
        if mode == .qr, (error as? PronoteFailure)?.code == "mfa" {
            // Vérification exigée par l'API après l'enregistrement du jeton : ce jeton ne servira pas, il est effacé
            // (sinon chaque lancement recommencerait), et la page officielle est affichée pour la terminer.
            vault.removeCredentials()
            hasSavedConnection = false
            client = nil
            account = nil
            errorMessage = nil
            phase = .signingIn
            webActive = true
            webVisible = true
            webMessage = "Pronote demande une vérification de sécurité : termine-la ci-dessous, la connexion continuera ensuite."
            page.revealForVerification(webMessage ?? "")
            return
        }
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
        notebookScope += 1
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
