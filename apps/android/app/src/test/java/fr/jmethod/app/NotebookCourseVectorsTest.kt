package fr.jmethod.app

import fr.jmethod.app.documents.DocType
import fr.jmethod.app.documents.DocumentAnalysis
import fr.jmethod.app.documents.DocumentAnalyzer
import fr.jmethod.app.domain.CourseEditing
import fr.jmethod.app.domain.NotebookCourses
import fr.jmethod.app.domain.SubjectMatcher
import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.CourseSource
import fr.jmethod.app.model.AppDocument
import fr.jmethod.app.data.DataMigrations
import fr.jmethod.app.pronote.NotebookRules
import fr.jmethod.app.model.NotebookImport
import fr.jmethod.app.model.Subject
import fr.jmethod.app.pronote.NotebookAttachment
import fr.jmethod.app.pronote.NotebookContent
import fr.jmethod.app.pronote.NotebookLink
import fr.jmethod.app.pronote.NotebookSnapshot
import fr.jmethod.app.pronote.SessionEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Rejoue `native/shared/documents/document-title-vectors.json` (28 documents) et
 * `native/shared/notebook-course-vectors.json` (préremplissage et 5 scénarios).
 */
class NotebookCourseVectorsTest {
    private fun resource(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(path)?.use { it.readBytes() } ?: error("Ressource absente : $path")

    private fun json(path: String) = JSONObject(String(resource(path), Charsets.UTF_8))
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    private val documents by lazy { json("documents/document-title-vectors.json") }
    private val courses by lazy { json("notebook-course-vectors.json") }

    // region Documents

    @Test
    fun documentTitleVectors() {
        val cases = documents.getJSONArray("cases").objects()
        for (case in cases) {
            val id = case.getString("id")
            val input = case.getJSONObject("input")
            val name = input.getString("fileName")
            val bytes = resource("documents/" + case.getString("file"))
            assertEquals(id, case.getInt("size"), bytes.size)
            val analysis = DocumentAnalyzer.analyze(bytes, name)
            assertEquals(id, case.str("detectedType"), analysis.type?.extension)
            assertEquals(id, case.str("detectedBy"), analysis.detectedBy)
            assertEquals(id, case.getString("mime"), DocType.mimeOf(name, analysis.type))

            val extraction = case.getJSONObject("extraction")
            assertEquals(id, extraction.str("error"), if (analysis.unreadable) "unreadable" else null)
            assertEquals(id, extraction.str("metadata"), analysis.metadata)
            assertEquals(id, extraction.str("metadataDetail"), analysis.metadataDetail)
            if (extraction.has("pdfLines") && !extraction.isNull("pdfLines")) {
                val lines = extraction.getJSONArray("pdfLines").objects().map { it.getString("text") to it.getDouble("size") }
                assertEquals(id, lines, analysis.pdfLines?.map { it.text to it.size })
            }

            val names = listOfNotNull(input.str("subjectLabel"), input.str("subjectName"))
            val principal = if (analysis.type != null) name else null
            val result = NotebookCourses.titlePipeline(
                principal, analysis.extraction, input.str("contentTitle"), names, LocalDate.parse(input.getString("sessionDate")),
            )
            val candidates = case.getJSONArray("candidates").objects()
            assertEquals(id, candidates.map { it.getString("source") }, result.candidates.map { it.source.wire })
            candidates.zip(result.candidates).forEach { (expected, actual) ->
                assertEquals("$id ${actual.source}", expected.getString("raw"), actual.raw)
                assertEquals("$id ${actual.source}", expected.getString("value"), actual.value)
                assertEquals("$id ${actual.source}", expected.str("reason"), actual.reason)
            }
            val expected = case.getJSONObject("expected")
            assertEquals(id, expected.getString("title"), result.title)
            assertEquals(id, expected.getString("chapter"), result.chapter)
            assertEquals(id, expected.str("titleSource"), result.titleSource?.wire)
            assertEquals(id, expected.str("chapterSource"), result.chapterSource?.wire)
            assertEquals(id, expected.str("extractedTitle"), NotebookCourses.extractedTitle(analysis.extraction, name, names))

            if (case.has("readingView") && !case.isNull("readingView")) {
                val blocks = case.getJSONArray("readingView").objects().map {
                    Triple(it.getString("kind"), if (it.has("level")) it.getInt("level") else null, it.getString("text"))
                }
                assertEquals(id, blocks, analysis.readingView?.map { Triple(it.kind.wire, it.level, it.text) })
            }
        }
        assertEquals(28, cases.size)
    }

    @Test
    fun normalizationRules() {
        assertEquals("Cours du 1er novembre", NotebookCourses.fallbackTitle(LocalDate.of(2026, 11, 1)))
        // Règle 22 : « CHIMIE » (ch + I) n’est pas un chapitre ; numéro gardé tel quel.
        assertNull(NotebookCourses.chapterOf("CHIMIE organique"))
        assertNull(NotebookCourses.chapterOf("Partie civile"))
        assertEquals("Chapitre 02" to "Les nombres", NotebookCourses.chapterOf("chapitre 02 : Les nombres"))
        assertEquals("Physique-chimie", NotebookCourses.fileStem("Physique-chimie.pdf").let { NotebookCourses.normalize(it, fileName = true) })
    }

    // endregion

    // region Cahier de textes → cours

    private val subjects by lazy {
        courses.getJSONArray("subjects").objects().map { Subject(it.getString("id"), it.getString("name"), it.getString("color"), it.str("description")) }
    }

    private fun attachment(raw: JSONObject) = NotebookAttachment(
        if (raw.getString("kind") == "file") NotebookAttachment.Kind.FILE else NotebookAttachment.Kind.LINK,
        raw.getString("id"), raw.getString("name"), raw.str("url"),
    )

    private fun session(raw: JSONObject) = SessionEntry(
        id = raw.getString("id"),
        lessonId = raw.getString("lessonId"),
        date = LocalDate.parse(raw.getString("date")),
        subject = raw.getString("subject"),
        contents = raw.getJSONArray("contents").objects().map { c ->
            NotebookContent(
                c.getString("title"), c.getString("description"),
                c.getJSONArray("links").objects().map { NotebookLink(it.getString("label"), it.getString("url")) },
                c.getString("category"), c.getJSONArray("attachments").objects().map(::attachment),
            )
        },
    )

    private val rawSessions by lazy { courses.getJSONArray("sessions").objects() }
    /** Rangs comptés sur l’instantané entier, dans l’ordre du modèle (règle 34). */
    private val sessions by lazy { NotebookRules.withOrdinals(rawSessions.map(::session)) }

    private data class Download(val name: String, val bytes: ByteArray, val analysis: DocumentAnalysis)

    /** Téléchargement simulé : `fixture` = contenu reçu, `downloadError` = échec ; 10 enregistrés au plus. */
    private fun download(raw: JSONObject): Pair<List<Download>, List<Triple<String, String, String>>> {
        val docs = mutableListOf<Download>()
        val skipped = mutableListOf<Triple<String, String, String>>()
        for (content in raw.getJSONArray("contents").objects()) {
            for (a in content.getJSONArray("attachments").objects()) {
                if (a.getString("kind") != "file") continue
                when {
                    docs.size >= NotebookCourses.MAX_DOCUMENTS -> skipped += Triple(a.getString("id"), a.getString("name"), "limit")
                    a.has("downloadError") -> skipped += Triple(a.getString("id"), a.getString("name"), a.getString("downloadError"))
                    else -> {
                        val bytes = resource("documents/" + a.getString("fixture"))
                        docs += Download(a.getString("name"), bytes, DocumentAnalyzer.analyze(bytes, a.getString("name")))
                    }
                }
            }
        }
        return docs to skipped
    }

    private data class Prefill(
        val subjectId: String?, val chapter: String, val title: String, val titleSource: String?, val description: String,
        val principal: String?, val documents: List<JSONObject>, val skipped: List<Triple<String, String, String>>,
    )

    private fun prefill(raw: JSONObject, session: SessionEntry): Prefill {
        val subject = SubjectMatcher.match(session.subject, subjects)
        val (docs, skipped) = download(raw)
        val principal = docs.firstOrNull { it.analysis.type != null }
        val names = NotebookCourses.subjectNames(session, subjects)
        val result = NotebookCourses.titlePipeline(principal?.name, principal?.analysis?.extraction, NotebookCourses.contentTitle(session), names, session.date)
        return Prefill(
            subject?.id, result.chapter, result.title, result.titleSource?.wire, NotebookCourses.description(session), principal?.name,
            docs.map { d ->
                JSONObject().put("name", d.name).put("mime", DocType.mimeOf(d.name, d.analysis.type)).put("size", d.bytes.size)
                    .put("extractedTitle", if (d.analysis.type != null) NotebookCourses.extractedTitle(d.analysis.extraction, d.name, names) ?: JSONObject.NULL else JSONObject.NULL)
            },
            skipped,
        )
    }

    private fun course(raw: JSONObject) = Course(
        raw.getString("id"), raw.getString("subjectId"), raw.getString("chapter"), raw.getInt("courseNumber"), raw.getString("title"),
        raw.getString("date"), raw.str("description"), raw.str("createdAt") ?: raw.getString("date"),
        source = raw.optJSONObject("source")?.let { src ->
            CourseSource(
                src.getString("kind"), src.getString("sessionKey"), src.getString("subjectLabel"), src.getString("date"),
                if (src.has("ordinal") && !src.isNull("ordinal")) src.getInt("ordinal") else null,
            )
        },
    )

    private fun imports(raw: JSONObject): MutableMap<String, NotebookImport> = raw.keys().asSequence().associateWith { key ->
        raw.getJSONObject(key).let { NotebookImport(it.getString("status"), it.str("courseId"), it.optString("at")) }
    }.toMutableMap()

    private fun documentsEqual(label: String, expected: JSONArray, actual: List<JSONObject>) {
        assertEquals(label, expected.length(), actual.size)
        expected.objects().zip(actual).forEach { (e, a) ->
            listOf("name", "mime", "size", "extractedTitle").forEach { key -> assertEquals("$label $key", e.opt(key).toString(), a.opt(key).toString()) }
        }
    }

    @Test
    fun prefillVectors() {
        val base = courses.getJSONArray("scenarios").objects().first().getJSONArray("courses").objects().map(::course)
        val expectedList = courses.getJSONArray("prefill").objects()
        assertEquals(sessions.size, expectedList.size)
        for ((index, expected) in expectedList.withIndex()) {
            val session = sessions[index]
            val label = expected.getString("sessionKey")
            assertEquals(label, session.key)
            assertEquals(label, expected.getInt("ordinal"), session.ordinal)
            assertEquals(label, expected.getString("notebookId"), session.id)
            val actual = prefill(rawSessions[index], session)
            assertEquals(label, expected.str("subjectId"), actual.subjectId)
            assertEquals(label, expected.getString("date"), session.date.toString())
            assertEquals(label, expected.getString("chapter"), actual.chapter)
            assertEquals(label, expected.getString("title"), actual.title)
            assertEquals(label, expected.str("titleSource"), actual.titleSource)
            assertEquals(label, expected.getString("description"), actual.description)
            assertEquals(label, expected.str("principalDocument"), actual.principal)
            documentsEqual(label, expected.getJSONArray("documents"), actual.documents)
            assertEquals(label, expected.getJSONArray("skippedAttachments").objects().map { Triple(it.getString("id"), it.getString("name"), it.getString("reason")) }, actual.skipped)
            if (actual.subjectId != null) {
                assertEquals(label, expected.getInt("courseNumber"), CourseEditing.courseNumber(base, actual.subjectId, actual.chapter))
            } else {
                assertTrue(label, !expected.has("courseNumber"))
            }
        }
    }

    private fun settings(raw: JSONObject) = AppSettings(
        notebookAutoCreate = if (raw.has("notebookAutoCreate")) raw.getBoolean("notebookAutoCreate") else null,
        notebookAutoCreateSince = raw.str("notebookAutoCreateSince"),
    )

    @Test
    fun subjectNormalizationAndSessionKeys() {
        courses.getJSONArray("subjectNormalization").objects().forEach {
            assertEquals(it.getString("input"), it.getString("expected"), NotebookRules.normalizeSubject(it.getString("input")))
        }
        val expected = courses.getJSONArray("sessionKeys").objects()
        assertEquals(expected.size, sessions.size)
        expected.zip(sessions).forEach { (e, s) ->
            assertEquals(e.getString("id"), s.id)
            assertEquals(e.getString("normalizedSubject"), NotebookRules.normalizeSubject(s.subject))
            assertEquals(e.getInt("ordinal"), s.ordinal)
            assertEquals(e.getString("sessionKey"), s.key)
        }
    }

    private fun runScenario(scenario: JSONObject) {
        val label = scenario.getString("id")
        val today = LocalDate.parse(scenario.getString("today"))
        val settings = settings(scenario.getJSONObject("settings"))
        val raws = scenario.getJSONArray("sessions").objects()
        val sessions = NotebookRules.withOrdinals(raws.map(::session))
        val existing = scenario.getJSONArray("courses").objects().map(::course).toMutableList()
        val imports = imports(scenario.getJSONObject("notebookImports"))
        val expected = scenario.getJSONObject("expected")
        val snapshot = NotebookSnapshot(today, today, sessions, emptyList(), 0, 0)
        assertEquals(label, expected.getJSONArray("sessionKeys").let { a -> (0 until a.length()).map { a.getString(it) } }, sessions.map { it.key })

        if (expected.has("backfillProposal")) {
            val proposal = expected.getJSONObject("backfillProposal")
            val backfill = NotebookCourses.backfill(snapshot, imports, subjects, today, existing)
            assertEquals(label, proposal.getString("from"), backfill.from.toString())
            assertEquals(label, proposal.getString("to"), backfill.to.toString())
            assertEquals(label, proposal.getInt("count"), backfill.count)
            assertEquals(label, proposal.getJSONArray("sessionKeys").let { a -> (0 until a.length()).map { a.getString(it) } }, backfill.sessionKeys)
            assertEquals(label, proposal.str("message"), backfill.message)
            assertEquals(label, proposal.getString("acceptSetsSince"), today.minusDays(NotebookCourses.BACKFILL_DAYS).toString())
        }

        val skipped = sessions.mapNotNull { s -> NotebookCourses.skipReason(s, settings, imports, subjects, today, existing)?.let { s.key to it } }
        assertEquals(label, expected.getJSONArray("skipped").objects().map { it.getString("sessionKey") to it.getString("reason") }, skipped)
        val eligible = NotebookCourses.eligible(snapshot, settings, imports, subjects, today, existing)
        assertEquals(label, expected.getJSONArray("eligibleSessionKeys").let { a -> (0 until a.length()).map { a.getString(it) } }, eligible.map { it.key })

        val createdExpected = expected.getJSONArray("createdCourses").objects()
        assertEquals(label, createdExpected.size, eligible.size)
        for ((session, e) in eligible.zip(createdExpected)) {
            val raw = raws[sessions.indexOf(session)]
            val pf = prefill(raw, session)
            val subjectId = pf.subjectId!!
            val number = CourseEditing.courseNumber(existing, subjectId, pf.chapter)
            val id = "course-nb-${session.date}-${NotebookRules.normalizeSubject(session.subject).replace(' ', '-')}-${session.ordinal}"
            val tag = "$label ${session.key}"
            assertEquals(tag, e.getString("sessionKey"), session.key)
            assertEquals(tag, e.getString("id"), id)
            assertEquals(tag, e.getString("subjectId"), subjectId)
            assertEquals(tag, e.getString("chapter"), pf.chapter)
            assertEquals(tag, e.getInt("courseNumber"), number)
            assertEquals(tag, e.getString("title"), pf.title)
            assertEquals(tag, e.getString("date"), session.date.toString())
            assertEquals(tag, e.getString("description"), pf.description)
            assertEquals(tag, e.getString("titleSource"), pf.titleSource)
            val source = CourseSource(NotebookCourses.SOURCE_KIND, session.key, session.subject, session.date.toString(), session.ordinal)
            val src = e.getJSONObject("source")
            assertEquals(tag, CourseSource(src.getString("kind"), src.getString("sessionKey"), src.getString("subjectLabel"), src.getString("date"), src.getInt("ordinal")), source)
            documentsEqual(tag, e.getJSONArray("documents"), pf.documents)
            existing += Course(id, subjectId, pf.chapter, number, pf.title, session.date.toString(), pf.description, today.toString(), source)
            imports[session.key] = NotebookImport(NotebookImport.CREATED, id, "")
        }

        val after = expected.getJSONObject("notebookImportsAfter")
        assertEquals(label, after.keys().asSequence().toSortedSet(), imports.keys.toSortedSet())
        after.keys().forEach { key ->
            assertEquals("$label $key", after.getJSONObject(key).getString("status"), imports.getValue(key).status)
            assertEquals("$label $key", after.getJSONObject(key).str("courseId"), imports.getValue(key).courseId)
        }
        assertEquals(label, expected.str("toast"), NotebookCourses.toast(createdExpected.size))
        val buttons = expected.getJSONObject("buttons")
        assertEquals(label, buttons.keys().asSequence().toSet(), sessions.map { it.key }.toSet())
        for (s in sessions) {
            val state = NotebookCourses.importState(s, imports, existing)
            assertEquals("$label ${s.key}", buttons.getString(s.key), if (state is NotebookCourses.ImportState.View) "viewCourse" else "createCourse")
        }
    }

    @Test
    fun scenarios() {
        val scenarios = courses.getJSONArray("scenarios").objects()
        scenarios.forEach(::runScenario)
        assertEquals(8, scenarios.size)
    }

    @Test
    fun migration() {
        val migration = courses.getJSONObject("migration")
        val input = migration.getJSONObject("input")
        val document = AppDocument(courses = input.getJSONArray("courses").objects().map(::course), notebookImports = imports(input.getJSONObject("notebookImports")))
        val migrated = DataMigrations.migrateNotebookKeys(document)
        val expected = migration.getJSONObject("expected")
        val expectedCourses = expected.getJSONArray("courses").objects()
        assertEquals(expectedCourses.map { it.getString("id") }, migrated.courses.map { it.id })
        expectedCourses.zip(migrated.courses).forEach { (e, c) -> assertEquals(c.id, course(e).source, c.source) }
        val expectedImports = expected.getJSONObject("notebookImports")
        // Clés triées (règle 39) ; l’ordre des clés JSON n’est pas conservé par org.json.
        assertEquals(expectedImports.keys().asSequence().sorted().toList(), migrated.notebookImports.keys.toList())
        expectedImports.keys().forEach { key ->
            val e = expectedImports.getJSONObject(key)
            assertEquals(key, NotebookImport(e.getString("status"), e.str("courseId"), e.optString("at")), migrated.notebookImports.getValue(key))
        }
        // Idempotente.
        assertTrue(migration.getBoolean("idempotent"))
        assertEquals(migrated, DataMigrations.migrateNotebookKeys(migrated))
        runScenario(migration.getJSONObject("afterMigration"))
    }

    // endregion
}
