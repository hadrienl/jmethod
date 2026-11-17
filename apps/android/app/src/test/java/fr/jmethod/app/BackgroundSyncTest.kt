package fr.jmethod.app

import fr.jmethod.app.pronote.BackgroundSync
import fr.jmethod.app.pronote.Credentials
import fr.jmethod.app.pronote.Lesson
import fr.jmethod.app.pronote.PronoteSessionGuard
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Synchronisation d’arrière-plan (logique du worker) : jeton par HTTPS natif uniquement. */
class BackgroundSyncTest {
    private fun vault(server: FakePronoteServer, token: String) = ConnectionControllerTest.FakeVault().apply {
        credentials = Credentials("${server.origin}/pronote/mobile.eleve.html?fd=1", server.username, token, "uuid")
    }

    private fun sync(server: FakePronoteServer, vault: ConnectionControllerTest.FakeVault, guard: PronoteSessionGuard, stored: MutableList<List<Lesson>>) =
        BackgroundSync(vault, guard, server.transport, Dispatchers.Unconfined, { LocalDate.of(2026, 11, 17) }, { stored += it })

    @Test
    fun renewsTokenAndStoresFourteenDays() = runBlocking {
        val server = FakePronoteServer(token = "jeton-enregistre").lesson("c1", "18/11/2026 08:30:00", "MATHEMATIQUES")
        val vault = vault(server, "jeton-enregistre")
        val stored = mutableListOf<List<Lesson>>()
        assertEquals(BackgroundSync.Result.SYNCED, sync(server, vault, PronoteSessionGuard(), stored).run())
        assertEquals("jeton-rotation-1", vault.credentials?.password)
        assertEquals("MATHEMATIQUES", stored.single().single().subject)
        assertTrue("aucune page officielle ni identifiant", server.calls.none { it == "PageInfosPerso" })
    }

    @Test
    fun refusedTokenIsOnlyMarked() = runBlocking {
        val server = FakePronoteServer(token = "jeton-valide")
        val vault = vault(server, "jeton-perime")
        val stored = mutableListOf<List<Lesson>>()
        assertEquals(BackgroundSync.Result.TOKEN_REFUSED, sync(server, vault, PronoteSessionGuard(), stored).run())
        assertTrue(vault.relogin)
        assertTrue(stored.isEmpty())
        assertEquals(1, server.calls.count { it == "Authentification" })
        // Déjà marqué : rien n’est retenté.
        assertEquals(BackgroundSync.Result.TOKEN_REFUSED, sync(server, vault, PronoteSessionGuard(), stored).run())
        assertEquals(1, server.calls.count { it == "Authentification" })
    }

    @Test
    fun networkErrorAsksForRetryAndKeepsToken() = runBlocking {
        val server = FakePronoteServer(token = "jeton-enregistre").apply { offline = true }
        val vault = vault(server, "jeton-enregistre")
        assertEquals(BackgroundSync.Result.RETRY, sync(server, vault, PronoteSessionGuard(), mutableListOf()).run())
        assertEquals("jeton-enregistre", vault.credentials?.password)
        assertFalse(vault.relogin)
    }

    @Test
    fun noSessionDoesNothing() = runBlocking {
        val server = FakePronoteServer(token = "t")
        val vault = ConnectionControllerTest.FakeVault()
        assertEquals(BackgroundSync.Result.NO_SESSION, sync(server, vault, PronoteSessionGuard(), mutableListOf()).run())
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun stoppedWorkerStillPersistsRenewedToken() = runBlocking {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val vault = vault(server, "jeton-enregistre")
        var job: Job? = null
        // Le worker est arrêté pendant l’authentification, juste avant que Pronote renouvelle le jeton.
        server.beforeRequest = { if (it == "Authentification") job?.cancel() }
        job = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
            sync(server, vault, PronoteSessionGuard(), mutableListOf()).run()
        }
        job.start()
        job.join()
        assertEquals("jeton-rotation-1", vault.credentials?.password)
    }

    @Test
    fun disconnectDuringBackgroundSyncWins() = runBlocking {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val vault = vault(server, "jeton-enregistre")
        val guard = PronoteSessionGuard()
        val stored = mutableListOf<List<Lesson>>()
        server.beforeRequest = {
            if (it == "Authentification") synchronized(guard.vaultLock) {
                guard.epoch.incrementAndGet()
                vault.clear()
            }
        }
        assertEquals(BackgroundSync.Result.NO_SESSION, sync(server, vault, guard, stored).run())
        assertNull(vault.credentials)
        assertTrue(stored.isEmpty())
    }
}
