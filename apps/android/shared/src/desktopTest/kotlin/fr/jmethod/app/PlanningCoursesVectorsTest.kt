package fr.jmethod.app

import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.domain.CourseDetailModel
import fr.jmethod.app.domain.CourseEditing
import fr.jmethod.app.domain.CoursesListModel
import fr.jmethod.app.domain.PlanningMode
import fr.jmethod.app.domain.PlanningModel
import fr.jmethod.app.domain.RevisionDetailModel
import fr.jmethod.app.domain.sentenceCase
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Jalons 2 et 3 : rejoue les suites `planning`, `planningNavigate`, `revisionDetail`,
 * `coursesList`, `courseDetail`, `courseCreate`, `courseCreatePrefill` et `courseDelete`.
 * Les écarts volontaires (`DEVIATIONS.md`) sont appliqués champ par champ ou par nom de cas,
 * avec leur numéro, et le comportement corrigé a son propre test en bas de fichier.
 */
class PlanningCoursesVectorsTest {
    private val json = AppDocumentCodec.json
    private val paris: ZoneId = ZoneId.of("Europe/Paris")

    private val vectors: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("domain/domain-vectors.json") ?: error("Vecteurs absents")
        json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun suite(name: String) = vectors.getValue("suites").jsonObject.getValue(name).jsonArray.map { it.jsonObject }
    private fun <T> decode(serializer: KSerializer<T>, element: JsonElement): T = json.decodeFromJsonElement(serializer, element)
    private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.boolean
    private fun name(case: JsonObject) = case.getValue("name").jsonPrimitive.content
    private fun now(case: JsonObject): Instant = Instant.ofEpochMilli(case.getValue("now").jsonObject.getValue("epochMs").jsonPrimitive.long)
    private fun ids(element: JsonElement) = element.jsonArray.map { it.jsonPrimitive.content }

    private fun subjects(element: JsonElement): List<Subject> = when {
        element is JsonPrimitive && element.isString ->
            decode(ListSerializer(Subject.serializer()), vectors.getValue("subjectSets").jsonObject.getValue(element.content))
        else -> decode(ListSerializer(Subject.serializer()), element)
    }

    private class Fixture(val courses: List<Course>, val revisions: List<Revision>, val subjects: List<Subject>, val events: List<PronoteEvent>)

    private fun fixture(case: JsonObject): Fixture {
        val f = vectors.getValue("fixtures").jsonObject.getValue(case.str("fixture")!!).jsonObject
        return Fixture(
            courses = decode(ListSerializer(Course.serializer()), f.getValue("courses")),
            revisions = decode(ListSerializer(Revision.serializer()), f.getValue("revisions")),
            subjects = subjects(f.getValue("subjects")),
            events = f["pronoteEvents"]?.let { decode(ListSerializer(PronoteEvent.serializer()), it) }.orEmpty(),
        )
    }

    private fun mode(value: String) = when (value) {
        "day" -> PlanningMode.DAY
        "week" -> PlanningMode.WEEK
        "month" -> PlanningMode.MONTH
        else -> error(value)
    }

    private fun phase(value: String?): Phase? = value?.let { decode(Phase.serializer(), JsonPrimitive(it)) }

