package fr.jmethod.app

import fr.jmethod.app.pronote.HttpResult
import fr.jmethod.app.pronote.PronoteCrypto
import fr.jmethod.app.pronote.PronoteTransport
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * Serveur Pronote synthétique (chiffrement et compression actifs) pour éprouver le client
 * natif hors ligne : identifiants, jetons et cours sont fictifs.
 */
class FakePronoteServer(
    var token: String,
    val username: String = "eleve.test",
    var secureMode: Int = 0,
    val parent: Boolean = false,
) {
    val origin = "https://abcd1234.index-education.net"
    /** Simule une absence de réseau. */
    var offline = false
    val calls = mutableListOf<String>()
    val timetableMembers = mutableListOf<String?>()
    val timetableResources = mutableListOf<String?>()
    /** Charges utiles `PageCahierDeTexte` reçues (avant compression et chiffrement). */
    val notebookPayloads = mutableListOf<JSONObject>()
    /** Réponses `data` du cahier de textes par onglet. */
    val notebookResponses = mutableMapOf<Int, JSONObject>()
    /** `listeOnglets` renvoyé par ParametresUtilisateur (absent si `null`). */
    var tabs: JSONArray? = null
    /** `actionsDoubleAuth` renvoyé à l’authentification (3 ou 5 : vérification exigée). */
    var doubleAuth: Int? = null
    /** Origine écrite dans le QR (par défaut celle du serveur). */
    var qrOrigin: String? = null
    /** Clé et IV de session côté serveur (contrôle de l’identifiant de pièce jointe). */
    val sessionKey get() = key
    val sessionIv get() = iv
    var issued = 0
    /** Code `Erreur.G` renvoyé à la prochaine requête de cette fonction. */
    val failures = mutableMapOf<String, Int>()
    /** Appelé avant chaque requête (pour simuler une annulation pendant un appel). */
    var beforeRequest: (String) -> Unit = {}

    private val premierLundi = LocalDate.of(2026, 8, 31)
    val lessons = mutableListOf<JSONObject>()

    private var key = PronoteCrypto.md5(byteArrayOf())
    private var iv = PronoteCrypto.zeroIv
    private var authKey = ByteArray(16)
    private var challenge = ""
    private var pendingKey: ByteArray? = null

    fun lesson(id: String, start: String, subject: String, cancelled: Boolean = false, end: String? = null, place: Int = 0, duration: Int = 2): FakePronoteServer {
        val content = JSONArray()
            .put(JSONObject().put("G", 16).put("L", subject))
            .put(JSONObject().put("G", 3).put("L", "Professeur Test"))
            .put(JSONObject().put("G", 17).put("L", "Salle 1"))
        val lesson = JSONObject().put("N", id).put("P", 0).put("place", place).put("duree", duration).put("estAnnule", cancelled)
            .put("DateDuCours", JSONObject().put("_T", 7).put("V", start)).put("ListeContenus", JSONObject().put("V", content))
        if (end != null) lesson.put("DateDuCoursFin", JSONObject().put("_T", 7).put("V", end))
        lessons += lesson
        return this
    }

    private fun weekOf(date: LocalDate) = 1 + (ChronoUnit.DAYS.between(premierLundi, date) / 7).toInt()

    val transport = PronoteTransport { method, url, body, _ ->
        if (offline) throw java.io.IOException("réseau indisponible")
        require(url.startsWith("$origin/"))
        if (method == "GET") {
            beforeRequest("GET")
            key = PronoteCrypto.md5(byteArrayOf())
            iv = PronoteCrypto.zeroIv
            return@PronoteTransport HttpResult(200, mapOf("Set-Cookie" to listOf("sid=abc; Path=/")), "<script>Start({h:424242,a:3,CrA:true,CoA:true,http:false})</script>")
        }
        val envelope = JSONObject(body!!)
        val function = envelope.getString("id")
        beforeRequest(function)
        calls += function
        val requestIv = iv
        val clear = PronoteCrypto.decrypt(PronoteCrypto.unhex(envelope.getString("dataSec")), key, requestIv)
        val payload = JSONObject(String(PronoteCrypto.unhex(String(PronoteCrypto.inflate(clear), UTF_8)), UTF_8))
        failures.remove(function)?.let { code ->
            return@PronoteTransport HttpResult(200, emptyMap(), JSONObject().put("Erreur", JSONObject().put("G", code)).toString())
        }
        val data = when (function) {
            "FonctionParametres" -> {
                val temporary = Base64.getDecoder().decode(payload.getJSONObject("data").getString("Uuid"))
                iv = PronoteCrypto.md5(temporary)
                val grid = JSONArray()
                listOf("8h25", "9h20", "10h15", "11h25").forEachIndexed { i, label -> grid.put(JSONObject().put("G", i).put("L", label)) }
                JSONObject().put("identifiantNav", "nav-test")
                    .put("General", JSONObject().put("PremierLundi", JSONObject().put("_T", 7).put("V", "31/08/2026")).put("ListeHeuresFin", JSONObject().put("V", grid)))
            }
            "Identification" -> {
                challenge = "défi-${calls.size}"
                JSONObject().put("challenge", challenge).put("alea", "sel").put("modeCompLog", false).put("modeCompMdp", false)
            }
            "Authentification" -> {
                authKey = PronoteCrypto.md5((username + PronoteCrypto.sha256Upper("sel$token")).toByteArray(UTF_8))
                val expected = PronoteCrypto.hex(PronoteCrypto.encrypt(challenge.toByteArray(UTF_8), authKey, iv))
                if (payload.getJSONObject("data").getString("challenge") != expected) {
                    JSONObject()
                } else {
                    val secret = (1..16).joinToString(",")
                    pendingKey = PronoteCrypto.md5(ByteArray(16) { (it + 1).toByte() })
                    issued++
                    token = "jeton-rotation-$issued"
                    JSONObject().put("cle", PronoteCrypto.hex(PronoteCrypto.encrypt(secret.toByteArray(UTF_8), authKey, iv)))
                        .put("jetonConnexionAppliMobile", token)
                        .also { result -> doubleAuth?.let { result.put("actionsDoubleAuth", JSONObject().put("V", "[$it]")) } }
                }
            }
            "ParametresUtilisateur" -> {
                val resource = if (parent) {
                    JSONObject().put("N", "parent-1").put("L", "Parent Test").put(
                        "listeRessources",
                        JSONArray().put(JSONObject().put("N", "enfant-a").put("G", 4).put("L", "Enfant A"))
                            .put(JSONObject().put("N", "enfant-b").put("G", 4).put("L", "Enfant B")),
                    )
                } else {
                    JSONObject().put("N", "eleve-1").put("L", "Élève Test")
                }
                JSONObject().put("ressource", resource).also { result -> tabs?.let { result.put("listeOnglets", it) } }
            }
            "PageCahierDeTexte" -> {
                notebookPayloads += payload
                notebookResponses[payload.getJSONObject("Signature").getInt("onglet")] ?: JSONObject()
            }
            "PageInfosPerso" -> JSONObject().put("securisation", JSONObject().put("mode", secureMode))
            "PageEmploiDuTemps" -> {
                val signature = payload.optJSONObject("Signature")
                timetableMembers += signature?.optJSONObject("membre")?.optString("N")
                timetableResources += payload.getJSONObject("data").getJSONObject("ressource").optString("N")
                val week = payload.getJSONObject("data").getInt("NumeroSemaine")
                val format = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                val list = JSONArray()
                lessons.filter { weekOf(LocalDate.parse(it.getJSONObject("DateDuCours").getString("V").take(10), format)) == week }.forEach { list.put(it) }
                JSONObject().put("ListeCours", list)
            }
            else -> error("Fonction inattendue : $function")
        }
        val response = PronoteCrypto.encrypt(PronoteCrypto.deflate(JSONObject().put("data", data).toString().toByteArray(UTF_8)), key, iv)
        pendingKey?.let { key = it; pendingKey = null }
        HttpResult(200, emptyMap(), JSONObject().put("nom", function).put("dataSec", PronoteCrypto.hex(response).uppercase()).toString())
    }

    /** QR officiel synthétique chiffré avec [pin]. */
    fun qr(pin: String): JSONObject {
        val qrKey = PronoteCrypto.md5(pin.toByteArray(UTF_8))
        return JSONObject().put("url", "${qrOrigin ?: origin}/pronote/eleve.html")
            .put("login", PronoteCrypto.hex(PronoteCrypto.encrypt(username.toByteArray(UTF_8), qrKey, PronoteCrypto.zeroIv)))
            .put("jeton", PronoteCrypto.hex(PronoteCrypto.encrypt(token.toByteArray(UTF_8), qrKey, PronoteCrypto.zeroIv)))
    }
}
