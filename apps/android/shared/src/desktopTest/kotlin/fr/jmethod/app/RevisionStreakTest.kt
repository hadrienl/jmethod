package fr.jmethod.app

import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.domain.ImportExport
import fr.jmethod.app.domain.RevisionStreak
import fr.jmethod.app.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RevisionStreakTest {
    private fun revision(id: String, date: String) = Revision(id, "c1", 0, "J0", date, status = RevisionStatus.PENDING, phase = Phase.ENCODAGE,
        courseIds = listOf("c1", "c2"), programmeId = "p")
    private fun document(revisions: List<Revision>) = AppDocument(courses = listOf(
        Course("c1","maths","",1,"C1","2026-11-01",createdAt = "2026-11-01"),
        Course("c2","maths","",2,"C2","2026-11-01",createdAt = "2026-11-01")), revisions = revisions, initialized = true, schemaVersion = 2)
    private fun status(day: RevisionDay?) = day?.status?.let { AppDocumentCodec.json.encodeToJsonElement(RevisionDayStatus.serializer(), it).jsonPrimitive.content }

    @Test fun sharedEventVectors() {
        val resource = javaClass.classLoader.getResource("domain/streak-vectors.json")!!.readText()
        val cases = AppDocumentCodec.json.parseToJsonElement(resource).jsonObject.getValue("cases").jsonArray
        for (case in cases.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            var doc = document(case.getValue("revisions").jsonArray.map { value ->
                val r = value.jsonObject; revision(r.getValue("id").jsonPrimitive.content,r.getValue("date").jsonPrimitive.content)
            })
            for ((index,event) in case.getValue("events").jsonArray.map { it.jsonObject }.withIndex()) {
                val now = Instant.parse(event.getValue("at").jsonPrimitive.content)
                val date = now.atZone(AppClock.ZONE).toLocalDate().toString()
                val frozen = doc.streakState?.days?.associate { it.date to it.frozenAt }.orEmpty()
                val id = event["id"]?.jsonPrimitive?.content
                doc = when (event.getValue("op").jsonPrimitive.content) {
                    "open" -> RevisionStreak.reconcile(doc,now,openCurrent = true)
                    "complete" -> {
                        val before = RevisionStreak.reconcile(doc,now,openCurrent = true)
                        val changed = before.copy(revisions = before.revisions.map { r ->
                            if (r.id == id && r.status != RevisionStatus.COMPLETED) r.copy(status = RevisionStatus.COMPLETED, completedDate = date, score = 0) else r
                        })
                        RevisionStreak.completed(changed,id!!,now)
                    }
                    "add" -> RevisionStreak.reconcile(doc,now).let { it.copy(revisions = it.revisions + revision(id!!,event.getValue("date").jsonPrimitive.content)) }
                    "delete" -> RevisionStreak.reconcile(doc,now).let { it.copy(revisions = it.revisions.filterNot { r -> r.id == id }) }
                    "move" -> RevisionStreak.reconcile(doc,now).let { it.copy(revisions = it.revisions.map { r -> if(r.id == id) r.copy(scheduledDate = event.getValue("date").jsonPrimitive.content) else r }) }
                    else -> error("Événement inconnu")
                }
                val label = "$name / $index"
                val state = doc.streakState!!
                RevisionStreak.validate(state)
                assertEquals(label,event.getValue("count").jsonPrimitive.int,state.currentCount)
                assertEquals(label,event.getValue("best").jsonPrimitive.int,state.bestCount)
                assertEquals(label,event.getValue("status").jsonPrimitive.content,status(state.days.firstOrNull { it.date == date }))
                event["required"]?.jsonArray?.let { ids -> assertEquals(label,ids.map { it.jsonPrimitive.content },state.days.first { it.date == date }.requiredRevisionIds) }
                event["past"]?.jsonObject?.forEach { (day, expected) -> assertEquals(label,expected.jsonPrimitive.content,status(state.days.first { it.date == day })) }
                state.days.forEach { entry -> frozen[entry.date]?.let { assertEquals(label,it,entry.frozenAt) } }
                assertEquals(label,doc,RevisionStreak.reconcile(doc,now,openCurrent = true))
            }
        }
    }

    @Test fun migrationAndBackgroundDoNotInventPastFlames() {
        val historical = revision("old","2026-11-01").copy(status = RevisionStatus.COMPLETED,completedDate = "2026-11-01",score = 100)
        val doc = document(listOf(historical))
        val now = Instant.parse("2026-11-23T18:00:00Z")
        assertEquals(doc,RevisionStreak.reconcile(doc,now))
        val started = RevisionStreak.reconcile(doc,now,openCurrent = true).streakState!!
        assertEquals("2026-11-23",started.trackingStartDate)
        assertEquals(0,started.currentCount)
        assertEquals(0,started.bestCount)
        assertEquals(1,started.days.size)
        assertEquals(RevisionDayStatus.REST,started.days.single().status)
    }

    /** Export puis restauration : journal et record repris, jours écoulés clôturés ; un ancien export n'invente rien. */
    @Test fun exportRestoreKeepsJournalAndRecord() {
        fun day(date: String, status: RevisionDayStatus): RevisionDay {
            val required = listOf("r-$date")
            return RevisionDay(date, required, if (status == RevisionDayStatus.EARNED) required else emptyList(),
                "${date}T17:00:00.000Z", "${date}T22:00:00.000Z", status)
        }
        val state = RevisionStreak.derived(StreakState("2026-11-13", "2026-11-17", days = listOf(
            day("2026-11-13", RevisionDayStatus.EARNED), day("2026-11-14", RevisionDayStatus.EARNED), day("2026-11-15", RevisionDayStatus.MISSED),
            day("2026-11-16", RevisionDayStatus.EARNED), day("2026-11-17", RevisionDayStatus.EARNED))))
        val subjects = listOf(Subject("maths", "Mathématiques", "#38BDF8"))
        val doc = document(emptyList()).copy(subjects = subjects, schemaVersion = 3, streakState = state)
        assertEquals(doc.streakState, AppDocumentCodec.decode(AppDocumentCodec.encode(doc)).streakState)

        val accepted = ImportExport.parse(AppDocumentCodec.export(doc, Instant.parse("2026-11-17T20:00:00Z"))) as ImportExport.Result.Accepted
        assertEquals(state, accepted.streakState)
        val now = Instant.parse("2026-11-19T18:00:00Z")
        val restored = RevisionStreak.reconcile(accepted.applyTo(document(emptyList()).copy(subjects = subjects)), now, openCurrent = true).streakState!!
        assertEquals(2, restored.currentCount)
        assertEquals(2, restored.bestCount)
        assertEquals(listOf("2026-11-18" to RevisionDayStatus.REST, "2026-11-19" to RevisionDayStatus.REST), restored.days.takeLast(2).map { it.date to it.status })

        val legacy = ImportExport.parse(AppDocumentCodec.export(doc.copy(schemaVersion = 2, streakState = null), now)) as ImportExport.Result.Accepted
        assertNull(legacy.streakState)
        val fresh = RevisionStreak.reconcile(legacy.applyTo(doc), now, openCurrent = true).streakState!!
        assertEquals("2026-11-19", fresh.trackingStartDate)
        assertEquals(0, fresh.bestCount)
        assertEquals(1, fresh.days.size)
    }
}
