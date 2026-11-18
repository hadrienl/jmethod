package fr.jmethod.app

import fr.jmethod.app.data.AppClock
import fr.jmethod.app.domain.RevisionSession
import fr.jmethod.app.domain.ScoreSubmission
import fr.jmethod.app.domain.TodayModel
import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.CourseDocument
import fr.jmethod.app.model.CourseSource
import fr.jmethod.app.model.Phase
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.RevisionStatus
import fr.jmethod.app.model.Subject
import fr.jmethod.app.pronote.NotebookAttachment
import fr.jmethod.app.pronote.NotebookContent
import fr.jmethod.app.pronote.NotebookSnapshot
import fr.jmethod.app.pronote.SessionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Réviser sans quitter Aujourd’hui (`notebook-to-course.md`, section 8). Données synthétiques. */
class RevisionSessionTest {
    private val today = "2026-11-18"
    private val tomorrow = "2026-11-19"
    private val evening = Instant.parse("2026-11-18T19:00:00Z")
    private val subjects = listOf(Subject("maths", "Mathématiques", "#2563eb"), Subject("anglais", "Anglais", "#db2777"))

    private fun course(id: String, subjectId: String, date: String = "2026-11-10", source: CourseSource? = null, documents: List<CourseDocument>? = null) =
        Course(id, subjectId, "Sans chapitre", 1, "Cours $id", date, null, "2026-11-10T18:00:00.000Z", source, documents)

    private fun revision(id: String, courseId: String, step: Int, date: String, done: Boolean = false) = Revision(
        id, courseId, step, "J$step", date,
        completedDate = if (done) date else null, score = if (done) 60 else null,
        status = if (done) RevisionStatus.COMPLETED else RevisionStatus.PENDING, phase = Phase.CONSOLIDATION,
    )

    private fun doc(name: String, title: String? = null) = CourseDocument("d-$name", name, "application/pdf", 10, "documents/c/$name", title)

    @Test
    fun nextSessionFollowsTheEveningProgrammeThenEnds() {
        val courses = listOf(course("a", "maths"), course("b", "anglais"))
        val revisions = listOf(
            revision("late-b", "b", 1, "2026-11-17"),
            revision("due-a", "a", 2, tomorrow),
            revision("later-a", "a", 3, "2026-11-25"),
            revision("done-b", "b", 0, "2026-11-10", done = true),
        )
        // Ordre du programme du soir : la séance en retard d’abord, puis celle de demain.
        assertEquals("late-b", RevisionSession.nextDue(revisions, courses, today)?.id)

        val first = ScoreSubmission.submit(courses, revisions, AppSettings(), "late-b", 60, evening, AppClock.ZONE)
        val next = RevisionSession.nextDue(first.revisions, courses, first.today, excluding = "late-b")
        assertEquals("due-a", next?.id)
        assertFalse(first.celebrate)
        assertEquals("Séance suivante · Mathématiques — Cours a", RevisionSession.nextLabel(next!!, courses, subjects))

        // Dernière séance due : plus de séance suivante, programme terminé.
        val second = ScoreSubmission.submit(courses, first.revisions, AppSettings(), "due-a", 70, evening, AppClock.ZONE)
        assertNull(RevisionSession.nextDue(second.revisions, courses, second.today, excluding = "due-a"))
        assertTrue(second.celebrate)
    }

    @Test
    fun catchUpSessionIsNotProposedTonight() {
        val courses = listOf(course("a", "maths"))
        val revisions = listOf(revision("due-a", "a", 1, tomorrow), revision("later-a", "a", 2, "2026-11-30"))
        // Rattrapage : programmé le lendemain du jour préparé, donc hors du programme de ce soir.
        val result = ScoreSubmission.submit(courses, revisions, AppSettings(), "due-a", 10, evening, AppClock.ZONE)
        assertEquals(ScoreSubmission.ToastType.WARNING, result.toast?.type)
        assertNull(RevisionSession.nextDue(result.revisions, courses, result.today, excluding = "due-a"))
    }

    @Test
    fun nextSessionOfACourseIsTheFirstUndoneStep() {
        val revisions = listOf(
            revision("a3", "a", 3, "2026-11-30"),
            revision("a1", "a", 1, "2026-11-12", done = true),
            revision("a2", "a", 2, "2026-11-20"),
            revision("b1", "b", 1, "2026-11-13"),
        )
        assertEquals("a2", RevisionSession.nextOfCourse("a", revisions)?.id)
        assertNull(RevisionSession.nextOfCourse("c", revisions))
    }

