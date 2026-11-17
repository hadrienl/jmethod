import XCTest
@testable import JMethod

// Durcissement après revue de sécurité (points 1 à 18 de la revue Android, appliqués à iOS).

/// Serveur Pronote scripté, en clair (sans compression ni chiffrement des charges utiles), pour le client réel.
/// Une étape peut être retenue jusqu'à ce que le test la libère ; la réponse respecte alors l'annulation de la
/// tâche appelante, comme `URLSession`.
actor ScriptedPronoteServer: PronoteTransport {
    private var serverIV = ProtocolCrypto.zeroIV
    private(set) var functions: [String] = []
    private var held: String?
    private var heldArrived: CheckedContinuation<Void, Never>?
    private var release: CheckedContinuation<Void, Never>?
    private var arrived = false
    private let doubleAuthentication: Bool

    init(hold function: String? = nil, doubleAuthentication: Bool = false) {
        self.held = function
        self.doubleAuthentication = doubleAuthentication
    }

    /// Attend que la requête retenue soit arrivée.
    func waitForHeld() async {
        if arrived { return }
        await withCheckedContinuation { heldArrived = $0 }
    }

    func releaseHeld() {
        release?.resume()
        release = nil
    }

    func request(url: URL, method: String, body: Data?, headers: [String: String]) async throws -> PronoteHTTPResponse {
        if method == "GET" {
            let html = "<html><body onload=\"Start({h:'424242',a:3,CoA:false,CrA:false,http:false})\"></body></html>"
            return PronoteHTTPResponse(data: Data(html.utf8), status: 200)
        }
        let envelope = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(body)) as? [String: Any])
        let function = try XCTUnwrap(envelope["id"] as? String)
        functions.append(function)
        let payload = (envelope["dataSec"] as? [String: Any])?["data"] as? [String: Any] ?? [:]
        if function == held {
            arrived = true
            heldArrived?.resume()
            heldArrived = nil
            await withCheckedContinuation { release = $0 }
            // Comme URLSession : une tâche annulée pendant l'attente ne reçoit pas la réponse.
            try Task.checkCancellation()
        }
        let data: [String: Any]
        switch function {
        case "FonctionParametres":
            let temporary = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(payload["Uuid"] as? String)))
            serverIV = ProtocolCrypto.md5(temporary)
            data = ["General": ["PremierLundi": ["V": "31/08/2026"], "ListeHeuresFin": []] as [String: Any], "identifiantNav": "nav-test"]
        case "Identification":
            data = ["alea": "", "challenge": "defi", "modeCompLog": 0, "modeCompMdp": 0]
        case "Authentification":
            let key = ProtocolCodec.authenticationKey(username: "u", password: "old-token", alea: "", lowercaseUsername: false, lowercasePassword: false)
            var answer: [String: Any] = [
                "cle": ProtocolCrypto.hex(try ProtocolCrypto.encrypt(Data("1,2,3".utf8), key: key, iv: serverIV)),
                "jetonConnexionAppliMobile": "rotated-token",
            ]
            if doubleAuthentication { answer["actionsDoubleAuth"] = ["_T": 26, "V": "[3]"] }
            data = answer
        case "ParametresUtilisateur":
            data = ["ressource": ["N": "1", "L": "Élève test"], "listeOnglets": [["G": 16], ["G": 89]]]
        default:
            data = [:]
        }
        let response = try JSONSerialization.data(withJSONObject: ["dataSec": ["data": data]])
        return PronoteHTTPResponse(data: response, status: 200)
    }
}

/// Jeton enregistré par le client (fermeture `persist`).
final class SavedToken: @unchecked Sendable {
    // Accès protégé par le verrou.
    private let lock = NSLock()
    private var stored: PronoteCredentials?
    var value: PronoteCredentials? { lock.withLock { stored } }
    func set(_ credentials: PronoteCredentials) { lock.withLock { stored = credentials } }
}

/// Coffre dont toutes les lectures échouent comme un trousseau verrouillé (errSecInteractionNotAllowed).
final class LockedStore: SecureItemStore, @unchecked Sendable {
    // Accès protégé par le verrou.
    private let lock = NSLock()
    private var items: [String: Data] = [:]
    var locked = true
    private(set) var deletions: [String] = []

    func read(_ account: String) throws -> Data? {
        try lock.withLock {
            if locked { throw PronoteVault.unavailable }
            return items[account]
        }
    }

    func write(_ data: Data, account: String) throws { lock.withLock { items[account] = data } }
    func delete(_ account: String) { lock.withLock { deletions.append(account); items[account] = nil } }
}

