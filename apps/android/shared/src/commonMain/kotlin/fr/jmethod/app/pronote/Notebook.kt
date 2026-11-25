package fr.jmethod.app.pronote

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Cahier de textes en lecture seule (`native/shared/notebook-reference.md`, vecteurs
 * `notebook-vectors.json`) : fenêtre de 28 jours, dates, HTML vers texte sûr, liens, modèle.
 * Aucune URL de téléchargement n’est conservée ici : seuls l’identifiant `N` et le nom le sont.
 */
data class NotebookSnapshot(
    val from: LocalDate,
    val to: LocalDate,
    val sessions: List<SessionEntry>,
    val homework: List<HomeworkItem>,
    val skippedEntries: Int,
    val attachmentCount: Int,
    /** Onglet 88 indisponible pour ce compte : contenus seuls. */
    val homeworkAvailable: Boolean = true,
)

/**
 * Séance du cahier de textes. [id] est le `N` Pronote, valable pour la seule session Pronote en cours ;
 * [ordinal] est le rang de la séance parmi celles de même date et même matière, dans l’ordre de la réponse.
 */
data class SessionEntry(
    val id: String,
    val lessonId: String,
    val date: LocalDate,
    val subject: String,
    val contents: List<NotebookContent>,
    val ordinal: Int = 1,
) {
    /** Clé stable entre sessions Pronote (`notebook-to-course.md`, section 1). */
    val key: String get() = NotebookRules.sessionKey(date.toString(), subject, ordinal)
}

data class NotebookContent(
    val title: String,
    val description: String,
    val links: List<NotebookLink>,
    val category: String,
    val attachments: List<NotebookAttachment>,
)

data class HomeworkItem(
    val id: String,
    val dueDate: LocalDate,
    val subject: String,
    val description: String,
    val links: List<NotebookLink>,
    val done: Boolean,
    val attachments: List<NotebookAttachment>,
    val remoteDone: Boolean? = done,
)

/** Clé de suivi personnel, utilisable seulement si elle est unique dans la lecture. */
fun HomeworkItem.agendaKey(): String = fr.jmethod.app.model.agendaFingerprint(
    listOf(dueDate.toString(), subject, description) + attachments.flatMap { listOf(it.name, it.url.orEmpty()) } +
        links.flatMap { listOf(it.label, it.url) },
)

fun HomeworkItem.agendaSourceTag(): String? = id.takeIf { it.isNotBlank() }
    ?.let { fr.jmethod.app.model.agendaFingerprint(listOf(it)) }

/**
 * Pièce jointe : [name] nettoyé pour l’affichage, [rawName] = `L` brut (éventuellement vide), seul
 * utilisé pour construire l’adresse `FichiersExternes` et le nom d’enregistrement.
 */
data class NotebookAttachment(val kind: Kind, val id: String, val name: String, val url: String? = null, val rawName: String = name) {
    enum class Kind(val wire: String) { FILE("file"), LINK("link") }
}

data class NotebookLink(val label: String, val url: String)

object NotebookRules {
    /** Préfixe des clés de séance stables (`pn1:<date>|<matière normalisée>|<rang>`). */
    const val KEY_PREFIX = "pn1:"

    /**
     * Règle 33 : minuscules, NFD, retrait de U+0300–U+036F (sans recomposition), toute suite de caractères
     * ni lettre ni chiffre remplacée par une espace, rognage.
     */
    fun normalizeSubject(subject: String): String {
        val folded = java.text.Normalizer.normalize(subject.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("[\u0300-\u036F]"), "")
        val spaced = buildString { folded.forEach { append(if (it.isLetterOrDigit()) it else ' ') } }
        return spaced.replace(Regex(" +"), " ").trim()
    }

    fun sessionKey(date: String, subject: String, ordinal: Int): String = "$KEY_PREFIX$date|${normalizeSubject(subject)}|$ordinal"

    /** Rangs des séances de même date et même matière normalisée, dans l’ordre de [sessions]. */
    fun withOrdinals(sessions: List<SessionEntry>): List<SessionEntry> {
        val seen = HashMap<Pair<LocalDate, String>, Int>()
        return sessions.map { session ->
            val group = session.date to normalizeSubject(session.subject)
            val rank = (seen[group] ?: 0) + 1
            seen[group] = rank
            session.copy(ordinal = rank)
        }
    }

    const val CONTENTS_TAB = 89
    const val HOMEWORK_TAB = 88
    const val MAX_WEEK_SPAN = 5
    const val MAX_DOWNLOAD_BYTES = 20_971_520L