    @Test
    fun planning() {
        val cases = suite("planning")
        for (case in cases) {
            val label = name(case)
            val f = fixture(case)
            val input = case.getValue("input").jsonObject
            val today = now(case).atZone(paris).toLocalDate()
            val model = PlanningModel.build(
                today, mode(input.str("mode")!!), LocalDate.parse(input.str("date")), input.str("subjectId"), phase(input.str("phase")),
                f.courses, f.revisions, f.subjects, f.events,
            )
            val expected = case.getValue("expected").jsonObject
            // Les chaînes de date restent en minuscules ; D11 ne touche que l’affichage (sentenceCase).
            assertEquals(label, expected.str("period"), model.period)
            val days = expected.getValue("days").jsonArray.map { it.jsonObject }
            assertEquals(label, days.map { it.str("date") }, model.days.map { it.date })
            for ((day, actual) in days.zip(model.days)) {
                val where = "$label ${actual.date}"
                assertEquals(where, day.str("heading"), actual.heading)
                assertEquals(where, day.str("countLabel"), actual.countLabel)
                assertEquals(where, day.bool("isToday"), actual.isToday)
                assertEquals(where, day.bool("outsideMonth"), actual.outsideMonth)
                assertEquals(where, day.str("timetableSummary"), actual.timetableSummary)
                val events = day["events"]?.jsonArray?.map { it.jsonObject }.orEmpty()
                assertEquals(where, events.map { it.str("id") }, actual.events.map { it.id })
                assertEquals(where, events.map { it.str("label") }, actual.events.map { it.label })
                assertEquals(where, events.map { it.str("subjectId") }, actual.events.map { it.subjectId })
                assertEquals(where, day.str("empty"), actual.empty)
                val tiles = day["tiles"]?.jsonArray?.map { it.jsonObject }.orEmpty()
                assertEquals(where, tiles.map { it.str("revisionId") }, actual.tiles.map { it.revisionId })
                for ((tile, real) in tiles.zip(actual.tiles)) {
                    assertEquals(where, tile.str("subjectName"), real.subjectName)
                    assertEquals(where, tile.str("stepLabel"), real.stepLabel)
                    assertEquals(where, Deviations.d10(tile.str("phaseLine")), real.phaseLine)
                    assertEquals(where, tile.str("title"), real.title)
                    assertEquals(where, tile.str("chapter"), real.chapter)
                    assertEquals(where, tile.bool("done"), real.done)
                    assertEquals(where, tile.str("footer"), real.footer)
                    assertEquals(where, Deviations.d10(tile.str("accessibilityLabel")), real.accessibilityLabel)
                }
            }
        }
        assertEquals(14, cases.size)
    }

    @Test
    fun planningNavigate() {
        val cases = suite("planningNavigate")
        for (case in cases) {
            val input = case.getValue("input").jsonObject
            val moved = PlanningModel.navigate(mode(input.str("mode")!!), LocalDate.parse(input.str("date")), input.getValue("direction").jsonPrimitive.int)
            assertEquals(name(case), case.str("expected"), moved.toString())
        }
        assertEquals(10, cases.size)
    }

    @Test
    fun revisionDetail() {
        val cases = suite("revisionDetail")
        for (case in cases) {
            val label = name(case)
            val f = fixture(case)
            val model = RevisionDetailModel.build(case.getValue("input").jsonObject.str("revisionId")!!, f.courses, f.revisions, f.subjects)
            val e = case.getValue("expected").jsonObject
            if (e.bool("opens") == false) {
                assertNull(label, model)
                continue
            }
            assertNotNull(label, model)
            model!!
            assertEquals(label, e.str("subjectName"), model.subjectName)
            assertEquals(label, e.str("title"), model.title)
            assertEquals(label, e.str("chapterLine"), model.chapterLine)
            assertEquals(label, e.str("stepLabel"), model.stepLabel)
            assertEquals(label, Deviations.d10(e.str("phaseLabel")), model.phaseLabel)
            assertEquals(label, e.str("plannedLine"), model.plannedLine)
            assertEquals(label, e.str("description"), model.description)
            assertEquals(label, e.str("historyTitle"), model.historyTitle)
            val history = e.getValue("history").jsonArray.map { it.jsonObject }
            assertEquals(label, history.map { it.str("revisionId") }, model.history.map { it.revisionId })
            assertEquals(label, history.map { Deviations.d10(it.str("label")) }, model.history.map { it.label })
            assertEquals(label, history.map { it.str("detail") }, model.history.map { it.detail })
            assertEquals(label, e.str("historyEmpty"), model.historyEmpty)
            assertEquals(label, e.str("completedLine"), model.completedLine)
            assertEquals(label, e.bool("canValidate"), model.canValidate)
        }
        assertEquals(7, cases.size)
    }

    /** D9 : la recherche nettoie la requête et replie les accents ; ces cas du web n’ont plus le même résultat. */
    private val coursesListIgnored = mapOf(
        "search_trailing_space_no_match" to "D9",
        "search_without_accent_does_not_match" to "D9",
    )

