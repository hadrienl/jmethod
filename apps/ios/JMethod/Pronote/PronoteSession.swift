import Foundation
import Observation

/// Connexion Pronote de l'app : formulaire natif ou page officielle, QR automatique,
/// reprise par jeton au lancement, synchronisation des 14 prochains jours, choix de l'enfant, déconnexion.
@Observable
@MainActor
final class PronoteSession {
    enum Phase: Equatable, Sendable {
        case idle, opening, login, signingIn, authenticated, qr, nativeAuthentication, synchronizing, ready, failed

        var label: String {
            switch self {
            case .idle: "Pronote n’est pas connecté"
            case .opening: "Ouverture de Pronote"
            case .login: "Connecte-toi dans la page officielle"
            case .signingIn: "Connexion à Pronote en cours"
            case .authenticated: "Session Pronote reconnue"
            case .qr: "Préparation de la connexion de l’iPhone"
            case .nativeAuthentication: "Connexion directe depuis l’iPhone"
            case .synchronizing: "Lecture de l’emploi du temps"
            case .ready: "Pronote est connecté"
            case .failed: "Une étape demande ton intervention"
            }
        }

        var isWorking: Bool {
            switch self {
            case .opening, .signingIn, .authenticated, .qr, .nativeAuthentication, .synchronizing: true
            case .idle, .login, .ready, .failed: false
            }
        }
    }

    private(set) var phase: Phase = .idle
    private(set) var errorMessage: String?
    private(set) var account: PronoteAccount?
    private(set) var selectedChildId: String?
    private(set) var hasSavedConnection = false
    /// Adresse de l'espace web associée au jeton enregistré, pour pré-remplir le formulaire.
    private(set) var savedAddress = ""
    /// Page officielle en cours d'utilisation (masquée ou visible).
    private(set) var webActive = false
    /// Page officielle affichée à l'écran.
    var webVisible = false {
        didSet { web.setPageVisible(webVisible) }
    }

    @ObservationIgnored let web = PronoteWebFlow()
    @ObservationIgnored private weak var app: AppModel?
    @ObservationIgnored private var client: PronoteClient?
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()
    @ObservationIgnored private var resumed = false
    @ObservationIgnored private var publicDemo = false

    init(app: AppModel) {
        self.app = app
        if let saved = try? PronoteKeychain.loadCredentials() {
            hasSavedConnection = true
            savedAddress = PronoteURL.webPage(fromMobile: saved.url)
        }
        selectedChildId = PronoteKeychain.loadSelectedChild()
        web.onEvent = { [weak self] event in self?.handle(event) }
    }

    var isWorking: Bool { phase.isWorking }
    var isConnected: Bool { account != nil }

    // MARK: Entrées utilisateur

