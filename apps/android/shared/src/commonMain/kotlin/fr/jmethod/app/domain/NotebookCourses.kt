package fr.jmethod.app.domain

import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.CourseSource
import fr.jmethod.app.pronote.NotebookRules
import fr.jmethod.app.model.NotebookImport
import fr.jmethod.app.model.Subject
import fr.jmethod.app.pronote.NotebookSnapshot
import fr.jmethod.app.pronote.SessionEntry
import java.text.Normalizer
import java.time.LocalDate

/**
 * Du cahier de textes aux cours (`native/shared/notebook-to-course.md`, règles précisées dans
 * `native/shared/documents/README.md`) : une séance = un cours. Logique pure, partagée par le bouton
 * « Créer un cours » et la création automatique, vérifiée par les vecteurs `document-title-vectors.json`
 * et `notebook-course-vectors.json`.
 */
object NotebookCourses {
    const val SOURCE_KIND = "pronote-notebook"
    const val NO_CHAPTER = "Sans chapitre"
    const val MAX_DOCUMENTS = 10
    const val MAX_DESCRIPTION = 2_000
    const val MAX_TITLE = 120
    const val BACKFILL_DAYS = 14L

    // region État d’import, éligibilité, proposition

    sealed interface ImportState {
        /** Jamais importée, ou cours supprimé (`dismissed`) : « Créer un cours ». */
        data class Create(val dismissed: Boolean) : ImportState
        /** `created` et cours existant : « Voir le cours ». */
        data class View(val courseId: String) : ImportState
    }

    /** Clé stable de la séance (section 1) : `pn1:<date>|<matière normalisée>|<rang>`. */
    fun sessionKey(session: SessionEntry): String = session.key

    /**
     * Ceinture et bretelles (section 1) : cours existant issu du cahier pour la même date, la même
     * matière normalisée et le même rang, quelle que soit sa clé.
     */
    fun courseFor(date: String, subjectLabel: String, ordinal: Int, courses: List<Course>): Course? {
        val subject = NotebookRules.normalizeSubject(subjectLabel)
        return courses.firstOrNull { course ->
            (course.sources ?: listOfNotNull(course.source)).any { source ->
                source.kind == SOURCE_KIND && source.date == date && source.ordinal == ordinal &&
                    NotebookRules.normalizeSubject(source.subjectLabel) == subject
            }
        }
    }

    fun courseFor(session: SessionEntry, courses: List<Course>): Course? = courseFor(session.date.toString(), session.subject, session.ordinal, courses)

    fun courseFor(source: CourseSource, courses: List<Course>): Course? = source.ordinal?.let { courseFor(source.date, source.subjectLabel, it, courses) }

    /** Règle 31 : `viewCourse` si `created` et le cours existe ; sinon `createCourse`. */
    fun importState(sessionKey: String, imports: Map<String, NotebookImport>, courses: List<Course>): ImportState {
        val entry = imports[sessionKey] ?: return ImportState.Create(dismissed = false)
        val courseId = entry.courseId
        return if (entry.status == NotebookImport.CREATED && courseId != null && courses.any { it.id == courseId }) {
            ImportState.View(courseId)
        } else {
            ImportState.Create(dismissed = entry.status == NotebookImport.DISMISSED)
        }
    }

    /** Règle 31 avec la ceinture et bretelles : un cours existant pour la même séance donne « Voir le cours ». */
    fun importState(session: SessionEntry, imports: Map<String, NotebookImport>, courses: List<Course>): ImportState {
        val state = importState(session.key, imports, courses)
        if (state is ImportState.View) return state
        return courseFor(session, courses)?.let { ImportState.View(it.id) } ?: state
    }

    /** Règle 27 : une seule raison d’exclusion, par priorité ; `null` si la séance est éligible. */
    fun skipReason(
        session: SessionEntry,
        settings: AppSettings,
        imports: Map<String, NotebookImport>,
        subjects: List<Subject>,
        today: LocalDate,
        courses: List<Course> = emptyList(),
    ): String? {
        if (settings.notebookAutoCreate != true) return "autoCreateDisabled"
        imports[session.key]?.status?.let { if (it == NotebookImport.CREATED || it == NotebookImport.DISMISSED) return it }
        if (courseFor(session, courses) != null) return "courseExists"
        if (session.date.isAfter(today)) return "future"
        settings.notebookAutoCreateSince?.let(FrenchDates::parse)?.let { if (session.date.isBefore(it)) return "beforeSince" }
        if (SubjectMatcher.match(session.subject, subjects) == null) return "unknownSubject"
        return null
    }