    @Test
    fun coursesList() {
        val cases = suite("coursesList")
        var replayed = 0
        for (case in cases) {
            val label = name(case)
            if (label in coursesListIgnored) continue
            val f = fixture(case)
            val input = case.getValue("input").jsonObject
            val model = CoursesListModel.build(f.courses, f.revisions, f.subjects, input.str("search") ?: "", input.str("subjectId"))
            val e = case.getValue("expected").jsonObject
            assertEquals(label, e.str("header"), model.header)
            assertEquals(label, e.bool("empty"), model.empty)
            val groups = e.getValue("groups").jsonArray.map { it.jsonObject }
            assertEquals(label, groups.map { it.str("subjectId") }, model.groups.map { it.subject.id })
            for ((group, actual) in groups.zip(model.groups)) {
                assertEquals(label, group.str("countLabel"), actual.countLabel)
                val chapters = group.getValue("chapters").jsonArray.map { it.jsonObject }
                assertEquals(label, chapters.map { it.str("chapter") }, actual.chapters.map { it.chapter })
                for ((chapter, real) in chapters.zip(actual.chapters)) {
                    val cards = chapter.getValue("courses").jsonArray.map { it.jsonObject }
                    assertEquals(label, cards.map { it.str("courseId") }, real.courses.map { it.course.id })
                    for ((card, c) in cards.zip(real.courses)) {
                        assertEquals(label, card.str("badge"), c.badge)
                        assertEquals(label, card.str("dateLine"), c.dateLine)
                        assertEquals(label, card.str("lastScoreLine"), c.lastScoreLine)
                        assertEquals(label, card.str("counter"), c.counter)
                        assertEquals(label, card.getValue("progress").jsonPrimitive.int, c.progress)
                        val next = card["next"]?.takeIf { it !is JsonNull }?.jsonObject
                        assertEquals(label, next?.str("label"), c.next?.label)
                        assertEquals(label, next?.str("date"), c.next?.date)
                    }
                }
            }
            replayed++
        }
        assertEquals(11, cases.size)
        assertEquals(9, replayed)
    }

    @Test
    fun courseDetail() {
        val cases = suite("courseDetail")
        for (case in cases) {
            val label = name(case)
            val f = fixture(case)
            val course = f.courses.first { it.id == case.getValue("input").jsonObject.str("courseId") }
            val model = CourseDetailModel.build(course, f.subjects, f.revisions)
            val e = case.getValue("expected").jsonObject
            assertEquals(label, e.str("subjectLabel"), model.subjectLabel)
            // D2 : « Créé le » lit createdAt ; égal à la date du cours dans la fixture, donc même texte.
            assertEquals(label, course.date, course.createdAt)
            assertEquals(label, e.str("createdLine"), model.createdLine)
            assertEquals(label, e.str("averageLine"), model.averageLine)
            assertEquals(label, e.str("notes"), model.notes)
            if (label == "no_revisions_gives_nan_percent") {
                // D1 : 0 % au lieu de « NaN% ».
                assertEquals(label, "0 / 0 effectuées (NaN%)", e.str("progressLine"))
                assertEquals(label, "0 / 0 effectuées (0%)", model.progressLine)
                assertEquals(label, 0, model.progressPercent)
            } else {
                assertEquals(label, e.str("progressLine"), model.progressLine)
                assertEquals(label, e.getValue("progressPercent").jsonPrimitive.int, model.progressPercent)
            }
            val timeline = e.getValue("timeline").jsonArray.map { it.jsonObject }
            assertEquals(label, timeline.map { it.str("revisionId") }, model.timeline.map { it.revisionId })
            for ((row, real) in timeline.zip(model.timeline)) {
                assertEquals(label, row.str("stepLabel"), real.stepLabel)
                assertEquals(label, row.str("phaseLabel"), real.phaseLabel)
                assertEquals(label, row.bool("emergencyBadge"), real.emergencyBadge)
                assertEquals(label, row.str("state"), real.state.wire)
                assertEquals(label, row.str("plannedLine"), real.plannedLine)
                assertEquals(label, row.str("doneLine"), real.doneLine)
                // D12 : une séance terminée sans score affiche « —/100 » (le web écrit « 50/100 »).
                val expectedRight = if (real.state == CourseDetailModel.State.COMPLETED && real.revision.score == null) "—/100" else row.str("right")
                assertEquals(label, expectedRight, real.right)
            }
            assertEquals(label, e.str("deleteConfirm"), model.deleteConfirm)
        }
        assertEquals(5, cases.size)
    }

