package fr.jmethod.app

import fr.jmethod.app.pronote.AccessPhase
import fr.jmethod.app.pronote.AccountType
import fr.jmethod.app.pronote.Credentials
import fr.jmethod.app.pronote.FileThrottle
import fr.jmethod.app.pronote.Lesson
import fr.jmethod.app.pronote.LoginFlow
import fr.jmethod.app.pronote.MemoryThrottle
import fr.jmethod.app.pronote.MemoryVault
import fr.jmethod.app.pronote.NoOfficialPage
import fr.jmethod.app.pronote.PronoteClient
import fr.jmethod.app.pronote.PronoteConnectionController
import fr.jmethod.app.pronote.PronoteThrottle
import fr.jmethod.app.pronote.SavedLogin
import fr.jmethod.app.pronote.SecretLoginStore
import fr.jmethod.app.pronote.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.LocalDate

/** Connexion directe de l’app ordinateur : identifiant et mot de passe par le protocole natif, sans page officielle. */
class DirectLoginTest {
    @get:Rule val folder = TemporaryFolder()

    private class MapSecrets : SecretStore {
        val values = mutableMapOf<String, String>()
        override val available = true
        override fun read(name: String) = values[name]
        override fun write(name: String, value: String) { values[name] = value }
        override fun delete(name: String) { values.remove(name) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val now: Instant = Instant.parse("2026-11-17T19:00:00Z")
    private val origin = "https://abcd1234.index-education.net"

    @After
    fun tearDown() = scope.cancel()

    private fun server(password: String = "secret") = FakePronoteServer(token = password).apply {
        plainWeb = true
        lesson("c1", "18/11/2026 08:00:00", "Mathématiques", end = "18/11/2026 09:00:00")
    }

    private fun controller(
        server: FakePronoteServer,
        secrets: MapSecrets = MapSecrets(),
        stored: MutableList<List<Lesson>> = mutableListOf(),
        throttle: PronoteThrottle = MemoryThrottle(),
        vault: MemoryVault = MemoryVault(secrets),
        clock: () -> Instant = { now },
    ) = PronoteConnectionController(
        scope = scope,
        io = Dispatchers.Unconfined,
        page = NoOfficialPage,
        vault = vault,
        transport = server.transport,
        decodeQr = { error("pas de QR") },
        automationScript = { "" },
        today = { LocalDate.of(2026, 11, 17) },
        storeLessons = { stored += it },
        loginStore = SecretLoginStore(secrets),
        now = clock,
        throttle = throttle,
        loginFlow = LoginFlow.DIRECT,
        device = "cet ordinateur",
    )

    private fun PronoteConnectionController.fill(school: String = "abcd1234", username: String = "eleve.test", password: String = "secret") {
        onSchoolChange(school)
        onUsernameChange(username)
        onPasswordChange(password)
    }

    @Test
    fun passwordModeUsesTheWebLoginPageWithoutMobileFlags() {
        val server = server()
        val saved = mutableListOf<Credentials>()
        val client = PronoteClient(server.transport, Credentials("$origin/pronote/eleve.html?login=true", "eleve.test", "secret", ""), { saved += it })
        client.login(PronoteClient.Mode.PASSWORD)
        assertEquals("$origin/pronote/eleve.html?login=true", server.pages.single())
        assertEquals(PronoteClient.DESKTOP_USER_AGENT, server.userAgents.single())
        val identification = server.identifications.single()
        assertFalse(identification.getBoolean("demandeConnexionAppliMobile"))
        assertFalse(identification.getBoolean("demandeConnexionAppliMobileJeton"))
        assertFalse(identification.getBoolean("enConnexionAppliMobile"))
        assertEquals("", identification.getString("uuidAppliMobile"))
        // Aucun jeton mobile : les identifiants de la session restent ceux saisis (en mémoire).
        assertEquals("secret", client.credentials.password)
        assertEquals(1, client.timetable(LocalDate.of(2026, 11, 17), 14).size)
        assertFalse(server.calls.contains("PageInfosPerso"))
    }

    @Test
    fun numericCaseInsensitiveLoginFlagLowersTheIdentifier() {
        val server = server().apply { modeCompLog = 1 }
        val client = PronoteClient(server.transport, Credentials("$origin/pronote/eleve.html?login=true", "Eleve.Test", "secret", ""))
        client.login(PronoteClient.Mode.PASSWORD)
        assertTrue(server.calls.contains("ParametresUtilisateur"))
    }

    @Test
    fun formLoginConnectsAndStaysConnectedInTheSystemStore() {
        val server = server()
        val secrets = MapSecrets()
        val stored = mutableListOf<List<Lesson>>()
        val controller = controller(server, secrets, stored)
        assertEquals(AccessPhase.SIGNED_OUT, controller.uiState.value.access)
        controller.fill()
        controller.submitCredentials()
        val state = controller.uiState.value
        assertEquals(AccessPhase.CONNECTED, state.access)
        assertFalse(state.browserStarted)
        assertEquals("", state.form.password)
        assertEquals(1, stored.single().size)
        assertEquals("$origin/pronote/eleve.html?login=true", server.pages.single())
        // « Rester connecté » : établissement, type, identifiant et mot de passe dans le coffre du système.
        val login = SecretLoginStore(secrets).load()!!
        assertEquals("abcd1234", login.school)
        assertEquals("eleve.test", login.username)
        assertEquals("secret", login.password)
        assertTrue(state.hasStoredPassword)
    }

    @Test
    fun withoutStayConnectedOnlyTheSchoolIsKept() {
        val server = server()
        val secrets = MapSecrets()
        val controller = controller(server, secrets)
        controller.fill()
        controller.onStayConnectedChange(false)
        controller.submitCredentials()
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
        val login = SecretLoginStore(secrets).load()!!
        assertNull(login.username)
        assertNull(login.password)
        assertFalse(controller.uiState.value.hasStoredPassword)
    }

    @Test
    fun rejectedPasswordShowsTheFormError() {
        val server = server(password = "autre")
        val controller = controller(server)
        controller.fill()
        controller.submitCredentials()
        val state = controller.uiState.value
        assertEquals(AccessPhase.SIGNED_OUT, state.access)
        assertEquals("Pronote a refusé l’identifiant ou le mot de passe. Utilise ceux de Pronote, pas ceux d’ÉduConnect, puis réessaie.", state.formError)
        assertFalse(state.busy)
    }

    @Test
    fun doubleAuthenticationIsReportedAsNotSupported() {
        val server = server().apply { doubleAuth = 3 }
        val controller = controller(server)
        controller.fill()
        controller.submitCredentials()
        val state = controller.uiState.value
        assertEquals(AccessPhase.SIGNED_OUT, state.access)
        assertTrue(state.formError!!.contains("double authentification"))
        assertFalse(server.calls.contains("PageEmploiDuTemps"))
    }

    @Test
    fun launchReconnectsWithStoredCredentialsEvenRightAfterAPreviousLaunch() {
        val server = server()
        val secrets = MapSecrets()
        SecretLoginStore(secrets).save(SavedLogin("abcd1234", AccountType.STUDENT, "eleve.test", "secret"))
        // Lancement précédent il y a cinq minutes : le plafond horaire ne bloque pas un lancement voulu.
        val throttle = MemoryThrottle().apply { recordSilentAttempt(now.minusSeconds(300)) }
        val controller = controller(server, secrets, throttle = throttle)
        assertEquals(AccessPhase.CHECKING, controller.uiState.value.access)
        controller.start()
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
        assertEquals(1, server.pages.size)
    }

    @Test
    fun errorTwentyFiveBlocksAutomaticLoginsForAnHour() {
        val server = server().apply { failures["Identification"] = 25 }
        val secrets = MapSecrets()
        SecretLoginStore(secrets).save(SavedLogin("abcd1234", AccountType.STUDENT, "eleve.test", "secret"))
        val throttle = FileThrottle(folder.root.resolve("throttle.properties"))
        controller(server, secrets, throttle = throttle).start()
        assertTrue(throttle.isBlocked(now.plusSeconds(3500)))
        // Lancement suivant (fichier relu) : aucune requête tant que le blocage dure.
        val again = server()
        val next = controller(again, secrets, throttle = FileThrottle(folder.root.resolve("throttle.properties")))
        next.start()
        assertTrue(again.pages.isEmpty())
        assertEquals(AccessPhase.OFFLINE, next.uiState.value.access)
    }

    @Test
    fun expiredSessionLogsInAgainWithThePasswordAndReplays() {
        val server = server()
        val stored = mutableListOf<List<Lesson>>()
        val controller = controller(server, stored = stored)
        controller.fill()
        controller.submitCredentials()
        server.failures["PageEmploiDuTemps"] = 8
        controller.refresh()
        assertEquals(AccessPhase.CONNECTED, controller.uiState.value.access)
        assertFalse(controller.uiState.value.isError)
        assertEquals(2, stored.size)
        assertEquals(2, server.pages.size)
        assertEquals(2, server.identifications.size)
        assertFalse(server.identifications.last().getBoolean("enConnexionAppliMobile"))
    }

    @Test
    fun disconnectForgetsTheSessionAndKeepsIdentifiersForPrefill() {
        val server = server()
        val secrets = MapSecrets()
        val vault = MemoryVault(secrets)
        val controller = controller(server, secrets, vault = vault)
        controller.fill()
        controller.submitCredentials()
        assertEquals("secret", vault.load()?.password)
        controller.disconnect()
        assertNull(vault.load())
        val state = controller.uiState.value
        assertEquals(AccessPhase.SIGNED_OUT, state.access)
        assertEquals("eleve.test", state.form.username)
        assertTrue(state.form.passwordFromStore)
        assertFalse(SecretLoginStore(secrets).load()!!.autoReconnect)
        controller.disconnect(forgetCredentials = true)
        assertNull(SecretLoginStore(secrets).load()!!.password)
    }
}
