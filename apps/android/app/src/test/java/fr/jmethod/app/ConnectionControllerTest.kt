package fr.jmethod.app

import fr.jmethod.app.pronote.Credentials
import fr.jmethod.app.pronote.Lesson
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.time.LocalDate

/** Flux de connexion sans WebView : origines du bridge, réclamation du QR, annulation et déconnexion. */
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
        fun config(): JSONObject = JSONObject(scripts.last { it.startsWith("window.__JM_CONFIG=") }.removePrefix("window.__JM_CONFIG=").substringBefore(";\n"))
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fixedPin = object : SecureRandom() {
        override fun nextInt(bound: Int) = 4321
    }

    @After
    fun tearDown() = scope.cancel()

    private fun controller(server: FakePronoteServer, page: FakePage, vault: FakeVault, stored: MutableList<List<Lesson>>) =
        PronoteConnectionController(
            scope = scope,
            io = Dispatchers.Unconfined,
            page = page,
            vault = vault,
            transport = server.transport,
            decodeQr = { server.qr("4321") },
            automationScript = { "/* script */" },
            today = { LocalDate.of(2026, 11, 17) },
            storeLessons = { stored += it },
            random = fixedPin,
        )

    private val pageUrl = "https://school.index-education.net/pronote/eleve.html"

    private fun message(type: String, nonce: String, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("version", 1).put("nonce", nonce).put("type", type)
            .put("origin", "https://school.index-education.net").put("url", pageUrl).apply(extra).toString()

    private fun startAndAuthenticate(controller: PronoteConnectionController, page: FakePage): String {
        controller.onAddressChange("school.index-education.net")
        controller.onUsernameChange("eleve.test")
        controller.onPasswordChange("mot-de-passe")
        controller.submitCredentials()
        assertEquals("", controller.uiState.value.form.password)
        assertEquals(pageUrl, page.loaded.single())
        controller.onPageStarted()
        controller.onPageFinished(pageUrl)
        val config = page.config()
        // Identifiants transmis une seule fois, à la première injection sur la page de confiance.
        assertEquals("mot-de-passe", config.getJSONObject("credentials").getString("password"))
        assertEquals("4321", config.getString("pin"))
        val nonce = config.getString("nonce")
        assertTrue(nonce.length >= 16)
        controller.onBridgeMessage(message("authenticated", nonce), "https://school.index-education.net", true)
        assertTrue(controller.uiState.value.busy)
        assertFalse(controller.uiState.value.showBrowser)
        return nonce
    }

    @Test
    fun fullQrFlowStoresRotatedTokenAndLessons() {
        val server = FakePronoteServer(token = "jeton-qr").lesson("c1", "18/11/2026 08:30:00", "MATHEMATIQUES", end = "18/11/2026 09:30:00")
        val page = FakePage()
        val vault = FakeVault()
        val stored = mutableListOf<List<Lesson>>()
        val controller = controller(server, page, vault, stored)
        val nonce = startAndAuthenticate(controller, page)

        // Rejeu de l’injection : les identifiants ne sont plus jamais renvoyés.
        controller.onPageStarted()
        controller.onPageFinished(pageUrl)
        assertFalse(page.config().has("credentials"))

        val qr = message("qr", nonce) { put("pin", "4321").put("image", "data:image/png;base64,AAAA") }
        controller.onBridgeMessage(qr, "https://school.index-education.net", true)
        controller.onBridgeMessage(qr, "https://school.index-education.net", true)

        assertEquals(1, server.calls.count { it == "Authentification" })
        assertEquals("jeton-rotation-1", vault.credentials?.password)
        assertEquals(1, stored.size)
        assertEquals("MATHEMATIQUES", stored.single().single().subject)
        val state = controller.uiState.value
        assertEquals("Élève Test", state.account?.name)
        assertFalse(state.browserStarted)
        assertTrue(page.scripts.contains("window.__JM_PRONOTE_POC?.stop()"))
    }

    @Test
    fun bridgeRejectsForeignFramesOriginsNoncesAndPins() {
        val server = FakePronoteServer(token = "jeton-qr")
        val page = FakePage()
        val controller = controller(server, page, FakeVault(), mutableListOf())
        val nonce = startAndAuthenticate(controller, page)
        val qr = { n: String, pin: String -> message("qr", n) { put("pin", pin).put("image", "data:image/png;base64,AAAA") } }
        controller.onBridgeMessage(qr(nonce, "4321"), "https://school.index-education.net", false)
        controller.onBridgeMessage(qr(nonce, "4321"), "https://other.index-education.net", true)
        controller.onBridgeMessage(qr("autre-nonce-0000000", "4321"), "https://school.index-education.net", true)
        controller.onBridgeMessage(qr(nonce, "0000"), "https://school.index-education.net", true)
        page.currentUrl = "https://ent.example.test/login"
        controller.onBridgeMessage(qr(nonce, "4321"), "https://school.index-education.net", true)
        page.currentUrl = "https://school.index-education.net/pronote/mobile.eleve.html"
        controller.onBridgeMessage(qr(nonce, "4321"), "https://school.index-education.net", true)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun cancelDuringNativeLoginDiscardsTokenAndResult() {
        val server = FakePronoteServer(token = "jeton-qr")
        val page = FakePage()
        val vault = FakeVault()
        val stored = mutableListOf<List<Lesson>>()
        val controller = controller(server, page, vault, stored)
        val nonce = startAndAuthenticate(controller, page)
        server.beforeRequest = { function -> if (function == "Authentification") controller.cancel() }
        controller.onBridgeMessage(message("qr", nonce) { put("pin", "4321").put("image", "data:image/png;base64,AAAA") }, "https://school.index-education.net", true)
        assertTrue("l’authentification a bien été tentée", server.calls.contains("Authentification"))
        assertNull(vault.credentials)
        assertTrue(stored.isEmpty())
        assertNull(controller.uiState.value.account)
        assertEquals("Connexion annulée", controller.uiState.value.title)
    }

    @Test
    fun disconnectDuringResyncPreventsLateTokenSave() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val page = FakePage()
        val vault = FakeVault().apply {
            credentials = Credentials("${server.origin}/pronote/mobile.eleve.html?fd=1", server.username, "jeton-enregistre", "uuid")
        }
        val stored = mutableListOf<List<Lesson>>()
        val controller = controller(server, page, vault, stored)
        assertTrue(controller.uiState.value.hasSavedSession)
        server.beforeRequest = { function -> if (function == "Authentification") controller.disconnect() }
        controller.restoreSaved()
        assertNull(vault.credentials)
        assertTrue(vault.saves.isEmpty())
        assertTrue(stored.isEmpty())
        assertFalse(controller.uiState.value.hasSavedSession)
        assertEquals(1, page.cleared)
    }

    @Test
    fun restoreAndRefreshKeepRotatingTokenAfterCancel() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val vault = FakeVault().apply {
            credentials = Credentials("${server.origin}/pronote/mobile.eleve.html?fd=1", server.username, "jeton-enregistre", "uuid")
        }
        val stored = mutableListOf<List<Lesson>>()
        val controller = controller(server, FakePage(), vault, stored)
        controller.restoreSaved()
        assertEquals("jeton-rotation-1", vault.credentials?.password)
        assertEquals(1, stored.size)
        // Une annulation ne doit pas condamner la session adoptée : la session expirée se reconnecte et le jeton est conservé.
        controller.cancel()
        server.failures["PageEmploiDuTemps"] = 10
        controller.refresh()
        assertEquals("jeton-rotation-2", vault.credentials?.password)
        assertEquals(2, stored.size)
        assertFalse(controller.uiState.value.isError)
    }

    @Test
    fun rejectedSavedTokenIsForgotten() {
        val server = FakePronoteServer(token = "jeton-valide")
        val vault = FakeVault().apply {
            credentials = Credentials("${server.origin}/pronote/mobile.eleve.html?fd=1", server.username, "jeton-perime", "uuid")
        }
        val controller = controller(server, FakePage(), vault, mutableListOf())
        controller.restoreSaved()
        assertNull(vault.credentials)
        assertEquals("Session expirée", controller.uiState.value.title)
    }

    @Test
    fun loginRejectedReturnsToNativeFormAndEntRevealsPage() {
        val server = FakePronoteServer(token = "jeton-qr")
        val page = FakePage()
        val controller = controller(server, page, FakeVault(), mutableListOf())
        controller.onAddressChange("school.index-education.net")
        controller.onUsernameChange("eleve.test")
        controller.onPasswordChange("faux")
        controller.submitCredentials()
        controller.onPageFinished(pageUrl)
        val nonce = page.config().getString("nonce")
        controller.onBridgeMessage(message("error", nonce) { put("code", "LOGIN_REJECTED") }, "https://school.index-education.net", true)
        assertEquals("Identifiants refusés", controller.uiState.value.title)
        assertFalse(controller.uiState.value.browserStarted)

        controller.onPasswordChange("secret")
        controller.submitCredentials()
        val injections = page.scripts.size
        controller.onPageFinished("https://ent.example.test/cas/login")
        assertEquals(injections, page.scripts.size)
        assertTrue(controller.uiState.value.showBrowser)
        assertEquals("Connexion par l’ENT", controller.uiState.value.title)
        // Retour sur Pronote après l’ENT : le mot de passe n’est plus transmis.
        controller.onPageStarted()
        controller.onPageFinished(pageUrl)
        assertFalse(page.config().has("credentials"))
    }
}
