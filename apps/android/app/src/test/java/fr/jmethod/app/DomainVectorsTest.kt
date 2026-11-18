package fr.jmethod.app

import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.data.DataMigrations
import fr.jmethod.app.model.AppDocument
import fr.jmethod.app.domain.AdaptiveAlgorithm
import fr.jmethod.app.domain.EveningProgramme
import fr.jmethod.app.domain.ScorePreview
import fr.jmethod.app.domain.ScoreSubmission
import fr.jmethod.app.domain.SubjectMatcher
import fr.jmethod.app.domain.TodayModel
import fr.jmethod.app.domain.jsRound
import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.DEFAULT_INTERVALS
import fr.jmethod.app.model.DEFAULT_SETTINGS
import fr.jmethod.app.model.DEFAULT_SUBJECTS
import fr.jmethod.app.model.PHASE_CONFIG
import fr.jmethod.app.model.Phase
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.SCORE_INITIAL
import fr.jmethod.app.model.SCORE_MAX
import fr.jmethod.app.model.SCORE_MIN
import fr.jmethod.app.model.SCORE_PRESETS
import fr.jmethod.app.model.SCORE_STEP
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Rejoue intégralement les vecteurs générés depuis le TypeScript (`native/shared/domain/`).
 * Les fichiers sont lus directement dans le dossier partagé (ressources de test), sans copie.
 */
/** Nombre de cas nommés dont le message attendu est corrigé par l’écart D14 (dates ISO). */
private const val D14_REVIEW_CASES = 49
private const val D14_CHAIN_STEPS = 34
private const val D14_SUBMIT_CASES = 3

class DomainVectorsTest {
    private val json = AppDocumentCodec.json
    private val paris: ZoneId = ZoneId.of("Europe/Paris")

    private fun resource(name: String): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream(name) ?: error("Ressource partagée absente : $name")
        return json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private val vectors by lazy { resource("domain/domain-vectors.json") }
    private val constants by lazy { resource("domain/constants.json") }

    private fun suite(name: String) = vectors.getValue("suites").jsonObject.getValue(name).jsonArray.map { it.jsonObject }
    private fun <T> decode(serializer: KSerializer<T>, element: JsonElement): T = json.decodeFromJsonElement(serializer, element)
    private fun revisions(element: JsonElement) = decode(ListSerializer(Revision.serializer()), element)
    private fun courses(element: JsonElement) = decode(ListSerializer(Course.serializer()), element)
    private fun events(element: JsonElement) = decode(ListSerializer(PronoteEvent.serializer()), element)
    private fun settings(element: JsonElement) = decode(AppSettings.serializer(), element)
    private fun subjects(element: JsonElement): List<Subject> = when {
        element is JsonPrimitive && element.isString ->
            decode(ListSerializer(Subject.serializer()), vectors.getValue("subjectSets").jsonObject.getValue(element.content))
        else -> decode(ListSerializer(Subject.serializer()), element)
    }
    private fun now(case: JsonObject): Instant = Instant.ofEpochMilli(case.getValue("now").jsonObject.getValue("epochMs").jsonPrimitive.long)
    private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun ids(element: JsonElement) = element.jsonArray.map { it.jsonPrimitive.content }
    private fun name(case: JsonObject) = case.getValue("name").jsonPrimitive.content

    private var checked = 0