    /** Règle 28 : séances éligibles, date croissante, ordre de la réponse pour une même date. */
    fun eligible(
        snapshot: NotebookSnapshot,
        settings: AppSettings,
        imports: Map<String, NotebookImport>,
        subjects: List<Subject>,
        today: LocalDate,
        courses: List<Course> = emptyList(),
    ): List<SessionEntry> = snapshot.sessions.filter { skipReason(it, settings, imports, subjects, today, courses) == null }.sortedBy { it.date }

    data class Backfill(val from: LocalDate, val to: LocalDate, val sessionKeys: List<String>) {
        val count get() = sessionKeys.size
        val message get() = if (count > 0) "Importer aussi les $count séances des 14 derniers jours ?" else null
    }

    /** Règle 29 : `today − 14 ≤ date ≤ today − 1`, ni `created` ni `dismissed`, matière reconnue. */
    fun backfill(snapshot: NotebookSnapshot, imports: Map<String, NotebookImport>, subjects: List<Subject>, today: LocalDate, courses: List<Course> = emptyList()): Backfill {
        val from = today.minusDays(BACKFILL_DAYS)
        val to = today.minusDays(1)
        val keys = snapshot.sessions.filter { session ->
            !session.date.isBefore(from) && !session.date.isAfter(to) &&
                imports[session.key]?.status.let { it != NotebookImport.CREATED && it != NotebookImport.DISMISSED } &&
                courseFor(session, courses) == null &&
                SubjectMatcher.match(session.subject, subjects) != null
        }.map { it.key }
        return Backfill(from, to, keys)
    }

    data class KeyMigration(val courses: List<Course>, val imports: Map<String, NotebookImport>)

    /**
     * Migration des anciennes clés (`N` de session Pronote) vers `pn1:` (section 1, règles 37 à 39 du
     * README des documents, port de `reference.py`), idempotente. Aucun cours n’est supprimé.
     */
    fun migrateKeys(courses: List<Course>, imports: Map<String, NotebookImport>): KeyMigration {
        val ids = courses.mapTo(HashSet()) { it.id }
        // Entrées qui désignent chaque cours existant, dans l’ordre de `notebookImports`.
        val pointing = LinkedHashMap<String, MutableList<Pair<String, NotebookImport>>>()
        imports.forEach { (key, entry) -> entry.courseId?.takeIf { it in ids }?.let { pointing.getOrPut(it) { mutableListOf() } += key to entry } }
        val position = courses.withIndex().associate { (index, course) -> course.id to index }
        // Ordre de création : `createdAt` (jour), plus petit `at` des entrées (absent = en dernier), position décroissante.
        val creationOrder = compareBy<Course>(
            { it.createdAt },
            { course -> pointing[course.id].orEmpty().map { it.second.at }.filter { it.isNotEmpty() }.minOrNull() ?: "\uFFFF" },
            { -position.getValue(it.id) },
        )
        val notebookIds = courses.filter { it.source?.kind == SOURCE_KIND }.mapTo(LinkedHashSet()) { it.id }
        val ordinals = HashMap<String, Int>()
        val keys = HashMap<String, String>()
        courses.filter { it.id in notebookIds }
            .groupBy { it.source!!.date to NotebookRules.normalizeSubject(it.source!!.subjectLabel) }
            .forEach { (group, members) ->
                val (date, subject) = group
                // Doublons de l’ancien bug (même titre et même chapitre) : même rang.
                val signatures = mutableListOf<Pair<Pair<String, String>, Int>>()
                members.filter { it.source!!.ordinal == null }.sortedWith(creationOrder).forEach { course ->
                    val signature = fold(cleanWhitespace(course.title)) to fold(cleanWhitespace(course.chapter))
                    val ordinal = signatures.firstOrNull { it.first == signature }?.second
                        ?: (signatures.size + 1).also { signatures += signature to it }
                    ordinals[course.id] = ordinal
                }
                members.forEach { course ->
                    val ordinal = ordinals[course.id] ?: course.source!!.ordinal!!
                    ordinals[course.id] = ordinal
                    keys[course.id] = NotebookRules.sessionKey(date, subject, ordinal)
                }
            }
        val migrated = courses.map { course ->
            val key = keys[course.id] ?: return@map course
            val source = course.source!!
            if (source.sessionKey == key && source.ordinal == ordinals[course.id]) course
            else course.copy(source = source.copy(sessionKey = key, ordinal = ordinals[course.id]))
        }
        val result = HashMap<String, NotebookImport>()
        val consumed = HashSet<String>()
        migrated.filter { it.id in notebookIds }.groupBy { keys.getValue(it.id) }.forEach { (key, members) ->
            val sorted = members.sortedWith(creationOrder)
            val entries = sorted.flatMap { pointing[it.id].orEmpty() }
            if (entries.isEmpty()) return@forEach
            entries.forEach { consumed += it.first }
            val oldest = sorted.first()
            val own = pointing[oldest.id].orEmpty().map { it.second }
            val at = (own.ifEmpty { entries.map { it.second } }).map { it.at }.filter { it.isNotEmpty() }.minOrNull().orEmpty()
            result[key] = NotebookImport(own.firstOrNull()?.status ?: NotebookImport.CREATED, oldest.id, at)
        }
        imports.forEach { (key, entry) ->
            if (key in consumed || key in result) return@forEach
            if (key.startsWith(NotebookRules.KEY_PREFIX) || entry.courseId in ids) result[key] = entry
        }
        return KeyMigration(migrated, result.toSortedMap().let { LinkedHashMap(it) })
    }

