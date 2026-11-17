package fr.jmethod.app.pronote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stockage sécurisé de la session mobile Pronote. */
interface SessionVault {
    fun load(): Credentials?
    fun save(credentials: Credentials)
    fun selectedChildId(): String?
    fun saveSelectedChildId(childId: String?)
    fun clear()
}

/**
 * Jeton mobile chiffré AES-GCM par une clé Android Keystore non exportable. Les sauvegardes
 * Android sont désactivées dans le manifeste ; rien n’est journalisé.
 */
class TokenVault(context: Context) : SessionVault {
    private val prefs = context.applicationContext.getSharedPreferences("encrypted_session", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun seal(clear: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(clear.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun open(sealed: String): String {
        val blob = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        }
        return String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8)
    }

    @Synchronized
    override fun save(credentials: Credentials) {
        prefs.edit().putString(SESSION, seal(credentials.json().toString())).commit()
    }

    @Synchronized
    override fun load(): Credentials? = try {
        prefs.getString(SESSION, null)?.let { Credentials.fromJson(JSONObject(open(it))) }
    } catch (_: Exception) {
        clear()
        null
    }

    @Synchronized
    override fun selectedChildId(): String? = try {
        prefs.getString(CHILD, null)?.let { open(it) }
    } catch (_: Exception) {
        null
    }

    @Synchronized
    override fun saveSelectedChildId(childId: String?) {
        if (childId == null) prefs.edit().remove(CHILD).commit() else prefs.edit().putString(CHILD, seal(childId)).commit()
    }

    @Synchronized
    override fun clear() {
        prefs.edit().clear().commit()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(ALIAS)) deleteEntry(ALIAS)
            }
        }
    }

    private companion object {
        const val ALIAS = "fr.jmethod.app.pronote.token.v1"
        const val SESSION = "session"
        const val CHILD = "child"
    }
}