    /// Reprise automatique par jeton, une seule fois par lancement.
    func resumeAtLaunch() {
        guard !resumed else { return }
        resumed = true
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--pronote-public-demo") {
            connectPublicDemo()
            return
        }
        #endif
        guard hasSavedConnection else { return }
        resumeSavedConnection()
    }

    func resumeSavedConnection() {
        do {
            guard let saved = try PronoteKeychain.loadCredentials() else {
                hasSavedConnection = false
                return
            }
            publicDemo = false
            connect(saved, mode: .token)
        } catch {
            fail(error)
        }
    }

    /// Formulaire natif : la page officielle reste masquée et reçoit les identifiants une seule fois.
    func signIn(address: String, username: String, password: String) {
        let user = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !user.isEmpty, !password.isEmpty else {
            errorMessage = "Indique ton identifiant et ton mot de passe Pronote."
            phase = .failed
            return
        }
        open(address: address, username: user, password: password)
    }

    /// Connexion manuelle dans la page officielle visible (ENT, formulaire inhabituel).
    func openOfficialPage(address: String) {
        open(address: address, username: nil, password: nil)
    }

    /// Après une vérification terminée dans la page, relance la génération automatique du QR code.
    func continueOnVisiblePage() {
        errorMessage = nil
        web.resumeOnVisiblePage()
    }

    func importQR(image: Data, pin: String) {
        do {
            let qr = try PronoteWebFlow.decode(image: image)
            publicDemo = false
            connect(try PronoteClient.credentials(qr: qr, pin: pin, uuid: stableUUID()), mode: .qr)
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
                self.fail(error)
            }
        }
    }

    func selectChild(_ id: String) {
        guard id != selectedChildId, account?.children.contains(where: { $0.id == id }) == true else { return }
        selectedChildId = id
        if !publicDemo { PronoteKeychain.saveSelectedChild(id) }
        synchronize()
    }

    /// Annule la tentative en cours ; rien n'est enregistré après l'annulation.
    func cancel() {
        generation = UUID()
        task?.cancel()
        task = nil
        web.close()
        webActive = false
        webVisible = false
        if phase.isWorking || phase == .login { phase = account == nil ? .idle : .ready }
    }

    func dismissError() {
        errorMessage = nil
        if phase == .failed { phase = account == nil ? .idle : .ready }
    }

    /// Oublie le compte : jeton et choix d'enfant retirés du trousseau, données WebKit et créneaux Pronote effacés.
    func disconnect() async {
        cancel()
        PronoteKeychain.removeAll()
        await PronoteWebFlow.clearWebsiteData()
        app?.clearPronoteData()
        client = nil
        account = nil
        selectedChildId = nil
        hasSavedConnection = false
        savedAddress = ""
        errorMessage = nil
        publicDemo = false
        phase = .idle
    }

    // MARK: Flux

    private func open(address: String, username: String?, password: String?) {
        cancel()
        errorMessage = nil
        phase = .opening
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            fail(PronoteFailure.invalid("url", "Indique l’adresse de ton espace Pronote, par exemple xxxx.index-education.net."))
            return
        }
        do {
            let normalized = PronoteURL.normalized(trimmed)
            let url = try PronoteURL.validated(normalized)
            guard PronoteURL.trustedPages.contains(url.lastPathComponent.lowercased()) else {
                throw PronoteFailure.invalid("url", "Utilise l’adresse de l’espace Élèves ou Parents, sans « mobile ».")
            }
            savedAddress = normalized
            publicDemo = false
            webActive = true
            webVisible = username == nil
            web.start(url: url, username: username, password: password)
        } catch {
            fail(error)
        }
    }

    private func handle(_ event: PronoteWebFlow.Event) {
        switch event {
        case .phase(let next):
            phase = next
            if next == .authenticated { webVisible = false }
        case .needsUser(let message):
            errorMessage = message
            phase = .failed
            webVisible = true
        case .rejected(let message):
            web.close()
            webActive = false
            webVisible = false
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
                if !self.publicDemo {
                    let snapshot = await client.credentialSnapshot()
                    try Task.checkCancellation()
                    guard self.generation == attempt else { return }
                    try PronoteKeychain.saveCredentials(snapshot)
                    self.hasSavedConnection = true
                    self.savedAddress = PronoteURL.webPage(fromMobile: snapshot.url)
                }
                self.client = client
                self.account = account
                if account.children.isEmpty {
                    self.selectedChildId = nil
                } else if !account.children.contains(where: { $0.id == self.selectedChildId }) {
                    self.selectedChildId = account.children.first?.id
                    if !self.publicDemo { PronoteKeychain.saveSelectedChild(self.selectedChildId) }
                }
                self.phase = .synchronizing
                let lessons = try await client.timetable(childId: self.selectedChildId)
                try Task.checkCancellation()
                guard self.generation == attempt else { return }
                self.finish(lessons: lessons)
            } catch {
                guard let self, self.generation == attempt, !Task.isCancelled else { return }
                self.fail(error)
            }
        }
    }

    /// Enregistrement immédiat du jeton rotatif, seulement pour la tentative courante et non annulée.
    private func persister(for attempt: UUID) -> @Sendable (PronoteCredentials) async throws -> Void {
        let save = !publicDemo
        return { [weak self] credentials in
            guard save else { return }
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
        errorMessage = nil
        phase = .ready
    }

    private func fail(_ failure: Error) {
        if failure is CancellationError { return }
        errorMessage = (failure as? PronoteFailure)?.message
            ?? ((failure as? URLError).map { _ in "Pronote est injoignable. Vérifie la connexion réseau puis réessaie." })
            ?? "La connexion n’a pas abouti. Réessaie ou passe par la page officielle."
        phase = .failed
        if webActive { webVisible = true }
    }

    #if DEBUG
    /// Démonstration publique Index Education par le protocole natif seul (lancement avec `--pronote-public-demo`).
    /// Rien n'est enregistré dans le trousseau.
    private func connectPublicDemo() {
        publicDemo = true
        connect(.init(url: "https://demo.index-education.net/pronote/mobile.eleve.html?fd=1", username: "demonstration",
                      password: "pronotevs", uuid: UUID().uuidString, clientIdentifier: nil), mode: .publicDemo)
    }
    #endif
}
