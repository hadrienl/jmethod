import XCTest
@testable import JMethod

/// Coffre en mémoire (remplace le trousseau).
final class MemoryStore: SecureItemStore, @unchecked Sendable {
    // Accès protégé par le verrou.
    private let lock = NSLock()
    private var items: [String: Data] = [:]

    func read(_ account: String) throws -> Data? { lock.withLock { items[account] } }
    func write(_ data: Data, account: String) throws { lock.withLock { items[account] = data } }
    func delete(_ account: String) { lock.withLock { items[account] = nil } }
}

/// Page officielle simulée : enregistre les démarrages, les événements sont émis par le test.
@MainActor
final class FakePage: LoginPageDriver {
    var onEvent: ((PronoteWebFlow.Event) -> Void)?
    private(set) var starts: [(url: URL, username: String, password: String)] = []
    private(set) var closes = 0
    func start(url: URL, username: String, password: String) { starts.append((url, username, password)) }
    func close() { closes += 1 }
    func setPageVisible(_ visible: Bool) {}
    func emit(_ event: PronoteWebFlow.Event) { onEvent?(event) }
}

/// Client Pronote simulé.
actor FakeClient: PronoteSessionClient {
    enum Outcome: Sendable {
        case success(rotatedToken: String?)
        case refused
        case offline
        /// Jeton renouvelé reçu puis tâche annulée (expiration de l'arrière-plan).
        case rotatedThenCancelled(String)
    }

    final class Counter: @unchecked Sendable {
        // Utilisé uniquement depuis le MainActor du test et l'acteur du client, jamais en même temps.
        var logins = 0
        var timetables = 0
    }

    private var credentials: PronoteCredentials
    private let tokenOutcome: Outcome
    private let qrOutcome: Outcome
    private let counter: Counter

    init(credentials: PronoteCredentials, token: Outcome, qr: Outcome, counter: Counter) {
        self.credentials = credentials
        self.tokenOutcome = token
        self.qrOutcome = qr
        self.counter = counter
    }

    func login(mode: PronoteClient.LoginMode, persist: (@Sendable (PronoteCredentials) async throws -> Void)?) async throws -> PronoteAccount {
        counter.logins += 1
        switch mode == .token ? tokenOutcome : qrOutcome {
        case .success(let rotated):
            if let rotated {
                credentials.password = rotated
                try await persist?(credentials)
            }
            return PronoteAccount(name: "Élève test", children: [])
        case .refused:
            throw PronoteFailure.invalid("login", "refusé")
        case .offline:
            throw URLError(.notConnectedToInternet)
        case .rotatedThenCancelled(let rotated):
            credentials.password = rotated
            try await persist?(credentials)
            throw CancellationError()
        }
    }

    func timetable(days: Int, childId: String?, now: Date) async throws -> [PronoteLesson] {
        counter.timetables += 1
        return [PronoteLesson(id: "l-2026-11-18T08:00:00-0", subject: "MATHEMATIQUES", start: "2026-11-18T08:00:00", end: "2026-11-18T09:00:00", teacher: "", room: "", cancelled: false)]
    }

    func credentialSnapshot() -> PronoteCredentials { credentials }
}

@MainActor
final class SessionTests: XCTestCase {
    private let token = PronoteCredentials(url: "https://abcd1234.index-education.net/pronote/mobile.eleve.html", username: "u", password: "old-token", uuid: "uuid-test", clientIdentifier: nil)

    private struct Setup {
        let session: PronoteSession
        let vault: PronoteVault
        let page: FakePage
        let app: AppModel
        let counter: FakeClient.Counter
    }

