package fr.jmethod.app.pronote

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.RSAPublicKeySpec
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives du protocole mobile (voir `native/shared/protocol-reference.md`) :
 * AES-128-CBC PKCS#7, clés MD5, DEFLATE brut niveau 6.
 */
object PronoteCrypto {
    val zeroIv = ByteArray(16)

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun sha256Upper(text: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(UTF_8))).uppercase()

    fun hex(data: ByteArray): String {
        val out = StringBuilder(data.size * 2)
        for (byte in data) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    fun unhex(text: String): ByteArray {
        if (text.length % 2 != 0 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            throw PronoteException("crypto", "Données de connexion invalides.")
        }
        return ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = try {
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
    } catch (_: Exception) {
        throw PronoteException("crypto", "Le QR code est expiré ou le code temporaire est incorrect.")
    }

    fun deflate(data: ByteArray): ByteArray {
        val compressor = Deflater(6, true)
        compressor.setInput(data)
        compressor.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            while (!compressor.finished()) {
                val count = compressor.deflate(buffer)
                out.write(buffer, 0, count)
            }
        } finally {
            compressor.end()
        }
        return out.toByteArray()
    }

    fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw PronoteException("compression", "Réponse Pronote incomplète.")
                }
                out.write(buffer, 0, count)
                if (out.size() > MAX_RESPONSE) throw PronoteException("size", "Réponse Pronote trop volumineuse.")
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    /** Chiffrement RSA PKCS#1 v1.5 de l’UUID quand la page l’exige (`http`). Clé publique Pronote. */
    fun rsa(data: ByteArray): ByteArray {
        val modulus = BigInteger(
            "130337874517286041778445012253514395801341480334668979416920989365464528904618150245388048105865059387076357492684573172203245221386376405947824377827224846860699130638566643129067735803555082190977267155957271492183684665050351182476506458843580431717209261903043895605014125081521285387341454154194253026277",
        )
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(65537)))
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(data)
        }
    }

    private const val HEX = "0123456789abcdef"
    private const val MAX_RESPONSE = 16 * 1024 * 1024
}