    @Test
    fun constantsMatchTypeScript() {
        assertEquals(decode(ListSerializer(Subject.serializer()), constants.getValue("DEFAULT_SUBJECTS")), DEFAULT_SUBJECTS)
        assertEquals(constants.getValue("DEFAULT_INTERVALS").jsonArray.map { it.jsonPrimitive.int }, DEFAULT_INTERVALS)
        assertEquals(settings(constants.getValue("DEFAULT_SETTINGS")), DEFAULT_SETTINGS)
        val phases = constants.getValue("PHASE_CONFIG").jsonObject
        val today = constants.getValue("TODAY_PHASES").jsonObject
        for (phase in Phase.entries) {
            val key = json.encodeToJsonElement(Phase.serializer(), phase).jsonPrimitive.content
            val info = PHASE_CONFIG.getValue(phase)
            val config = phases.getValue(key).jsonObject
            assertEquals(config.str("label"), info.label)
            assertEquals(config.str("description"), info.description)
            assertEquals(config.str("color"), "#%06X".format(info.color and 0xFFFFFF))
            val todayPhase = today.getValue(key).jsonObject
            assertEquals(todayPhase.str("label"), info.label)
            assertEquals(todayPhase.str("hint"), info.hint)
            assertEquals(todayPhase.str("symbol"), info.symbol)
        }
        val presets = constants.getValue("SCORE_PRESETS").jsonArray.map { it.jsonObject }
        assertEquals(presets.map { Triple(it.getValue("val").jsonPrimitive.int, it.str("label"), it.str("sub")) },
            SCORE_PRESETS.map { Triple(it.value, it.label, it.sub) })
        val slider = constants.getValue("SCORE_SLIDER").jsonObject
        assertEquals(listOf(SCORE_MIN, SCORE_MAX, SCORE_STEP, SCORE_INITIAL),
            listOf("min", "max", "step", "initial").map { slider.getValue(it).jsonPrimitive.int })
    }

    @Test
    fun jsRoundingRoundsHalvesUp() {
        assertEquals(3, jsRound(2.5))
        assertEquals(-2, jsRound(-2.5))
        assertEquals(13, jsRound(12.5))
        assertEquals(0, jsRound(0.49999999999999994))
    }

    @Test
    fun getPhaseForStep() {
        for (case in suite("getPhaseForStep")) {
            val step = case.getValue("input").jsonObject.getValue("stepIndex").jsonPrimitive.int
            assertEquals(name(case), case.str("expected"),
                json.encodeToJsonElement(Phase.serializer(), AdaptiveAlgorithm.phaseForStep(step)).jsonPrimitive.content)
        }
    }

    @Test
    fun generateInitialRevisions() {
        for (case in suite("generateInitialRevisions")) {
            val input = case.getValue("input").jsonObject
            val course = decode(Course.serializer(), input.getValue("course"))
            val intervals = input.getValue("intervals").jsonArray.map { it.jsonPrimitive.int }
            assertEquals(name(case), revisions(case.getValue("expected")), AdaptiveAlgorithm.generateInitialRevisions(course, intervals))
        }
    }

    /** Cas nommés dont le message attendu a été corrigé par D14 (date ISO → français relatif). */
    private val d14Cases = mutableSetOf<String>()

    /** D7 puis D14 sur un message attendu du web, en notant le cas touché par D14. */
    private fun nativeMessage(label: String, expected: String?, now: Instant): String? {
        if (Deviations.touchedByD14(expected)) d14Cases += label
        return Deviations.d14(Deviations.d7Days(expected), now.atZone(paris).toLocalDate())
    }

    private fun assertReview(label: String, expected: JsonObject, result: AdaptiveAlgorithm.Result, now: Instant) {
        assertEquals(label, revisions(expected.getValue("updatedRevisions")), result.updatedRevisions)
        val explanation = expected.getValue("explanation").jsonObject
        assertEquals(label, explanation.getValue("score").jsonPrimitive.int, result.explanation.score)
        assertEquals(label, explanation.getValue("factor").jsonPrimitive.double, result.explanation.factor, 1e-12)
        assertEquals(label, nativeMessage(label, explanation.str("message"), now), result.explanation.message)
        assertFalse(label, Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(result.explanation.message))
        assertEquals(label, explanation.str("actionType"), result.explanation.actionType.wire)
        assertEquals(label, explanation.str("nextDate"), result.explanation.nextDate)
    }

    @Test
    fun applyAdaptiveReview() {
        val cases = suite("applyAdaptiveReview")
        for (case in cases) {
            val input = case.getValue("input").jsonObject
            val run = {
                AdaptiveAlgorithm.applyAdaptiveReview(
                    revisions(input.getValue("revisions")),
                    input.str("revisionId")!!,
                    input.getValue("score").jsonPrimitive.int,
                    settings(input.getValue("settings")),
                    input.str("actualCompletionDate"),
                    input.str("schedulingReferenceDate"),
                    now(case),
                    paris,
                )
            }
            val error = case["expectedError"]?.jsonObject
            if (error != null) {
                try {
                    run()
                    fail("${name(case)} : exception attendue")
                } catch (thrown: IllegalArgumentException) {
                    assertEquals(name(case), error.str("message"), thrown.message)
                }
            } else {
                assertReview(name(case), case.getValue("expected").jsonObject, run(), now(case))
            }
        }
        assertEquals(65, cases.size)
        assertEquals("cas corrigés par D14", D14_REVIEW_CASES, d14Cases.size)
    }

