package fr.jmethod.app.pronote

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.Properties

/**
 * Coffre de secrets du système (Trousseau macOS, DPAPI Windows), par nom. Les valeurs ne sont
 * jamais journalisées ; une lecture impossible rend `null`.
 */
interface SecretStore {
    /** Faux si le système n’offre pas de coffre utilisable : rien de secret n’est alors enregistré. */
    val available: Boolean
    fun read(name: String): String?
    fun write(name: String, value: String)
    fun delete(name: String)
}

/** Aucun coffre (système non pris en charge) : rien n’est enregistré. */
object NoSecretStore : SecretStore {
    override val available = false
    override fun read(name: String): String? = null
    override fun write(name: String, value: String) {}
    override fun delete(name: String) {}
}

/**
 * « Rester connecté » sur ordinateur : établissement, type de compte, identifiant et mot de passe en
 * un seul secret JSON dans le coffre du système. Le mot de passe n’est jamais affiché.
 */
class SecretLoginStore(private val secrets: SecretStore) : LoginStore {
    @Synchronized
    override fun load(): SavedLogin? = try {
        secrets.read(LOGIN)?.let { text ->
            val raw = JSONObject(text)
            SavedLogin(
                school = raw.getString("school"),
                accountType = runCatching { AccountType.valueOf(raw.getString("accountType")) }.getOrDefault(AccountType.STUDENT),
                username = raw.optString("username").takeIf { raw.has("username") && !raw.isNull("username") },
                password = raw.optString("password").takeIf { raw.has("password") && !raw.isNull("password") },
                autoReconnect = raw.optBoolean("autoReconnect", true),
            )
        }
    } catch (error: Exception) {
        // Secret illisible : il est effacé ; une erreur passagère du coffre le garde.
        if (SealedData.isCorrupted(error)) secrets.delete(LOGIN)
        null
    }

    @Synchronized
    override fun save(login: SavedLogin) {
        val raw = JSONObject().put("school", login.school).put("accountType", login.accountType.name)
            .put("username", login.username ?: JSONObject.NULL).put("password", login.password ?: JSONObject.NULL)
            .put("autoReconnect", login.autoReconnect)
        // Coffre indisponible (trousseau verrouillé, session distante) : la connexion réussie n’échoue pas,
        // l’app ne reste simplement pas connectée au prochain lancement.
        runCatching { secrets.write(LOGIN, raw.toString()) }
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
        const val LOGIN = "pronote-login"
    }
}

/**
 * Session de la connexion directe : identifiants de la session en cours gardés en mémoire seulement
 * (aucun jeton mobile n’existe sur ordinateur). L’enfant suivi d’un compte parent est conservé dans le
 * coffre du système d’un lancement à l’autre.
 */
class MemoryVault(private val secrets: SecretStore = NoSecretStore) : SessionVault {
    @Volatile private var credentials: Credentials? = null
    @Volatile private var relogin = false

    @Synchronized override fun load(): Credentials? = credentials

    @Synchronized
    override fun save(credentials: Credentials) {
        this.credentials = credentials
        relogin = false
    }

    @Synchronized override fun selectedChildId(): String? = runCatching { secrets.read(CHILD) }.getOrNull()

    @Synchronized
    override fun saveSelectedChildId(childId: String?) {
        runCatching { if (childId == null) secrets.delete(CHILD) else secrets.write(CHILD, childId) }
    }

    @Synchronized override fun needsRelogin(): Boolean = relogin

    @Synchronized
    override fun markNeedsRelogin() {
        relogin = true
    }

    @Synchronized
    override fun clear() {
        credentials = null
        relogin = false
        runCatching { secrets.delete(CHILD) }
    }

    private companion object {
        const val CHILD = "pronote-child"
    }
}

/** Erreur 25 et reconnexions silencieuses : horodatages non secrets dans un fichier du dossier de l’app. */
class FileThrottle(private val file: File) : PronoteThrottle {
    private fun read(key: String): Instant? = runCatching {
        if (!file.isFile) return null
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        properties.getProperty(key)?.toLongOrNull()?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
    }.getOrNull()

    private fun write(key: String, value: Instant) {
        runCatching {
            val properties = Properties()
            if (file.isFile) file.inputStream().use { properties.load(it) }
            properties.setProperty(key, value.toEpochMilli().toString())
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.outputStream().use { properties.store(it, null) }
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        }
    }

    @Synchronized override fun blockedUntil() = read(BLOCKED)
    @Synchronized override fun block(until: Instant) = write(BLOCKED, until)
    @Synchronized override fun lastSilentAttempt() = read(SILENT)
    @Synchronized override fun recordSilentAttempt(at: Instant) = write(SILENT, at)

    private companion object {
        const val BLOCKED = "blockedUntil"
        const val SILENT = "lastSilent"
    }
}

/** Page officielle absente : la connexion directe n’en utilise pas. */
object NoOfficialPage : OfficialPage {
    override val currentUrl: String? = null
    override fun load(url: String) {}
    override fun evaluate(script: String) {}
    override fun release() {}
    override fun clearBrowsingData() {}
}
