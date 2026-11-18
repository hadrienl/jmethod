package fr.jmethod.app.pronote

import android.content.Context
import java.time.Instant
import java.util.UUID

/** Horodatages non secrets, conservés entre deux lancements (préférences privées, hors sauvegarde). */
class PrefsThrottle(context: Context) : PronoteThrottle {
    private val prefs = context.applicationContext.getSharedPreferences("pronote_throttle", Context.MODE_PRIVATE)
    private fun read(key: String) = prefs.getLong(key, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    @Synchronized override fun blockedUntil() = read(BLOCKED)
    @Synchronized override fun block(until: Instant) { prefs.edit().putLong(BLOCKED, until.toEpochMilli()).commit() }
    @Synchronized override fun lastSilentAttempt() = read(SILENT)
    @Synchronized override fun recordSilentAttempt(at: Instant) { prefs.edit().putLong(SILENT, at.toEpochMilli()).commit() }

    private companion object {
        const val BLOCKED = "blockedUntil"
        const val SILENT = "lastSilent"
    }
}

/**
 * UUID d’application mobile Pronote : généré une fois puis réutilisé pour toutes les connexions,
 * chiffré par une clé Keystore dédiée. Il survit à la déconnexion (identité de l’installation).
 */
class KeystoreAppIdentity(context: Context, private val seed: () -> String?) {
    private val prefs = context.applicationContext.getSharedPreferences("encrypted_identity", Context.MODE_PRIVATE)
    private val sealer = KeystoreSealer("fr.jmethod.app.pronote.uuid.v1")

    @Synchronized
    fun uuid(): String {
        val stored = prefs.getString(UUID_KEY, null)?.let { sealed ->
            try {
                sealer.open(sealed)
            } catch (error: Exception) {
                if (!SealedData.isCorrupted(error)) throw error
                null
            }
        }
        if (stored != null) return stored
        // Première utilisation : l’UUID de la session déjà enregistrée est repris pour rester cohérent.
        val created = seed() ?: UUID.randomUUID().toString()
        prefs.edit().putString(UUID_KEY, sealer.seal(created)).commit()
        return created
    }

    private companion object {
        const val UUID_KEY = "uuid"
    }
}
