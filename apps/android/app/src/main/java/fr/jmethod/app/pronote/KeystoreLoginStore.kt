package fr.jmethod.app.pronote

import android.content.Context
import org.json.JSONObject

/**
 * Chiffré AES-GCM par une clé Android Keystore dédiée, dans un fichier de préférences privé
 * exclu des sauvegardes (`allowBackup=false`, règles d’extraction vides).
 */
class KeystoreLoginStore(context: Context) : LoginStore {
    private val prefs = context.applicationContext.getSharedPreferences("encrypted_login", Context.MODE_PRIVATE)
    private val sealer = KeystoreSealer("fr.jmethod.app.pronote.login.v1")

    @Synchronized
    override fun load(): SavedLogin? = try {
        prefs.getString(LOGIN, null)?.let { sealed ->
            val raw = JSONObject(sealer.open(sealed))
            SavedLogin(
                school = raw.getString("school"),
                accountType = runCatching { AccountType.valueOf(raw.getString("accountType")) }.getOrDefault(AccountType.STUDENT),
                username = raw.optString("username").takeIf { raw.has("username") && !raw.isNull("username") },
                password = raw.optString("password").takeIf { raw.has("password") && !raw.isNull("password") },
                autoReconnect = raw.optBoolean("autoReconnect", true),
            )
        }
    } catch (error: Exception) {
        // Seules des données illisibles sont effacées ; une erreur passagère du Keystore les garde.
        if (SealedData.isCorrupted(error)) prefs.edit().clear().commit()
        null
    }

    @Synchronized
    override fun save(login: SavedLogin) {
        val raw = JSONObject().put("school", login.school).put("accountType", login.accountType.name)
            .put("username", login.username ?: JSONObject.NULL).put("password", login.password ?: JSONObject.NULL)
            .put("autoReconnect", login.autoReconnect)
        prefs.edit().putString(LOGIN, sealer.seal(raw.toString())).commit()
    }

    @Synchronized
    override fun forgetPassword() {
        load()?.let { save(it.copy(password = null)) }
    }

    @Synchronized
    override fun forgetCredentials() {
        load()?.let { save(it.copy(username = null, password = null)) }
    }

    private companion object {
        const val LOGIN = "login"
    }
}