    /** Règle 30. */
    fun toast(count: Int): String? = when (count) {
        0 -> null
        1 -> "1 cours créé depuis le cahier de textes"
        else -> "$count cours créés depuis le cahier de textes"
    }

    // endregion

    // region Normalisation (règles 15 à 21)

    private const val WHITESPACE = "\t\n\u000B\u000C\r    "
    private val removed = Regex("[\u0000-\u0008\u000E-\u001F\u007F-\u009F‎‏‪-‮⁦-⁩﻿]")
    private val whitespaceRun = Regex("[\\t\\n\\u000B\\u000C\\r \\u00A0\\u2009\\u202F]+")
    private const val QUOTES = "\"'«»“”„‘’‹›"
    private const val TRAILING = ".:;-–—"
    private val prefixes = listOf("Microsoft Word - ", "Microsoft PowerPoint - ")
    private val suffixes = listOf(".docx", ".odt", ".pdf", ".pptx")
    private val months = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

    private val numberPattern = Regex("^[+-]?\\d+([.,]\\d+)?$")
    private val dateNumeric = Regex("^\\d{1,4}[/.-]\\d{1,2}([/.-]\\d{1,4})?$")
    private val dateText = Regex(
        "^((lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)\\s+)?(\\d{1,2}|1er)\\s+" +
            "(janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)(\\s+\\d{4})?$",
    )
    private val adminHeader = Regex("^(nom|prenom|classe|date)\\s*:")
    private val generic = Regex("^(sans titre|untitled|document\\s*\\d*|presentation\\s*\\d*|diapositive\\s*\\d+|cours|seance|exercices?|devoirs?)$")

    /** Repli de `matchSubject` : minuscules, NFD, retrait de U+0300–U+036F. */
    fun fold(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("[̀-ͯ]"), "")

    /** NFC, caractères de contrôle et de direction retirés, blancs repliés, rognage. */
    fun cleanWhitespace(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).replace(removed, "").replace(whitespaceRun, " ").trim(' ')

    /** Au plus [limit] caractères, coupe au dernier blanc (coupe sèche s’il n’y en a pas). */
    fun cutWords(text: String, limit: Int, spaces: String = " "): String {
        if (text.length <= limit) return text
        if (text[limit] in spaces) return text.substring(0, limit).trimEnd { it in spaces }
        val head = text.substring(0, limit)
        val cut = head.indexOfLast { it in spaces }
        return (if (cut > 0) head.substring(0, cut) else head).trimEnd { it in spaces }
    }

    private fun stripEnds(text: String): String {
        var current = text
        var previous: String? = null
        while (previous != current) {
            previous = current
            current = current.trim(' ').trimStart { it in QUOTES }.trimEnd { it in QUOTES || it in TRAILING }
        }
        return current
    }

    /** Règle 15 ; [fileName] : `_+` et `-{2,}` deviennent une espace (candidat 4). */
    fun normalize(raw: String, fileName: Boolean = false): String {
        var text = raw
        if (fileName) text = text.replace(Regex("_+"), " ").replace(Regex("-{2,}"), " ")
        text = cleanWhitespace(text)
        prefixes.firstOrNull { text.lowercase().startsWith(it.lowercase()) }?.let { text = text.substring(it.length).trimStart(' ') }
        suffixes.firstOrNull { text.lowercase().endsWith(it) }?.let { text = text.dropLast(it.length).trimEnd(' ') }
        text = stripEnds(text)
        if (text.length > MAX_TITLE) text = stripEnds(cutWords(text, MAX_TITLE))
        return text
    }

