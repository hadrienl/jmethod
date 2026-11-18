package fr.jmethod.app

import fr.jmethod.app.pronote.AccessPhase
import fr.jmethod.app.pronote.AccountType
import fr.jmethod.app.pronote.Credentials
import fr.jmethod.app.pronote.Lesson
import fr.jmethod.app.pronote.BackgroundScheduler
import fr.jmethod.app.pronote.LoginStore
import fr.jmethod.app.pronote.SavedLogin
import fr.jmethod.app.pronote.OfficialPage
import fr.jmethod.app.pronote.PronoteConnectionController
import fr.jmethod.app.pronote.SessionVault
import fr.jmethod.app.pronote.MemoryThrottle
import fr.jmethod.app.pronote.PronoteThrottle
import fr.jmethod.app.pronote.LoginForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate

/** Flux de connexion sans WebView : routage d’accès, bridge, réclamation du QR, MFA, annulation et déconnexion. */
class ConnectionControllerTest {
    private class FakePage : OfficialPage {
        override var currentUrl: String? = null
        val loaded = mutableListOf<String>()
        val scripts = mutableListOf<String>()
        var released = 0
        var cleared = 0
        override fun load(url: String) { loaded += url; currentUrl = url }
        override fun evaluate(script: String) { scripts += script }
        override fun release() { released++ }
        override fun clearBrowsingData() { cleared++ }
        fun configs() = scripts.filter { it.contains("window.__JM_CONFIG=") }
            .map { JSONObject(it.substringAfter("window.__JM_CONFIG=").substringBefore(";\n")) }
        fun config(): JSONObject = configs().last()
    }

    class FakeVault : SessionVault {
        var credentials: Credentials? = null
        var child: String? = null
        var relogin = false
        val saves = mutableListOf<String>()
        override fun load() = credentials
        override fun save(credentials: Credentials) { saves += credentials.password; this.credentials = credentials; relogin = false }
        override fun selectedChildId() = child
        override fun saveSelectedChildId(childId: String?) { child = childId }
        override fun needsRelogin() = relogin
        override fun markNeedsRelogin() { relogin = true }
        override fun clear() { credentials = null; child = null; relogin = false }
    }

    class FakeLoginStore(var value: SavedLogin? = null) : LoginStore {
        override fun load() = value
        override fun save(login: SavedLogin) { value = login }
        override fun forgetPassword() { value = value?.copy(password = null) }
        override fun forgetCredentials() { value = value?.copy(username = null, password = null) }
    }

    private class FakeScheduler : BackgroundScheduler {
        var scheduled = 0
        var cancelled = 0
        override fun schedule() { scheduled++ }
        override fun cancel() { cancelled++ }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fixedPin = object : SecureRandom() {
        override fun nextInt(bound: Int) = 4321
    }
    private val origin = "https://abcd1234.index-education.net"
    private val pageUrl = "$origin/pronote/eleve.html"
    private val now: Instant = Instant.parse("2026-11-17T19:00:00Z")
    private var cleared = 0

    @After
    fun tearDown() = scope.cancel()

    private fun controller(
        server: FakePronoteServer,
        page: FakePage,
        vault: FakeVault,
        stored: MutableList<List<Lesson>>,
        store: FakeLoginStore = FakeLoginStore(),
        scheduler: FakeScheduler = FakeScheduler(),
        lastSync: Instant? = null,
        throttle: PronoteThrottle = MemoryThrottle(),
        clock: () -> Instant = { now },
    ) = PronoteConnectionController(
        scope = scope,
        io = Dispatchers.Unconfined,
        page = page,
        vault = vault,
        transport = server.transport,
        decodeQr = { server.qr("4321") },
        automationScript = { "/* script */" },
        today = { LocalDate.of(2026, 11, 17) },
        storeLessons = { stored += it },
        clearLessons = { cleared++ },
        loginStore = store,
        scheduler = scheduler,
        now = clock,
        lastSync = { lastSync },
        random = fixedPin,
        throttle = throttle,
        appUuid = { APP_UUID },
    )

    private fun savedVault(server: FakePronoteServer, token: String = "jeton-enregistre") = FakeVault().apply {
        credentials = Credentials("${server.origin}/pronote/mobile.eleve.html?fd=1", server.username, token, "uuid")
    }