    @Test
    fun applyAdaptiveReviewChains() {
        var steps = 0
        for (chain in suite("applyAdaptiveReviewChains")) {
            val settings = settings(chain.getValue("settings"))
            var current = revisions(chain.getValue("initialRevisions"))
            for ((index, step) in chain.getValue("steps").jsonArray.map { it.jsonObject }.withIndex()) {
                val result = AdaptiveAlgorithm.applyAdaptiveReview(
                    current, step.str("revisionId")!!, step.getValue("score").jsonPrimitive.int, settings,
                    step.str("actualCompletionDate"), step.str("schedulingReferenceDate"), now(step), paris,
                )
                val expected = step.getValue("expected").jsonObject
                assertReview("${name(chain)} étape $index", expected, result, now(step))
                current = result.updatedRevisions
                steps++
            }
        }
        assertEquals(44, steps)
        assertEquals("étapes corrigées par D14", D14_CHAIN_STEPS, d14Cases.size)
    }

    @Test
    fun getEveningProgramme() {
        for (case in suite("getEveningProgramme")) {
            val input = case.getValue("input").jsonObject
            val programme = EveningProgramme.of(revisions(input.getValue("revisions")), courses(input.getValue("courses")), input.str("today")!!)
            val expected = case.getValue("expected").jsonObject
            assertEquals(name(case), expected.str("preparationDate"), programme.preparationDate)
            assertEquals(name(case), ids(expected.getValue("due")), programme.due.map { it.id })
            assertEquals(name(case), ids(expected.getValue("completed")), programme.completed.map { it.id })
            assertEquals(name(case), ids(expected.getValue("overdue")), programme.overdue.map { it.id })
            assertEquals(name(case), ids(expected.getValue("visible")), programme.visible.map { it.id })
        }
    }

    @Test
    fun matchSubject() {
        val cases = suite("matchSubject")
        for (case in cases) {
            val input = case.getValue("input").jsonObject
            val match = SubjectMatcher.match(input.str("summary")!!, subjects(input.getValue("subjects")))
            assertEquals(name(case), case.str("expectedSubjectId"), match?.id)
        }
        assertEquals(53, cases.size)
    }

    /**
     * L’app ne crée plus de données de démonstration : le vecteur `initializeDefaultData` sert de
     * sauvegarde web réaliste (export) et d’installation héritée du jalon 1 (migration).
     */
    @Test
    fun webExportOfDemoDataIsReadableAndMigratedAway() {
        for (case in suite("initializeDefaultData")) {
            val expected = case.getValue("expected").jsonObject
            val exported = case.getValue("expectedExport")
            // Compatibilité de l’export web : décodage puis réencodage identique.
            val imported = AppDocumentCodec.decodeExport(exported.toString())!!
            assertEquals(name(case), courses(expected.getValue("courses")), imported.courses)
            assertEquals(name(case), revisions(expected.getValue("revisions")), imported.revisions)
            assertEquals(name(case), settings(expected.getValue("settings")), imported.settings)
            assertEquals(name(case), exported, json.parseToJsonElement(AppDocumentCodec.export(imported, now(case))))
            // Un import explicite est conservé tel quel : il est déjà à la version courante.
            assertEquals(imported, DataMigrations.migrate(imported))

            // Installation du jalon 1 : démo modifiée par des validations, plus de vrais créneaux synchronisés.
            val realEvent = PronoteEvent(
                id = "pronote-c1-2026-11-18T08:30-0", subjectName = "MATHEMATIQUES", matchedSubjectId = "maths",
                date = "2026-11-18", startTime = "08:30", endTime = "09:30", isCancelled = false,
            )
            val demoRevisions = revisions(expected.getValue("revisions"))
            val validated = ScoreSubmission.submit(
                courses(expected.getValue("courses")), demoRevisions, DEFAULT_SETTINGS,
                demoRevisions.first { it.status != fr.jmethod.app.model.RevisionStatus.COMPLETED }.id, 10, now(case), paris,
            ).revisions
            val legacy = AppDocument(
                courses = courses(expected.getValue("courses")),
                revisions = validated,
                pronoteEvents = events(expected.getValue("pronoteEvents")) + realEvent,
                initialized = true,
            )
            assertTrue(validated.any { it.isEmergencySession == true })
            val migrated = DataMigrations.migrate(legacy)
            assertTrue(migrated.courses.isEmpty())
            assertTrue(migrated.revisions.isEmpty())
            assertEquals(listOf(realEvent), migrated.pronoteEvents)
            assertEquals(DataMigrations.CURRENT_VERSION, migrated.dataVersion)
            assertEquals(legacy.subjects, migrated.subjects)
            // Idempotente.
            assertEquals(migrated, DataMigrations.migrate(migrated))
            assertEquals(migrated, DataMigrations.migrate(DataMigrations.removeLegacyDemo(migrated).copy(dataVersion = 0)))
        }
    }

