package fr.jmethod.app.pronote

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import org.json.JSONException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.AEADBadTagException

/**
 * Limites des essais automatiques, communes au premier plan et à l’arrière-plan :
 * erreur Pronote 25 (trop de tentatives) et reconnexions silencieuses.
 */
interface PronoteThrottle {
    /** Aucune connexion automatique avant cet instant (erreur 25). */
    fun blockedUntil(): Instant?
    fun block(until: Instant)
    fun lastSilentAttempt(): Instant?
    fun recordSilentAttempt(at: Instant)

    fun isBlocked(now: Instant): Boolean = blockedUntil()?.isAfter(now) == true

    /** Au plus une reconnexion silencieuse automatique par heure. */
    fun silentAllowed(now: Instant): Boolean = lastSilentAttempt()?.let { Duration.between(it, now) >= SILENT_INTERVAL } ?: true

    companion object {
        /** Pronote bloque les connexions après trop d’essais : on attend au moins une heure. */
        val BLOCK_DURATION: Duration = Duration.ofHours(1)
        val SILENT_INTERVAL: Duration = Duration.ofHours(1)
        const val BLOCKED_CODE = "pronote-25"
        const val BLOCKED_MESSAGE = "Pronote a temporairement bloqué les connexions. Réessaie dans une heure."

        fun isBlockedError(error: Throwable?) = error is PronoteException && error.code == BLOCKED_CODE
    }
}

class MemoryThrottle : PronoteThrottle {
    @Volatile private var until: Instant? = null
    @Volatile private var silent: Instant? = null
    override fun blockedUntil() = until
    override fun block(until: Instant) { this.until = until }
    override fun lastSilentAttempt() = silent
    override fun recordSilentAttempt(at: Instant) { silent = at }
}

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

/** Lecture des données chiffrées : seules des données illisibles justifient de les effacer. */
object SealedData {
    /**
     * Vrai si l’erreur prouve que les données ou la clé sont inutilisables (étiquette AES-GCM invalide,
     * clé invalidée, Base64 ou JSON corrompus). Une erreur passagère du Keystore ne fait rien effacer.
     */
    fun isCorrupted(error: Throwable): Boolean = when (error) {
        is AEADBadTagException, is JSONException, is IllegalArgumentException -> true
        else -> isInvalidatedKey(error)
    }

    private fun isInvalidatedKey(error: Throwable): Boolean = try {
        error is KeyPermanentlyInvalidatedException
    } catch (_: Throwable) {
        false
    }
}

/**
 * Expiration de session (`protocol-reference.md`, « Session expiry ») : une seule règle pour la
 * synchronisation, le cahier de textes, le changement d’enfant, les pièces jointes et l’arrière-plan.
 */
object PronoteSessionRules {
    /** Comme pronotepy : au-delà de 10 minutes d’inactivité, reconnexion par jeton avant d’appeler. */
    val IDLE_LIMIT: Duration = Duration.ofMinutes(10)

    /** Codes d’erreur qui signifient une session perdue sur une session établie. */
    private val LOST = setOf("http", "format", "crypto", "compression")

    /**
     * Toute erreur Pronote (`Erreur.G`) sauf 25, un statut HTTP inattendu, une réponse non JSON ou
     * indéchiffrable : la session est perdue. Le réseau (IOException) et l’erreur 25 n’en sont pas.
     */
    fun isSessionLost(error: Throwable): Boolean = when (error) {
        is PronoteException -> (error.code.startsWith("pronote-") && error.code != PronoteThrottle.BLOCKED_CODE) || error.code in LOST
        is JSONException -> true
        else -> false
    }

    /**
     * Exécute [read] sur une session établie : reconnexion préventive si elle est inactive depuis plus
     * de 10 minutes, puis, si la session s’avère perdue, une seule reconnexion par jeton et un seul rejeu.
     * Un second échec est remonté tel quel.
     */
    fun <T> run(client: PronoteClient, now: Instant, relogin: () -> Unit, read: () -> T): T {
        if (client.isIdle(now)) relogin()
        return try {
            read()
        } catch (error: Exception) {
            if (!isSessionLost(error)) throw error
            relogin()
            read()
        }
    }
}
