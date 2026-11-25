package fr.jmethod.app

import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.domain.SuccessSound
import fr.jmethod.app.domain.TodayWidgetVisit
import fr.jmethod.app.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SuccessSoundTest {
    @Test fun sharedPriorityVectors() {
        val day = "2026-11-25"
        val priorDay = "2026-11-24"
        val fixture = javaClass.classLoader.getResource("domain/success-sound-vectors.json")!!.readText()
        for (test in AppDocumentCodec.json.parseToJsonElement(fixture).jsonArray.map { it.jsonObject }) {
            fun flag(key: String) = test[key]?.jsonPrimitive?.booleanOrNull == true
            fun entry(date: String, earned: Boolean) = RevisionDay(
                date, listOf("r"), if (earned) listOf("r") else emptyList(), "${date}T12:00:00Z",
                status = if (earned) RevisionDayStatus.EARNED else RevisionDayStatus.PENDING,
            )
            val prior = if (flag("prior")) listOf(entry(priorDay, true)) else emptyList()
            val revision = Revision("r", "c", 0, "J0", day, phase = Phase.ENCODAGE,
                status = if (flag("duplicate")) RevisionStatus.COMPLETED else RevisionStatus.PENDING,
                programmeId = "p".takeIf { flag("programme") })
            val initial = AppDocument(
                revisions = listOf(revision),
                settings = AppSettings(soundEnabled = !flag("muted")),
                streakState = StreakState(prior.firstOrNull()?.date ?: day, day, days = prior + entry(day, false)),
                todayWidgetVisit = TodayWidgetVisit(day, selection = listOf("r")).takeIf { flag("resume") },
            )
            val updated = initial.copy(
                revisions = listOf(if (flag("unchanged")) revision else revision.copy(status = RevisionStatus.COMPLETED)) +
                    if (flag("recovery")) listOf(revision.copy(id = "recovery", isEmergencySession = true)) else emptyList(),
                streakState = initial.streakState?.copy(days = prior + entry(day, flag("flame"))),
            )
            val actual = SuccessSound.afterRevision(initial, updated, revision.id, day, flag("evening"))
            assertEquals(test.getValue("case").jsonPrimitive.content,
                test["expected"]?.jsonPrimitive?.contentOrNull, actual?.name?.lowercase())
        }
    }
}
