package fr.jmethod.app.pronote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Planification de la synchronisation d’arrière-plan (WorkManager dans l’app). */
interface BackgroundScheduler {
    fun schedule()
    fun cancel()
}

/**
 * Synchronisation d’arrière-plan : reconnexion par jeton en HTTPS natif (jamais de WebView ni
 * d’identifiants), enregistrement immédiat du jeton renouvelé, lecture des 14 prochains jours.
 * Un jeton refusé est seulement signalé pour une reconnexion au prochain premier plan.
 */
class BackgroundSync(
    private val vault: SessionVault,
    private val guard: PronoteSessionGuard,
    private val transport: PronoteTransport,
    private val io: CoroutineDispatcher,
    private val today: () -> LocalDate,
    private val storeLessons: suspend (List<Lesson>) -> Unit,
) {
    enum class Result { SYNCED, NO_SESSION, TOKEN_REFUSED, RETRY }

    suspend fun run(): Result = guard.mutex.withLock {
        val credentials = vault.load() ?: return@withLock Result.NO_SESSION
        if (vault.needsRelogin()) return@withLock Result.TOKEN_REFUSED
        val epoch = guard.epoch.get()
        val save: (Credentials) -> Unit = { renewed ->
            synchronized(guard.vaultLock) { if (epoch == guard.epoch.get()) vault.save(renewed) }
        }
        try {
            // Non annulable : un arrêt du worker n’interrompt pas l’échange en cours, et le jeton
            // renouvelé par Pronote est toujours enregistré avant la fin.
            val lessons = withContext(io + NonCancellable) {
                val client = PronoteClient(transport, credentials, save)
                client.login(PronoteClient.Mode.TOKEN)
                val child = vault.selectedChildId()
                if (child != null && client.children.any { it.id == child }) client.selectChild(child)
                client.timetable(today(), 14)
            }
            if (epoch != guard.epoch.get()) return@withLock Result.NO_SESSION
            storeLessons(lessons)
            Result.SYNCED
        } catch (error: PronoteException) {
            if (error.code in REFUSED) {
                synchronized(guard.vaultLock) { if (epoch == guard.epoch.get()) vault.markNeedsRelogin() }
                Result.TOKEN_REFUSED
            } else {
                Result.RETRY
            }
        } catch (_: Exception) {
            Result.RETRY
        }
    }

    companion object {
        /** Jeton refusé ou vérification de sécurité exigée : il faut une connexion au premier plan. */
        val REFUSED = setOf("login", "mfa")
    }
}