final class ClientHardeningTests: XCTestCase {
    private let credentials = PronoteCredentials(url: "https://school.index-education.net/pronote/mobile.eleve.html",
                                                 username: "u", password: "old-token", uuid: "uuid-stable", clientIdentifier: nil)

    /// Point 1 : une fois l'Authentification envoyée, une annulation n'empêche pas l'enregistrement du jeton renouvelé.
    func testCancelledTokenLoginStillPersistsRotatedToken() async throws {
        let server = ScriptedPronoteServer(hold: "Authentification")
        let client = try PronoteClient(credentials: credentials, transport: server)
        let saved = SavedToken()
        let login = Task { try await client.login(mode: .token, persist: { saved.set($0) }) }
        await server.waitForHeld()
        login.cancel()
        await server.releaseHeld()
        _ = await login.result
        XCTAssertEqual(saved.value?.password, "rotated-token", "jeton renouvelé enregistré malgré l'annulation")
        XCTAssertEqual(saved.value?.uuid, "uuid-stable")
    }

    /// Point 1 : une annulation avant l'envoi de l'Authentification arrête la connexion (jeton intact côté Pronote).
    func testCancellationBeforeAuthenticationSendsNothingMore() async throws {
        let server = ScriptedPronoteServer(hold: "Identification")
        let client = try PronoteClient(credentials: credentials, transport: server)
        let saved = SavedToken()
        let login = Task { try await client.login(mode: .token, persist: { saved.set($0) }) }
        await server.waitForHeld()
        login.cancel()
        await server.releaseHeld()
        _ = await login.result
        let functions = await server.functions
        XCTAssertFalse(functions.contains("Authentification"))
        XCTAssertNil(saved.value)
    }

    /// Point 4 (client) : la double authentification exigée après le QR est signalée après l'enregistrement du jeton.
    func testDoubleAuthenticationReportedAfterTokenPersisted() async throws {
        let server = ScriptedPronoteServer(doubleAuthentication: true)
        let client = try PronoteClient(credentials: credentials, transport: server)
        let saved = SavedToken()
        do {
            _ = try await client.login(mode: .qr, persist: { saved.set($0) })
            XCTFail("double authentification attendue")
        } catch {
            XCTAssertEqual((error as? PronoteFailure)?.code, "mfa")
        }
        XCTAssertEqual(saved.value?.password, "rotated-token")
    }

    /// Point 11 : un QR dont l'URL n'a pas l'origine de la tentative est refusé.
    func testQRFromAnotherOriginIsRefused() throws {
        let qr = try XCTUnwrap(try SharedVectors.object("protocol-vectors.json")["qr"] as? [String: Any])
        let data = try decodeJSON(PronoteQRData.self, qr["qrData"])
        let pin = try XCTUnwrap(qr["pin"] as? String)
        XCTAssertThrowsError(try PronoteClient.credentials(qr: data, pin: pin, expectedOrigin: "https://school.index-education.net"))
        XCTAssertNoThrow(try PronoteClient.credentials(qr: data, pin: pin, expectedOrigin: "https://demo.index-education.net"))
        XCTAssertEqual(try PronoteClient.credentials(qr: data, pin: pin, uuid: "uuid-stable", expectedOrigin: "https://demo.index-education.net").uuid, "uuid-stable")
    }

    /// Point 14 : réponses HTTP et décompression plafonnées à 16 Mo.
    func testResponseAndInflationCaps() async throws {
        XCTAssertEqual(URLSessionPronoteTransport.maximumResponseBytes, 16 * 1024 * 1024)
        let origin = try XCTUnwrap(URL(string: "https://school.index-education.net/pronote/mobile.eleve.html"))
        let transport = URLSessionPronoteTransport(origin: origin, protocolClasses: [StubDownloadProtocol.self])
        let small = try XCTUnwrap(URL(string: "https://school.index-education.net/pronote/x?size=1024"))
        let ok = try await transport.request(url: small, method: "GET", body: nil, headers: [:])
        XCTAssertEqual(ok.data.count, 1024)
        let large = try XCTUnwrap(URL(string: "https://school.index-education.net/pronote/x?size=\(16 * 1024 * 1024 + 1)"))
        do {
            _ = try await transport.request(url: large, method: "GET", body: nil, headers: [:])
            XCTFail("réponse trop volumineuse acceptée")
        } catch {
            XCTAssertEqual((error as? PronoteFailure)?.code, "size")
        }
        let bomb = try ProtocolCrypto.deflateRaw(Data(count: 16 * 1024 * 1024 + 1))
        XCTAssertThrowsError(try ProtocolCrypto.inflateRaw(bomb))
        let fine = try ProtocolCrypto.deflateRaw(Data(count: 1024 * 1024))
        XCTAssertEqual(try ProtocolCrypto.inflateRaw(fine).count, 1024 * 1024)
    }

