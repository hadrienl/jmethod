package fr.jmethod.app.domain

import fr.jmethod.app.model.Course
import fr.jmethod.app.model.CourseDocument
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.RevisionStatus
import fr.jmethod.app.model.Subject
import fr.jmethod.app.pronote.NotebookAttachment
import fr.jmethod.app.pronote.NotebookContent
import fr.jmethod.app.pronote.NotebookSnapshot
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Réviser sans quitter Aujourd’hui (`notebook-to-course.md`, section 8) : fiche de séance,
 * enchaînement des séances du soir, accès au cours depuis les créneaux de demain.
 */
object RevisionSession {

    /** Le cours a-t-il au moins un document (icône sur les lignes, « Revoir le cours ») ? */
    fun hasDocuments(course: Course?): Boolean = !course?.documents.isNullOrEmpty()

    /** Document principal : le premier de la liste. */
    fun principalDocument(course: Course?): CourseDocument? = course?.documents?.firstOrNull()

    /**
     * Séance suivante après une validation (section 8.2) : la première séance encore due du programme
     * du soir, dans l’ordre du programme (celui de « Pour demain »), hors séance [excluding].
     */
    fun nextDue(revisions: List<Revision>, courses: List<Course>, today: String, excluding: String? = null): Revision? =
        EveningProgramme.of(revisions, courses, today).due.firstOrNull { it.id != excluding }

    /** Prochaine séance due d’un cours (section 8.4) : la première non faite dans l’ordre des J. */
    fun nextOfCourse(courseId: String, revisions: List<Revision>): Revision? =
        revisions.filter { it.courseId == courseId && it.status != RevisionStatus.COMPLETED }.minByOrNull { it.stepIndex }

    /** Libellé du bouton d’enchaînement : « Séance suivante · <matière> — <titre> ». */
    fun nextLabel(revision: Revision, courses: List<Course>, subjects: List<Subject>): String? {
        val course = courses.firstOrNull { it.id == revision.courseId } ?: return null
        val subject = subjects.firstOrNull { it.id == course.subjectId }?.name ?: "Matière"
        return "Séance suivante · $subject — ${course.title}"
    }

    /** Retard d’une séance non faite par rapport au jour préparé ce soir, en jours (0 : à l’heure). */
    fun lateDays(revision: Revision, today: String): Int {
        if (revision.status == RevisionStatus.COMPLETED) return 0
        val preparation = LocalDate.parse(today).plusDays(1)
        val scheduled = FrenchDates.parse(revision.scheduledDate) ?: return 0
        return ChronoUnit.DAYS.between(scheduled, preparation).toInt().coerceAtLeast(0)
    }

    fun lateLabel(revision: Revision, today: String): String? {
        val days = lateDays(revision, today)
        return if (days > 0) "En retard de $days jour${if (days > 1) "s" else ""}" else null
    }

    /**
     * Créneau de demain déjà importé (section 8.3) : cours de la même matière J-Method et du même jour,
     * de préférence issu du cahier de textes. `null` pour un créneau annulé ou une matière non reconnue.
     */
    fun courseForSlot(event: PronoteEvent, courses: List<Course>, subjects: List<Subject>): Course? {
        if (event.isCancelled) return null
        val subject = SubjectMatcher.match(event.subjectName, subjects) ?: return null
        val candidates = courses.filter { it.subjectId == subject.id && it.date == event.date }
        return candidates.firstOrNull { it.source != null } ?: candidates.firstOrNull()
    }

    /** Partie « Dans le cahier de textes » de la fiche (section 8.1, point 3). */
    data class NotebookPart(
        /** « Séance du 18 novembre · Pronote ». */
        val sourceLine: String,
        /** Contenus de la séance d’origine ; `null` si la séance n’est pas dans la fenêtre lue. */
        val contents: List<NotebookContent>?,
    )

    fun sessionLine(date: String): String {
        val parsed = FrenchDates.parse(date) ?: return "Séance · Pronote"
        val day = if (parsed.dayOfMonth == 1) "1er" else parsed.dayOfMonth.toString()
        return "Séance du $day ${FrenchDates.monthName(parsed)} · Pronote"
    }

    /**
     * `null` pour un cours saisi à la main. Les pièces jointes déjà importées dans le cours (même nom)
     * sont retirées : elles s’ouvrent depuis « Le cours ».
     */
    fun notebookPart(course: Course, snapshot: NotebookSnapshot?): NotebookPart? {
        val source = course.source ?: return null
        val session = snapshot?.sessions?.firstOrNull { it.id == source.sessionKey }
        val imported = course.documents.orEmpty().mapTo(HashSet()) { it.name }
        val contents = session?.contents?.map { content ->
            content.copy(attachments = content.attachments.filter { it.kind != NotebookAttachment.Kind.FILE || it.name !in imported })
        }
        return NotebookPart(sessionLine(source.date), contents)
    }

    /** Description repliée au-delà de cette hauteur, « Afficher plus ». */
    const val DESCRIPTION_LINES = 6
}