    private fun message(type: String, nonce: String, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("version", 1).put("nonce", nonce).put("type", type)
            .put("origin", origin).put("url", pageUrl).apply(extra).toString()

    private fun send(controller: PronoteConnectionController, raw: String) = controller.onBridgeMessage(raw, origin, true)

    private fun qr(nonce: String, pin: String = "4321") = message("qr", nonce) { put("pin", pin).put("image", "data:image/png;base64,AAAA") }

    private fun submit(controller: PronoteConnectionController, page: FakePage, password: String = "mot-de-passe"): String {
        controller.onSchoolChange(" ABCD1234 ")
        controller.onUsernameChange("eleve.test")
        val form = controller.uiState.value.form
        // Mot de passe enregistré : utilisé tel quel, ou effacé puis ressaisi (il n’est jamais modifiable en partie).
        if (!(form.passwordFromStore && form.password == password)) {
            if (form.passwordFromStore) controller.onPasswordChange("")
            controller.onPasswordChange(password)
        }
        controller.submitCredentials()
        assertEquals("", controller.uiState.value.form.password)
        assertEquals(pageUrl, page.loaded.last())
        assertFalse(controller.uiState.value.showBrowser)
        controller.onPageStarted()
        controller.onPageFinished(pageUrl)
        val config = page.config()
        // Identifiants transmis une seule fois, à la première injection sur la page de confiance.
        assertEquals(password, config.getJSONObject("credentials").getString("password"))
        assertEquals("4321", config.getString("pin"))
        return config.getString("nonce").also { assertTrue(it.length >= 16) }
    }

    @Test
    fun firstConnectionSyncsThenOpensApp() {
        val server = FakePronoteServer(token = "jeton-qr").lesson("c1", "18/11/2026 08:30:00", "MATHEMATIQUES", end = "18/11/2026 09:30:00")
        val page = FakePage()
        val vault = FakeVault()
        val stored = mutableListOf<List<Lesson>>()
        val store = FakeLoginStore()
        val scheduler = FakeScheduler()
        val controller = controller(server, page, vault, stored, store, scheduler)
        assertEquals(AccessPhase.SIGNED_OUT, controller.uiState.value.access)
        val nonce = submit(controller, page)
        send(controller, message("authenticated", nonce))
        assertTrue(controller.uiState.value.busy)

        // Rejeu de l’injection : les identifiants ne sont plus jamais renvoyés.
        controller.onPageStarted()
        controller.onPageFinished(pageUrl)
        assertFalse(page.config().has("credentials"))

        send(controller, qr(nonce))
        send(controller, qr(nonce))
        assertEquals(1, server.calls.count { it == "Authentification" })
        assertEquals("jeton-rotation-1", vault.credentials?.password)
        assertEquals("MATHEMATIQUES", stored.single().single().subject)
        val state = controller.uiState.value
        assertEquals(AccessPhase.CONNECTED, state.access)
        assertEquals("Élève Test", state.account?.name)
        assertFalse(state.browserStarted)
        assertTrue(page.scripts.contains("window.__JM_PRONOTE_POC?.stop()"))
        // « Rester connecté » (par défaut) : établissement, type, identifiant et mot de passe enregistrés.
        assertEquals(SavedLogin("ABCD1234", AccountType.STUDENT, "eleve.test", "mot-de-passe"), store.value)
        assertTrue(state.hasStoredPassword)
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun savedSchoolPrefillsTheForm() {
        val controller = controller(FakePronoteServer(token = "t"), FakePage(), FakeVault(), mutableListOf(), FakeLoginStore(SavedLogin("abcd1234", AccountType.PARENT)))
        assertEquals("abcd1234", controller.uiState.value.form.school)
        assertEquals(AccountType.PARENT, controller.uiState.value.form.accountType)
        assertEquals("", controller.uiState.value.form.password)
    }

    @Test
    fun fullAddressPageOverridesAccountChoice() {
        val page = FakePage()
        val controller = controller(FakePronoteServer(token = "t"), page, FakeVault(), mutableListOf())
        controller.onSchoolChange("https://abcd1234.index-education.net/pronote/parent.html?login=true")
        controller.onUsernameChange("u")
        controller.onPasswordChange("p")
        controller.submitCredentials()
        assertEquals("$origin/pronote/parent.html", page.loaded.single())
        assertEquals(AccountType.PARENT, controller.uiState.value.form.accountType)
    }

    @Test
    fun invalidSchoolShowsErrorUnderButton() {
        val page = FakePage()
        val controller = controller(FakePronoteServer(token = "t"), page, FakeVault(), mutableListOf())
        controller.onSchoolChange("autre-domaine.example.test")
        controller.onUsernameChange("u")
        controller.onPasswordChange("p")
        controller.submitCredentials()
        assertTrue(page.loaded.isEmpty())
        assertNotNull(controller.uiState.value.formError)
        assertFalse(controller.uiState.value.busy)
    }

    @Test
    fun mfaRevealsPageWithoutEndingAttempt() {
        val server = FakePronoteServer(token = "jeton-qr")
        val page = FakePage()
        val vault = FakeVault()
        val controller = controller(server, page, vault, mutableListOf())
        val nonce = submit(controller, page)
        send(controller, message("error", nonce) { put("code", "MFA_REQUIRED") })
        assertTrue(controller.uiState.value.showBrowser)
        assertTrue(controller.uiState.value.browserStarted)
        assertEquals("Vérification de sécurité", controller.uiState.value.title)
        assertEquals(1, page.configs().size)
        send(controller, message("progress", nonce) { put("stage", "mfa-done") })
        assertFalse(controller.uiState.value.showBrowser)
        send(controller, message("authenticated", nonce))
        send(controller, qr(nonce))
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
        assertNotNull(vault.credentials)
    }

    @Test
    fun unrecognisedFormRevealsPageAndRearms() {
        val page = FakePage()
        val controller = controller(FakePronoteServer(token = "t"), page, FakeVault(), mutableListOf())
        val nonce = submit(controller, page)
        send(controller, message("error", nonce) { put("code", "LOGIN_FORM_NOT_FOUND") })
        assertTrue(controller.uiState.value.showBrowser)
        val rearmed = page.config()
        assertNotEquals(nonce, rearmed.getString("nonce"))
        assertFalse(rearmed.has("credentials"))
        // L’ancien nonce est invalide.
        send(controller, message("authenticated", nonce))
        assertTrue(controller.uiState.value.showBrowser)
        send(controller, message("authenticated", rearmed.getString("nonce")))
        assertFalse(controller.uiState.value.showBrowser)
    }

    @Test
    fun bridgeRejectsForeignFramesOriginsNoncesAndPins() {
        val server = FakePronoteServer(token = "jeton-qr")
        val page = FakePage()
        val controller = controller(server, page, FakeVault(), mutableListOf())
        val nonce = submit(controller, page)
        controller.onBridgeMessage(qr(nonce), origin, false)
        controller.onBridgeMessage(qr(nonce), "https://other.index-education.net", true)
        send(controller, qr("autre-nonce-0000000"))
        send(controller, qr(nonce, "0000"))
        page.currentUrl = "https://ent.example.test/login"
        send(controller, qr(nonce))
        page.currentUrl = "$origin/pronote/mobile.eleve.html"
        send(controller, qr(nonce))
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun cancelDuringNativeLoginDiscardsTokenAndResult() {
        val server = FakePronoteServer(token = "jeton-qr")
        val page = FakePage()
        val vault = FakeVault()
        val stored = mutableListOf<List<Lesson>>()
        val controller = controller(server, page, vault, stored)
        val nonce = submit(controller, page)
        server.beforeRequest = { function -> if (function == "Authentification") controller.cancel() }
        send(controller, qr(nonce))
        assertTrue("l’authentification a bien été tentée", server.calls.contains("Authentification"))
        assertNull(vault.credentials)
        assertTrue(stored.isEmpty())
        assertEquals(AccessPhase.SIGNED_OUT, controller.uiState.value.access)
        assertFalse(controller.uiState.value.browserStarted)
    }

    @Test
    fun savedTokenShowsLoadingThenApp() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val vault = savedVault(server)
        val stored = mutableListOf<List<Lesson>>()
        val controller = controller(server, FakePage(), vault, stored)
        assertEquals(AccessPhase.CHECKING, controller.uiState.value.access)
        controller.restoreSaved()
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
        assertEquals("jeton-rotation-1", vault.credentials?.password)
        assertEquals(1, stored.size)
    }

    @Test
    fun networkFailureOpensOfflineAndRetryReconnects() {
        val server = FakePronoteServer(token = "jeton-enregistre").apply { offline = true }
        val vault = savedVault(server)
        val stored = mutableListOf<List<Lesson>>()
        val controller = controller(server, FakePage(), vault, stored)
        controller.restoreSaved()
        val offline = controller.uiState.value
        assertEquals(AccessPhase.OFFLINE, offline.access)
        assertEquals("Hors ligne", offline.title)
        assertFalse(offline.busy)
        assertEquals("jeton-enregistre", vault.credentials?.password)
        server.offline = false
        controller.retry()
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
        assertEquals(1, stored.size)
    }

    @Test
    fun skipResumeOpensOfflineWithoutSigningOut() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val vault = savedVault(server)
        val controller = controller(server, FakePage(), vault, mutableListOf())
        controller.skipResume()
        assertEquals(AccessPhase.OFFLINE, controller.uiState.value.access)
        assertNotNull(vault.credentials)
    }

    @Test
    fun refusedTokenReturnsToSignIn() {
        val server = FakePronoteServer(token = "jeton-valide")
        val vault = savedVault(server, token = "jeton-perime")
        val controller = controller(server, FakePage(), vault, mutableListOf())
        controller.restoreSaved()
        assertNull(vault.credentials)
        assertEquals(AccessPhase.SIGNED_OUT, controller.uiState.value.access)
        assertNotNull(controller.uiState.value.formError)
    }

    @Test
    fun disconnectDuringResyncPreventsLateTokenSave() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val page = FakePage()
        val vault = savedVault(server)
        val stored = mutableListOf<List<Lesson>>()
        val controller = controller(server, page, vault, stored)
        server.beforeRequest = { function -> if (function == "Authentification") controller.disconnect() }
        controller.restoreSaved()
        assertNull(vault.credentials)
        assertTrue(vault.saves.isEmpty())
        assertTrue(stored.isEmpty())
        assertEquals(AccessPhase.SIGNED_OUT, controller.uiState.value.access)
        assertEquals(1, page.cleared)
    }