    /** Contexte des rejets : noms de matière (libellé Pronote et matière reconnue) et nom du fichier principal. */
    class Context(subjectNames: List<String>, fileName: String?) {
        val subjects: Set<String> = subjectNames.mapNotNull { name -> normalize(name).takeIf { it.isNotEmpty() }?.let(::fold) }.toSet()
        val fileName: String? = fileName?.let(::fold)
    }

    /** Règle 17 : première raison de rejet, ou `null`. */
    fun rejectReason(value: String, raw: String, context: Context): String? {
        if (value.isEmpty()) return "empty"
        val folded = fold(value)
        if (numberPattern.matches(folded) || dateNumeric.matches(folded) || dateText.matches(folded)) return "numberOrDate"
        if (value.count { it.isLetter() } < 3) return "tooFewLetters"
        if (adminHeader.containsMatchIn(folded)) return "adminHeader"
        if (generic.matches(folded)) return "generic"
        if (folded in context.subjects) return "subjectName"
        val file = context.fileName
        if (file != null && (file == folded || file == fold(cleanWhitespace(raw)))) return "fileName"
        return null
    }

    enum class Source(val wire: String) {
        METADATA("metadata"), FIRST_HEADING("firstHeading"), PRONOTE("pronoteTitle"), FILE_NAME("fileName"), DATE_FALLBACK("dateFallback")
    }

    data class Candidate(val source: Source, val raw: String, val value: String, val reason: String?) {
        val accepted get() = reason == null
    }

    fun candidate(source: Source, raw: String, context: Context): Candidate {
        val value = normalize(raw, fileName = source == Source.FILE_NAME)
        return Candidate(source, raw, value, rejectReason(value, raw, context))
    }

    /** Candidat 5 : « Cours du 18 novembre », « Cours du 1er novembre ». */
    fun fallbackTitle(date: LocalDate) = "Cours du ${if (date.dayOfMonth == 1) "1er" else date.dayOfMonth} ${months[date.monthValue - 1]}"

    /** Candidat 4 : nom privé de sa dernière extension (`.` + 1 à 5 alphanumériques). */
    fun fileStem(name: String): String = Regex("^(.*)\\.[A-Za-z0-9]{1,5}$").find(name)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: name

    // endregion

    // region Chapitre et titre (règles 22 à 25)

    private val chapterPattern = Regex(
        "^(?i:(chapitre|chap\\.?|ch\\.?|s[eéEÉ]quence|s[eéEÉ]q\\.?|th[eèéEÈÉ]me|partie|unit[eéEÉ]))" +
            "\\s*([0-9]+|[IVXLC]+)(?![0-9A-Za-zÀ-ÖØ-öø-ÿ])\\s*[:\\-–—.)]?\\s*(.*)$",
    )
    private val chapterLabels = mapOf(
        "chapitre" to "Chapitre", "chap" to "Chapitre", "ch" to "Chapitre", "sequence" to "Séquence",
        "seq" to "Séquence", "theme" to "Thème", "partie" to "Partie", "unite" to "Unité",
    )

    /** Intitulé de chapitre normalisé et reste de la ligne, ou `null`. */
    fun chapterOf(value: String): Pair<String, String>? {
        val match = chapterPattern.find(value) ?: return null
        val key = fold(match.groupValues[1]).trimEnd('.')
        return "${chapterLabels.getValue(key)} ${match.groupValues[2]}" to match.groupValues[3]
    }

    private fun validRest(rest: String, context: Context): String? = normalize(rest).takeIf { rejectReason(it, rest, context) == null }

    data class Choice(val title: String?, val chapter: String, val titleFrom: Int?, val chapterFrom: Int?)

