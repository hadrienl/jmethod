package fr.jmethod.app

import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.domain.ImportExport
import fr.jmethod.app.domain.SettingsLogic
import fr.jmethod.app.domain.StatsModel
import fr.jmethod.app.model.AppDocument
import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.Phase
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.RevisionStatus
import fr.jmethod.app.model.Subject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Jalon 4 : suites `stats`, `settingsUpdate` et `importExport`, avec les écarts D3 à D7 et D13
 * (`DEVIATIONS.md`) appliqués nommément.
 */
class StatsSettingsVectorsTest {
    private val json = AppDocumentCodec.json
    private val vectors: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("domain/domain-vectors.json") ?: error("Vecteurs absents")
        json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun suite(name: String) = vectors.getValue("suites").jsonObject.getValue(name).jsonArray.map { it.jsonObject }
    private fun <T> decode(serializer: KSerializer<T>, element: JsonElement): T = json.decodeFromJsonElement(serializer, element)
    private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun name(case: JsonObject) = case.getValue("name").jsonPrimitive.content
    private fun ints(element: JsonElement) = element.jsonArray.map { it.jsonPrimitive.int }
    private fun subjects(element: JsonElement): List<Subject> = when {
        element is JsonPrimitive && element.isString ->
            decode(ListSerializer(Subject.serializer()), vectors.getValue("subjectSets").jsonObject.getValue(element.content))
        else -> decode(ListSerializer(Subject.serializer()), element)
    }

    private val today = LocalDate.of(2026, 11, 17)

    /**
     * Valeurs corrigées par cas (le reste est comparé au web) :
     * D4 sans aucune note « — » au lieu de 50 ; D5 seules les séances terminées notées comptent ;
     * D6 `score: null` = pas de note.
     */
    private data class Corrected(val overall: Int?, val subjectAverages: Map<String, Int?> = emptyMap(), val weak: List<String>? = null)

    private val statsCorrections = mapOf(
        "no_data" to Corrected(null), // D4
        "only_overdue_pending" to Corrected(null), // D4
        // D5 : le 100 posé sur une séance anglaise non terminée ne compte plus.
        // Global (49+50+45+54+0)/5 = 39,6 → 40 ; maths 49,5 → 50 ; anglais (45+54+0)/3 = 33.
        "rounding_and_weak_subjects" to Corrected(40, mapOf("maths" to 50, "anglais" to 33), weak = listOf("anglais")),
        "subject_without_revisions_listed_with_null_average" to Corrected(40, mapOf("maths" to 50, "anglais" to 33, "techno" to null), weak = listOf("anglais")),
        // D6 : `score: null` n’est pas une note de 0.
        "score_null_from_import_counts_as_zero" to Corrected(null, mapOf("maths" to null), weak = emptyList()),
    )