    @Test
    fun courseCreate() {
        val cases = suite("courseCreate")
        for (case in cases) {
            val label = name(case)
            val f = fixture(case)
            val input = case.getValue("input").jsonObject
            val settings = decode(AppSettings.serializer(), input.getValue("settings"))
            val form = input.getValue("form").jsonObject.let {
                CourseEditing.Form(it.str("subjectId"), it.str("chapter")!!, it.str("title")!!, it.str("date")!!, it.str("description")!!)
            }
            val e = case.getValue("expected").jsonObject
            val number = CourseEditing.courseNumber(f.courses, form.subjectId, form.chapter)
            assertEquals(label, e.getValue("courseNumber").jsonPrimitive.int, number)
            assertEquals(label, e.str("submitLabel"), CourseEditing.submitLabel(number))
            assertEquals(label, ids(e.getValue("chapterSuggestions")), CourseEditing.chapterSuggestions(f.courses, form.subjectId, form.chapter))
            val created = CourseEditing.create(f.courses, f.revisions, settings.intervals, form, now(case), paris)
            assertEquals(label, e.bool("created"), created != null)
            if (created == null) continue
            assertEquals(label, decode(Course.serializer(), e.getValue("course")), created.course)
            assertEquals(label, decode(ListSerializer(Revision.serializer()), e.getValue("newRevisions")), created.newRevisions)
            assertEquals(label, ids(e.getValue("courseOrder")), created.courses.map { it.id })
            assertEquals(label, ids(e.getValue("revisionOrder")), created.revisions.map { it.id })
            val toast = e.getValue("toast").jsonObject
            assertEquals(label, toast.str("title"), created.toast.title)
            assertEquals(label, toast.str("type"), created.toast.type.wire)
            if (created.newRevisions.size == 10) {
                assertEquals(label, toast.str("message"), created.toast.message)
            } else {
                // D8 : nombre réel d’échéances (cas custom_intervals_and_empty_description, 3 intervalles).
                assertEquals(label, "custom_intervals_and_empty_description", label)
                assertTrue(label, toast.str("message")!!.contains("10 échéances"))
                assertEquals(label, "C1 « Cours de test » ajouté avec ses 3 échéances de révision.", created.toast.message)
            }
        }
        assertEquals(9, cases.size)
    }

    @Test
    fun courseCreatePrefill() {
        val cases = suite("courseCreatePrefill")
        for (case in cases) {
            val input = case.getValue("input").jsonObject
            val prefill = CourseEditing.prefill(decode(PronoteEvent.serializer(), input.getValue("event")), subjects(input.getValue("subjects")))
            val e = case.getValue("expected").jsonObject
            assertEquals(name(case), e.str("subjectId"), prefill.subjectId)
            assertEquals(name(case), e.str("date"), prefill.date)
            assertEquals(name(case), e.str("title"), prefill.title)
        }
        assertEquals(4, cases.size)
    }

    @Test
    fun courseDelete() {
        val cases = suite("courseDelete")
        for (case in cases) {
            val f = fixture(case)
            val deleted = CourseEditing.delete(f.courses, f.revisions, case.getValue("input").jsonObject.str("courseId")!!)
            val e = case.getValue("expected").jsonObject
            assertEquals(name(case), ids(e.getValue("courses")), deleted.courses.map { it.id })
            assertEquals(name(case), ids(e.getValue("revisions")), deleted.revisions.map { it.id })
            val toast = e.getValue("toast").jsonObject
            assertEquals(name(case), toast.str("title"), deleted.toast.title)
            assertEquals(name(case), toast.str("message"), deleted.toast.message)
            assertEquals(name(case), toast.str("type"), deleted.toast.type.wire)
        }
        assertEquals(3, cases.size)
    }

    // Comportements corrigés (DEVIATIONS.md).

    private val listFixture by lazy { fixture(JsonObject(mapOf("fixture" to JsonPrimitive("coursesList")))) }

    private fun listIds(search: String) = CoursesListModel.build(listFixture.courses, listFixture.revisions, listFixture.subjects, search, null)
        .groups.flatMap { g -> g.chapters.flatMap { c -> c.courses.map { it.course.id } } }

    @Test
    fun d9SearchIsTrimmedAndAccentInsensitive() {
        assertEquals(listOf("course-l-maths-1"), listIds("thalès "))
        assertEquals(listOf("course-l-maths-1"), listIds("  THALES"))
        assertEquals(listOf("course-l-pc"), listIds("energie"))
        assertEquals(listOf("course-l-pc"), listIds("ÉNERGIE "))
        // « c1 » reste un préfixe : C1 et C10 (comme le web).
        assertEquals(listOf("course-l-maths-10", "course-l-maths-1", "course-l-fr-new", "course-l-pc"), listIds(" C1"))
        assertTrue(listIds("   ").size == 6)
        assertTrue(listIds("introuvable").isEmpty())
    }

