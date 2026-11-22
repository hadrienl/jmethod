package fr.jmethod.app

import fr.jmethod.app.data.*
import fr.jmethod.app.domain.*
import fr.jmethod.app.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId

class RevisionProgrammesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun sheet(id: String, subject: String = "maths") = Course(id, subject, "Sans chapitre", id.last().digitToInt(), id.uppercase(), "2026-11-22", createdAt = "2026-11-22", reference = id.uppercase())
    private fun document() = DataMigrations.emptyDocument().copy(courses = listOf(sheet("c1"), sheet("c2"), sheet("c3")))
    private val fixture by lazy { AppDocumentCodec.json.parseToJsonElement(javaClass.classLoader.getResource("domain/programme-vectors.json")!!.readText()).jsonObject }
    private fun score(doc: AppDocument, id: String, value: Int = 50) = ScoreSubmission.submit(doc.courses, doc.revisions, doc.settings, id, value,
        Instant.parse(fixture.getValue("evening").jsonObject.getValue("now").jsonPrimitive.content), ZoneId.of("Europe/Paris"), doc.programmes)

    @Test fun sharedCalendarAndPhases() {
        val expected = fixture.getValue("rhythm").jsonObject
        assertEquals(expected.getValue("days").jsonArray.map { it.jsonPrimitive.int }, RevisionRhythm.DEFAULT.steps.map { it.offsetDays })
        assertEquals(expected.getValue("phases").jsonArray.map { it.jsonPrimitive.content }, RevisionRhythm.DEFAULT.steps.map { AppDocumentCodec.json.encodeToJsonElement(Phase.serializer(), it.phase).jsonPrimitive.content })
        fixture.getValue("calendars").jsonArray.forEach { value ->
            val row = value.jsonObject
            val result = RevisionProgrammes.start(document(), listOf("c1", "c2"), row.getValue("start").jsonPrimitive.content, "p")
            assertEquals(row.getValue("dates").jsonArray.map { it.jsonPrimitive.content }, result.revisions.map { it.scheduledDate })
            assertTrue(result.revisions.all { it.courseIds == listOf("c1", "c2") })
            assertEquals("C1 + C2", RevisionProgrammes.references(result.revisions.first(), result.courses))
            assertTrue(result.revisions.none { it.appliesTo("c3") })
            assertEquals(9, result.revisions.size)
        }
    }
    @Test fun snapshotsFixedModeAndEveningValidation() {
        val base = document().copy(settings = document().settings.copy(adaptiveEnabled = false))
        val started = RevisionProgrammes.start(base, listOf("c1", "c2"), "2026-11-23", "p")
        val changedSettings = started.copy(settings = started.settings.copy(rhythm = RevisionRhythm.DEFAULT.copy(steps = listOf(RhythmStep("zero",0,Phase.RECUPERATION))), intervals = listOf(0,200), adaptiveEnabled = true))
        val outcome = score(changedSettings, started.revisions.first().id, 0)
        assertEquals(started.revisions.drop(1), outcome.revisions.filter { it.id != started.revisions.first().id })
        assertEquals("2026-11-22", outcome.revisions.first { it.status == RevisionStatus.COMPLETED }.completedDate)
        assertEquals("2026-11-24", outcome.revisions.first { it.stepLabel == "J1" }.scheduledDate)
        assertTrue(score(changedSettings.copy(revisions = outcome.revisions), started.revisions.first().id).ignored)
        val adaptive = RevisionProgrammes.start(document(), listOf("c1"), "2026-11-23", "a")
        val after = score(adaptive.copy(settings = changedSettings.settings), adaptive.revisions.first().id)
        assertEquals("2026-11-24", after.revisions.first { it.stepLabel == "J1" }.scheduledDate)
        assertTrue(adaptive.programmes.single().adaptiveEnabled)
    }
    @Test fun reapplyRetainsHistoryAndEmergencyAndUsesNominalDays() {
        val started = RevisionProgrammes.start(document(), listOf("c1", "c2"), "2026-11-23", "p")
        val outcome = score(started, started.revisions.first().id, 0)
        val doc = started.copy(revisions = outcome.revisions)
        val retained = doc.revisions.filter { it.status == RevisionStatus.COMPLETED || it.isEmergencySession == true }
        val rhythm = RevisionRhythm.DEFAULT.copy(revision = 2, steps = listOf(RhythmStep("zero",0,Phase.ENCODAGE), RhythmStep("three",3,Phase.ENCODAGE), RhythmStep("ten",10,Phase.CONSOLIDATION)))
        val applied = RevisionProgrammes.reapply(doc, setOf("p"), rhythm)
        assertTrue(applied.revisions.containsAll(retained))
        assertEquals(listOf("J3", "J10"), applied.revisions.filter { it.status != RevisionStatus.COMPLETED && it.isEmergencySession != true }.map { it.stepLabel })
        assertEquals(applied, RevisionProgrammes.reapply(applied, setOf("p"), rhythm))
        val emergency = applied.revisions.first { it.isEmergencySession == true }
        val scored = score(applied, emergency.id)
        assertEquals("2026-11-26", scored.revisions.first { it.stepLabel == "J3" }.scheduledDate)
        assertEquals("2026-12-03", scored.revisions.first { it.stepLabel == "J10" }.scheduledDate)
        assertTrue(scored.revisions.contains(retained.first { it.status == RevisionStatus.COMPLETED }))
    }
    @Test fun nextSessionPrioritizesRetainedEmergencyAfterRhythmReindexing() {
        var doc = RevisionProgrammes.start(document(), listOf("c1", "c2"), "2026-11-23", "p")
        for (day in listOf(0,1,3)) {
            val revision = doc.revisions.first { it.nominalOffsetDays == day && it.isEmergencySession != true }
            doc = doc.copy(revisions = score(doc, revision.id).revisions)
        }
        val j7 = doc.revisions.first { it.stepLabel == "J7" }
        doc = doc.copy(revisions = score(doc, j7.id, 0).revisions)
        val emergency = doc.revisions.first { it.isEmergencySession == true }
        val rhythm = RevisionRhythm.DEFAULT.copy(revision = 2, steps = RevisionRhythm.DEFAULT.steps.filter { it.offsetDays !in listOf(1,3) })
        val updated = RevisionProgrammes.reapply(doc, setOf("p"), rhythm)
        assertTrue(updated.revisions.first { it.stepLabel == "J14" }.stepIndex < emergency.stepIndex)
        assertEquals(emergency.id, RevisionSession.nextOfCourse("c1", updated.revisions)?.id)
        assertEquals(emergency.id, RevisionSession.nextOfCourse("c2", updated.revisions)?.id)
    }

    @Test fun migrationPreservesDatesScoresPhasesDocumentsAndIsIdempotent() {
        val course = sheet("c1").copy(documents = listOf(CourseDocument("d","fiche.pdf","application/pdf",12,"documents/c1/d.pdf")))
        val legacy = AdaptiveAlgorithm.generateInitialRevisions(course, listOf(0,1,7)).mapIndexed { i,r -> if (i == 0) r.copy(status = RevisionStatus.COMPLETED, score = 75, completedDate = "2026-11-22") else r }
        val old = AppDocument(courses = listOf(course), revisions = legacy, initialized = true, dataVersion = 1)
        val migrated = RevisionProgrammes.migrate(old)
        assertEquals(old.courses, migrated.courses)
        assertEquals(legacy, migrated.revisions.map { it.copy(programmeId = null, courseIds = null, rhythmStepId = null, nominalOffsetDays = null) })
        assertEquals(migrated, RevisionProgrammes.migrate(migrated))
        assertEquals(listOf(0,1,7), migrated.programmes.single().rhythmSnapshot.steps.map { it.offsetDays })
        val file = File(folder.root,"jmethod-data.json")
        file.writeText(AppDocumentCodec.encode(old))
        val before = file.readText()
        val store = AppDocumentStore(file)
        store.write(migrated)
        assertEquals(before, file.readText())
        assertEquals(migrated, (store.read() as AppDocumentStore.ReadResult.Loaded).document)
        assertTrue(File(folder.root,"jmethod-data-v2.json").exists())
    }
    @Test fun exportRestoreValidatesGroupIntegrityAndVersion() {
        val doc = RevisionProgrammes.start(document(), listOf("c1","c2"), "2026-11-23", "p")
        val text = ImportExport.export(doc, Instant.parse("2026-11-22T19:00:00Z"))
        val envelope = AppDocumentCodec.json.parseToJsonElement(text).jsonObject
        assertNull(envelope["courses"])
        assertNotNull(envelope["data"])
        val result = ImportExport.parse(text) as ImportExport.Result.Accepted
        val restored = result.applyTo(DataMigrations.emptyDocument())
        assertEquals(doc.programmes, restored.programmes)
        assertEquals(doc.courses, restored.courses)
        assertEquals(doc.revisions, restored.revisions)
        assertEquals(doc.settings, restored.settings)
        assertTrue(ImportExport.parse(text.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3")) is ImportExport.Result.Rejected)
        assertTrue(ImportExport.parse(text.replace("\"programmeId\": \"p\"", "\"programmeId\": \"absent\"")) is ImportExport.Result.Rejected)
    }
    @Test fun emptyVersionedExportCanBeRestored() {
        val empty = DataMigrations.emptyDocument()
        val text = ImportExport.export(empty, Instant.EPOCH)
        val result = ImportExport.parse(text) as ImportExport.Result.Accepted
        assertTrue(result.applyTo(document()).courses.isEmpty())
        assertTrue(result.applyTo(document()).programmes.isEmpty())
    }
    @Test fun invalidSelectionAndRhythmsAreRejected() {
        val doc = document().copy(courses = document().courses + sheet("c4","fr"))
        assertThrows(IllegalArgumentException::class.java) { RevisionProgrammes.start(doc, listOf("c1","c4"), "2026-11-23") }
        assertThrows(IllegalArgumentException::class.java) { RevisionProgrammes.start(doc, listOf("absent"), "2026-11-23") }
        val started = RevisionProgrammes.start(doc, listOf("c1"), "2026-11-23")
        assertThrows(IllegalArgumentException::class.java) { RevisionProgrammes.start(started, listOf("c1","c2"), "2026-11-23") }
        listOf(listOf(1,3), listOf(0,0), listOf(0,-1), listOf(0,3651)).forEach { days ->
            assertThrows(IllegalArgumentException::class.java) { RevisionProgrammes.validate(RevisionRhythm.DEFAULT.copy(steps = days.mapIndexed { i,d -> RhythmStep("$i",d,Phase.ENCODAGE) })) }
        }
    }
}