    private func makeSetup(token: FakeClient.Outcome, qr: FakeClient.Outcome = .success(rotatedToken: nil),
                           savedToken: Bool = true, savedPassword: String? = "secret") async -> Setup {
        let vault = PronoteVault(store: MemoryStore())
        if savedToken { try? vault.saveCredentials(self.token) }
        vault.saveSchool(.init(input: "abcd1234", account: .student))
        vault.saveLogin(.init(username: "eleve.test", password: savedPassword))
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-session-\(UUID().uuidString).json")
        addTeardownBlock { try? FileManager.default.removeItem(at: file) }
        let suite = "jmethod-session-\(UUID().uuidString)"
        addTeardownBlock { UserDefaults().removePersistentDomain(forName: suite) }
        let app = AppModel(store: DocumentStore(url: file), preferences: UserDefaults(suiteName: suite)!)
        await app.load()
        let page = FakePage()
        let counter = FakeClient.Counter()
        let session = PronoteSession(app: app, vault: vault, page: page, clearWebData: {}) { credentials in
            FakeClient(credentials: credentials, token: token, qr: qr, counter: counter)
        }
        return Setup(session: session, vault: vault, page: page, app: app, counter: counter)
    }

    /// Attend qu'une condition devienne vraie (tâches de la session sur le MainActor).
    private func until(_ condition: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<200 {
            if condition() { return }
            try? await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("condition non atteinte", file: file, line: line)
    }

    private func vectorQR() throws -> (PronoteQRData, String) {
        let qr = try XCTUnwrap(try SharedVectors.object("protocol-vectors.json")["qr"] as? [String: Any])
        return (try decodeJSON(PronoteQRData.self, qr["qrData"]), try XCTUnwrap(qr["pin"] as? String))
    }

    // MARK: Coffre

    func testVaultStoresAndRemovesLogin() throws {
        let vault = PronoteVault(store: MemoryStore())
        try vault.saveCredentials(token)
        vault.saveSchool(.init(input: "abcd1234", account: .parent))
        vault.saveLogin(.init(username: "eleve.test", password: "secret"))
        vault.saveSelectedChild("child-1")
        vault.setNeedsSilentRelogin(true)
        XCTAssertEqual(vault.loadLogin(), .init(username: "eleve.test", password: "secret"))
        XCTAssertTrue(vault.needsSilentRelogin)

        vault.removePassword()
        XCTAssertEqual(vault.loadLogin(), .init(username: "eleve.test", password: nil), "l'identifiant reste")
        XCTAssertEqual(vault.loadSchool(), .init(input: "abcd1234", account: .parent))

        vault.removeConnection(forgetLogin: false)
        XCTAssertNil(try vault.loadCredentials())
        XCTAssertNil(vault.loadSelectedChild())
        XCTAssertFalse(vault.needsSilentRelogin)
        XCTAssertEqual(vault.loadLogin(), .init(username: "eleve.test", password: nil))
        XCTAssertEqual(vault.loadSchool()?.input, "abcd1234", "l'établissement reste pour préremplir")
        XCTAssertFalse(vault.autoReloginEnabled, "une déconnexion volontaire coupe la reconnexion automatique")
        vault.removeConnection(forgetLogin: true)
        XCTAssertNil(vault.loadLogin())
        XCTAssertEqual(vault.loadSchool(), .init(input: "abcd1234", account: .parent), "établissement et type de compte restent")
    }

    // MARK: Reconnexion silencieuse

    func testRefusedTokenStartsExactlyOneSilentRelogin() async throws {
        let setup = await makeSetup(token: .refused)
        XCTAssertEqual(setup.session.access, .resuming)
        setup.session.resumeAtLaunch()
        await until { setup.page.starts.count == 1 }
        let start = try XCTUnwrap(setup.page.starts.first)
        XCTAssertEqual(start.url.absoluteString, "https://abcd1234.index-education.net/pronote/eleve.html")
        XCTAssertEqual(start.username, "eleve.test")
        XCTAssertEqual(start.password, "secret")
        XCTAssertTrue(setup.session.silentRelogin)
        XCTAssertEqual(setup.session.access, .resuming, "pas d'écran de connexion pendant la reconnexion silencieuse")
        XCTAssertNil(try setup.vault.loadCredentials(), "le jeton refusé est retiré")

        // Échec du parcours (délai, réseau) : app hors ligne, mot de passe conservé, aucun nouvel essai automatique.
        setup.page.emit(.failed("La connexion n’a pas abouti. Réessaie."))
        XCTAssertEqual(setup.session.access, .offline)
        XCTAssertFalse(setup.session.silentRelogin)
        XCTAssertEqual(setup.vault.loadLogin()?.password, "secret")
        XCTAssertTrue(setup.vault.needsSilentRelogin, "le prochain lancement retentera une fois")
        setup.session.foregroundActivated()
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(setup.page.starts.count, 1, "une seule tentative automatique par jeton refusé")
        XCTAssertEqual(setup.session.access, .offline)

        // Bouton Réessayer : nouvelle tentative demandée par l'utilisateur.
        setup.session.resumeSavedConnection()
        XCTAssertEqual(setup.page.starts.count, 2)
        XCTAssertTrue(setup.session.silentRelogin)
    }

    func testSilentReloginNetworkFailureAfterQROpensOffline() async throws {
        let (qr, pin) = try vectorQR()
        let setup = await makeSetup(token: .refused, qr: .offline)
        setup.session.resumeAtLaunch()
        await until { setup.page.starts.count == 1 }
        setup.page.emit(.qr(qr, pin: pin))
        await until { setup.session.access == .offline }
        XCTAssertEqual(setup.vault.loadLogin()?.password, "secret")
    }

    func testLoginRejectedClearsStoredPasswordOnly() async throws {
        let setup = await makeSetup(token: .refused)
        setup.session.resumeAtLaunch()
        await until { setup.page.starts.count == 1 }
        setup.page.emit(.rejected("Identifiant ou mot de passe incorrect."))
        XCTAssertEqual(setup.session.access, .signedOut)
        XCTAssertNotNil(setup.session.errorMessage)
        XCTAssertEqual(setup.vault.loadLogin(), .init(username: "eleve.test", password: nil))
        XCTAssertEqual(setup.vault.loadSchool()?.input, "abcd1234")
        XCTAssertEqual(setup.session.savedLogin?.password, nil, "le formulaire n'est plus prérempli avec l'ancien mot de passe")
    }

    func testSilentReloginSucceedsThroughQRAndKeepsLogin() async throws {
        let (qr, pin) = try vectorQR()
        let setup = await makeSetup(token: .refused, qr: .success(rotatedToken: "qr-token"))
        setup.session.resumeAtLaunch()
        await until { setup.page.starts.count == 1 }
        setup.page.emit(.qr(qr, pin: pin))
        await until { setup.session.access == .connected }
        XCTAssertEqual(try setup.vault.loadCredentials()?.password, "qr-token")
        XCTAssertEqual(setup.vault.loadLogin()?.password, "secret")
        XCTAssertFalse(setup.session.silentRelogin)
        XCTAssertEqual(setup.app.document.pronoteEvents.count, 1)
    }

    func testNetworkFailureKeepsConnectionOffline() async throws {
        let setup = await makeSetup(token: .offline)
        setup.session.resumeAtLaunch()
        await until { setup.session.access == .offline }
        XCTAssertEqual(try setup.vault.loadCredentials(), token, "le jeton n'est pas supprimé hors ligne")
        XCTAssertTrue(setup.page.starts.isEmpty)
    }

    // MARK: Rester connecté

    func testStayConnectedChoiceControlsStoredLogin() async throws {
        let (qr, pin) = try vectorQR()
        let on = await makeSetup(token: .success(rotatedToken: nil), savedToken: false, savedPassword: nil)
        on.session.signIn(school: "abcd1234", account: .parent, username: "parent.test", password: "motdepasse", remember: true)
        XCTAssertEqual(on.page.starts.first?.url.absoluteString, "https://abcd1234.index-education.net/pronote/parent.html")
        on.page.emit(.qr(qr, pin: pin))
        await until { on.session.access == .connected }
        XCTAssertEqual(on.vault.loadLogin(), .init(username: "parent.test", password: "motdepasse"))
        XCTAssertEqual(on.vault.loadSchool(), .init(input: "abcd1234", account: .parent))

        let off = await makeSetup(token: .success(rotatedToken: nil), savedToken: false, savedPassword: "ancien")
        off.session.signIn(school: "abcd1234", account: .student, username: "eleve.test", password: "motdepasse", remember: false)
        off.page.emit(.qr(qr, pin: pin))
        await until { off.session.access == .connected }
        XCTAssertEqual(off.vault.loadLogin(), .init(username: "eleve.test", password: nil), "désactivé : le mot de passe enregistré est effacé")
        XCTAssertNotNil(try off.vault.loadCredentials())
    }

    func testDisconnectKeepsLoginUnlessForgotten() async throws {
        let keep = await makeSetup(token: .success(rotatedToken: "t2"))
        keep.vault.saveSelectedChild("child-1")
        keep.session.resumeAtLaunch()
        await until { keep.session.access == .connected }
        await keep.session.disconnect(forgetLogin: false)
        XCTAssertEqual(keep.session.access, .signedOut)
        XCTAssertNil(try keep.vault.loadCredentials())
        XCTAssertNil(keep.vault.loadSelectedChild())
        XCTAssertFalse(keep.vault.needsSilentRelogin)
        XCTAssertEqual(keep.app.document.pronoteEvents, [])
        XCTAssertEqual(keep.vault.loadLogin(), .init(username: "eleve.test", password: "secret"))
        XCTAssertEqual(keep.vault.loadSchool(), .init(input: "abcd1234", account: .student))
        XCTAssertEqual(keep.session.savedLogin?.password, "secret", "le formulaire est prérempli")

        let forget = await makeSetup(token: .success(rotatedToken: "t2"))
        forget.session.resumeAtLaunch()
        await until { forget.session.access == .connected }
        await forget.session.disconnect(forgetLogin: true)
        XCTAssertNil(try forget.vault.loadCredentials())
        XCTAssertNil(forget.vault.loadLogin())
        XCTAssertEqual(forget.vault.loadSchool(), .init(input: "abcd1234", account: .student))
        XCTAssertNil(forget.session.savedLogin)
    }

    func testVoluntaryDisconnectDisablesAutomaticRelogin() async throws {
        let (qr, pin) = try vectorQR()
        let setup = await makeSetup(token: .refused, qr: .success(rotatedToken: "t3"))
        await setup.session.disconnect(forgetLogin: false)
        // Lancement suivant : formulaire prérempli, aucune reconnexion silencieuse.
        setup.vault.setNeedsSilentRelogin(true)
        let next = PronoteSession(app: setup.app, vault: setup.vault, page: setup.page, clearWebData: {}) { credentials in
            FakeClient(credentials: credentials, token: .refused, qr: .success(rotatedToken: "t3"), counter: setup.counter)
        }
        XCTAssertEqual(next.access, .signedOut)
        next.resumeAtLaunch()
        XCTAssertEqual(next.access, .signedOut)
        XCTAssertTrue(setup.page.starts.isEmpty)
        XCTAssertEqual(next.savedLogin?.password, "secret")

        // La connexion réussie suivante réactive la reconnexion automatique.
        next.signIn(school: "abcd1234", account: .student, username: "eleve.test", password: "secret", remember: true)
        setup.page.emit(.qr(qr, pin: pin))
        await until { next.access == .connected }
        XCTAssertTrue(setup.vault.autoReloginEnabled)
    }

    func testTypedPasswordRejectedAlsoClearsStoredPassword() async throws {
        let setup = await makeSetup(token: .success(rotatedToken: nil), savedToken: false)
        setup.session.signIn(school: "abcd1234", account: .student, username: "eleve.test", password: "autre", remember: true)
        setup.page.emit(.rejected("Identifiant ou mot de passe incorrect."))
        XCTAssertEqual(setup.vault.loadLogin(), .init(username: "eleve.test", password: nil))
        XCTAssertEqual(setup.session.access, .signedOut)
        XCTAssertNotNil(setup.session.errorMessage)
    }

    // MARK: Arrière-plan

    func testBackgroundRefreshPersistsRotatedTokenAndSyncs() async throws {
        let setup = await makeSetup(token: .success(rotatedToken: "rotated"))
        let result = await setup.session.backgroundRefresh(now: Date(timeIntervalSince1970: 1_794_942_000))
        XCTAssertEqual(result, .synced)
        XCTAssertEqual(try setup.vault.loadCredentials()?.password, "rotated")
        XCTAssertEqual(setup.app.document.pronoteEvents.count, 1)
        XCTAssertNil(setup.app.toast, "pas de message en arrière-plan")
        XCTAssertTrue(setup.page.starts.isEmpty, "jamais de page web en arrière-plan")
    }

    func testBackgroundCancellationNeverLosesRotatedToken() async throws {
        let setup = await makeSetup(token: .rotatedThenCancelled("rotated-before-expiry"))
        let result = await setup.session.backgroundRefresh()
        XCTAssertEqual(result, .failed)
        XCTAssertEqual(try setup.vault.loadCredentials()?.password, "rotated-before-expiry")
        XCTAssertFalse(setup.vault.needsSilentRelogin)
    }

    func testBackgroundRefusalOnlyMarksSilentReloginForForeground() async throws {
        let setup = await makeSetup(token: .refused)
        let result = await setup.session.backgroundRefresh()
        XCTAssertEqual(result, .tokenRefused)
        XCTAssertTrue(setup.vault.needsSilentRelogin)
        XCTAssertTrue(setup.page.starts.isEmpty, "rien d'autre n'est tenté en arrière-plan")

        // Premier plan suivant : reconnexion silencieuse directe, sans nouvel essai du jeton refusé.
        let loginsBefore = setup.counter.logins
        setup.session.resumeAtLaunch()
        await until { setup.page.starts.count == 1 }
        XCTAssertEqual(setup.counter.logins, loginsBefore)
        XCTAssertTrue(setup.vault.needsSilentRelogin, "la marque reste jusqu'à la reconnexion réussie")
    }

    func testBackgroundWithoutConnection() async {
        let setup = await makeSetup(token: .success(rotatedToken: nil), savedToken: false)
        let result = await setup.session.backgroundRefresh()
        XCTAssertEqual(result, .noConnection)
    }

    // MARK: Premier plan

    func testForegroundSyncAfterSixHours() async throws {
        let base = Date(timeIntervalSince1970: 1_794_942_000)
        XCTAssertTrue(PronoteSession.shouldSyncOnForeground(lastSync: nil, now: base))
        XCTAssertFalse(PronoteSession.shouldSyncOnForeground(lastSync: base, now: base.addingTimeInterval(6 * 3600 - 1)))
        XCTAssertTrue(PronoteSession.shouldSyncOnForeground(lastSync: base, now: base.addingTimeInterval(6 * 3600)))

        let setup = await makeSetup(token: .success(rotatedToken: nil))
        setup.session.resumeAtLaunch()
        await until { setup.session.access == .connected }
        XCTAssertEqual(setup.counter.timetables, 1)
        let synced = try XCTUnwrap(setup.app.lastPronoteSync)
        setup.session.foregroundActivated(now: synced.addingTimeInterval(5 * 3600))
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(setup.counter.timetables, 1, "pas de synchronisation avant 6 h")
        setup.session.foregroundActivated(now: synced.addingTimeInterval(7 * 3600))
        await until { setup.counter.timetables == 2 }
    }
}