    @Test
    fun documentPresenceAndPrincipalDocument() {
        assertFalse(RevisionSession.hasDocuments(course("a", "maths")))
        assertFalse(RevisionSession.hasDocuments(course("a", "maths", documents = emptyList())))
        assertFalse(RevisionSession.hasDocuments(null))
        val withDocs = course("a", "maths", documents = listOf(doc("fiche.pdf", "Les fractions"), doc("annexe.png")))
        assertTrue(RevisionSession.hasDocuments(withDocs))
        assertEquals("fiche.pdf", RevisionSession.principalDocument(withDocs)?.name)

        // Lignes d’Aujourd’hui : icône seulement pour le cours qui a des documents.
        val courses = listOf(withDocs, course("b", "anglais"))
        val model = TodayModel.build(today, courses, listOf(revision("ra", "a", 1, tomorrow), revision("rb", "b", 1, tomorrow)), subjects, emptyList())
        val rows = model.groups.flatMap { it.rows }.associate { it.revision.id to it.hasDocuments }
        assertEquals(mapOf("ra" to true, "rb" to false), rows)
    }

    @Test
    fun tomorrowSlotMatchesAnImportedCourse() {
        val source = CourseSource("pronote-notebook", "cdt-1", "MATHEMATIQUES", tomorrow)
        val typed = course("typed", "maths", date = tomorrow)
        val imported = course("imported", "maths", date = tomorrow, source = source)
        val other = course("other", "maths", date = today)
        fun event(id: String, subject: String, date: String = tomorrow, cancelled: Boolean = false) =
            PronoteEvent(id, subject, date = date, startTime = "08:00", endTime = "09:00", isCancelled = cancelled)

        // Même matière et même jour : le cours issu du cahier est préféré.
        assertEquals("imported", RevisionSession.courseForSlot(event("e1", "MATHEMATIQUES"), listOf(typed, imported, other), subjects)?.id)
        assertEquals("typed", RevisionSession.courseForSlot(event("e1", "MATHEMATIQUES"), listOf(typed, other), subjects)?.id)
        assertNull(RevisionSession.courseForSlot(event("e2", "MATHEMATIQUES", date = "2026-11-20"), listOf(imported), subjects))
        assertNull(RevisionSession.courseForSlot(event("e3", "ANGLAIS LV1"), listOf(imported), subjects))
        assertNull(RevisionSession.courseForSlot(event("e4", "MATHEMATIQUES", cancelled = true), listOf(imported), subjects))
        assertNull(RevisionSession.courseForSlot(event("e5", "LATIN"), listOf(imported), subjects))

        val model = TodayModel.build(
            today, listOf(imported), emptyList(), subjects,
            listOf(event("e1", "MATHEMATIQUES"), event("e3", "ANGLAIS LV1"), event("e4", "MATHEMATIQUES", cancelled = true)),
        )
        assertEquals(listOf("imported", null, null), model.tomorrowEvents.map { it.courseId })
        // « Ajouter ce cours » reste calculé comme le web ; l’écran affiche « Voir le cours » à la place.
        assertEquals(listOf(true, true, false), model.tomorrowEvents.map { it.canAddCourse })
    }

    @Test
    fun notebookPartShowsTheOriginSessionWithoutImportedFiles() {
        val source = CourseSource("pronote-notebook", "cdt-1", "MATHEMATIQUES", "2026-12-01")
        val imported = course("a", "maths", source = source, documents = listOf(doc("fiche.pdf")))
        val content = NotebookContent(
            "Fractions", "Lire la leçon", emptyList(), "Cours",
            listOf(
                NotebookAttachment(NotebookAttachment.Kind.FILE, "f1", "fiche.pdf"),
                NotebookAttachment(NotebookAttachment.Kind.FILE, "f2", "exercices.pdf"),
                NotebookAttachment(NotebookAttachment.Kind.LINK, "l1", "Vidéo", url = "https://example.org/video"),
            ),
        )
        val snapshot = NotebookSnapshot(
            LocalDate.parse("2026-11-20"), LocalDate.parse("2026-12-10"),
            listOf(SessionEntry("cdt-1", "lesson-1", LocalDate.parse("2026-12-01"), "MATHEMATIQUES", listOf(content))),
            emptyList(), 0, 3,
        )
        val part = RevisionSession.notebookPart(imported, snapshot)!!
        assertEquals("Séance du 1er décembre · Pronote", part.sourceLine)
        assertEquals(listOf("exercices.pdf", "Vidéo"), part.contents!!.single().attachments.map { it.name })

        // Séance hors de la fenêtre lue, ou cahier pas encore lu : la seule ligne de la séance.
        val outside = RevisionSession.notebookPart(imported, snapshot.copy(sessions = emptyList()))!!
        assertNull(outside.contents)
        assertNull(RevisionSession.notebookPart(imported, null)!!.contents)
        // Cours saisi à la main : pas de partie cahier.
        assertNull(RevisionSession.notebookPart(course("b", "maths"), snapshot))
    }

    @Test
    fun lateness() {
        assertNull(RevisionSession.lateLabel(revision("r", "a", 1, tomorrow), today))
        assertEquals("En retard de 1 jour", RevisionSession.lateLabel(revision("r", "a", 1, today), today))
        assertEquals("En retard de 3 jours", RevisionSession.lateLabel(revision("r", "a", 1, "2026-11-16"), today))
        assertNull(RevisionSession.lateLabel(revision("r", "a", 1, "2026-11-16", done = true), today))
        assertNotEquals(0, RevisionSession.lateDays(revision("r", "a", 1, "2026-11-01"), today))
    }
}