    /// Point 17 : l'URL `FichiersExternes` utilise le `L` brut (même vide) ; le nom nettoyé ne sert qu'à l'affichage.
    func testAttachmentKeepsRawNameForURL() {
        let parsed = NotebookRules.attachments(["ListePieceJointe": ["V": [
            ["N": "pj-1", "L": "", "G": 1],
            ["N": "pj-2", "L": "  Fiche\u{202E}  .pdf ", "G": 1],
        ]]])
        XCTAssertEqual(parsed.map(\.name), ["Document", "Fiche .pdf"])
        XCTAssertEqual(parsed.map(\.fileName), ["", "  Fiche\u{202E}  .pdf "])
        XCTAssertTrue(NotebookRules.downloadURL(root: "https://school.index-education.net/pronote", encryptedId: "00", name: parsed[0].fileName, session: 1)
            .hasSuffix("/FichiersExternes/00/?Session=1"))
    }

    /// Point 15 : aucune description n'expose un jeton ou un mot de passe.
    func testSecretsAreRedactedFromDescriptions() {
        let login = PronoteVault.SavedLogin(username: "eleve.secret", password: "motdepasse-secret")
        var field = PasswordFieldState()
        field.prefill(stored: "motdepasse-secret")
        let qr = PronoteQRData(url: "https://demo.index-education.net/pronote/eleve.html", login: "login-secret", jeton: "jeton-secret")
        for value in [String(describing: credentials), String(reflecting: credentials), String(describing: login), String(reflecting: login),
                      String(describing: field), String(describing: qr), String(describing: [credentials])] {
            for secret in ["old-token", "motdepasse-secret", "eleve.secret", "jeton-secret", "login-secret"] {
                XCTAssertFalse(value.contains(secret), "\(secret) visible dans une description")
            }
        }
        var dumped = ""
        dump(credentials, to: &dumped)
        dump(login, to: &dumped)
        dump(field, to: &dumped)
        XCTAssertFalse(dumped.contains("old-token") || dumped.contains("motdepasse-secret"))
    }

    /// Point 3 : le mot de passe enregistré ne peut jamais être affiché ni conservé partiellement.
    func testStoredPasswordIsNeverRevealable() {
        var field = PasswordFieldState()
        field.prefill(stored: "secret")
        XCTAssertFalse(field.canReveal)
        XCTAssertEqual(field.text, "secret")
        // Effacer un caractère vide le champ.
        var erased = field
        erased.edit("secre")
        XCTAssertEqual(erased.text, "")
        XCTAssertTrue(erased.canReveal)
        // Taper à la suite : seule la saisie est gardée.
        var appended = field
        appended.edit("secretx")
        XCTAssertEqual(appended.text, "x")
        XCTAssertTrue(appended.canReveal)
        // Remplacer tout : seule la saisie est gardée (aucune partie du mot de passe enregistré).
        var replaced = field
        replaced.edit("y")
        XCTAssertEqual(replaced.text, "")
        // Une saisie de l'utilisateur se modifie librement et peut être affichée.
        var typed = PasswordFieldState()
        typed.edit("abc")
        typed.edit("ab")
        XCTAssertEqual(typed.text, "ab")
        XCTAssertTrue(typed.canReveal)
        typed.clear()
        XCTAssertTrue(typed.isEmpty)
    }

    /// Point 6 : `LOGIN_STALLED` affiche la page ; seul `LOGIN_REJECTED` (refus visible) mène à l'effacement.
    func testStalledLoginRevealsPageWithoutRejection() {
        XCTAssertEqual(PronoteWebFlow.outcome(forErrorCode: "LOGIN_STALLED"), .watch("Pronote ne répond pas : vérifie la page ci-dessous."))
        XCTAssertEqual(PronoteWebFlow.outcome(forErrorCode: "LOGIN_REJECTED"), .rejected("Identifiant ou mot de passe incorrect."))
        XCTAssertEqual(PronoteWebFlow.outcome(forErrorCode: "LOGIN_FORM_NOT_FOUND"), .watch("Connecte-toi ci-dessous pour terminer."))
        if case .rejected = PronoteWebFlow.outcome(forErrorCode: "LOGIN_TIMEOUT") { XCTFail("un délai n'est pas un refus") }
    }