    data class Window(val today: LocalDate, val from: LocalDate, val to: LocalDate, val firstWeek: Int, val lastWeek: Int) {
        val domaine: String get() = "[$firstWeek..$lastWeek]"
        fun contains(date: LocalDate) = !date.isBefore(from) && !date.isAfter(to)
    }

    /** `semaine(d) = 1 + trunc(jours(d − PremierLundi) / 7)` (troncature vers zéro). */
    fun week(day: LocalDate, firstMonday: LocalDate): Int = 1 + (ChronoUnit.DAYS.between(firstMonday, day) / 7).toInt()

    /** 14 jours avant, le jour même, 13 jours après ; première semaine ramenée à au moins 1. */
    fun window(today: LocalDate, firstMonday: LocalDate): Window {
        val from = today.minusDays(14)
        val to = today.plusDays(13)
        val first = maxOf(1, week(from, firstMonday))
        val last = maxOf(first, week(to, firstMonday))
        return Window(today, from, to, first, last)
    }

    // region Dates

    private val dateFormats = listOf(
        Regex("^(\\d{2})/(\\d{2})/(\\d{4})$"),
        Regex("^(\\d{2})/(\\d{2})/(\\d{4}) (\\d{2}):(\\d{2}):(\\d{2})$"),
        Regex("^(\\d{2})/(\\d{2})/(\\d{2})$"),
        Regex("^(\\d{2})/(\\d{2})/(\\d{2}) (\\d{2})h(\\d{2})$"),
    )

    /** Dates PRONOTE en correspondance exacte ; seule la partie date est gardée, sans fuseau. */
    fun parseDate(raw: String?): LocalDate? {
        if (raw == null) return null
        for (format in dateFormats) {
            val match = format.matchEntire(raw) ?: continue
            val values = match.groupValues.drop(1).map { it.toInt() }
            val year = if (match.groupValues[3].length == 2) 2000 + values[2] else values[2]
            if (values.size > 3) {
                if (values[3] > 23 || values[4] > 59) return null
                if (values.size > 5 && values[5] > 59) return null
            }
            return runCatching { LocalDate.of(year, values[1], values[0]) }.getOrNull()
        }
        return null
    }

    // endregion

    // region Texte sûr

    private fun isRemoved(code: Int) =
        code in 0x00..0x1F || code in 0x7F..0x9F || code in 0x202A..0x202E || code in 0x2066..0x2069 || code == 0xFEFF

    private fun isBlank(code: Int) = code == 0x09 || code == 0x0A || code == 0x0B || code == 0x0C || code == 0x0D || code == 0x20 || code == 0xA0

