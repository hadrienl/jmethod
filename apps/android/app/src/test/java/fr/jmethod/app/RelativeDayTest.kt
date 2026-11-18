package fr.jmethod.app

import fr.jmethod.app.domain.AdaptiveAlgorithm
import fr.jmethod.app.domain.FrenchDates
import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Écart D14 : dates des messages de validation en français, relatives au jour réel. */
class RelativeDayTest {
    private val today = LocalDate.parse("2026-11-18")
    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun relativeDays() {
        assertEquals("demain", FrenchDates.relativeDay(LocalDate.parse("2026-11-19"), today))
        assertEquals("après-demain", FrenchDates.relativeDay(LocalDate.parse("2026-11-20"), today))
        assertEquals("le vendredi 27 novembre", FrenchDates.relativeDay(LocalDate.parse("2026-11-27"), today))
        assertEquals("le mercredi 18 novembre", FrenchDates.relativeDay(today, today))
        assertEquals("le vendredi 1 janvier 2027", FrenchDates.relativeDay(LocalDate.parse("2027-01-01"), today))
        // Passage d’année : le 31 décembre, le 1er janvier reste « demain ».
        assertEquals("demain", FrenchDates.relativeDay(LocalDate.parse("2027-01-01"), LocalDate.parse("2026-12-31")))
    }

    @Test
    fun validationMessageUsesTheRealDay() {
        val course = Course("c", "math", "Volumes", 1, "Essai", "2026-11-18", createdAt = "2026-11-18T08:00:00.000Z")
        val revisions = AdaptiveAlgorithm.generateInitialRevisions(course, AppSettings().intervals)
        // Validé le soir du 18 pour préparer le 19 : J1 tombe le 19, soit « demain ».
        val evening = ZonedDateTime.of(2026, 11, 18, 20, 0, 0, 0, paris).toInstant()
        val result = AdaptiveAlgorithm.applyAdaptiveReview(revisions, "c-rev-0", 50, AppSettings(), now = evening, zone = paris)
        assertEquals("2026-11-19", result.explanation.nextDate)
        assertTrue(result.explanation.message, result.explanation.message.endsWith("Prochaine révision demain."))
        // Même calcul, validé une semaine plus tard (historique) : la date est dite par rapport au jour réel.
        val later = ZonedDateTime.of(2026, 11, 25, 20, 0, 0, 0, paris).toInstant()
        val late = AdaptiveAlgorithm.applyAdaptiveReview(revisions, "c-rev-0", 50, AppSettings(), "2026-11-18", now = later, zone = paris)
        assertTrue(late.explanation.message, late.explanation.message.endsWith("Prochaine révision le jeudi 19 novembre."))
    }
}
