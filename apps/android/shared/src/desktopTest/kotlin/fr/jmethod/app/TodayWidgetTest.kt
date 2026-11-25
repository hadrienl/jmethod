package fr.jmethod.app

import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.domain.*
import fr.jmethod.app.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TodayWidgetTest {
    @Test fun sharedVectors() {
        val raw = javaClass.classLoader.getResource("domain/today-widget-vectors.json")!!.readText()
        val fixture = AppDocumentCodec.json.parseToJsonElement(raw).jsonObject
        val day = fixture.getValue("day").jsonPrimitive.content
        for (row in fixture.getValue("cases").jsonArray.map { it.jsonObject }) {
            fun string(key: String) = row[key]?.jsonPrimitive?.contentOrNull
            fun ids(key: String) = row[key]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            fun revisions(key: String) = ids(key).map { id ->
                Revision(id, "c1", 0, "J0", day, status = if (key == "done") RevisionStatus.COMPLETED else RevisionStatus.PENDING,
                    phase = Phase.ENCODAGE, courseIds = listOf("c1", "c2"))
            }
            val visit = TodayWidgetVisit(day, day.takeIf { string("returning") == "true" }, ids("selection"))
            val state = TodayWidgetState.build(day, revisions("due"), revisions("done"), revisions("overdue"),
                ids("done").toSet(), visit, string("flameEarned") == "true")
            val name = string("name")
            val expected = string("state")!!.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
                .replace("RETURNED", "RETURN")
            assertEquals(name, expected, state.mode.name)
            assertEquals(name, string("next"), state.next?.id)
            assertEquals(name, string("remaining")?.toInt() ?: ids("due").size, state.remaining)
            string("outside")?.let { assertEquals(name, it.toInt(), state.outsidePlan) }
        }
    }

    @Test fun visitAndSelectionRoundTrip() {
        val visit = TodayWidgetVisit("2026-11-21", selection = listOf("b", "a")).opened("2026-11-24")
        assertEquals("2026-11-24", visit.returnDay)
        assertEquals(visit, visit.opened("2026-11-24"))
        assertNull(visit.opened("2026-11-25").returnDay)
        val document = AppDocument(schemaVersion = 3, todayWidgetVisit = visit)
        val text = AppDocumentCodec.export(document, Instant.parse("2026-11-24T20:00:00Z"))
        assertEquals(visit, AppDocumentCodec.decodeExport(text)?.todayWidgetVisit)
        assertEquals(visit, AppDocumentCodec.decode(AppDocumentCodec.encode(document)).todayWidgetVisit)
    }
}
