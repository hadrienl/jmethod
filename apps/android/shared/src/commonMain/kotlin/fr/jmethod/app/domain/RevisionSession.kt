package fr.jmethod.app.domain

import fr.jmethod.app.model.Course
import fr.jmethod.app.model.CourseDocument
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.RevisionStatus
import fr.jmethod.app.model.Subject
import fr.jmethod.app.pronote.NotebookAttachment
import fr.jmethod.app.pronote.NotebookRules
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

    /** Première séance due du programme du soir par date (carte « Pour demain »), hors [excluding]. */
    fun nextDue(revisions: List<Revision>, courses: List<Course>, today: String, excluding: String? = null): Revision? =
        EveningProgramme.of(revisions, courses, today).due.firstOrNull { it.id != excluding }

    /**
     * Ordre affiché dans Aujourd’hui : groupes par matière dans l’ordre de la liste des matières, puis les
     * séances de chaque groupe dans leur ordre d’affichage (dues puis faites aujourd’hui), comme [TodayModel].
     */
    fun displayOrder(revisions: List<Revision>, courses: List<Course>, subjects: List<Subject>, today: String): List<Revision> {
        val subjectOf = courses.associate { it.id to it.subjectId }
        val visible = EveningProgramme.of(revisions, courses, today).visible
        return subjects.flatMap { subject -> visible.filter { subjectOf[it.courseId] == subject.id } }
    }

    /**
     * Séance suivante après une validation (section 8.2, identique à iOS) : la première séance encore due
     * dans l’ordre affiché par Aujourd’hui après la validation, hors [excluding].
     */
    fun nextInDisplayOrder(revisions: List<Revision>, courses: List<Course>, subjects: List<Subject>, today: String, excluding: String?): Revision? =
        displayOrder(revisions, courses, subjects, today).firstOrNull { it.status != RevisionStatus.COMPLETED && it.id != excluding }

    /** Prochaine séance due d’un cours (section 8.4) : la première non faite dans l’ordre des J. */
    fun nextOfCourse(courseId: String, revisions: List<Revision>): Revision? {
        val pending = revisions.filter { it.appliesTo(courseId) && it.status != RevisionStatus.COMPLETED }
        if (pending.none { it.programmeId != null }) return pending.minByOrNull { it.stepIndex }
        // Une réapplication réindexe les nouvelles étapes, pas les rattrapages historiques.
        // Le J nominal reste comparable ; un historique ambigu suit ses dates sans inventer de J.
        val knownDays = pending.all { it.nominalOffsetDays != null }
        return pending.minWithOrNull(compareBy<Revision> { if (knownDays) it.nominalOffsetDays!! else 0 }
            .thenBy { it.scheduledDate }.thenBy { it.stepIndex })
    }

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
        val sources = (course.sources ?: listOfNotNull(course.source)).distinctBy { it.sessionKey }
        if (sources.isEmpty()) return null
        val imported = course.documents.orEmpty().mapTo(HashSet()) { it.name }
        val sessions = sources.mapNotNull { source -> snapshot?.sessions?.firstOrNull { it.key == source.sessionKey } }
        val contents = if (sessions.isEmpty()) null else sessions.flatMap { session -> session.contents.map { content ->
            content.copy(attachments = content.attachments.filter { it.kind != NotebookAttachment.Kind.FILE || it.name !in imported })
        } }
        return NotebookPart(sources.joinToString(" · ") { sessionLine(it.date) }, contents)
    }

    // region Documents proches (section 8.5)

    const val NEARBY_DAYS = 10L
    const val NEARBY_VISIBLE = 5

    /** Document joint à une autre séance de la même matière, à 10 jours au plus. */
    sealed interface Nearby {
        val name: String
        val title: String?
        /** YYYY-MM-DD, date de la séance d’origine. */
        val date: String
        /** « Séance du 5 novembre ». */
        val dayLine: String get() = dayLine(date)

        /** Document enregistré dans un autre cours. */
        data class FromCourse(val courseId: String, val document: CourseDocument, override val date: String) : Nearby {
            override val name get() = document.name
            override val title get() = document.extractedTitle?.takeIf { it.isNotBlank() && it != document.name }
        }

        /** Pièce jointe du cahier de textes lu (téléchargée au besoin). */
        data class FromNotebook(val sessionKey: String, val attachment: NotebookAttachment, override val date: String) : Nearby {
            override val name get() = attachment.name
            override val title: String? get() = null
        }
    }

    fun dayLine(date: String): String {
        val parsed = FrenchDates.parse(date) ?: return "Séance"
        val day = if (parsed.dayOfMonth == 1) "1er" else parsed.dayOfMonth.toString()
        return "Séance du $day ${FrenchDates.monthName(parsed)}"
    }

    /** Date de la séance d’un cours : celle de sa séance d’origine, sinon sa date (J0). */
    private fun courseDate(course: Course) = course.source?.date ?: course.date

    /**
     * Fichiers (pas les liens) joints à d’autres séances de la même matière normalisée à ±10 jours,
     * hors documents déjà joints à [course] (même nom) : documents des autres cours, puis pièces jointes du
     * cahier [snapshot]. Tri : écart de date croissant, séance postérieure avant antérieure, ordre d’origine.
     * Une pièce jointe déjà enregistrée dans le cours de sa séance n’apparaît qu’une fois.
     * @param available documents présents sur ce téléphone (les autres ne peuvent être ni ouverts ni copiés).
     */
    fun nearbyDocuments(
        course: Course,
        courses: List<Course>,
        snapshot: NotebookSnapshot?,
        subjects: List<Subject>,
        available: (CourseDocument) -> Boolean = { true },
    ): List<Nearby> {
        val origin = FrenchDates.parse(courseDate(course)) ?: return emptyList()
        val label = course.source?.subjectLabel?.let(NotebookRules::normalizeSubject)
        fun sameSubjectCourse(other: Course): Boolean {
            val otherLabel = other.source?.subjectLabel?.let(NotebookRules::normalizeSubject)
            return if (label != null && otherLabel != null) otherLabel == label else other.subjectId == course.subjectId
        }
        fun sameSubjectSession(subject: String): Boolean =
            if (label != null) NotebookRules.normalizeSubject(subject) == label else SubjectMatcher.match(subject, subjects)?.id == course.subjectId
        fun near(date: String): Long? {
            val parsed = FrenchDates.parse(date) ?: return null
            val delta = ChronoUnit.DAYS.between(origin, parsed)
            return delta.takeIf { kotlin.math.abs(it) <= NEARBY_DAYS }
        }
        val own = course.documents.orEmpty().mapTo(HashSet()) { it.name }
        val candidates = mutableListOf<Pair<Long, Nearby>>()
        val stored = HashSet<Pair<String, String>>()
        for (other in courses) {
            if (other.id == course.id || !sameSubjectCourse(other)) continue
            val date = courseDate(other)
            val delta = near(date) ?: continue
            other.documents.orEmpty().forEach { document ->
                other.source?.sessionKey?.let { stored += it to document.name }
                if (document.name !in own && available(document)) candidates += delta to Nearby.FromCourse(other.id, document, date)
            }
        }
        snapshot?.sessions.orEmpty().forEach { session ->
            if (session.key == course.source?.sessionKey || !sameSubjectSession(session.subject)) return@forEach
            val date = session.date.toString()
            val delta = near(date) ?: return@forEach
            session.contents.flatMap { it.attachments }
                .filter { it.kind == NotebookAttachment.Kind.FILE }
                .distinctBy { it.id }
                .forEach { attachment ->
                    if (attachment.name !in own && (session.key to attachment.name) !in stored) {
                        candidates += delta to Nearby.FromNotebook(session.key, attachment, date)
                    }
                }
        }
        return candidates.withIndex()
            .sortedWith(compareBy({ kotlin.math.abs(it.value.first) }, { if (it.value.first >= 0) 0 else 1 }, { it.index }))
            .map { it.value.second }
    }

    // endregion

    /** Description repliée au-delà de cette hauteur, « Afficher plus ». */
    const val DESCRIPTION_LINES = 6
}
