package fr.jmethod.app.pronote

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Base64

data class HttpResult(val status: Int, val headers: Map<String, List<String>>, val body: String)

fun interface PronoteTransport {
    fun request(method: String, url: String, body: String?, headers: Map<String, String>): HttpResult
}

/** Taille maximale d’une réponse HTTP Pronote lue en mémoire. */
const val MAX_HTTP_BODY = 16 * 1024 * 1024

/** Lit au plus [max] octets en UTF-8 ; au-delà, la réponse est refusée sans être lue entièrement. */
fun readBounded(input: java.io.InputStream, max: Int): String {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (out.size() + read > max) throw PronoteException("size", "Réponse Pronote trop volumineuse.")
        out.write(buffer, 0, read)
    }
    return out.toString("UTF-8")
}

/** HTTPS natif, sans redirection automatique ni relâchement TLS. */
class NativeTransport : PronoteTransport {
    override fun request(method: String, url: String, body: String?, headers: Map<String, String>): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { readBounded(it, MAX_HTTP_BODY) }.orEmpty()
            HttpResult(status, connection.headerFields.filterKeys { it != null }, text)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Téléchargement d’une pièce jointe vers un fichier privé. L’URL n’est connue que de l’appel :
 * elle n’apparaît ni dans les exceptions ni dans les journaux.
 * @return le statut HTTP, ou 413 si la limite de taille est dépassée.
 */
fun interface FileTransport {
    fun download(url: String, headers: Map<String, String>, maxBytes: Long, target: java.io.File): Int
}

/** GET HTTPS natif, sans redirection, lecture en flux bornée. */
class NativeFileTransport : FileTransport {
    override fun download(url: String, headers: Map<String, String>, maxBytes: Long, target: java.io.File): Int {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            throw java.io.IOException("download")
        }
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            if (status != 200) return status
            val length = connection.contentLengthLong
            if (length > maxBytes) return 413
            var total = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            output.close()
                            target.delete()
                            return 413
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            return 200
        } catch (_: Exception) {
            target.delete()
            // Aucun détail : le message d’une exception réseau peut contenir l’URL.
            throw java.io.IOException("download")
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Client du protocole mobile Pronote, en lecture seule. Non thread-safe : les appels
 * doivent être sérialisés (le numéro d’ordre avance de deux à chaque requête).
 *
 * @param save appelé dès qu’un jeton renouvelé est reçu, avant toute autre lecture.
 */
class PronoteClient(
    private val transport: PronoteTransport,
    credentials: Credentials,
    private val save: (Credentials) -> Unit = {},
    /** Horloge du contrôle d’inactivité (injectée par les tests). */
    private val clock: () -> java.time.Instant = { java.time.Instant.now() },
) {
    enum class Mode { QR, TOKEN }

    var credentials: Credentials = credentials
        private set
    private var attributes = JSONObject()
    private var parameters = JSONObject()
    private var user = JSONObject()
    private var key = PronoteCrypto.md5(byteArrayOf())
    private var iv = PronoteCrypto.zeroIv
    private var order = 1
    private var compressed = false
    private var encrypted = false
    private val cookies = linkedMapOf<String, String>()
    /** Instant du dernier appel réussi ; `null` avant la première connexion. */
    @Volatile
    var lastCallAt: java.time.Instant? = null
        private set

    /** Session inactive depuis plus de [limit] (ou jamais établie) : reconnexion par jeton avant d’appeler. */
    fun isIdle(now: java.time.Instant, limit: java.time.Duration = PronoteSessionRules.IDLE_LIMIT): Boolean =
        lastCallAt?.let { java.time.Duration.between(it, now) > limit } ?: true
    private val origin = PronotePolicy.originOf(credentials.url)
    private val root = credentials.url.substringBefore('?').substringBeforeLast('/')

    var accountName = ""
        private set
    var children: List<Child> = emptyList()
        private set
    var selectedChildId: String? = null
        private set
    /** Onglets autorisés (`listeOnglets` aplati) ; `null` si Pronote ne les fournit pas. */
    var authorizedTabs: Set<Int>? = null
        private set
    /** Matière des cours de l’emploi du temps lu, par identifiant `N` (secours du cahier de textes). */
    private var lessonSubjects: Map<String, String> = emptyMap()

    /** Remplace les identifiants mobiles par les plus récents du coffre (jeton renouvelé ailleurs). */
    fun useCredentials(latest: Credentials) {
        credentials = latest
    }

    private fun cookieHeader() = cookies.entries.joinToString("; ") { it.key + "=" + it.value }

    private fun request(method: String, url: String, body: String? = null): HttpResult {
        if (!url.startsWith("$origin/")) throw PronoteException("origin", "Requête hors établissement refusée.")
        val headers = linkedMapOf("User-Agent" to USER_AGENT, "Accept" to if (method == "GET") "text/html" else "application/json")
        if (method == "POST") headers["Content-Type"] = "application/json"
        if (cookies.isNotEmpty()) headers["Cookie"] = cookieHeader()
        val response = transport.request(method, url, body, headers)
        response.headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().forEach { cookie ->
            val pair = cookie.substringBefore(';')
            val equal = pair.indexOf('=')
            if (equal > 0) cookies[pair.substring(0, equal).trim()] = pair.substring(equal + 1)
        }
        if (response.status !in 200..299) {
            throw PronoteException("http", "Pronote répond avec le statut ${response.status}. Réessaie dans quelques instants.")
        }
        return response
    }

    fun call(function: String, data: JSONObject? = null, tab: Int? = null, nextIv: ByteArray? = null): JSONObject {
        PronotePolicy.checkCall(function, tab, data)
        if (tab != null && function == "PageCahierDeTexte" && authorizedTabs?.contains(tab) == false) {
            throw PronoteException("notebook-unavailable", "Le cahier de textes n’est pas disponible pour ce compte.")
        }
        val payload = JSONObject()
        if (tab != null) {
            val signature = JSONObject().put("onglet", tab)
            if (children.isNotEmpty()) signature.put("membre", JSONObject().put("N", user.get("N")).put("G", 4))
            payload.put("Signature", signature)
        }
        if (data != null) payload.put("data", data)
        var secured: Any = payload
        if (compressed) {
            secured = PronoteCrypto.hex(PronoteCrypto.deflate(PronoteCrypto.hex(payload.toString().toByteArray(UTF_8)).toByteArray(UTF_8))).uppercase()
        }
        if (encrypted) {
            val clear = if (secured is String) PronoteCrypto.unhex(secured) else payload.toString().toByteArray(UTF_8)
            secured = PronoteCrypto.hex(PronoteCrypto.encrypt(clear, key, iv)).uppercase()
        }
        val number = PronoteCrypto.hex(PronoteCrypto.encrypt(order.toString().toByteArray(UTF_8), key, iv))
        val session = attributes.getLong("h")
        val envelope = JSONObject().put("session", session).put("no", number).put("id", function).put("dataSec", secured)
        val body = request("POST", "$root/appelfonction/${attributes.getInt("a")}/$session/$number", envelope.toString()).body
        // Réponse non JSON (page HTML d’erreur, session perdue) : erreur typée, jamais de détail du contenu.
        val response = try {
            JSONObject(body)
        } catch (_: org.json.JSONException) {
            throw PronoteException("format", "Réponse Pronote inattendue.")
        }
        order += 2
        if (response.has("Erreur")) {
            val code = response.optJSONObject("Erreur")?.optInt("G") ?: 0
            throw PronoteException("pronote-$code", "Pronote a refusé la requête. Réessaie plus tard sans multiplier les essais.")
        }
        if (nextIv != null) iv = nextIv
        val section = try {
            val raw = response.get("dataSec")
            val decoded = when {
                encrypted && compressed -> String(PronoteCrypto.inflate(PronoteCrypto.decrypt(PronoteCrypto.unhex(raw as String), key, iv)), UTF_8)
                encrypted -> String(PronoteCrypto.decrypt(PronoteCrypto.unhex(raw as String), key, iv), UTF_8)
                compressed -> String(PronoteCrypto.inflate(PronoteCrypto.unhex(raw as String)), UTF_8)
                else -> raw.toString()
            }
            JSONObject(decoded)
        } catch (error: PronoteException) {
            throw error
        } catch (_: Exception) {
            throw PronoteException("format", "Réponse Pronote inattendue.")
        }
        lastCallAt = clock()
        return section.optJSONObject("data") ?: section
    }

    /** Change à chaque connexion : les identifiants de pièces jointes ne valent que pour la session qui les a lus. */
    @Volatile
    var sessionStamp: Any = Any()
        private set

    fun login(mode: Mode = Mode.TOKEN) {
        sessionStamp = Any()
        // Reconnexion depuis un état propre : cookies, compteur, clé et IV de la session précédente oubliés.
        cookies.clear()
        lastCallAt = null
        order = 1
        key = PronoteCrypto.md5(byteArrayOf())
        iv = PronoteCrypto.zeroIv
        PronotePolicy.validatedUrl(credentials.url)
        attributes = PronotePolicy.parseStart(request("GET", credentials.url).body)
        compressed = attributes.optBoolean("CoA", false)
        encrypted = attributes.optBoolean("CrA", false)
        val temporary = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val uuidData = if (attributes.optBoolean("http", false)) PronoteCrypto.rsa(temporary) else temporary
        parameters = call(
            "FonctionParametres",
            JSONObject().put("Uuid", Base64.getEncoder().encodeToString(uuidData))
                .put("identifiantNav", credentials.clientIdentifier ?: JSONObject.NULL),
            nextIv = PronoteCrypto.md5(temporary),
        )
        credentials = credentials.copy(
            clientIdentifier = parameters.optString("identifiantNav").takeIf { it.isNotBlank() && it != "null" } ?: credentials.clientIdentifier,
        )
        val identification = call(
            "Identification",
            JSONObject().put("genreConnexion", 0).put("genreEspace", attributes.getInt("a")).put("identifiant", credentials.username)
                .put("pourENT", false).put("enConnexionAuto", false).put("demandeConnexionAuto", false)
                .put("demandeConnexionAppliMobile", mode == Mode.QR).put("demandeConnexionAppliMobileJeton", mode == Mode.QR)
                .put("enConnexionAppliMobile", mode == Mode.TOKEN).put("uuidAppliMobile", credentials.uuid).put("loginTokenSAV", ""),
        )
        val username = if (identification.optBoolean("modeCompLog")) credentials.username.lowercase() else credentials.username
        val password = if (identification.optBoolean("modeCompMdp")) credentials.password.lowercase() else credentials.password
        val authKey = PronoteCrypto.md5((username + PronoteCrypto.sha256Upper(identification.optString("alea") + password)).toByteArray(UTF_8))
        val challenge = PronoteCrypto.hex(PronoteCrypto.encrypt(identification.getString("challenge").toByteArray(UTF_8), authKey, iv))
        val auth = call("Authentification", JSONObject().put("connexion", 0).put("challenge", challenge).put("espace", attributes.getInt("a")))
        if (!auth.has("cle")) throw PronoteException("login", "La connexion n’a pas été acceptée.")
        val secret = String(PronoteCrypto.decrypt(PronoteCrypto.unhex(auth.getString("cle")), authKey, iv), UTF_8)
        key = PronoteCrypto.md5(secret.split(',').map { it.trim().toInt().toByte() }.toByteArray())
        // Le jeton mobile tourne à chaque connexion : il est enregistré avant toute autre lecture.
        auth.optString("jetonConnexionAppliMobile").takeIf { it.isNotBlank() }?.let { credentials = credentials.copy(password = it) }
        save(credentials)
        val actions = auth.optJSONObject("actionsDoubleAuth")?.optString("V")?.let { runCatching { JSONArray(it) }.getOrNull() }
        if (actions != null && (0 until actions.length()).any { actions.optInt(it) in setOf(3, 5) }) {
            throw PronoteException("mfa", "Pronote demande une vérification de sécurité supplémentaire.")
        }

        val personal = call("ParametresUtilisateur")
        user = personal.getJSONObject("ressource")
        authorizedTabs = personal.opt("listeOnglets")?.let { PronotePolicy.flattenTabs(it) }
        accountName = user.optString("L", "Compte connecté")
        val childResources = resourcesOf(user)
        children = if (childResources == null) emptyList() else (0 until childResources.length()).map {
            val child = childResources.getJSONObject(it)
            Child(child.get("N").toString(), child.optString("L"))
        }
        selectedChildId = null
        if (childResources != null && childResources.length() > 0) {
            user = childResources.getJSONObject(0)
            selectedChildId = user.get("N").toString()
        }
        if (mode == Mode.QR) {
            val security = call("PageInfosPerso", tab = 49).optJSONObject("securisation")?.optInt("mode", 0) ?: 0
            // Compte sécurisé : Pronote exige une seconde connexion avec le jeton qui vient d’être renouvelé.
            if (security != 0) {
                login(Mode.TOKEN)
                return
            }
        }
        save(credentials)
    }

    private fun resourcesOf(resource: JSONObject): JSONArray? =
        resource.optJSONArray("listeRessources") ?: resource.optJSONObject("listeRessources")?.optJSONArray("V")

    /** Sélectionne l’enfant d’un compte parent (`ressource.listeRessources`). */
    fun selectChild(childId: String) {
        if (children.isEmpty() || childId == selectedChildId) return
        val resources = resourcesOf(call("ParametresUtilisateur").getJSONObject("ressource"))
            ?: throw PronoteException("child", "Enfant introuvable.")
        user = (0 until resources.length()).map { resources.getJSONObject(it) }.firstOrNull { it.get("N").toString() == childId }
            ?: throw PronoteException("child", "Enfant introuvable.")
        selectedChildId = childId
    }

    /** Emploi du temps de [days] jours à partir de [today] (jour calendaire de l’établissement). */
    fun timetable(today: LocalDate, days: Int = 14): List<Lesson> {
        val last = today.plusDays(days.coerceIn(1, 14).toLong() - 1)
        val monday = LocalDate.parse(PronotePolicy.date(parameters.getJSONObject("General").get("PremierLundi")).take(10))
        fun week(day: LocalDate) = 1 + (ChronoUnit.DAYS.between(monday, day) / 7).toInt()
        val results = mutableListOf<Lesson>()
        val general = parameters.optJSONObject("General")
        for (w in week(today)..week(last)) {
            val data = JSONObject().put("ressource", user).put("Ressource", user).put("avecAbsencesEleve", false)
                .put("avecConseilDeClasse", true).put("estEDTPermanence", false).put("avecAbsencesRessource", true)
                .put("avecDisponibilites", true).put("avecInfosPrefsGrille", true).put("NumeroSemaine", w).put("numeroSemaine", w)
            val lessons = call("PageEmploiDuTemps", data, 16).optJSONArray("ListeCours") ?: JSONArray()
            for (i in 0 until lessons.length()) {
                val lesson = lessons.getJSONObject(i)
                val start = PronotePolicy.date(lesson.opt("DateDuCours"))
                if (start.isEmpty()) continue
                val date = LocalDate.parse(start.take(10))
                if (date < today || date > last) continue
                val raw = lesson.optJSONObject("ListeContenus")?.optJSONArray("V") ?: JSONArray()
                val content = (0 until raw.length()).map { raw.getJSONObject(it) }
                fun labels(kind: Int) = content.filter { it.optInt("G") == kind }.joinToString(", ") { it.optString("L") }
                val end = PronotePolicy.date(lesson.opt("DateDuCoursFin"))
                    .ifEmpty { PronotePolicy.endFromGrid(start, lesson.optInt("place"), lesson.optInt("duree"), general) }
                lessonSubjects = lessonSubjects + (lesson.optString("N") to labels(16).ifBlank { "Cours" })
                results += Lesson(
                    id = "${lesson.optString("N")}-$start-${lesson.optInt("P")}",
                    subject = labels(16).ifBlank { "Cours" },
                    start = start,
                    end = end,
                    teacher = labels(3),
                    room = labels(17),
                    cancelled = lesson.optBoolean("estAnnule"),
                )
            }
        }
        return results.sortedBy { it.start }
    }

    /**
     * Cahier de textes en lecture seule sur 28 jours : contenus (onglet 89) puis, s’il est autorisé,
     * travail à faire (onglet 88). Ne crée rien et n’écrit rien dans Pronote.
     */
    fun notebook(today: LocalDate): NotebookSnapshot {
        val monday = LocalDate.parse(PronotePolicy.date(parameters.getJSONObject("General").get("PremierLundi")).take(10))
        val window = NotebookRules.window(today, monday)
        fun page(tab: Int) = call("PageCahierDeTexte", JSONObject().put("domaine", JSONObject().put("_T", 8).put("V", window.domaine)), tab)
        val tabs = authorizedTabs
        if (tabs != null && NotebookRules.CONTENTS_TAB !in tabs) {
            throw PronoteException("notebook-unavailable", "Le cahier de textes n’est pas disponible pour ce compte.")
        }
        val contents = page(NotebookRules.CONTENTS_TAB)
        val homework = if (tabs == null || NotebookRules.HOMEWORK_TAB in tabs) page(NotebookRules.HOMEWORK_TAB) else null
        return NotebookRules.parse(window, contents, homework, lessonSubjects)
    }

    /**
     * Télécharge une pièce jointe (`FichiersExternes`) avec la session et les cookies courants. L’URL
     * est construite ici puis oubliée ; elle n’est jamais renvoyée.
     */
    fun download(id: String, name: String, files: FileTransport, target: java.io.File, doublePadding: Boolean = true) {
        if (!NotebookRules.isDownloadableId(id)) throw PronoteException("download", DOWNLOAD_UNAVAILABLE)
        val url = NotebookRules.downloadUrl(root, NotebookRules.encryptedId(id, key, iv, doublePadding), name, attributes.getLong("h"))
        if (!url.startsWith("$origin/")) throw PronoteException("origin", "Requête hors établissement refusée.")
        val headers = linkedMapOf("User-Agent" to USER_AGENT)
        if (cookies.isNotEmpty()) headers["Cookie"] = cookieHeader()
        when (files.download(url, headers, NotebookRules.MAX_DOWNLOAD_BYTES, target)) {
            200 -> {}
            413 -> throw PronoteException("download-size", "Ce document dépasse la limite de 20 Mo.")
            else -> throw PronoteException("download", DOWNLOAD_UNAVAILABLE)
        }
    }

    companion object {
        const val USER_AGENT = "iPhone"
        const val DOWNLOAD_UNAVAILABLE = "Document indisponible. Actualise le cahier de textes puis réessaie."
    }
}
