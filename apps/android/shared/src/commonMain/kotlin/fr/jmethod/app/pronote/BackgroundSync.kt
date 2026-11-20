package fr.jmethod.app.pronote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
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
    /**
     * Enregistre les créneaux ; `stillValid` est réévalué dans la transformation du document,
     * pour qu’une déconnexion survenue entre-temps ne laisse jamais réécrire les créneaux effacés.
     */
    private val storeLessons: suspend (List<Lesson>, stillValid: () -> Boolean) -> Unit,
    private val throttle: PronoteThrottle = MemoryThrottle(),
    private val now: () -> Instant = { Instant.now() },
) {
    /**
     * [RETRY] seulement pour une erreur réseau ; [BLOCKED] : erreur 25, aucun essai pendant une heure ;
     * [FAILED] : autre erreur, on attend la prochaine période sans nouvel essai rapproché.
     */
    enum class Result { SYNCED, NO_SESSION, TOKEN_REFUSED, BLOCKED, FAILED, RETRY }

    suspend fun run(): Result = guard.mutex.withLock {
        if (throttle.isBlocked(now())) return@withLock Result.BLOCKED
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
                val client = PronoteClient(transport, credentials, save, now)
                client.login(PronoteClient.Mode.TOKEN)
                // Même règle qu’au premier plan : session perdue (erreur 8, réponse illisible…) ⇒ une
                // reconnexion par jeton avec le jeton le plus récent, puis un seul rejeu.
                PronoteSessionRules.run(client, now(), relogin = {
                    vault.load()?.let { client.useCredentials(it) }
                    client.login(PronoteClient.Mode.TOKEN)
                }) {
                    val child = vault.selectedChildId()
                    if (child != null && client.children.any { it.id == child }) client.selectChild(child)
                    client.timetable(today(), 14)
                }
            }
            if (epoch != guard.epoch.get()) return@withLock Result.NO_SESSION
            storeLessons(lessons) { epoch == guard.epoch.get() }
            if (epoch != guard.epoch.get()) Result.NO_SESSION else Result.SYNCED
        } catch (error: PronoteException) {
            when {
                PronoteThrottle.isBlockedError(error) -> {
                    throttle.block(now().plus(PronoteThrottle.BLOCK_DURATION))
                    Result.BLOCKED
                }
                error.code in REFUSED -> {
                    synchronized(guard.vaultLock) { if (epoch == guard.epoch.get()) vault.markNeedsRelogin() }
                    Result.TOKEN_REFUSED
                }
                else -> Result.FAILED
            }
        } catch (_: java.io.IOException) {
            Result.RETRY
        } catch (_: Exception) {
            Result.FAILED
        }
    }

    companion object {
        /** Jeton refusé ou vérification de sécurité exigée : il faut une connexion au premier plan. */
        val REFUSED = setOf("login", "mfa")
    }
}