    fun chooseTitle(candidates: List<Candidate>, context: Context): Choice {
        val accepted = candidates.indices.filter { candidates[it].accepted }
        val first = accepted.firstOrNull() ?: return Choice(null, NO_CHAPTER, null, null)
        val found = chapterOf(candidates[first].value)
        if (found != null) {
            var title = validRest(found.second, context)
            var titleFrom: Int? = first
            if (title == null) {
                titleFrom = null
                for (i in accepted.drop(1)) {
                    val sub = chapterOf(candidates[i].value)
                    if (sub == null) {
                        title = candidates[i].value
                        titleFrom = i
                        break
                    }
                    val rest = validRest(sub.second, context)
                    if (rest != null) {
                        title = rest
                        titleFrom = i
                        break
                    }
                }
            }
            return Choice(title, found.first, titleFrom, first)
        }
        candidates.forEachIndexed { i, c ->
            if (c.source in setOf(Source.METADATA, Source.FIRST_HEADING, Source.PRONOTE) && c.value.isNotEmpty()) {
                chapterOf(c.value)?.let { return Choice(candidates[first].value, it.first, first, i) }
            }
        }
        return Choice(candidates[first].value, NO_CHAPTER, first, null)
    }

    /** Extraction d’un document, réduite à ce qui sert au titre. */
    data class Extraction(
        val metadata: String? = null,
        /** Premier titre (hors PDF). */
        val firstHeading: String? = null,
        /** Lignes de la page 1 d’un PDF (texte, taille), dans l’ordre du flux. */
        val pdfLines: List<Pair<String, Double>>? = null,
    )

    /** Règle 7 : première ligne non rejetée par taille décroissante (tri stable), sinon la première de cet ordre. */
    fun pdfHeading(lines: List<Pair<String, Double>>, context: Context): String? {
        val ordered = lines.sortedByDescending { it.second }
        return ordered.firstOrNull { candidate(Source.FIRST_HEADING, it.first, context).accepted }?.first ?: ordered.firstOrNull()?.first
    }

    fun documentCandidates(extraction: Extraction, context: Context): List<Candidate> = buildList {
        extraction.metadata?.let { add(candidate(Source.METADATA, it, context)) }
        val heading = extraction.pdfLines?.let { pdfHeading(it, context) } ?: extraction.firstHeading.takeIf { extraction.pdfLines == null }
        heading?.let { add(candidate(Source.FIRST_HEADING, it, context)) }
    }

    data class TitleResult(
        val candidates: List<Candidate>,
        val title: String,
        val chapter: String,
        val titleSource: Source?,
        val chapterSource: Source?,
    )

    /**
     * Titre et chapitre d’une séance. [principalName] et [extraction] : document principal (null sans
     * document extractible ; extraction null si elle a échoué).
     */
    fun titlePipeline(principalName: String?, extraction: Extraction?, contentTitle: String?, subjectNames: List<String>, date: LocalDate): TitleResult {
        val context = Context(subjectNames, principalName)
        val candidates = mutableListOf<Candidate>()
        if (principalName != null && extraction != null) candidates += documentCandidates(extraction, context)
        if (contentTitle != null) candidates += candidate(Source.PRONOTE, contentTitle, context)
        if (principalName != null) candidates += candidate(Source.FILE_NAME, fileStem(principalName), context)
        val fallback = fallbackTitle(date)
        candidates += Candidate(Source.DATE_FALLBACK, fallback, fallback, null)
        val choice = chooseTitle(candidates, context)
        return TitleResult(candidates, choice.title ?: fallback, choice.chapter, choice.titleFrom?.let { candidates[it].source }, choice.chapterFrom?.let { candidates[it].source })
    }

    /** Règle 25 : titre tiré des seuls candidats 1 et 2 du document. */
    fun extractedTitle(extraction: Extraction?, name: String, subjectNames: List<String>): String? {
        extraction ?: return null
        val context = Context(subjectNames, name)
        val candidates = documentCandidates(extraction, context)
        return if (candidates.isEmpty()) null else chooseTitle(candidates, context).title
    }

    /** Règle 12 : titre du premier contenu dont le titre est non vide. */
    fun contentTitle(session: SessionEntry): String? = session.contents.firstOrNull { it.title.isNotEmpty() }?.title

    /** Règle 20 : libellé Pronote de la séance et matière reconnue. */
    fun subjectNames(session: SessionEntry, subjects: List<Subject>): List<String> =
        listOfNotNull(session.subject, SubjectMatcher.match(session.subject, subjects)?.name)

    // endregion

    // region Description (règle 26)

    fun description(session: SessionEntry): String {
        val parts = session.contents.flatMap { listOf(it.title, it.description) }.filter { it.isNotEmpty() }
        return cutWords(parts.joinToString("\n\n"), MAX_DESCRIPTION, WHITESPACE)
    }

    // endregion
}