    @Test
    fun disconnectClearsTokenWebDataAndImportedLessons() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val page = FakePage()
        val vault = savedVault(server)
        val controller = controller(server, page, vault, mutableListOf())
        controller.restoreSaved()
        controller.disconnect()
        assertNull(vault.credentials)
        assertEquals(1, page.cleared)
        assertEquals(1, cleared)
        assertEquals(AccessPhase.SIGNED_OUT, controller.uiState.value.access)
        assertNull(controller.uiState.value.account)
    }

    @Test
    fun refreshAfterCancelKeepsRotatingToken() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val vault = savedVault(server)
        val stored = mutableListOf<List<Lesson>>()
        val controller = controller(server, FakePage(), vault, stored)
        controller.restoreSaved()
        // Une annulation ne condamne pas la session adoptée : la session expirée se reconnecte et le jeton est conservé.
        controller.cancel()
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
        server.failures["PageEmploiDuTemps"] = 10
        controller.refresh()
        assertEquals("jeton-rotation-2", vault.credentials?.password)
        assertEquals(2, stored.size)
        assertFalse(controller.uiState.value.isError)
    }

    @Test
    fun loginRejectedReturnsToFormAndEntRevealsPage() {
        val page = FakePage()
        val controller = controller(FakePronoteServer(token = "jeton-qr"), page, FakeVault(), mutableListOf())
        val nonce = submit(controller, page, password = "faux")
        send(controller, message("error", nonce) { put("code", "LOGIN_REJECTED") })
        assertEquals("Pronote a refusé l’identifiant ou le mot de passe. Vérifie-les puis réessaie.", controller.uiState.value.formError)
        assertFalse(controller.uiState.value.browserStarted)
        assertEquals(AccessPhase.SIGNED_OUT, controller.uiState.value.access)

        controller.onPasswordChange("secret")
        controller.submitCredentials()
        val injections = page.scripts.size
        controller.onPageFinished("https://ent.example.test/cas/login")
        assertEquals(injections, page.scripts.size)
        assertTrue(controller.uiState.value.showBrowser)
        assertEquals("Connexion par l’ENT", controller.uiState.value.title)
        // Retour sur Pronote après l’ENT : le mot de passe n’est plus transmis, la page se masque à la connexion.
        controller.onPageStarted()
        controller.onPageFinished(pageUrl)
        assertFalse(page.config().has("credentials"))
        send(controller, message("authenticated", page.config().getString("nonce")))
        assertFalse(controller.uiState.value.showBrowser)
    }

    private fun storedLogin() = FakeLoginStore(SavedLogin("abcd1234", AccountType.STUDENT, "eleve.test", "mot-de-passe-enregistre"))

    @Test
    fun stayConnectedOffKeepsOnlyTheToken() {
        val server = FakePronoteServer(token = "jeton-qr")
        val page = FakePage()
        val vault = FakeVault()
        val store = FakeLoginStore()
        val controller = controller(server, page, vault, mutableListOf(), store)
        controller.onStayConnectedChange(false)
        val nonce = submit(controller, page)
        send(controller, message("authenticated", nonce))
        send(controller, qr(nonce))
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
        assertEquals(SavedLogin("ABCD1234", AccountType.STUDENT), store.value)
        assertFalse(controller.uiState.value.hasStoredPassword)
        assertNotNull(vault.credentials)
    }

    @Test
    fun failedSignInStoresNothing() {
        val page = FakePage()
        val store = FakeLoginStore()
        val controller = controller(FakePronoteServer(token = "t"), page, FakeVault(), mutableListOf(), store)
        val nonce = submit(controller, page, password = "faux")
        send(controller, message("error", nonce) { put("code", "LOGIN_REJECTED") })
        assertNull(store.value)
    }

    @Test
    fun refusedTokenTriggersOneSilentReconnection() {
        val server = FakePronoteServer(token = "jeton-valide")
        val page = FakePage()
        val vault = savedVault(server, token = "jeton-perime")
        val store = storedLogin()
        val controller = controller(server, page, vault, mutableListOf(), store)
        assertEquals(AccessPhase.CHECKING, controller.uiState.value.access)
        controller.start()
        // Jeton refusé : parcours masqué avec les identifiants enregistrés, sans écran de connexion.
        assertNull(vault.credentials)
        assertEquals(pageUrl, page.loaded.single())
        val state = controller.uiState.value
        assertEquals(AccessPhase.CHECKING, state.access)
        assertTrue(state.reconnecting)
        assertFalse(state.showBrowser)
        controller.onPageFinished(pageUrl)
        assertEquals("mot-de-passe-enregistre", page.config().getJSONObject("credentials").getString("password"))
        // Échec du parcours : bandeau hors ligne, pas de nouvelle tentative automatique.
        send(controller, message("error", page.config().getString("nonce")) { put("code", "QR_TIMEOUT") })
        assertEquals(AccessPhase.OFFLINE, controller.uiState.value.access)
        assertFalse(controller.uiState.value.reconnecting)
        controller.onForeground()
        assertEquals(1, page.loaded.size)
        // Réessayer est une action de l’utilisateur : nouvelle tentative autorisée.
        controller.retry()
        assertEquals(2, page.loaded.size)
    }

    @Test
    fun silentReconnectionSucceedsAndOpensApp() {
        val server = FakePronoteServer(token = "jeton-qr")
        val page = FakePage()
        val vault = FakeVault()
        val stored = mutableListOf<List<Lesson>>()
        val controller = controller(server, page, vault, stored, storedLogin())
        assertEquals(AccessPhase.CHECKING, controller.uiState.value.access)
        controller.start()
        controller.onPageFinished(pageUrl)
        val nonce = page.config().getString("nonce")
        send(controller, message("authenticated", nonce))
        send(controller, qr(nonce))
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
        assertFalse(controller.uiState.value.reconnecting)
        assertEquals("jeton-rotation-1", vault.credentials?.password)
        assertEquals(1, stored.size)
    }

    @Test
    fun silentLoginRejectedForgetsOnlyThePassword() {
        val page = FakePage()
        val store = storedLogin()
        val controller = controller(FakePronoteServer(token = "t"), page, FakeVault(), mutableListOf(), store)
        controller.start()
        controller.onPageFinished(pageUrl)
        send(controller, message("error", page.config().getString("nonce")) { put("code", "LOGIN_REJECTED") })
        assertEquals(SavedLogin("abcd1234", AccountType.STUDENT, "eleve.test", null), store.value)
        val state = controller.uiState.value
        assertEquals(AccessPhase.SIGNED_OUT, state.access)
        assertFalse(state.hasStoredPassword)
        assertEquals("eleve.test", state.form.username)
        assertTrue(state.formError!!.contains("mot de passe"))
    }

    @Test
    fun silentMfaRevealsThePage() {
        val page = FakePage()
        val controller = controller(FakePronoteServer(token = "t"), page, FakeVault(), mutableListOf(), storedLogin())
        controller.start()
        controller.onPageFinished(pageUrl)
        send(controller, message("error", page.config().getString("nonce")) { put("code", "MFA_REQUIRED") })
        assertTrue(controller.uiState.value.showBrowser)
        assertEquals(AccessPhase.CHECKING, controller.uiState.value.access)
    }

    @Test
    fun disconnectKeepsCredentialsForPrefillWithoutAutoReconnect() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val vault = savedVault(server).apply { child = "enfant-a" }
        val store = storedLogin()
        val scheduler = FakeScheduler()
        val controller = controller(server, FakePage(), vault, mutableListOf(), store, scheduler)
        controller.restoreSaved()
        assertEquals(1, scheduler.scheduled)
        controller.disconnect()
        assertNull(vault.credentials)
        assertNull(vault.child)
        assertEquals(1, cleared)
        assertTrue(scheduler.cancelled >= 1)
        assertEquals(SavedLogin("abcd1234", AccountType.STUDENT, "eleve.test", "mot-de-passe-enregistre", autoReconnect = false), store.value)
        val form = controller.uiState.value.form
        assertEquals("eleve.test", form.username)
        assertEquals("mot-de-passe-enregistre", form.password)
        // Lancement suivant : écran de connexion prérempli, aucune reconnexion silencieuse.
        val page = FakePage()
        val next = controller(server, page, FakeVault(), mutableListOf(), store)
        assertEquals(AccessPhase.SIGNED_OUT, next.uiState.value.access)
        assertEquals("mot-de-passe-enregistre", next.uiState.value.form.password)
        next.start()
        assertTrue(page.loaded.isEmpty())
        // Une connexion réussie réactive la reconnexion silencieuse.
        val nonce = submit(next, page, password = "mot-de-passe-enregistre")
        send(next, message("authenticated", nonce))
        send(next, qr(nonce))
        assertTrue(store.value!!.autoReconnect)
    }

    @Test
    fun disconnectAndForgetCredentials() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val vault = savedVault(server)
        val store = storedLogin()
        val controller = controller(server, FakePage(), vault, mutableListOf(), store)
        controller.restoreSaved()
        controller.disconnect(forgetCredentials = true)
        assertEquals(SavedLogin("abcd1234", AccountType.STUDENT), store.value)
        assertNull(vault.credentials)
        assertFalse(controller.uiState.value.hasStoredPassword)
        assertEquals("", controller.uiState.value.form.username)
        assertEquals("", controller.uiState.value.form.password)
    }

    @Test
    fun storedPasswordIsPrefilled() {
        val controller = controller(FakePronoteServer(token = "t"), FakePage(), FakeVault(), mutableListOf(), storedLogin())
        val form = controller.uiState.value.form
        assertEquals("abcd1234", form.school)
        assertEquals("eleve.test", form.username)
        assertEquals("mot-de-passe-enregistre", form.password)
    }

    @Test
    fun rejectedTypedPasswordAlsoErasesStoredPassword() {
        val page = FakePage()
        val store = storedLogin().apply { value = value!!.copy(autoReconnect = false) }
        val controller = controller(FakePronoteServer(token = "t"), page, FakeVault(), mutableListOf(), store)
        val nonce = submit(controller, page, password = "nouveau")
        send(controller, message("error", nonce) { put("code", "LOGIN_REJECTED") })
        assertNull(store.value!!.password)
        assertEquals("", controller.uiState.value.form.password)
    }

    @Test
    fun foregroundSyncsOnlyAfterSixHours() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val recent = controller(server, FakePage(), savedVault(server), mutableListOf(), lastSync = now.minusSeconds(3_600))
        recent.restoreSaved()
        val reads = server.calls.count { it == "PageEmploiDuTemps" }
        recent.onForeground()
        assertEquals(reads, server.calls.count { it == "PageEmploiDuTemps" })

        val server2 = FakePronoteServer(token = "jeton-enregistre")
        val stored = mutableListOf<List<Lesson>>()
        val stale = controller(server2, FakePage(), savedVault(server2), stored, lastSync = now.minusSeconds(7 * 3_600))
        stale.restoreSaved()
        stale.onForeground()
        assertEquals(2, stored.size)
    }

    @Test
    fun foregroundAfterBackgroundRefusalReconnectsSilently() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val page = FakePage()
        val vault = savedVault(server)
        val controller = controller(server, page, vault, mutableListOf(), storedLogin(), lastSync = now)
        controller.restoreSaved()
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
        // Le worker a trouvé le jeton refusé pendant que l’app était en arrière-plan.
        vault.markNeedsRelogin()
        controller.onForeground()
        assertNull(vault.credentials)
        assertEquals(pageUrl, page.loaded.single())
        assertTrue(controller.uiState.value.reconnecting)
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
    }

    @Test
    fun skippingSilentReconnectionOpensOfflineAndAllowsRetry() {
        val page = FakePage()
        val controller = controller(FakePronoteServer(token = "t"), page, FakeVault(), mutableListOf(), storedLogin())
        controller.start()
        controller.skipResume()
        assertEquals(AccessPhase.OFFLINE, controller.uiState.value.access)
        assertFalse(controller.uiState.value.browserStarted)
        controller.retry()
        assertEquals(2, page.loaded.size)
    }

    @Test
    fun backgroundRefusalWithoutCredentialsShowsSignInAtLaunch() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val vault = savedVault(server).apply { markNeedsRelogin() }
        val controller = controller(server, FakePage(), vault, mutableListOf())
        assertEquals(AccessPhase.SIGNED_OUT, controller.uiState.value.access)
        assertNull(vault.credentials)
    }

    // region Revue : jeton, erreur 25, mot de passe enregistré, MFA, reconnexions silencieuses

    /** Point 1 : « Ouvrir sans attendre » pendant la connexion par jeton garde le jeton renouvelé. */
    @Test
    fun skipResumeDuringTokenLoginKeepsRenewedToken() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val vault = savedVault(server)
        val controller = controller(server, FakePage(), vault, mutableListOf())
        server.beforeRequest = { if (it == "ParametresUtilisateur") controller.skipResume() }
        controller.restoreSaved()
        assertEquals("jeton-rotation-1", vault.credentials?.password)
        assertEquals(AccessPhase.OFFLINE, controller.uiState.value.access)
        // Le jeton enregistré est bien le dernier émis par Pronote : la reprise suivante réussit.
        server.beforeRequest = {}
        controller.retry()
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
        assertEquals("jeton-rotation-2", vault.credentials?.password)
    }

    /** Point 1 : fin du ViewModel (dispose) pendant la reconnexion par jeton. */
    @Test
    fun disposeDuringTokenLoginKeepsRenewedToken() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val vault = savedVault(server)
        val controller = controller(server, FakePage(), vault, mutableListOf())
        server.beforeRequest = { if (it == "ParametresUtilisateur") controller.dispose() }
        controller.restoreSaved()
        assertEquals("jeton-rotation-1", vault.credentials?.password)
    }

    /** Point 2 : erreur 25, plus aucun essai automatique ni manuel pendant une heure, message clair. */
    @Test
    fun tooManyAttemptsStopsAutomaticRetriesForAnHour() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        server.failures["Identification"] = 25
        val vault = savedVault(server)
        val throttle = MemoryThrottle()
        var clock = now
        val controller = controller(server, FakePage(), vault, mutableListOf(), throttle = throttle, clock = { clock })
        controller.start()
        assertEquals(now.plusSeconds(3600), throttle.blockedUntil())
        val state = controller.uiState.value
        assertEquals(AccessPhase.OFFLINE, state.access)
        assertEquals(PronoteThrottle.BLOCKED_MESSAGE, state.message)
        assertEquals("jeton-enregistre", vault.credentials?.password)
        val calls = server.calls.size
        controller.onForeground()
        controller.retry()
        assertEquals(calls, server.calls.size)
        assertEquals(PronoteThrottle.BLOCKED_MESSAGE, controller.uiState.value.message)
        // Une heure plus tard, la reprise redevient possible.
        clock = now.plusSeconds(3601)
        controller.retry()
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)

        // Au lancement suivant, le blocage mémorisé est respecté sans aucune requête.
        val other = FakePronoteServer(token = "jeton-enregistre")
        val blocked = MemoryThrottle().apply { block(now.plusSeconds(1800)) }
        val next = controller(other, FakePage(), savedVault(other), mutableListOf(), throttle = blocked)
        next.start()
        assertTrue(other.calls.isEmpty())
        assertEquals(AccessPhase.OFFLINE, next.uiState.value.access)
    }

    /** Point 3 : le mot de passe enregistré est prérempli mais jamais révélable, ni conservé partiellement. */
    @Test
    fun storedPasswordCanNeverBeRevealed() {
        val controller = controller(FakePronoteServer(token = "t"), FakePage(), FakeVault(), mutableListOf(), storedLogin())
        assertTrue(controller.uiState.value.form.passwordFromStore)
        // Saisie à la suite du mot de passe enregistré : seule la saisie reste.
        controller.onPasswordChange("mot-de-passe-enregistreX")
        assertEquals("X", controller.uiState.value.form.password)
        assertFalse(controller.uiState.value.form.passwordFromStore)

        val other = controller(FakePronoteServer(token = "t"), FakePage(), FakeVault(), mutableListOf(), storedLogin())
        // Effacement d’un caractère : rien du mot de passe enregistré n’est gardé.
        other.onPasswordChange("mot-de-passe-enregistr")
        assertEquals("", other.uiState.value.form.password)
        // Saisie libre ensuite (œil autorisé).
        other.onPasswordChange("nouveau")
        assertEquals("nouveau", other.uiState.value.form.password)

        assertEquals(LoginForm(password = "abc", passwordFromStore = true).editPassword("abcd").password, "d")
        val text = LoginForm(username = "eleve.test", password = "secret").toString()
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("eleve.test"))
        assertFalse(controller.uiState.value.toString().contains("mot-de-passe"))
    }

    /** Point 4 : vérification exigée par l’API après le QR : coffre vidé et page officielle affichée. */
    @Test
    fun apiMfaAfterQrClearsVaultAndRevealsPage() {
        val server = FakePronoteServer(token = "jeton-qr").apply { doubleAuth = 3 }
        val page = FakePage()
        val vault = FakeVault()
        val controller = controller(server, page, vault, mutableListOf())
        val nonce = submit(controller, page)
        send(controller, message("authenticated", nonce))
        send(controller, qr(nonce))
        assertNull(vault.credentials)
        val state = controller.uiState.value
        assertTrue(state.showBrowser)
        assertTrue(state.browserStarted)
        // Automatisation réarmée (nouveau nonce) pour continuer après la vérification.
        assertNotEquals(nonce, page.config().getString("nonce"))
    }

    /** Point 5 : au plus une reconnexion silencieuse automatique par heure. */
    @Test
    fun silentReconnectionIsLimitedToOnePerHour() {
        val server = FakePronoteServer(token = "jeton-valide")
        val page = FakePage()
        val throttle = MemoryThrottle().apply { recordSilentAttempt(now.minusSeconds(1200)) }
        val controller = controller(server, page, savedVault(server, token = "jeton-perime"), mutableListOf(), storedLogin(), throttle = throttle)
        controller.start()
        assertTrue(page.loaded.isEmpty())
        // Comme iOS : hors ligne sur les données locales, pas l’écran de connexion ; « Réessayer » reste possible.
        assertEquals(AccessPhase.OFFLINE, controller.uiState.value.access)
        assertTrue(controller.uiState.value.isError)
        controller.retry()
        assertEquals(pageUrl, page.loaded.single())

        val later = FakePage()
        val allowed = MemoryThrottle().apply { recordSilentAttempt(now.minusSeconds(3700)) }
        val second = controller(server, later, savedVault(server, token = "jeton-perime"), mutableListOf(), storedLogin(), throttle = allowed)
        second.start()
        assertEquals(pageUrl, later.loaded.single())
        assertEquals(now, allowed.lastSilentAttempt())
    }

    /** Point 6 : LOGIN_STALLED révèle la page sans effacer le mot de passe enregistré. */
    @Test
    fun loginStalledRevealsPageAndKeepsStoredPassword() {
        val server = FakePronoteServer(token = "jeton-valide")
        val page = FakePage()
        val store = storedLogin()
        val controller = controller(server, page, savedVault(server, token = "jeton-perime"), mutableListOf(), store)
        controller.start()
        controller.onPageFinished(pageUrl)
        send(controller, message("error", page.config().getString("nonce")) { put("code", "LOGIN_STALLED") })
        assertTrue(controller.uiState.value.showBrowser)
        assertEquals("mot-de-passe-enregistre", store.value?.password)
    }

    /** Point 9 : la configuration n’est posée que sur l’origine attendue, dans le cadre principal. */
    @Test
    fun injectionIsGuardedByOriginAndTopFrame() {
        val page = FakePage()
        val controller = controller(FakePronoteServer(token = "t"), page, FakeVault(), mutableListOf())
        submit(controller, page)
        val script = page.scripts.first { it.contains("__JM_CONFIG=") }
        assertTrue(script.startsWith("if (location.origin === \"$origin\" && window.top === window) { window.__JM_CONFIG="))
    }

    /** Points 11 et 12 : QR lié à l’origine de la tentative, UUID d’application stable. */
    @Test
    fun qrMustMatchAttemptOriginAndUsesStableUuid() {
        val server = FakePronoteServer(token = "jeton-qr")
        val page = FakePage()
        val vault = FakeVault()
        val controller = controller(server, page, vault, mutableListOf())
        var nonce = submit(controller, page)
        send(controller, message("authenticated", nonce))
        send(controller, qr(nonce))
        assertEquals(APP_UUID, vault.credentials?.uuid)

        val foreign = FakePronoteServer(token = "jeton-qr").apply { qrOrigin = "https://autre.index-education.net" }
        val otherPage = FakePage()
        val otherVault = FakeVault()
        val other = controller(foreign, otherPage, otherVault, mutableListOf())
        nonce = submit(other, otherPage)
        send(other, message("authenticated", nonce))
        send(other, qr(nonce))
        assertNull(otherVault.credentials)
        assertTrue(foreign.calls.isEmpty())
        assertTrue(other.uiState.value.isError)
    }

    // endregion

    // region Expiration de session (protocol-reference.md, « Session expiry »)

    private fun connected(server: FakePronoteServer, clock: () -> Instant = { now }, throttle: PronoteThrottle = MemoryThrottle()): Pair<PronoteConnectionController, FakeVault> {
        val vault = savedVault(server)
        val controller = controller(server, FakePage(), vault, mutableListOf(), throttle = throttle, clock = clock)
        controller.restoreSaved()
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
        assertEquals(1, server.calls.count { it == "Authentification" })
        return controller to vault
    }

    @Test
    fun errorEightReconnectsByTokenThenSucceeds() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val (controller, vault) = connected(server)
        server.failures["PageEmploiDuTemps"] = 8
        controller.refresh()
        val state = controller.uiState.value
        assertFalse(state.isError)
        assertEquals("Pronote connecté", state.title)
        assertEquals(2, server.calls.count { it == "Authentification" })
        assertEquals("jeton-rotation-2", vault.credentials?.password)
    }

    @Test
    fun errorEightTwiceShowsMessageAfterOneReconnection() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val (controller, _) = connected(server)
        server.failureQueue["PageEmploiDuTemps"] = ArrayDeque(listOf(8, 8))
        controller.refresh()
        val state = controller.uiState.value
        assertTrue(state.isError)
        assertEquals("Synchronisation impossible", state.title)
        assertEquals("Lecture de l’emploi du temps impossible. Réessaie plus tard.", state.message)
        assertEquals(2, server.calls.count { it == "Authentification" })
    }

    @Test
    fun errorTwentyFiveNeverReconnects() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val throttle = MemoryThrottle()
        val (controller, _) = connected(server, throttle = throttle)
        server.failures["PageEmploiDuTemps"] = 25
        controller.refresh()
        assertEquals(1, server.calls.count { it == "Authentification" })
        assertEquals(PronoteThrottle.BLOCKED_MESSAGE, controller.uiState.value.message)
        assertTrue(throttle.isBlocked(now))
    }

    @Test
    fun idleSessionReconnectsBeforeCallingWithoutError() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        var clock = now
        val (controller, _) = connected(server, clock = { clock })
        clock = now.plusSeconds(5 * 60)
        controller.refresh()
        assertEquals(1, server.calls.count { it == "Authentification" })
        clock = now.plusSeconds(5 * 60 + 11 * 60)
        val timetableCalls = server.calls.count { it == "PageEmploiDuTemps" }
        controller.refresh()
        assertEquals(2, server.calls.count { it == "Authentification" })
        // Aucune requête de lecture n’est partie sur la session inactive avant la reconnexion.
        val lastAuth = server.calls.lastIndexOf("Authentification")
        assertEquals(timetableCalls, server.calls.subList(0, lastAuth).count { it == "PageEmploiDuTemps" })
        assertFalse(controller.uiState.value.isError)
    }

    @Test
    fun htmlResponseIsTreatedAsLostSession() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val (controller, _) = connected(server)
        server.htmlOnce += "PageEmploiDuTemps"
        controller.refresh()
        assertFalse(controller.uiState.value.isError)
        assertEquals(2, server.calls.count { it == "Authentification" })
    }

    @Test
    fun notebookAndChildChangeFollowTheSameRule() = kotlinx.coroutines.runBlocking {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val (controller, _) = connected(server)
        server.failures["PageCahierDeTexte"] = 8
        val read = controller.readNotebook()
        assertTrue(read.snapshot.sessions.isEmpty())
        assertEquals(2, server.calls.count { it == "Authentification" })

        val parent = FakePronoteServer(token = "jeton-enregistre", parent = true)
        val (other, vault) = connected(parent)
        parent.failures["ParametresUtilisateur"] = 8
        other.selectChild("enfant-b")
        assertFalse(other.uiState.value.isError)
        assertEquals("enfant-b", vault.child)
        assertEquals("enfant-b", other.uiState.value.account?.selectedChildId)
        assertEquals(2, parent.calls.count { it == "Authentification" })
    }

    /** Pièce jointe après inactivité : reconnexion, relecture du cahier (identifiants liés à la session), puis GET. */
    @Test
    fun idleDownloadReconnectsAndRereadsNotebookFirst() = kotlinx.coroutines.runBlocking {
        val server = FakePronoteServer(token = "jeton-enregistre")
        server.notebookResponses[89] = JSONObject("""{"ListeCahierDeTextes":{"V":[{"N":"cdt-1","Date":{"V":"16/11/2026"},
            "Matiere":{"V":{"L":"MATHS"}},"listeContenus":{"V":[{"L":"Cours","ListePieceJointe":{"V":[{"N":"pj-1","L":"a.pdf","G":1}]}}]}}]}}""")
        var clock = now
        val files = mutableListOf<String>()
        val vault = savedVault(server)
        val controller = PronoteConnectionController(
            scope = scope, io = kotlinx.coroutines.Dispatchers.Unconfined, page = FakePage(), vault = vault, transport = server.transport,
            decodeQr = { server.qr("4321") }, automationScript = { "" }, today = { LocalDate.of(2026, 11, 17) }, storeLessons = {},
            now = { clock }, random = fixedPin, appUuid = { APP_UUID },
            files = fr.jmethod.app.pronote.FileTransport { url, _, _, target -> files += url.substringBefore("/FichiersExternes"); target.writeText("x"); 200 },
        )
        controller.restoreSaved()
        val read = controller.readNotebook()
        clock = now.plusSeconds(11 * 60)
        val target = java.nio.file.Files.createTempFile("pj", ".pdf").toFile()
        val reread = controller.downloadAttachment(read.stamp, "pj-1", "a.pdf", target)
        assertNotNull(reread)
        assertNotEquals(read.stamp, reread!!.stamp)
        assertEquals(2, server.calls.count { it == "Authentification" })
        // Contenus relus avec la nouvelle session avant le GET.
        assertEquals(2, server.notebookPayloads.count { it.getJSONObject("Signature").getInt("onglet") == 89 })
        assertEquals(1, files.size)
        target.delete()
        Unit
    }

    // endregion

    companion object {
        const val APP_UUID = "uuid-application-stable"
    }
}
