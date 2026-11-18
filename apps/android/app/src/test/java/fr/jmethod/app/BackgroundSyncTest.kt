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
import java.time.Instant
import java.time.LocalDate
import fr.jmethod.app.pronote.MemoryThrottle
import fr.jmethod.app.pronote.PronoteThrottle

/** Synchronisation d’arrière-plan (logique du worker) : jeton par HTTPS natif uniquement. */
class BackgroundSyncTest {
    private fun vault(server: FakePronoteServer, token: String) = ConnectionControllerTest.FakeVault().apply {
        credentials = Credentials("${server.origin}/pronote/mobile.eleve.html?fd=1", server.username, token, "uuid")
    }

    private val now = Instant.parse("2026-11-17T19:00:00Z")

    private fun sync(
        server: FakePronoteServer,
        vault: ConnectionControllerTest.FakeVault,
        guard: PronoteSessionGuard,
        stored: MutableList<List<Lesson>>,
        throttle: PronoteThrottle = MemoryThrottle(),
        beforeStore: () -> Unit = {},
    ) = BackgroundSync(
        vault, guard, server.transport, Dispatchers.Unconfined, { LocalDate.of(2026, 11, 17) },
        { lessons, stillValid -> beforeStore(); if (stillValid()) stored += lessons },
        throttle, { now },
    )

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

    /** Point 8 : une déconnexion entre la lecture et l’écriture n’écrit plus les créneaux. */
    @Test
    fun disconnectJustBeforeStoringWritesNothing() = runBlocking {
        val server = FakePronoteServer(token = "jeton-enregistre").lesson("c1", "18/11/2026 08:30:00", "MATHEMATIQUES")
        val vault = vault(server, "jeton-enregistre")
        val guard = PronoteSessionGuard()
        val stored = mutableListOf<List<Lesson>>()
        val result = sync(server, vault, guard, stored, beforeStore = { guard.epoch.incrementAndGet() }).run()
        assertEquals(BackgroundSync.Result.NO_SESSION, result)
        assertTrue(stored.isEmpty())
    }

    /** Point 2 : erreur 25, aucun nouvel essai pendant une heure ; les autres erreurs ne relancent pas. */
    @Test
    fun tooManyAttemptsBlocksForAnHour() = runBlocking {
        val server = FakePronoteServer(token = "jeton-enregistre")
        server.failures["Identification"] = 25
        val vault = vault(server, "jeton-enregistre")
        val throttle = MemoryThrottle()
        assertEquals(BackgroundSync.Result.BLOCKED, sync(server, vault, PronoteSessionGuard(), mutableListOf(), throttle).run())
        assertEquals(now.plusSeconds(3600), throttle.blockedUntil())
        val calls = server.calls.size
        assertEquals(BackgroundSync.Result.BLOCKED, sync(server, vault, PronoteSessionGuard(), mutableListOf(), throttle).run())
        assertEquals(calls, server.calls.size)
        assertFalse(vault.relogin)

        val other = FakePronoteServer(token = "jeton-enregistre").apply { failures["Identification"] = 7 }
        assertEquals(BackgroundSync.Result.FAILED, sync(other, vault(other, "jeton-enregistre"), PronoteSessionGuard(), mutableListOf()).run())
    }

    /** Expiration de session : erreur 8 en arrière-plan ⇒ reconnexion par jeton puis synchronisation. */
    @Test
    fun expiredSessionErrorEightReconnectsThenSyncs() = runBlocking {
        val server = FakePronoteServer(token = "jeton-enregistre").lesson("c1", "18/11/2026 08:30:00", "MATHEMATIQUES")
        server.failures["PageEmploiDuTemps"] = 8
        val vault = vault(server, "jeton-enregistre")
        val stored = mutableListOf<List<Lesson>>()
        assertEquals(BackgroundSync.Result.SYNCED, sync(server, vault, PronoteSessionGuard(), stored).run())
        assertEquals(2, server.calls.count { it == "Authentification" })
        assertEquals("jeton-rotation-2", vault.credentials?.password)
        assertEquals("MATHEMATIQUES", stored.single().single().subject)
    }
}