    /// Points 12 et 13 : UUID d'application stable ; données illisibles effacées, erreur passagère sans effacement.
    func testApplicationUUIDAndTransientVaultErrors() throws {
        let store = MemoryStore()
        let vault = PronoteVault(store: store)
        try vault.saveCredentials(credentials)
        XCTAssertEqual(vault.applicationUUID(), "uuid-stable", "première utilisation : UUID de la session existante")
        vault.removeConnection(forgetLogin: true)
        XCTAssertEqual(vault.applicationUUID(), "uuid-stable", "conservé à la déconnexion")
        let fresh = PronoteVault(store: MemoryStore())
        let generated = fresh.applicationUUID()
        XCTAssertEqual(fresh.applicationUUID(), generated, "généré une fois")

        // Erreur passagère (iPhone verrouillé) : rien n'est effacé, l'état reste inconnu.
        let locked = LockedStore()
        try locked.write(JSONEncoder().encode(credentials), account: "mobile-token")
        let lockedVault = PronoteVault(store: locked)
        XCTAssertThrowsError(try lockedVault.loadCredentials())
        XCTAssertNil(lockedVault.credentialsState)
        XCTAssertTrue(locked.deletions.isEmpty)
        locked.locked = false
        XCTAssertEqual(try lockedVault.loadCredentials(), credentials)

        // Données illisibles : effacées.
        try store.write(Data("{abîmé".utf8), account: "mobile-token")
        XCTAssertNil(try vault.loadCredentials())
        XCTAssertNil(try store.read("mobile-token"))
    }
}

@MainActor
extension SessionTests {
    /// Point 2 : erreur 25 → aucun essai automatique pendant une heure, au lancement, au premier plan, en arrière-plan
    /// et par « Réessayer » ; message dédié.
    func testTooManyAttemptsBlocksEveryRetryForOneHour() async throws {
        let setup = await makeSetup(token: .blocked)
        setup.session.resumeAtLaunch()
        await until { setup.session.access == .offline }
        XCTAssertEqual(setup.counter.logins, 1)
        XCTAssertEqual(setup.session.errorMessage, PronoteThrottle.blockedMessage)
        XCTAssertTrue(setup.throttle.isBlocked(now: setup.clock.now))
        XCTAssertEqual(try setup.vault.loadCredentials()?.password, "old-token", "le jeton est conservé")

        // Nouveau lancement dans l'heure : aucun essai.
        let next = await makeSetup(token: .success(rotatedToken: nil), savedToken: false, vault: setup.vault, throttle: setup.throttle, clock: setup.clock)
        next.session.resumeAtLaunch()
        XCTAssertEqual(next.session.access, .offline)
        XCTAssertEqual(next.session.errorMessage, PronoteThrottle.blockedMessage)
        next.session.foregroundActivated(now: setup.clock.now)
        next.session.resumeSavedConnection()
        next.session.synchronize()
        let background = await next.session.backgroundRefresh(now: setup.clock.now)
        XCTAssertEqual(background, .blocked)
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(next.counter.logins, 0)
        XCTAssertTrue(next.page.starts.isEmpty, "pas de reconnexion silencieuse non plus")

        // Une heure plus tard : « Réessayer » repart.
        setup.clock.now = setup.clock.now.addingTimeInterval(PronoteThrottle.blockDuration + 1)
        next.session.resumeSavedConnection()
        await until { next.session.access == .connected }
        XCTAssertEqual(next.counter.logins, 1)
    }

    /// Point 4 : double authentification exigée après le QR → jeton effacé (pas de boucle au lancement) et page affichée.
    func testDoubleAuthenticationAfterQRClearsTokenAndShowsPage() async throws {
        let (qr, pin) = try vectorQR()
        let setup = await makeSetup(token: .success(rotatedToken: nil), qr: .mfaAfterToken("qr-token"), savedToken: false)
        setup.session.signIn(school: "demo", account: .student, username: "eleve.test", password: "secret", remember: true)
        setup.page.emit(.qr(qr, pin: pin))
        await until { !setup.page.verifications.isEmpty }
        XCTAssertNil(try setup.vault.loadCredentials(), "le jeton du QR n'est pas conservé")
        XCTAssertFalse(setup.session.hasSavedConnection)
        XCTAssertTrue(setup.session.webVisible)
        XCTAssertNotEqual(setup.session.access, .connected)
    }

