import XCTest
@testable import JMethod

/// Session expirée (`protocol-reference.md`, « Session expiry ») : toute erreur sur une session établie sauf 25
/// entraîne une seule reconnexion par jeton puis un seul nouvel essai ; reconnexion préventive après 10 minutes
/// d'inactivité. Client réel face au serveur scripté.
@MainActor
final class SessionExpiryTests: XCTestCase {
    private struct Setup {
        let session: PronoteSession
        let vault: PronoteVault
        let app: AppModel
        let server: ScriptedPronoteServer
        let throttle: PronoteThrottle
        let clock: SessionTests.TestClock
    }

    private func makeSetup() async throws -> Setup {
        let vault = PronoteVault(store: MemoryStore())
        try vault.saveCredentials(PronoteCredentials(url: "https://demo.index-education.net/pronote/mobile.eleve.html",
                                                     username: "u", password: "old-token", uuid: "uuid-stable", clientIdentifier: nil))
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-expiry-\(UUID().uuidString).json")
        addTeardownBlock { try? FileManager.default.removeItem(at: file) }
        let suite = "jmethod-expiry-\(UUID().uuidString)"
        addTeardownBlock {
            UserDefaults().removePersistentDomain(forName: suite)
            UserDefaults().removePersistentDomain(forName: suite + "-throttle")
        }
        let app = AppModel(store: DocumentStore(url: file), preferences: UserDefaults(suiteName: suite)!)
        await app.load()
        let server = ScriptedPronoteServer()
        let throttle = PronoteThrottle(suiteName: suite + "-throttle")
        let clock = SessionTests.TestClock()
        let session = PronoteSession(app: app, vault: vault, page: FakePage(), clearWebData: {}, throttle: throttle, now: { clock.now }) {
            try PronoteClient(credentials: $0, transport: server)
        }
        return Setup(session: session, vault: vault, app: app, server: server, throttle: throttle, clock: clock)
    }