    @Test
    fun todayScreen() {
        for (case in suite("todayScreen")) {
            val input = case.getValue("input").jsonObject
            val label = name(case)
            val model = TodayModel.build(
                input.str("today")!!, courses(input.getValue("courses")), revisions(input.getValue("revisions")),
                subjects(input.getValue("subjects")), events(input.getValue("pronoteEvents")),
            )
            val e = case.getValue("expected").jsonObject
            assertEquals(label, e.str("preparationDate"), model.preparationDate)
            assertEquals(label, e.str("headerDate"), model.headerDate)
            assertEquals(label, e.str("tomorrowDate"), model.tomorrowDate)
            val navigation = e.getValue("navigation").jsonObject
            assertEquals(label, navigation.getValue("pendingCountToday").jsonPrimitive.int, model.pendingCountToday)
            assertEquals(label, navigation.getValue("overdueCount").jsonPrimitive.int, model.overdueCount)
            assertEquals(label, navigation.getValue("badgeCount").jsonPrimitive.int, model.badgeCount)
            assertEquals(label, ids(e.getValue("due")), model.due.map { it.id })
            assertEquals(label, ids(e.getValue("completed")), model.completed.map { it.id })
            assertEquals(label, ids(e.getValue("overdue")), model.overdue.map { it.id })
            assertEquals(label, e.getValue("total").jsonPrimitive.int, model.total)
            assertEquals(label, e.getValue("progress").jsonPrimitive.int, model.progress)
            assertEquals(label, e.str("progressSuffix"), model.progressSuffix)
            assertEquals(label, e.str("progressMessage"), model.progressMessage)
            assertEquals(label, e.getValue("allDoneBanner").jsonPrimitive.boolean, model.allDoneBanner)
            val hero = e.getValue("hero").jsonObject
            assertEquals(label, hero.str("title"), model.hero.title)
            assertEquals(label, hero.str("revisionId"), model.hero.revision?.id)
            assertEquals(label, hero.str("courseId"), model.hero.course?.id)
            assertEquals(label, hero.str("subjectLabel"), model.hero.subjectLabel)
            assertEquals(label, hero.str("courseTitle"), model.hero.courseTitle)
            assertEquals(label, e.str("dueLabel"), model.dueLabel)
            assertEquals(label, e.str("overdueBanner"), model.overdueBanner)
            assertEquals(label, e.getValue("emptyProgramme").jsonPrimitive.boolean, model.emptyProgramme)
            val groups = e.getValue("groups").jsonArray.map { it.jsonObject }
            assertEquals(label, groups.map { it.str("subjectId") }, model.groups.map { it.subject.id })
            for ((group, actual) in groups.zip(model.groups)) {
                assertEquals(label, group.str("subjectName"), actual.subject.name)
                assertEquals(label, group.str("countLabel"), actual.countLabel)
                val rows = group.getValue("rows").jsonArray.map { it.jsonObject }
                assertEquals(label, rows.size, actual.rows.size)
                for ((row, real) in rows.zip(actual.rows)) {
                    assertEquals(label, row.str("revisionId"), real.revision.id)
                    assertEquals(label, row.str("courseId"), real.course.id)
                    assertEquals(label, row.str("phase"), json.encodeToJsonElement(Phase.serializer(), real.phase).jsonPrimitive.content)
                    assertEquals(label, row.str("badge"), real.badge)
                    assertEquals(label, row.str("courseLine"), real.courseLine)
                    assertEquals(label, row.str("title"), real.title)
                    assertEquals(label, row.getValue("done").jsonPrimitive.boolean, real.done)
                    assertEquals(label, row.str("doneLabel"), real.doneLabel)
                    assertEquals(label, row.getValue("canValidate").jsonPrimitive.boolean, real.canValidate)
                }
            }
            val tomorrow = e.getValue("tomorrowEvents").jsonArray.map { it.jsonObject }
            assertEquals(label, tomorrow.size, model.tomorrowEvents.size)
            for ((event, real) in tomorrow.zip(model.tomorrowEvents)) {
                assertEquals(label, event.str("id"), real.id)
                assertEquals(label, event.str("timeRange"), real.timeRange)
                assertEquals(label, event.str("subjectName"), real.subjectName)
                assertEquals(label, event.getValue("cancelled").jsonPrimitive.boolean, real.cancelled)
                assertEquals(label, event.str("trailing"), real.trailing)
                assertEquals(label, event.str("teacher"), real.teacher)
                assertEquals(label, event.getValue("canAddCourse").jsonPrimitive.boolean, real.canAddCourse)
            }
            checked++
        }
        assertEquals(7, checked)
    }

