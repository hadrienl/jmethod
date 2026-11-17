package fr.jmethod.connectionpoc

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

class TokenVault(context: Context) {
    private val prefs=context.getSharedPreferences("encrypted_session",Context.MODE_PRIVATE)
    private val alias="jmethod.mobile.token.v1"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generateKey()
        }
    }
    fun save(credentials:Credentials) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val blob=cipher.iv+cipher.doFinal(credentials.json().toString().toByteArray())
        prefs.edit().putString("session",Base64.encodeToString(blob,Base64.NO_WRAP)).commit()
    }
    fun load():Credentials? = try {
        prefs.getString("session",null)?.let { value ->
            val blob=Base64.decode(value,Base64.NO_WRAP)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,blob.copyOfRange(0,12))) }
            Credentials.fromJson(JSONObject(String(cipher.doFinal(blob.copyOfRange(12,blob.size)))))
        }
    } catch (_:Exception) { clear();null }
    fun clear() { prefs.edit().clear().commit();KeyStore.getInstance("AndroidKeyStore").apply { load(null);if(containsAlias(alias))deleteEntry(alias) } }
}
