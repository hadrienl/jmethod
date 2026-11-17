package fr.jmethod.app.pronote

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Erreur Pronote : [code] stable pour la logique, [message] en français pour l’interface. Jamais de donnée sensible. */
class PronoteException(val code: String, override val message: String) : Exception(message)

/** Identifiants mobiles (modèle partagé `Credentials`) : `password` est le jeton mobile, jamais le mot de passe. */
data class Credentials(
    val url: String,
    val username: String,
    val password: String,
    val uuid: String,
    val clientIdentifier: String? = null,
) {
    fun json(): JSONObject = JSONObject()
        .put("url", url).put("username", username).put("password", password).put("uuid", uuid)
        .put("clientIdentifier", clientIdentifier ?: JSONObject.NULL)

    override fun toString(): String = "Credentials(<masqué>)"

    companion object {
        fun fromJson(raw: JSONObject) = Credentials(
            raw.getString("url"), raw.getString("username"), raw.getString("password"), raw.getString("uuid"),
            raw.optString("clientIdentifier").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

/** Créneau lu dans Pronote (modèle partagé `Lesson`). Dates en heure murale de l’établissement. */
data class Lesson(
    val id: String,
    val subject: String,
    /** `yyyy-MM-ddTHH:mm[:ss]` */
    val start: String,
    /** `yyyy-MM-ddTHH:mm[:ss]` ou vide si la fin est inconnue. */
    val end: String,
    val teacher: String,
    val room: String,
    val cancelled: Boolean,
)

data class Child(val id: String, val name: String)

/** Règles de sécurité et d’analyse héritées du POC validé sur appareil. */
object PronotePolicy {
    private val page = Regex(".*/(?:mobile\\.)?(eleve|parent)\\.html$")

    /** Liste blanche stricte des fonctions de lecture ; toute écriture (Saisie…) est refusée. */
    val allowedFunctions = setOf(
        "FonctionParametres", "Identification", "Authentification", "ParametresUtilisateur",
        "PageInfosPerso", "Navigation", "PageEmploiDuTemps", "PageCahierDeTexte",
    )

    private val domaineRange = Regex("^\\[(\\d{1,3})\\.\\.(\\d{1,3})\\]$")

    /**
     * Contrôle local avant tout envoi : liste blanche, aucune fonction `Saisie…`, et pour
     * `PageCahierDeTexte` onglet 88 ou 89, `data` réduit à `domaine` de type 8 sur six semaines au plus.
     */
    fun checkCall(function: String, tab: Int?, data: JSONObject?) {
        if (function !in allowedFunctions || function.startsWith("Saisie")) {
            throw PronoteException("readonly", "Cette opération n’est pas autorisée en lecture seule.")
        }
        if (function != "PageCahierDeTexte") return
        val refused = PronoteException("readonly", "Cette lecture du cahier de textes n’est pas autorisée.")
        if (tab != 88 && tab != 89) throw refused
        if (data == null || data.length() != 1 || !data.has("domaine")) throw refused
        val domaine = data.optJSONObject("domaine") ?: throw refused
        if (domaine.length() != 2 || domaine.opt("_T") !is Number || domaine.optInt("_T") != 8) throw refused
        val match = domaineRange.matchEntire(domaine.optString("V")) ?: throw refused
        val first = match.groupValues[1].toInt()
        val last = match.groupValues[2].toInt()
        if (first < 1 || last < first || last - first > NotebookRules.MAX_WEEK_SPAN) throw refused
    }

    /** `listeOnglets` aplati comme `_prepare_onglets` de pronotepy (toutes les valeurs, récursivement). */
    fun flattenTabs(raw: Any?): Set<Int> {
        val out = mutableSetOf<Int>()
        fun walk(value: Any?) {
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
                is JSONObject -> value.keys().forEach { walk(value.opt(it)) }
                is Number -> out += value.toInt()
                is String -> value.toIntOrNull()?.let { out += it }
            }
        }
        walk(raw)
        return out
    }

    fun validatedUrl(value: String): URI {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw PronoteException("url", "Saisis une adresse Pronote HTTPS valide.")
        }
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme != "https" || !host.endsWith(".index-education.net") || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443) || !page.matches(uri.path.orEmpty())
        ) {
            throw PronoteException("url", "J-Method accepte les espaces Élèves et Parents hébergés sur index-education.net.")
        }
        return uri
    }

    /**
     * Adresse de l’espace Pronote à partir du champ « Code ou adresse de l’établissement » :
     * un code seul (sans point, casse et espaces ignorés) devient `https://<code>.index-education.net/pronote/<page>` ;
     * un host ou une adresse complète passent par [normalizedAddress] (la page d’une adresse complète prime).
     */
    fun schoolAddress(input: String, accountType: AccountType): String {
        val compact = input.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return ""
        if ('.' !in compact && '/' !in compact && ':' !in compact) {
            return "https://${compact.lowercase()}.index-education.net/pronote/${accountType.page}"
        }
        return normalizedAddress(input, accountType.page)
    }

    /** Accepte une saisie approximative (sans https, sans page, avec ?/#, page mobile) et la ramène à l’espace web officiel. */
    fun normalizedAddress(input: String, defaultPage: String = "eleve.html"): String {
        var text = input.trim()
        if (text.isEmpty()) return text
        val lower = text.lowercase()
        text = when {
            lower.startsWith("https://") -> "https://" + text.substring(8)
            lower.startsWith("http://") -> "https://" + text.substring(7)
            else -> "https://$text"
        }
        text = text.substringBefore('#').substringBefore('?')
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return text
        }
        val authority = uri.rawAuthority?.lowercase() ?: return text
        var path = uri.rawPath.orEmpty()
        val last = path.substringAfterLast('/').lowercase()
        if (last.endsWith(".html")) {
            if (last.startsWith("mobile.")) path = path.substringBeforeLast('/') + "/" + last.removePrefix("mobile.")
        } else {
            if (!path.lowercase().contains("/pronote")) path = "/pronote"
            path = if (path.endsWith("/")) path + defaultPage else "$path/$defaultPage"
        }
        return "https://$authority$path"
    }

    /** Page desktop Élèves/Parents de l’origine exacte attendue (jamais une page mobile ni un ENT). */
    fun isTrustedPage(value: String?, allowedOrigin: String): Boolean = try {
        val uri = validatedUrl(value.orEmpty())
        uri.scheme + "://" + uri.authority == allowedOrigin && !uri.path.substringAfterLast('/').startsWith("mobile.")
    } catch (_: Exception) {
        false
    }

    fun originOf(url: String): String = validatedUrl(url).let { it.scheme + "://" + it.authority }

    /** Lit les attributs de `Start({...})` sans exécuter de JavaScript ; booléens typés. */
    fun parseStart(html: String): JSONObject {
        val body = Regex("\\bStart\\s*\\(\\s*(\\{[^}]*\\})\\s*\\)").find(html)?.groupValues?.get(1)
            ?: throw PronoteException("html", "La page Pronote nécessite une connexion officielle ou son format a changé.")
        val result = JSONObject()
        val attributes = Regex("(?:\\{|,)\\s*['\"]?([\\w]+)['\"]?\\s*:\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'|([^,}]+))")
        attributes.findAll(body).forEach { match ->
            val raw = match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }?.trim().orEmpty()
            val value: Any = when {
                raw.equals("true", true) -> true
                raw.equals("false", true) -> false
                raw.toLongOrNull() != null -> raw.toLong()
                else -> raw
            }
            result.put(match.groupValues[1], value)
        }
        if (!result.has("h") || !result.has("a")) throw PronoteException("html", "Paramètres de session Pronote introuvables.")
        return result
    }

    /**
     * Identifiants mobiles d’un QR officiel. [uuid] est l’UUID d’application stable (généré une fois) ;
     * [expectedOrigin], s’il est fourni, est l’origine de la tentative : un QR d’un autre établissement est refusé.
     */
    fun credentialsFromQr(qr: JSONObject, pin: String, uuid: String = UUID.randomUUID().toString(), expectedOrigin: String? = null): Credentials {
        val uri = validatedUrl(qr.getString("url"))
        if (expectedOrigin != null && uri.scheme + "://" + uri.authority != expectedOrigin) {
            throw PronoteException("qr-origin", "Ce QR code ne correspond pas à l’établissement de la connexion en cours.")
        }
        if (!pin.matches(Regex("[0-9]{4}"))) throw PronoteException("qr", "Le code temporaire doit contenir quatre chiffres.")
        val key = PronoteCrypto.md5(pin.toByteArray(UTF_8))
        val login = String(PronoteCrypto.decrypt(PronoteCrypto.unhex(qr.getString("login")), key, PronoteCrypto.zeroIv), UTF_8)
        val token = String(PronoteCrypto.decrypt(PronoteCrypto.unhex(qr.getString("jeton")), key, PronoteCrypto.zeroIv), UTF_8)
        val last = uri.path.substringAfterLast('/').let { if (it.startsWith("mobile.")) it else "mobile.$it" }
        val path = uri.path.substringBeforeLast('/') + "/" + last
        val url = URI("https", uri.authority, path, "fd=1&bydlg=A6ABB224-12DD-4E31-AD3E-8A39A1C2C335&login=true", null).toString()
        return Credentials(url, login, token, uuid)
    }

    /** Heure de fin déduite de la grille `General.ListeHeuresFin` quand `DateDuCoursFin` est absent (cf. `Util.place2time`). */
    fun endFromGrid(start: String, place: Int, duration: Int, general: JSONObject?): String {
        val grid = general?.opt("ListeHeuresFin").let { (it as? JSONObject)?.optJSONArray("V") ?: it as? JSONArray } ?: return ""
        val count = grid.length()
        if (count < 2 || start.length < 10 || duration < 1) return ""
        var endPlace = place % (count - 1) + duration - 1
        if (endPlace > count) endPlace %= count - 1
        val label = (0 until count).map { grid.getJSONObject(it) }.firstOrNull { it.optInt("G", -1) == endPlace }?.optString("L").orEmpty()
        val match = Regex("^(\\d{1,2})h(\\d{2})$").find(label.trim()) ?: return ""
        return start.take(10) + "T" + match.groupValues[1].padStart(2, '0') + ":" + match.groupValues[2]
    }

    /** Date Pronote `dd/MM/yyyy[ HH:mm:ss]` en heure murale locale, sans décalage UTC. */
    fun date(raw: Any?): String {
        val value = if (raw is JSONObject) raw.optString("V") else raw?.toString().orEmpty()
        return try {
            LocalDateTime.parse(value, DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")).toString()
        } catch (_: Exception) {
            try {
                LocalDate.parse(value, DateTimeFormatter.ofPattern("dd/MM/yyyy")).atStartOfDay().toString()
            } catch (_: Exception) {
                ""
            }
        }
    }
}