    @Test
    fun stats() {
        val cases = suite("stats")
        for (case in cases) {
            val label = name(case)
            val input = case.getValue("input").jsonObject
            val model = StatsModel.build(
                decode(ListSerializer(Course.serializer()), input.getValue("courses")),
                decode(ListSerializer(Revision.serializer()), input.getValue("revisions")),
                subjects(input.getValue("subjects")),
                today,
            )
            val e = case.getValue("expected").jsonObject
            assertEquals(label, e.getValue("completionRate").jsonPrimitive.int, model.completionRate)
            assertEquals(label, e.str("completionLine"), model.completionLine)
            // D3 : la régularité est calculée (voir d3Streak) ; le web affiche toujours « 5 j ».
            assertEquals(label, "5 j", e.str("streakLabel"))
            assertEquals(label, e.getValue("coursesCount").jsonPrimitive.int, model.coursesCount)
            // D7 : « Sur 1 matière ».
            assertEquals(label, e.str("subjectsLine")!!.replace(Regex("^Sur ([01]) matières$"), "Sur $1 matière"), model.subjectsLine)
            val rows = e.getValue("subjects").jsonArray.map { it.jsonObject }
            assertEquals(label, rows.map { it.str("subjectId") }, model.subjects.map { it.subject.id })
            val fix = statsCorrections[label]
            for ((row, actual) in rows.zip(model.subjects)) {
                assertEquals(label, row.getValue("coursesCount").jsonPrimitive.int, actual.coursesCount)
                assertEquals(label, row.getValue("totalRevs").jsonPrimitive.int, actual.totalRevs)
                assertEquals(label, row.getValue("completedRevs").jsonPrimitive.int, actual.completedRevs)
                assertEquals(label, row.getValue("rate").jsonPrimitive.int, actual.rate)
                assertEquals(label, row.str("line"), actual.line)
                val id = actual.subject.id
                if (fix != null && id in fix.subjectAverages) {
                    assertEquals("$label $id", fix.subjectAverages[id], actual.avgScore)
                } else {
                    assertEquals("$label $id", row["avgScore"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int, actual.avgScore)
                    assertEquals("$label $id", row.str("averageTone"), actual.averageTone?.wire)
                }
            }
            if (fix != null) {
                assertEquals(label, fix.overall, model.overallAvgScore)
                if (fix.overall == null) assertEquals(label, "—", model.overallAvgLabel)
                fix.weak?.let { assertEquals(label, it, model.weakSubjects.map { row -> row.subject.id }) }
            } else {
                assertEquals(label, e.getValue("overallAvgScore").jsonPrimitive.int, model.overallAvgScore)
                assertEquals(label, e.str("balanceLabel"), model.balanceLabel)
                assertEquals(label, e.getValue("weakSubjects").jsonArray.map { it.jsonPrimitive.content }, model.weakSubjects.map { it.subject.id })
            }
        }
        assertEquals(6, cases.size)
    }

    private fun revision(id: String, completed: String?, score: Int? = 70) = Revision(
        id = id, courseId = "c", stepIndex = 0, stepLabel = "J0", scheduledDate = "2026-11-01", completedDate = completed, score = score,
        status = if (completed != null) RevisionStatus.COMPLETED else RevisionStatus.PENDING, phase = Phase.ENCODAGE,
    )

    @Test
    fun d3StreakCountsConsecutiveDaysUpToTodayOrYesterday() {
        val days = listOf("2026-11-17", "2026-11-16", "2026-11-16", "2026-11-15", "2026-11-13")
        val revisions = days.mapIndexed { i, d -> revision("r$i", d) } + revision("p", null)
        assertEquals(3, StatsModel.streak(revisions, today))
        // Rien aujourd’hui : la série part d’hier.
        assertEquals(2, StatsModel.streak(revisions.filter { it.completedDate != "2026-11-17" }, today))
        assertEquals(0, StatsModel.streak(revisions.filter { it.completedDate == "2026-11-13" }, today))
        assertEquals(0, StatsModel.streak(emptyList(), today))
        val model = StatsModel.build(emptyList(), revisions, emptyList(), today)
        assertEquals("3 j", model.streakLabel)
    }

    @Test
    fun d4d5d6Averages() {
        val subject = Subject("maths", "Mathématiques", "#38BDF8")
        val course = Course("c", "maths", "A", 1, "T", "2026-11-01", null, "2026-11-01")
        // D5 : note sur une séance non terminée ignorée ; D6 : null ignoré ; D4 : « — » sans note.
        val pendingScored = revision("p", null, score = 10)
        val nullScore = revision("n", "2026-11-10", score = null)
        val empty = StatsModel.build(listOf(course), listOf(pendingScored, nullScore), listOf(subject), today)
        assertNull(empty.overallAvgScore)
        assertEquals("—", empty.overallAvgLabel)
        assertEquals("Pas encore de note", empty.balanceLabel)
        assertNull(empty.subjects.single().avgScore)
        val scored = StatsModel.build(listOf(course), listOf(pendingScored, nullScore, revision("d", "2026-11-11", 40)), listOf(subject), today)
        assertEquals(40, scored.overallAvgScore)
        assertEquals("Sous l’équilibre", scored.balanceLabel)
        assertEquals(listOf("maths"), scored.weakSubjects.map { it.subject.id })
        assertEquals("Sur 1 matière", scored.subjectsLine)
    }

    @Test
    fun settingsUpdate() {
        val cases = suite("settingsUpdate")
        for (case in cases) {
            val label = name(case)
            val input = case.getValue("input").jsonObject
            val e = case.getValue("expected").jsonObject
            when (input.str("action")) {
                "addInterval" -> assertEquals(label, ints(e.getValue("intervals")), SettingsLogic.addInterval(ints(input.getValue("intervals")), input.str("value")!!))
                "removeInterval" -> {
                    val value = input.getValue("value").jsonPrimitive.int
                    assertEquals(label, ints(e.getValue("intervals")), SettingsLogic.removeInterval(ints(input.getValue("intervals")), value))
                    assertEquals(label, e.getValue("removeButtonShown").jsonPrimitive.boolean, SettingsLogic.removeButtonShown(value))
                }
                "resetIntervals" -> assertEquals(label, ints(e.getValue("intervals")), SettingsLogic.resetIntervals())
                "save" -> {
                    val edit = input.getValue("edit").jsonObject
                    val saved = SettingsLogic.save(
                        decode(AppSettings.serializer(), input.getValue("settings")), edit.str("icalUrl")!!,
                        edit.getValue("sensitivity").jsonPrimitive.double, edit.getValue("emergencyThreshold").jsonPrimitive.int, ints(edit.getValue("intervals")),
                    )
                    assertEquals(label, decode(AppSettings.serializer(), e.getValue("settings")), saved)
                }
                "sensitivityLabel" -> {
                    val value = input.getValue("sensitivity").jsonPrimitive.double
                    assertEquals(label, e.str("label"), SettingsLogic.sensitivityLabel(value))
                    assertEquals(label, e.str("valueText"), SettingsLogic.jsNumber(SettingsLogic.snapSensitivity(value)))
                }
                "thresholdLabel" -> assertEquals(label, e.str("label"), SettingsLogic.thresholdLabel(input.getValue("emergencyThreshold").jsonPrimitive.int))
                "subjectColor" -> assertEquals(
                    label, subjects(e.getValue("subjects")),
                    SettingsLogic.subjectColor(subjects(input.getValue("subjects")), input.str("subjectId")!!, input.str("color")!!),
                )
                else -> error("Action inconnue dans $label")
            }
        }
        assertEquals(30, cases.size)
    }

    /** D13 : formes invalides refusées (le web les accepte et les stocke). */
    private val importRejectedByD13 = setOf("courses_object_is_accepted", "wrong_shape_string_is_accepted")

    @Test
    fun importExport() {
        val cases = suite("importExport")
        val before = vectors.getValue("fixtures").jsonObject.getValue("importBefore").jsonObject
        val beforeDocument = AppDocument(
            courses = decode(ListSerializer(Course.serializer()), before.getValue("courses")),
            revisions = decode(ListSerializer(Revision.serializer()), before.getValue("revisions")),
            subjects = decode(ListSerializer(Subject.serializer()), before.getValue("subjects")),
            pronoteEvents = decode(ListSerializer(PronoteEvent.serializer()), before.getValue("pronoteEvents")),
            settings = decode(AppSettings.serializer(), before.getValue("settings")),
            initialized = true,
            dataVersion = 1,
        )
        var replayed = 0
        for (case in cases) {
            val label = name(case)
            val e = case.getValue("expected").jsonObject
            if (label == "export_format") {
                val state = case.getValue("input").jsonObject.getValue("state").jsonObject
                val document = AppDocument(
                    courses = decode(ListSerializer(Course.serializer()), state.getValue("courses")),
                    revisions = decode(ListSerializer(Revision.serializer()), state.getValue("revisions")),
                    subjects = decode(ListSerializer(Subject.serializer()), state.getValue("subjects")),
                    settings = decode(AppSettings.serializer(), state.getValue("settings")),
                )
                val now = Instant.ofEpochMilli(case.getValue("now").jsonObject.getValue("epochMs").jsonPrimitive.long)
                assertEquals(label, e.str("json"), ImportExport.export(document, now))
                replayed++
                continue
            }
            val result = ImportExport.parse(case.getValue("input").jsonObject.str("json")!!)
            if (label in importRejectedByD13) {
                assertTrue(label, e.getValue("accepted").jsonPrimitive.boolean)
                assertTrue(label, result is ImportExport.Result.Rejected)
                assertEquals(label, ImportExport.ERROR_TITLE, (result as ImportExport.Result.Rejected).title)
                assertTrue(label, result.message.startsWith("Ce fichier n’est pas une sauvegarde J-Method"))
                continue
            }
            val accepted = e.getValue("accepted").jsonPrimitive.boolean
            assertEquals(label, accepted, result is ImportExport.Result.Accepted)
            val after = if (result is ImportExport.Result.Accepted) result.applyTo(beforeDocument) else {
                val toast = e.getValue("toast").jsonObject
                assertEquals(label, toast.str("title"), (result as ImportExport.Result.Rejected).title)
                assertEquals(label, toast.str("message"), result.message)
                beforeDocument
            }
            val state = e.getValue("stateAfterReload").jsonObject
            assertEquals(label, decode(ListSerializer(Course.serializer()), state.getValue("courses")), after.courses)
            assertEquals(label, decode(ListSerializer(Revision.serializer()), state.getValue("revisions")), after.revisions)
            assertEquals(label, decode(ListSerializer(Subject.serializer()), state.getValue("subjects")), after.subjects)
            assertEquals(label, decode(ListSerializer(PronoteEvent.serializer()), state.getValue("pronoteEvents")), after.pronoteEvents)
            assertEquals(label, decode(AppSettings.serializer(), state.getValue("settings")), after.settings)
            replayed++
        }
        assertEquals(13, cases.size)
        assertEquals(11, replayed)
    }

    @Test
    fun d13ShapeValidation() {
        fun rejected(text: String) = ImportExport.parse(text) is ImportExport.Result.Rejected
        assertTrue(rejected("""{"courses":{},"revisions":[]}"""))
        assertTrue(rejected("""{"courses":[],"revisions":[1]}"""))
        assertTrue(rejected("""{"courses":[{"id":"c"}],"revisions":[]}"""))
        assertTrue(rejected("""{"courses":[],"revisions":[],"subjects":"x"}"""))
        assertTrue(rejected("""{"courses":[],"revisions":[{"id":"r","courseId":"c","stepIndex":0,"stepLabel":"J0","scheduledDate":"2026-11-01","status":"inconnu","phase":"encodage"}]}"""))
        // D6 : un `score: null` importé est lu comme absent.
        val ok = ImportExport.parse(
            """{"courses":[],"revisions":[{"id":"r","courseId":"c","stepIndex":0,"stepLabel":"J0","scheduledDate":"2026-11-01","status":"completed","phase":"encodage","score":null}]}""",
        )
        assertNull((ok as ImportExport.Result.Accepted).revisions.single().score)
    }
}
