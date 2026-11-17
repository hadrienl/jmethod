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
            val text = stream?.bufferedReader(UTF_8)?.use { it.readText() }.orEmpty()
            HttpResult(status, connection.headerFields.filterKeys { it != null }, text)
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
    private val origin = PronotePolicy.originOf(credentials.url)
    private val root = credentials.url.substringBefore('?').substringBeforeLast('/')

    var accountName = ""
        private set
    var children: List<Child> = emptyList()
        private set
    var selectedChildId: String? = null
        private set

    /** Remplace les identifiants mobiles par les plus récents du coffre (jeton renouvelé ailleurs). */
    fun useCredentials(latest: Credentials) {
        credentials = latest
    }

    private fun request(method: String, url: String, body: String? = null): HttpResult {
        if (!url.startsWith("$origin/")) throw PronoteException("origin", "Requête hors établissement refusée.")
        val headers = linkedMapOf("User-Agent" to "iPhone", "Accept" to if (method == "GET") "text/html" else "application/json")
        if (method == "POST") headers["Content-Type"] = "application/json"
        if (cookies.isNotEmpty()) headers["Cookie"] = cookies.entries.joinToString("; ") { it.key + "=" + it.value }
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
        if (function !in PronotePolicy.allowedFunctions) {
            throw PronoteException("readonly", "Cette opération n’est pas autorisée en lecture seule.")
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
        val response = JSONObject(request("POST", "$root/appelfonction/${attributes.getInt("a")}/$session/$number", envelope.toString()).body)
        order += 2
        if (response.has("Erreur")) {
            val code = response.optJSONObject("Erreur")?.optInt("G") ?: 0
            throw PronoteException("pronote-$code", "Pronote a refusé la requête. Réessaie plus tard sans multiplier les essais.")
        }
        if (nextIv != null) iv = nextIv
        val raw = response.get("dataSec")
        val decoded = when {
            encrypted && compressed -> String(PronoteCrypto.inflate(PronoteCrypto.decrypt(PronoteCrypto.unhex(raw as String), key, iv)), UTF_8)
            encrypted -> String(PronoteCrypto.decrypt(PronoteCrypto.unhex(raw as String), key, iv), UTF_8)
            compressed -> String(PronoteCrypto.inflate(PronoteCrypto.unhex(raw as String)), UTF_8)
            else -> raw.toString()
        }
        val section = JSONObject(decoded)
        return section.optJSONObject("data") ?: section
    }

    fun login(mode: Mode = Mode.TOKEN) {
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
}
