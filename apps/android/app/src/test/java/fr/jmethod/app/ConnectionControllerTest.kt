package fr.jmethod.app

import fr.jmethod.app.pronote.AccessPhase
import fr.jmethod.app.pronote.AccountType
import fr.jmethod.app.pronote.Credentials
import fr.jmethod.app.pronote.Lesson
import fr.jmethod.app.pronote.LoginPreferences
import fr.jmethod.app.pronote.OfficialPage
import fr.jmethod.app.pronote.PronoteConnectionController
import fr.jmethod.app.pronote.SessionVault
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
        fun configs() = scripts.filter { it.startsWith("window.__JM_CONFIG=") }
            .map { JSONObject(it.removePrefix("window.__JM_CONFIG=").substringBefore(";\n")) }
        fun config(): JSONObject = configs().last()
    }

    private class FakeVault : SessionVault {
        var credentials: Credentials? = null
        var child: String? = null
        val saves = mutableListOf<String>()
        override fun load() = credentials
        override fun save(credentials: Credentials) { saves += credentials.password; this.credentials = credentials }
        override fun selectedChildId() = child
        override fun saveSelectedChildId(childId: String?) { child = childId }
        override fun clear() { credentials = null; child = null }
    }

    private class FakePreferences(var value: Pair<String, AccountType>? = null) : LoginPreferences {
        override fun load() = value
        override fun save(school: String, accountType: AccountType) { value = school to accountType }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fixedPin = object : SecureRandom() {
        override fun nextInt(bound: Int) = 4321
    }
    private val origin = "https://abcd1234.index-education.net"
    private val pageUrl = "$origin/pronote/eleve.html"
    private var cleared = 0

    @After
    fun tearDown() = scope.cancel()

    private fun controller(
        server: FakePronoteServer,
        page: FakePage,
        vault: FakeVault,
        stored: MutableList<List<Lesson>>,
        preferences: FakePreferences = FakePreferences(),
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
        loginPreferences = preferences,
        random = fixedPin,
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
        controller.onPasswordChange(password)
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
        val preferences = FakePreferences()
        val controller = controller(server, page, vault, stored, preferences)
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
        // Établissement et type mémorisés, jamais le mot de passe.
        assertEquals("ABCD1234" to AccountType.STUDENT, preferences.value)
    }

    @Test
    fun savedSchoolPrefillsTheForm() {
        val controller = controller(FakePronoteServer(token = "t"), FakePage(), FakeVault(), mutableListOf(), FakePreferences("abcd1234" to AccountType.PARENT))
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
}
