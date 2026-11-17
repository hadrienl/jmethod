package fr.jmethod.app.pronote

import android.content.Context
import org.json.JSONObject

/** Stockage sécurisé de la session mobile Pronote. */
interface SessionVault {
    fun load(): Credentials?
    /** Enregistre le jeton (et efface le marquage [needsRelogin]). */
    fun save(credentials: Credentials)
    fun selectedChildId(): String?
    fun saveSelectedChildId(childId: String?)
    /** Pronote a refusé le jeton en arrière-plan : une reconnexion est à faire au prochain premier plan. */
    fun needsRelogin(): Boolean
    fun markNeedsRelogin()
    fun clear()
}

/**
 * Jeton mobile chiffré AES-GCM par une clé Android Keystore. Les sauvegardes Android sont
 * désactivées dans le manifeste ; rien n’est journalisé.
 */
class TokenVault(context: Context) : SessionVault {
    private val prefs = context.applicationContext.getSharedPreferences("encrypted_session", Context.MODE_PRIVATE)
    private val sealer = KeystoreSealer("fr.jmethod.app.pronote.token.v1")

    @Synchronized
    override fun save(credentials: Credentials) {
        // Un jeton neuf remplace celui que l’arrière-plan avait trouvé refusé.
        prefs.edit().putString(SESSION, sealer.seal(credentials.json().toString())).remove(RELOGIN).commit()
    }

    @Synchronized
    override fun load(): Credentials? = try {
        prefs.getString(SESSION, null)?.let { Credentials.fromJson(JSONObject(sealer.open(it))) }
    } catch (error: Exception) {
        // Seules des données illisibles sont effacées ; une erreur passagère du Keystore garde la session.
        if (SealedData.isCorrupted(error)) clear()
        null
    }

    @Synchronized
    override fun selectedChildId(): String? = try {
        prefs.getString(CHILD, null)?.let { sealer.open(it) }
    } catch (_: Exception) {
        null
    }

    @Synchronized
    override fun saveSelectedChildId(childId: String?) {
        if (childId == null) prefs.edit().remove(CHILD).commit() else prefs.edit().putString(CHILD, sealer.seal(childId)).commit()
    }

    @Synchronized
    override fun needsRelogin(): Boolean = prefs.getBoolean(RELOGIN, false)

    @Synchronized
    override fun markNeedsRelogin() {
        prefs.edit().putBoolean(RELOGIN, true).commit()
    }

    @Synchronized
    override fun clear() {
        prefs.edit().clear().commit()
        sealer.deleteKey()
    }

    private companion object {
        const val SESSION = "session"
        const val CHILD = "child"
        const val RELOGIN = "relogin"
    }
}
