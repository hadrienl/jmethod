package fr.jmethod.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import fr.jmethod.app.pronote.NoSecretStore
import fr.jmethod.app.pronote.SecretStore
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Coffre du système courant : Trousseau sur macOS, DPAPI sur Windows, aucun ailleurs. */
fun systemSecretStore(dataDir: File, service: String): SecretStore {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return runCatching {
        when {
            os.contains("mac") -> MacKeychainStore(service)
            os.contains("win") -> WindowsDpapiStore(File(dataDir, "secrets"), service)
            else -> NoSecretStore
        }
    }.getOrDefault(NoSecretStore)
}

/**
 * Trousseau de session macOS (mots de passe génériques, service [service], compte = nom du secret),
 * par l’API Security. Les éléments ne sont pas synchronisés avec iCloud.
 */
class MacKeychainStore(private val service: String) : SecretStore {
    @Suppress("FunctionName")
    private interface Security : Library {
        fun SecKeychainAddGenericPassword(
            keychain: Pointer?, serviceLength: Int, service: ByteArray, accountLength: Int, account: ByteArray,
            passwordLength: Int, password: ByteArray, item: PointerByReference?,
        ): Int

        fun SecKeychainFindGenericPassword(
            keychain: Pointer?, serviceLength: Int, service: ByteArray, accountLength: Int, account: ByteArray,
            passwordLength: IntByReference?, password: PointerByReference?, item: PointerByReference?,
        ): Int

        fun SecKeychainItemModifyAttributesAndData(item: Pointer, attributes: Pointer?, length: Int, data: ByteArray): Int
        fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer?): Int
        fun SecKeychainItemDelete(item: Pointer): Int
    }

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFRelease(value: Pointer)
    }

    private val security: Security = Native.load("Security", Security::class.java)
    private val coreFoundation: CoreFoundation = Native.load("CoreFoundation", CoreFoundation::class.java)

    override val available = true

    @Synchronized
    override fun read(name: String): String? {
        val serviceBytes = service.toByteArray(UTF_8)
        val account = name.toByteArray(UTF_8)
        val length = IntByReference()
        val data = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(null, serviceBytes.size, serviceBytes, account.size, account, length, data, null)
        if (status == NOT_FOUND) return null
        if (status != 0) throw IllegalStateException("Trousseau indisponible ($status)")
        return try {
            String(data.value.getByteArray(0, length.value), UTF_8)
        } finally {
            security.SecKeychainItemFreeContent(null, data.value)
        }
    }

    @Synchronized
    override fun write(name: String, value: String) {
        val serviceBytes = service.toByteArray(UTF_8)
        val account = name.toByteArray(UTF_8)
        val secret = value.toByteArray(UTF_8)
        val item = PointerByReference()
        val found = security.SecKeychainFindGenericPassword(null, serviceBytes.size, serviceBytes, account.size, account, null, null, item)
        val status = if (found == 0) {
            try {
                security.SecKeychainItemModifyAttributesAndData(item.value, null, secret.size, secret)
            } finally {
                coreFoundation.CFRelease(item.value)
            }
        } else {
            security.SecKeychainAddGenericPassword(null, serviceBytes.size, serviceBytes, account.size, account, secret.size, secret, null)
        }
        secret.fill(0)
        if (status != 0) throw IllegalStateException("Trousseau indisponible ($status)")
    }

    @Synchronized
    override fun delete(name: String) {
        val serviceBytes = service.toByteArray(UTF_8)
        val account = name.toByteArray(UTF_8)
        val item = PointerByReference()
        val found = security.SecKeychainFindGenericPassword(null, serviceBytes.size, serviceBytes, account.size, account, null, null, item)
        if (found != 0) return
        try {
            security.SecKeychainItemDelete(item.value)
        } finally {
            coreFoundation.CFRelease(item.value)
        }
    }

    private companion object {
        /** errSecItemNotFound */
        const val NOT_FOUND = -25300
    }
}

/**
 * Secrets chiffrés par DPAPI (clé liée à la session Windows de l’utilisateur), un fichier par secret
 * dans le dossier de l’app. Illisibles depuis un autre compte ou un autre ordinateur.
 */
class WindowsDpapiStore(private val dir: File, service: String) : SecretStore {
    private val entropy = "fr.jmethod.desktop/$service".toByteArray(UTF_8)

    override val available = true

    private fun fileOf(name: String) = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".bin")

    @Synchronized
    override fun read(name: String): String? {
        val file = fileOf(name)
        if (!file.isFile) return null
        val clear = Crypt32Util.cryptUnprotectData(file.readBytes(), entropy, FLAGS, null)
        return try {
            String(clear, UTF_8)
        } finally {
            clear.fill(0)
        }
    }

    @Synchronized
    override fun write(name: String, value: String) {
        dir.mkdirs()
        val clear = value.toByteArray(UTF_8)
        val sealed = try {
            Crypt32Util.cryptProtectData(clear, entropy, FLAGS, "J-Method", null)
        } finally {
            clear.fill(0)
        }
        val target = fileOf(name)
        val temporary = File(dir, target.name + ".tmp")
        temporary.writeBytes(sealed)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized
    override fun delete(name: String) {
        fileOf(name).delete()
    }

    private companion object {
        /** CRYPTPROTECT_UI_FORBIDDEN : jamais de fenêtre système. */
        const val FLAGS = 0x1
    }
}