    /// Point 5 : reconnexion silencieuse automatique au plus une fois par heure, horodatage persistant.
    func testSilentReloginAtMostOncePerHour() async throws {
        let setup = await makeSetup(token: .refused)
        setup.session.resumeAtLaunch()
        await until { setup.page.starts.count == 1 }
        setup.page.emit(.failed("La connexion n’a pas abouti. Réessaie."))
        XCTAssertEqual(setup.session.access, .offline)

        // Relance de l'app dans l'heure : marque de reconnexion présente, mais aucun essai automatique.
        setup.clock.now = setup.clock.now.addingTimeInterval(30 * 60)
        let soon = await makeSetup(token: .refused, savedToken: false, vault: setup.vault, throttle: setup.throttle, clock: setup.clock)
        soon.session.resumeAtLaunch()
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertTrue(soon.page.starts.isEmpty)
        XCTAssertEqual(soon.session.access, .offline)
        XCTAssertEqual(setup.vault.loadLogin()?.password, "secret", "mot de passe conservé")

        // Plus d'une heure après : un nouvel essai automatique.
        setup.clock.now = setup.clock.now.addingTimeInterval(31 * 60)
        let later = await makeSetup(token: .refused, savedToken: false, vault: setup.vault, throttle: setup.throttle, clock: setup.clock)
        later.session.resumeAtLaunch()
        await until { later.page.starts.count == 1 }
    }

    /// Point 6 : la page affichée pendant une reconnexion silencieuse (réponse lente) n'efface pas le mot de passe.
    func testRevealDuringSilentReloginKeepsPassword() async throws {
        let setup = await makeSetup(token: .refused)
        setup.session.resumeAtLaunch()
        await until { setup.page.starts.count == 1 }
        setup.page.emit(.reveal("Pronote ne répond pas : vérifie la page ci-dessous."))
        XCTAssertTrue(setup.session.webVisible)
        XCTAssertEqual(setup.vault.loadLogin()?.password, "secret")
    }

    /// Point 8 : une actualisation en arrière-plan interrompue par une déconnexion ne réécrit pas les créneaux.
    func testBackgroundRefreshDoesNotWriteSlotsAfterDisconnect() async throws {
        let setup = await makeSetup(token: .success(rotatedToken: "t2"))
        setup.counter.timetableDelay = .milliseconds(300)
        let refresh = Task { await setup.session.backgroundRefresh(now: setup.clock.now) }
        await until { setup.counter.timetables == 1 }
        await setup.session.disconnect(forgetLogin: false)
        let result = await refresh.value
        XCTAssertEqual(result, .failed)
        XCTAssertEqual(setup.app.document.pronoteEvents, [])
        XCTAssertNil(try setup.vault.loadCredentials(), "aucun jeton réécrit après la déconnexion")
    }

    /// Points 11 et 12 : QR d'une autre origine refusé ; UUID d'application stable transmis à la connexion par QR.
    func testQROriginAndStableApplicationUUID() async throws {
        let (qr, pin) = try vectorQR()
        let other = await makeSetup(token: .success(rotatedToken: nil), savedToken: false)
        other.vault.saveSchool(.init(input: "abcd1234", account: .student))
        other.session.signIn(school: "abcd1234", account: .student, username: "eleve.test", password: "secret", remember: true)
        other.page.emit(.qr(qr, pin: pin))
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(other.counter.logins, 0, "QR d'une autre origine refusé")
        XCTAssertNotEqual(other.session.access, .connected)

        let setup = await makeSetup(token: .success(rotatedToken: nil), qr: .success(rotatedToken: "qr-token"), savedToken: false)
        let uuid = setup.vault.applicationUUID()
        setup.session.signIn(school: "demo", account: .student, username: "eleve.test", password: "secret", remember: true)
        setup.page.emit(.qr(qr, pin: pin))
        await until { setup.session.access == .connected }
        XCTAssertEqual(setup.counter.uuids, [uuid])
        XCTAssertEqual(try setup.vault.loadCredentials()?.uuid, uuid)
    }

    /// Point 13 : trousseau inaccessible au lancement (avant le premier déverrouillage) : pas d'écran de connexion, rien d'effacé.
    func testLockedKeychainAtLaunchDoesNotSignOut() async throws {
        let store = LockedStore()
        let setup = await makeSetup(token: .success(rotatedToken: nil), savedToken: false, vault: PronoteVault(store: store))
        XCTAssertEqual(setup.session.access, .resuming)
        XCTAssertTrue(setup.session.hasSavedConnection)
        let background = await setup.session.backgroundRefresh(now: setup.clock.now)
        XCTAssertEqual(background, .noConnection)
        XCTAssertFalse(store.deletions.contains("mobile-token"))
    }
}