    @Test
    fun d1d2d12CourseDetailCorrections() {
        val course = Course("c", "maths", "Algèbre", 2, "Cours de test", "2026-11-10", null, "2026-11-12")
        val empty = CourseDetailModel.build(course, listOf(Subject("maths", "Mathématiques", "#38BDF8")), emptyList())
        assertEquals("0 / 0 effectuées (0%)", empty.progressLine)
        assertEquals(0, empty.progressPercent)
        assertNull(empty.averageLine)
        assertEquals("Créé le 12 novembre 2026", empty.createdLine)
        assertEquals("Cours du 10 novembre 2026", empty.courseDateLine)
        val unscored = Revision("r0", "c", 0, "J0", "2026-11-10", "2026-11-09", null, RevisionStatus.COMPLETED, Phase.ENCODAGE, 0)
        val detail = CourseDetailModel.build(course, emptyList(), listOf(unscored))
        assertEquals("—/100", detail.timeline.single().right)
        assertEquals("Matière", detail.subjectLabel)
        assertFalse(detail.timeline.single().canValidate)
    }

    @Test
    fun d7d8CreationToastCountsGeneratedRevisions() {
        val form = CourseEditing.Form("maths", "Algèbre", "Cours de test", "2026-11-18", "")
        val now = Instant.parse("2026-11-17T17:30:00Z")
        val one = CourseEditing.create(emptyList(), emptyList(), listOf(0), form, now, paris)!!
        assertEquals("C1 « Cours de test » ajouté avec son échéance de révision.", one.toast.message)
        val ten = CourseEditing.create(emptyList(), emptyList(), fr.jmethod.app.model.DEFAULT_INTERVALS, form, now, paris)!!
        assertEquals("C1 « Cours de test » ajouté avec ses 10 échéances de révision.", ten.toast.message)
    }

    @Test
    fun d7AlgorithmMessageAgreesWithOneDay() {
        assertEquals("(1 jour au lieu de 1j)", Deviations.d7Days("(1 jours au lieu de 1j)"))
        assertEquals("(1 jour)", Deviations.d7Days("(1 jours)"))
        assertEquals("(11 jours au lieu de 11j)", Deviations.d7Days("(11 jours au lieu de 11j)"))
    }

    @Test
    fun d10AndD11Labels() {
        val f = fixture(JsonObject(mapOf("fixture" to JsonPrimitive("planning"))))
        val model = PlanningModel.build(
            LocalDate.parse("2026-03-30"), PlanningMode.DAY, LocalDate.parse("2026-03-30"), null, null,
            f.courses, f.revisions, f.subjects, f.events,
        )
        val tiles = model.days.single().tiles
        assertTrue(tiles.none { "Encodage" in it.phaseLine || "Consolidation" in it.phaseLine || "Récupération" in it.phaseLine })
        assertEquals("S’entraîner · C2", tiles.first().phaseLine)
        assertEquals("Lundi 30 mars 2026", model.period.sentenceCase())
        assertEquals("Lundi 30 mars", model.days.single().heading.sentenceCase())
        assertEquals("Décembre 2026", PlanningModel.period(PlanningMode.MONTH, LocalDate.parse("2026-12-01")).sentenceCase())
        assertEquals("19 oct. – 25 oct. 2026", PlanningModel.period(PlanningMode.WEEK, LocalDate.parse("2026-10-25")).sentenceCase())
        // Créneaux : seul un créneau maintenu propose « Ajouter ce cours ».
        assertEquals(listOf(true, false, true), model.days.single().events.map { it.canAddCourse })
    }

    @Test
    fun planningValidationUsesEveningReference() {
        // La validation depuis le Planning passe par ScoreSubmission (programme du soir) : couvert par scoreSubmit.
        val f = fixture(JsonObject(mapOf("fixture" to JsonPrimitive("revisionDetail"))))
        val pending = f.revisions.first { it.status != RevisionStatus.COMPLETED && f.courses.any { c -> c.id == it.courseId } }
        val outcome = fr.jmethod.app.domain.ScoreSubmission.submit(
            f.courses, f.revisions, AppSettings(), pending.id, 50, Instant.parse("2026-11-17T19:00:00Z"), paris,
        )
        assertEquals("2026-11-18", outcome.preparationDate)
        assertEquals("2026-11-17", outcome.revisions.first { it.id == pending.id }.completedDate)
    }
}