    private func until(_ condition: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<300 {
            if condition() { return }
            try? await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("condition non atteinte", file: file, line: line)
    }

    /// Connexion initiale par jeton, puis synchronisation réussie.
    private func connected() async throws -> Setup {
        let setup = try await makeSetup()
        setup.session.resumeAtLaunch()
        await until { setup.session.access == .connected && !setup.session.isWorking }
        let logins = await setup.server.count("Authentification")
        XCTAssertEqual(logins, 1)
        XCTAssertEqual(try setup.vault.loadCredentials()?.password, "rotated-token")
        return setup
    }

    private func synchronizeAndWait(_ setup: Setup) async {
        setup.session.synchronize()
        await until { !setup.session.isWorking }
    }

    func testError8ReconnectsOnceThenSucceeds() async throws {
        let setup = try await connected()
        await setup.server.enqueue([.error(8)], for: "PageEmploiDuTemps")
        await synchronizeAndWait(setup)
        let logins = await setup.server.count("Authentification")
        XCTAssertEqual(logins, 2, "une seule reconnexion par jeton")
        XCTAssertNil(setup.session.errorMessage)
        XCTAssertEqual(setup.session.phase, .ready)
        XCTAssertEqual(try setup.vault.loadCredentials()?.password, "rotated-token-2", "jeton renouvelé enregistré")
    }

    func testError8TwiceIsReportedToTheUser() async throws {
        let setup = try await connected()
        await setup.server.enqueue([.error(8), .error(8)], for: "PageEmploiDuTemps")
        await synchronizeAndWait(setup)
        let logins = await setup.server.count("Authentification")
        XCTAssertEqual(logins, 2, "pas de seconde reconnexion")
        XCTAssertEqual(setup.session.errorMessage, "La session Pronote a expiré.")
        XCTAssertEqual(setup.session.access, .connected, "l'app reste ouverte")
    }

    func testError25NeverReconnects() async throws {
        let setup = try await connected()
        await setup.server.enqueue([.error(25)], for: "PageEmploiDuTemps")
        await synchronizeAndWait(setup)
        let logins = await setup.server.count("Authentification")
        XCTAssertEqual(logins, 1)
        XCTAssertEqual(setup.session.errorMessage, PronoteThrottle.blockedMessage)
        XCTAssertTrue(setup.throttle.isBlocked(now: setup.clock.now))
    }

    func testIdleSessionReconnectsBeforeReading() async throws {
        let setup = try await connected()
        setup.clock.now = setup.clock.now.addingTimeInterval(PronoteSession.idleReloginInterval + 60)
        let before = await setup.server.functions.count
        await synchronizeAndWait(setup)
        let functions = Array(await setup.server.functions.dropFirst(before))
        XCTAssertEqual(functions.first { $0 == "Authentification" || $0 == "PageEmploiDuTemps" }, "Authentification", "reconnexion avant la lecture")
        XCTAssertEqual(functions.filter { $0 == "Authentification" }.count, 1)
        XCTAssertNil(setup.session.errorMessage)

        // Activité récente : aucune reconnexion.
        setup.clock.now = setup.clock.now.addingTimeInterval(5 * 60)
        await synchronizeAndWait(setup)
        let logins = await setup.server.count("Authentification")
        XCTAssertEqual(logins, 2)
    }

    func testNonJSONAnswerReconnects() async throws {
        let setup = try await connected()
        await setup.server.enqueue([.notJSON], for: "PageEmploiDuTemps")
        await synchronizeAndWait(setup)
        let logins = await setup.server.count("Authentification")
        XCTAssertEqual(logins, 2)
        XCTAssertNil(setup.session.errorMessage)

        await setup.server.enqueue([.status(503)], for: "PageEmploiDuTemps")
        await synchronizeAndWait(setup)
        let again = await setup.server.count("Authentification")
        XCTAssertEqual(again, 3, "statut HTTP non 2xx : session perdue")
        XCTAssertNil(setup.session.errorMessage)
    }

    func testNotebookReadReconnectsOnError8() async throws {
        let setup = try await connected()
        await setup.server.enqueue([.error(8)], for: "PageCahierDeTexte")
        let read = try await setup.session.readNotebook()
        XCTAssertTrue(read.snapshot.sessions.isEmpty)
        let logins = await setup.server.count("Authentification")
        XCTAssertEqual(logins, 2)
    }

    func testBackgroundRefreshReconnectsOnError8() async throws {
        let setup = try await makeSetup()
        await setup.server.enqueue([.error(8)], for: "PageEmploiDuTemps")
        let result = await setup.session.backgroundRefresh(now: setup.clock.now)
        XCTAssertEqual(result, .synced)
        let logins = await setup.server.count("Authentification")
        XCTAssertEqual(logins, 2)
        XCTAssertEqual(try setup.vault.loadCredentials()?.password, "rotated-token-2")
        XCTAssertNotNil(setup.app.lastPronoteSync)
    }

    func testSessionLostClassification() {
        XCTAssertTrue(PronoteSession.isSessionLost(PronoteFailure.invalid("pronote-8", "")))
        XCTAssertTrue(PronoteSession.isSessionLost(PronoteFailure.invalid("pronote-10", "")))
        XCTAssertTrue(PronoteSession.isSessionLost(PronoteFailure.invalid("http-500", "")))
        XCTAssertTrue(PronoteSession.isSessionLost(PronoteFailure.invalid("json", "")))
        XCTAssertTrue(PronoteSession.isSessionLost(PronoteFailure.invalid("crypto", "")))
        XCTAssertTrue(PronoteSession.isSessionLost(PronoteFailure.invalid("compression", "")))
        XCTAssertFalse(PronoteSession.isSessionLost(PronoteFailure.invalid("pronote-25", "")))
        XCTAssertFalse(PronoteSession.isSessionLost(PronoteFailure.invalid("readonly", "")))
        XCTAssertFalse(PronoteSession.isSessionLost(URLError(.notConnectedToInternet)))
    }
}