    /** Blancs en espaces, caractères de contrôle et de direction retirés. */
    private fun cleanChars(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val code = text.codePointAt(i)
            when {
                isBlank(code) -> out.append(' ')
                isRemoved(code) -> {}
                else -> out.appendCodePoint(code)
            }
            i += Character.charCount(code)
        }
        return out.toString()
    }

    private val spaces = Regex(" {2,}")

    /** Texte brut (titres, matières, catégories, noms) : sans décodage d’entités, espaces réduits, rogné. */
    fun plainText(raw: String?): String = cleanChars(raw.orEmpty()).replace(spaces, " ").trim(' ')

    private val entity = Regex("&(?:#([0-9]{1,7})|#[xX]([0-9A-Fa-f]{1,6})|([A-Za-z][A-Za-z0-9]{0,31}));")

    /** Entités HTML 4.01 + `apos` ; numériques invalides en U+FFFD ; inconnues laissées telles quelles. */
    fun decodeEntities(text: String): String = entity.replace(text) { match ->
        val (decimal, hexadecimal, name) = match.destructured
        val code = when {
            decimal.isNotEmpty() -> decimal.toInt()
            hexadecimal.isNotEmpty() -> hexadecimal.toInt(16)
            else -> HtmlEntities.table[name] ?: return@replace match.value
        }
        if (code == 0 || code in 0xD800..0xDFFF || code > 0x10FFFF) "�" else String(Character.toChars(code))
    }

    data class SafeText(val text: String, val links: List<NotebookLink>)

    private val rawTextElements = setOf(
        "head", "iframe", "math", "noembed", "noframes", "noscript", "object", "script", "style", "svg", "template", "textarea", "title", "xmp",
    )
    private val blockElements = setOf(
        "address", "article", "aside", "blockquote", "dd", "div", "dl", "dt", "figcaption", "figure", "footer",
        "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section",
        "table", "tbody", "tfoot", "thead", "tr", "ul",
    )

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun isAsciiBlank(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    /**
     * HTML des enseignants vers texte affiché comme texte brut (section 7) : balayage simple, sans
     * exécution ni ressource distante. Les liens `http(s)` valides sont extraits à part.
     */
    fun safeText(html: String?): SafeText {
        val input = html.orEmpty()
        val out = StringBuilder()
        val links = mutableListOf<NotebookLink>()
        var linkStart = -1
        var linkUrl: String? = null
        val segment = StringBuilder()

        fun flush() {
            if (segment.isEmpty()) return
            out.append(cleanChars(decodeEntities(segment.toString())))
            segment.setLength(0)
        }
        fun softBreak() {
            val trimmed = out.trimEnd(' ')
            if (trimmed.isNotEmpty() && !trimmed.endsWith("\n")) out.append('\n')
        }
        fun closeLink() {
            if (linkStart >= 0) {
                val url = linkUrl
                if (url != null) {
                    val label = out.substring(linkStart).replace('\n', ' ').replace(spaces, " ").trim(' ')
                    links += NotebookLink(label.ifEmpty { url }, url)
                }
            }
            linkStart = -1
            linkUrl = null
        }

        var i = 0
        val n = input.length
        loop@ while (i < n) {
            val c = input[i]
            if (c != '<') {
                segment.append(c)
                i++
                continue
            }
            if (input.startsWith("<!--", i)) {
                flush()
                val end = input.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            if (i + 1 < n && (input[i + 1] == '!' || input[i + 1] == '?')) {
                flush()
                val end = input.indexOf('>', i + 2)
                i = if (end < 0) n else end + 1
                continue
            }
            val closing = i + 1 < n && input[i + 1] == '/'
            val nameStart = if (closing) i + 2 else i + 1
            if (nameStart >= n || !isAsciiLetter(input[nameStart])) {
                segment.append(c)
                i++
                continue
            }
            // Balise : nom, puis fin au premier `>` hors valeur entre guillemets (après `=`).
            var j = nameStart
            while (j < n && (input[j].isLetterOrDigit() && input[j].code < 128 || input[j] == '-' || input[j] == ':')) j++
            val name = input.substring(nameStart, j).lowercase(java.util.Locale.ROOT)
            val attrStart = j
            var afterEquals = false
            var end = -1
            while (j < n) {
                val ch = input[j]
                when {
                    ch == '>' -> { end = j; break }
                    ch == '=' -> { afterEquals = true; j++ }
                    isAsciiBlank(ch) -> j++
                    (ch == '"' || ch == '\'') && afterEquals -> {
                        val close = input.indexOf(ch, j + 1)
                        if (close < 0) { j = n; break }
                        j = close + 1
                        afterEquals = false
                    }
                    else -> { afterEquals = false; j++ }
                }
            }
            flush()
            if (end < 0) break@loop // balise non terminée : tout le reste est ignoré
            val attributes = input.substring(attrStart, end)
            i = end + 1
            if (!closing && name in rawTextElements) {
                // Contenu supprimé jusqu’à `</nom` suivi d’un blanc, `/`, `>` ou de la fin.
                var k = i
                var resume = n
                while (true) {
                    val at = input.indexOf("</", k)
                    if (at < 0) break
                    val candidate = input.regionMatches(at + 2, name, 0, name.length, ignoreCase = true)
                    val after = at + 2 + name.length
                    if (candidate && (after >= n || isAsciiBlank(input[after]) || input[after] == '/' || input[after] == '>')) {
                        val gt = input.indexOf('>', after)
                        resume = if (gt < 0) n else gt + 1
                        break
                    }
                    k = at + 2
                }
                i = resume
                continue
            }
            when {
                name == "br" -> out.append('\n')
                name in blockElements -> {
                    softBreak()
                    if (name == "li" && !closing) out.append("• ")
                }
                name == "td" || name == "th" -> out.append(' ')
                name == "a" -> {
                    closeLink()
                    if (!closing) {
                        linkStart = out.length
                        linkUrl = hrefOf(attributes)?.let { validLinkUrl(decodeEntities(it)) }
                    }
                }
            }
        }
        flush()
        closeLink()
        return SafeText(normalize(out.toString()), links)
    }

    /** Premier attribut `href` (nom insensible à la casse), valeur entre guillemets ou jusqu’au blanc ou `>`. */
    private fun hrefOf(attributes: String): String? {
        var i = 0
        val n = attributes.length
        while (i < n) {
            while (i < n && (isAsciiBlank(attributes[i]) || attributes[i] == '/')) i++
            val start = i
            while (i < n && !isAsciiBlank(attributes[i]) && attributes[i] != '=' && attributes[i] != '/') i++
            val name = attributes.substring(start, i)
            while (i < n && isAsciiBlank(attributes[i])) i++
            var value: String? = null
            if (i < n && attributes[i] == '=') {
                i++
                while (i < n && isAsciiBlank(attributes[i])) i++
                if (i < n && (attributes[i] == '"' || attributes[i] == '\'')) {
                    val quote = attributes[i]
                    val close = attributes.indexOf(quote, i + 1).let { if (it < 0) n else it }
                    value = attributes.substring(i + 1, close)
                    i = close + 1
                } else {
                    val s = i
                    while (i < n && !isAsciiBlank(attributes[i])) i++
                    value = attributes.substring(s, i)
                }
            }
            if (name.equals("href", ignoreCase = true)) return value ?: ""
            if (start == i && value == null) i++
        }
        return null
    }

    private val manyBreaks = Regex("\n{3,}")

    private fun normalize(text: String): String =
        text.replace(spaces, " ").split('\n').joinToString("\n") { it.trim(' ') }
            .replace(manyBreaks, "\n\n").trim('\n', ' ')

    private val host = Regex("^(?:[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\])(?::([0-9]{1,5}))?$")

    /** URL de lien acceptée (section 8) : `http(s)`, autorité `hôte[:port]` ASCII, aucun blanc ni contrôle. */
    fun validLinkUrl(raw: String?): String? {
        val url = raw?.trim(' ', '\t', '\n', '\u000B', '\u000C', '\r') ?: return null
        val lower = url.lowercase(java.util.Locale.ROOT)
        val rest = when {
            lower.startsWith("https://") -> url.substring(8)
            lower.startsWith("http://") -> url.substring(7)
            else -> return null
        }
        if (url.any { it.code <= 0x20 || it.code in 0x7F..0x9F }) return null
        val authority = rest.substring(0, rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it })
        if (authority.isEmpty() || '@' in authority || '\\' in authority) return null
        val match = host.matchEntire(authority) ?: return null
        val port = match.groupValues[1]
        if (port.isNotEmpty() && port.toInt() > 65535) return null
        return url
    }

    // endregion

    // region Modèle

    private fun JSONObject.v(key: String): Any? = opt(key)?.let { if (it is JSONObject && it.has("V")) it.opt("V") else it }
    private fun JSONObject.text(key: String): String? = v(key)?.takeIf { it != JSONObject.NULL }?.toString()
    private fun JSONObject.listV(key: String): List<JSONObject> {
        val raw = v(key) as? JSONArray ?: return emptyList()
        return (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
    }
    private fun JSONObject.labelOf(key: String): String? = (v(key) as? JSONObject)?.optString("L")?.takeIf { it.isNotEmpty() }

    fun attachments(item: JSONObject): List<NotebookAttachment> = item.listV("ListePieceJointe").mapNotNull { raw ->
        val id = raw.opt("N")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val rawName = raw.optString("L", "")
        val name = plainText(rawName)
        when (raw.opt("G")?.toString()) {
            "0" -> {
                val url = validLinkUrl(raw.optString("url").takeIf { raw.has("url") && it.isNotEmpty() } ?: raw.optString("L", ""))
                    ?: return@mapNotNull null
                NotebookAttachment(NotebookAttachment.Kind.LINK, id, name.ifEmpty { "Lien" }, url, rawName)
            }
            "1" -> NotebookAttachment(NotebookAttachment.Kind.FILE, id, name.ifEmpty { "Document" }, rawName = rawName)
            else -> null
        }
    }

    /**
     * Réponses `PageCahierDeTexte` (section `data`) vers le modèle natif.
     * @param timetableSubjects matière d’un cours de l’emploi du temps, par identifiant `cours.V.N`.
     */
    fun parse(
        window: Window,
        contents: JSONObject?,
        homework: JSONObject?,
        timetableSubjects: Map<String, String> = emptyMap(),
    ): NotebookSnapshot {
        var skipped = 0
        val sessions = mutableListOf<SessionEntry>()
        for (entry in contents?.listV("ListeCahierDeTextes").orEmpty()) {
            val date = parseDate(entry.text("Date"))
            if (date == null) {
                skipped++
                continue
            }
            if (!window.contains(date)) continue
            val lessonId = (entry.v("cours") as? JSONObject)?.opt("N")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
            val subject = plainText(entry.labelOf("Matiere")).ifEmpty { plainText(timetableSubjects[lessonId]) }.ifEmpty { "Cours" }
            val items = entry.listV("listeContenus").mapNotNull { content ->
                val title = plainText(content.optString("L", ""))
                val description = safeText(content.text("descriptif"))
                val files = attachments(content)
                if (title.isEmpty() && description.text.isEmpty() && files.isEmpty()) return@mapNotNull null
                NotebookContent(title, description.text, description.links, plainText(content.labelOf("categorie")), files)
            }
            if (items.isEmpty()) continue
            val id = entry.opt("N")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotEmpty() } ?: "cours:$lessonId@$date"
            sessions += SessionEntry(id, lessonId, date, subject, items)
        }
        val tasks = mutableListOf<HomeworkItem>()
        for (item in homework?.listV("ListeTravauxAFaire").orEmpty()) {
            val due = parseDate(item.text("PourLe"))
            if (due == null) {
                skipped++
                continue
            }
            if (!window.contains(due)) continue
            val description = safeText(item.text("descriptif"))
            tasks += HomeworkItem(
                id = item.opt("N")?.toString().orEmpty(),
                dueDate = due,
                subject = plainText(item.labelOf("Matiere")).ifEmpty { "Cours" },
                description = description.text,
                links = description.links,
                done = item.optBoolean("TAFFait", false),
                attachments = attachments(item),
                remoteDone = item.opt("TAFFait") as? Boolean,
            )
        }
        val sortedSessions = withOrdinals(sessions).sortedByDescending { it.date }
        val sortedTasks = tasks.sortedBy { it.dueDate }
        val count = sortedSessions.sumOf { s -> s.contents.sumOf { it.attachments.size } } + sortedTasks.sumOf { it.attachments.size }
        return NotebookSnapshot(window.from, window.to, sortedSessions, sortedTasks, skipped, count, homeworkAvailable = homework != null)
    }

    // endregion

    // region Téléchargement

    private val acceptedId = Regex("^[!#-\\[\\]-~]+$")

    fun isDownloadableId(id: String) = acceptedId.matches(id)

    fun plaintext(id: String): ByteArray = ("{\"N\":\"" + id + "\",\"Actif\":true}").toByteArray(UTF_8)

    /** PKCS#7 sur des blocs de 16 octets. */
    fun pkcs7(data: ByteArray): ByteArray {
        val pad = 16 - data.size % 16
        return data + ByteArray(pad) { pad.toByte() }
    }

    /**
     * Identifiant chiffré de `FichiersExternes` : pronotepy remplit deux fois (PKCS#7 puis AES qui
     * remplit à nouveau). [doublePadding] faux donne la variante simple (point incertain).
     */
    fun encryptedId(id: String, key: ByteArray, iv: ByteArray, doublePadding: Boolean = true): String {
        val clear = plaintext(id).let { if (doublePadding) pkcs7(it) else it }
        return PronoteCrypto.hex(PronoteCrypto.encrypt(clear, key, iv)).lowercase()
    }

    /** `encodeURIComponent` : UTF-8, `%XX` majuscules, non encodés `A–Z a–z 0–9 - _ . ! ~ * ' ( )`. */
    fun encodeURIComponent(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(UTF_8)) {
            val c = (byte.toInt() and 0xFF)
            val ch = c.toChar()
            if (c < 128 && (ch.isLetterOrDigit() || ch in "-_.!~*'()")) out.append(ch) else out.append('%').append("%02X".format(c))
        }
        return out.toString()
    }

    /** Construite dans la couche transport au moment du `GET`, jamais exposée ni conservée. */
    fun downloadUrl(root: String, encryptedId: String, name: String, session: Long): String =
        "$root/FichiersExternes/$encryptedId/${encodeURIComponent(name)}?Session=$session"

    /** Nom d’enregistrement : dernier segment, caractères réservés remplacés, contrôles retirés. */
    fun saveAsName(name: String): String {
        val last = name.substring(maxOf(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1)
        val out = StringBuilder()
        var i = 0
        while (i < last.length) {
            val code = last.codePointAt(i)
            when {
                code < 128 && code.toChar() in "<>:\"|?*" -> out.append('_')
                isRemoved(code) -> {}
                else -> out.appendCodePoint(code)
            }
            i += Character.charCount(code)
        }
        return out.toString().trim(' ', '.').ifEmpty { "Document" }
    }

    // endregion
}