    @Test
    fun scoreSubmit() {
        for (case in suite("scoreSubmit")) {
            val input = case.getValue("input").jsonObject
            val label = name(case)
            val outcome = ScoreSubmission.submit(
                courses(input.getValue("courses")), revisions(input.getValue("revisions")), settings(input.getValue("settings")),
                input.str("revisionId")!!, input.getValue("score").jsonPrimitive.int, now(case), paris,
            )
            val e = case.getValue("expected").jsonObject
            assertEquals(label, e.str("today"), outcome.today)
            assertEquals(label, e.str("preparationDate"), outcome.preparationDate)
            assertEquals(label, e.getValue("ignored").jsonPrimitive.boolean, outcome.ignored)
            val expectedRevisions = e.getValue("revisions").jsonArray
            if (expectedRevisions.firstOrNull() is JsonPrimitive) {
                // Cas ignorés : le générateur ne liste que les ids, tableau inchangé.
                assertEquals(label, ids(expectedRevisions), outcome.revisions.map { it.id })
                assertEquals(label, revisions(input.getValue("revisions")), outcome.revisions)
            } else {
                assertEquals(label, revisions(expectedRevisions), outcome.revisions)
            }
            assertEquals(label, e.getValue("celebrate").jsonPrimitive.boolean, outcome.celebrate)
            val toast = e["toast"]?.takeIf { it !is JsonNull }?.jsonObject
            if (toast == null) {
                assertEquals(label, null, outcome.toast)
            } else {
                assertEquals(label, toast.str("title"), outcome.toast?.title)
                assertEquals(label, nativeMessage(label, toast.str("message"), now(case)), outcome.toast?.message)
                assertEquals(label, toast.str("type"), outcome.toast?.type?.wire)
            }
        }
        assertEquals("cas corrigés par D14", D14_SUBMIT_CASES, d14Cases.size)
    }

    @Test
    fun scorePreview() {
        val cases = suite("scorePreview")
        for (case in cases) {
            val input = case.getValue("input").jsonObject
            val preview = ScorePreview.of(
                input.getValue("score").jsonPrimitive.int,
                input.getValue("sensitivity").jsonPrimitive.double,
                input.getValue("emergencyThreshold").jsonPrimitive.int,
            )
            val e = case.getValue("expected").jsonObject
            assertEquals(name(case), e.str("tone"), preview.tone.wire)
            assertEquals(name(case), e.str("title"), preview.title)
            assertEquals(name(case), e.str("description"), preview.description)
            assertEquals(name(case), e.str("scoreBand"), preview.scoreBand.wire)
            assertEquals(name(case), e.getValue("confettiOnSubmit").jsonPrimitive.boolean, preview.confettiOnSubmit)
        }
        assertTrue(cases.size >= 23)
    }
}
