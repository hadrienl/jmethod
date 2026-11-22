package fr.jmethod.app.domain

import fr.jmethod.app.model.Course
import fr.jmethod.app.model.Phase
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.RevisionStatus
import fr.jmethod.app.model.Subject
import java.time.Instant
import java.time.ZoneId

/**
 * Liste de Mes cours, portée de `CoursesView.tsx` (suite `coursesList`). Seule la recherche
 * diffère (écart D9) : requête nettoyée, comparaison insensible à la casse et aux accents.
 */
data class CoursesListModel(
    val header: String,
    /** Vrai quand aucun groupe n’est affiché (filtres ou aucun cours). */
    val empty: Boolean,
    val groups: List<SubjectGroup>,
) {
    data class SubjectGroup(val subject: Subject, val countLabel: String, val chapters: List<ChapterGroup>)
    data class ChapterGroup(val chapter: String, val courses: List<Card>)
    data class Card(
        val course: Course,
        val badge: String,
        val dateLine: String,
        val lastScoreLine: String?,
        val counter: String,
        val progress: Int,
        val next: Next?,
    )
    data class Next(val label: String, val date: String)

    companion object {
        /** Écart D9 : `trim`, puis repli de la casse et des accents ; « c1 » reste un préfixe de C10 à C19. */
        fun matches(course: Course, search: String): Boolean {
            val query = search.trim().foldForSearch()
            if (query.isEmpty()) return true
            return course.title.foldForSearch().contains(query) ||
                course.chapter.foldForSearch().contains(query) ||
                course.displayReference.foldForSearch().contains(query)
        }

        fun build(
            courses: List<Course>,
            revisions: List<Revision>,
            subjects: List<Subject>,
            search: String,
            subjectId: String?,
        ): CoursesListModel {
            val filtered = courses.filter { (subjectId == null || it.subjectId == subjectId) && matches(it, search) }
            val byCourse = courses.associate { course -> course.id to revisions.filter { it.appliesTo(course.id) } }
            val groups = subjects.mapNotNull { subject ->
                val subjectCourses = filtered.filter { it.subjectId == subject.id }
                if (subjectCourses.isEmpty()) return@mapNotNull null
                // Chapitres dans l’ordre de première apparition, regroupés à l’identique de la chaîne.
                val chapters = LinkedHashMap<String, MutableList<Course>>()
                subjectCourses.forEach { chapters.getOrPut(it.chapter) { mutableListOf() }.add(it) }
                SubjectGroup(
                    subject = subject,
                    countLabel = "(${subjectCourses.size} cours)",
                    chapters = chapters.map { (chapter, list) ->
                        ChapterGroup(chapter, list.sortedBy { it.courseNumber }.map { card(it, byCourse[it.id].orEmpty()) })
                    },
                )
            }
            return CoursesListModel("Mes cours (${courses.size})", groups.isEmpty(), groups)
        }

        private fun card(course: Course, courseRevisions: List<Revision>): Card {
            val completed = courseRevisions.count { it.status == RevisionStatus.COMPLETED }
            val progress = if (courseRevisions.isNotEmpty()) jsRound(completed.toDouble() / courseRevisions.size * 100) else 0
            // « Prochain » et « Dernier score » suivent l’ordre du tableau, pas les dates.
            val next = courseRevisions.firstOrNull { it.status == RevisionStatus.PENDING }
            val lastScore = courseRevisions.lastOrNull { it.score != null }?.score
            return Card(
                course = course,
                badge = course.displayReference,
                dateLine = "Cours du ${FrenchDates.format(course.date, FrenchDates::numeric)}",
                lastScoreLine = lastScore?.let { "Dernier score : $it/100" },
                counter = "$completed / ${courseRevisions.size} J",
                progress = progress,
                next = next?.let { Next("Prochain : ${it.stepLabel}", FrenchDates.format(it.scheduledDate, FrenchDates::numericShort)) },
            )
        }
    }
}

/**
 * Fiche d’un cours, portée de `CourseDetailModal.tsx` (suite `courseDetail`), avec les écarts
 * D1 (0 % sans révision), D2 (« Créé le » = `createdAt`) et D12 (score absent : « —/100 »).
 */
data class CourseDetailModel(
    val course: Course,
    val subject: Subject?,
    val subjectLabel: String,
    val chapterLine: String,
    val title: String,
    val createdLine: String,
    val courseDateLine: String,
    val averageLine: String?,
    val notes: String?,
    val progressLine: String,
    val progressPercent: Int,
    val timeline: List<Row>,
    val deleteConfirm: String,
) {
    enum class State(val wire: String) { COMPLETED("completed"), OVERDUE("overdue"), PENDING("pending") }

    data class Row(
        val revision: Revision,
        val revisionId: String,
        val stepLabel: String,
        val phase: Phase,
        val phaseLabel: String,
        val emergencyBadge: Boolean,
        val state: State,
        val plannedLine: String,
        val doneLine: String?,
        /** Score (« 80/100 ») ou « Valider ». */
        val right: String,
        val canValidate: Boolean,
    )

    companion object {
        fun build(course: Course, subjects: List<Subject>, allRevisions: List<Revision>): CourseDetailModel {
            val subject = subjects.firstOrNull { it.id == course.subjectId }
            val sorted = allRevisions.filter { it.appliesTo(course.id) }.sortedBy { it.stepIndex }
            val completedCount = sorted.count { it.status == RevisionStatus.COMPLETED }
            // Écart D1 : 0 % au lieu de NaN % pour un cours sans révision.
            val percent = if (sorted.isEmpty()) 0 else jsRound(completedCount.toDouble() / sorted.size * 100)
            val scores = sorted.mapNotNull { it.score }
            val average = if (scores.isNotEmpty()) jsRound(scores.sum().toDouble() / scores.size) else null
            return CourseDetailModel(
                course = course,
                subject = subject,
                subjectLabel = subject?.name ?: "Matière",
                chapterLine = "Chapitre : ${course.chapter}",
                title = "${course.displayReference} ${course.title}",
                // Écart D2 : « Créé le » affiche la date de création, la date du cours reste « Cours du ».
                createdLine = "Créé le ${FrenchDates.format(course.createdAt, FrenchDates::paddedDayMonthYear)}",
                courseDateLine = "Cours du ${FrenchDates.format(course.date, FrenchDates::paddedDayMonthYear)}",
                averageLine = average?.let { "Moyenne des séances : $it/100" },
                notes = course.description?.takeIf { it.isNotEmpty() },
                progressLine = "$completedCount / ${sorted.size} effectuées ($percent%)",
                progressPercent = percent,
                timeline = sorted.map { revision ->
                    val state = when (revision.status) {
                        RevisionStatus.COMPLETED -> State.COMPLETED
                        RevisionStatus.OVERDUE -> State.OVERDUE
                        RevisionStatus.PENDING -> State.PENDING
                    }
                    Row(
                        revision = revision,
                        revisionId = revision.id,
                        stepLabel = revision.stepLabel,
                        phase = revision.phase,
                        phaseLabel = revision.phaseLabel,
                        emergencyBadge = revision.isEmergencySession == true,
                        state = state,
                        plannedLine = "Prévu : ${FrenchDates.format(revision.scheduledDate, FrenchDates::paddedDayMonthShortYear)}",
                        doneLine = revision.completedDate?.let { "• Fait le ${FrenchDates.format(it, FrenchDates::numericShort)}" },
                        // Écart D12 : un score absent s’affiche « —/100 » comme dans le Planning.
                        right = if (state == State.COMPLETED) "${revision.score?.toString() ?: "—"}/100" else "Valider",
                        canValidate = state != State.COMPLETED,
                    )
                },
                deleteConfirm = "Es-tu sûr de vouloir supprimer définitivement le cours ${course.displayReference} : ${course.title} ?",
            )
        }
    }
}

/** Création, préremplissage et suppression de cours (`AddCourseModal.tsx`, `App.tsx`). */
object CourseEditing {
    data class Form(
        val subjectId: String?,
        val chapter: String,
        val title: String,
        /** `yyyy-MM-dd`, date du cours (J0). */
        val date: String,
        val description: String,
        val reference: String? = null,
    )

    data class Prefill(val subjectId: String?, val date: String, val title: String)

    enum class ToastType(val wire: String) { SUCCESS("success"), INFO("info") }
    data class Toast(val title: String, val message: String, val type: ToastType)

    data class Created(
        val course: Course,
        val newRevisions: List<Revision>,
        val courses: List<Course>,
        val revisions: List<Revision>,
        val toast: Toast,
    )

    data class Deleted(val courses: List<Course>, val revisions: List<Revision>, val toast: Toast)

    /** 1 si le chapitre est vide ou nouveau, sinon max + 1 parmi les chapitres égaux après `trim` et minuscules. */
    fun courseNumber(courses: List<Course>, subjectId: String?, chapter: String): Int {
        val wanted = chapter.trim()
        if (wanted.isEmpty()) return 1
        val key = wanted.lowercase()
        val matches = courses.filter { it.subjectId == subjectId && it.chapter.trim().lowercase() == key }
        return if (matches.isEmpty()) 1 else maxOf(matches.maxOf { it.courseNumber }, 0) + 1
    }

    /** Chapitres distincts de la matière dans l’ordre des cours, au plus trois, seulement si le champ est vide. */
    fun chapterSuggestions(courses: List<Course>, subjectId: String?, chapter: String): List<String> =
        if (chapter.isNotEmpty()) emptyList() else courses.filter { it.subjectId == subjectId }.map { it.chapter }.distinct().take(3)

    fun submitLabel(courseNumber: Int) = "Créer le cours C$courseNumber"

    fun isValid(form: Form): Boolean = form.title.isNotBlank() && form.chapter.isNotBlank() && form.subjectId != null

    /** Préremplissage depuis un créneau Pronote. */
    fun prefill(event: PronoteEvent, subjects: List<Subject>): Prefill =
        Prefill(event.matchedSubjectId ?: subjects.firstOrNull()?.id, event.date, event.subjectName)

    /** Écart D8 (avec D7) : nombre réel d’échéances générées. */
    fun createdToastMessage(course: Course, count: Int) =
        "C${course.courseNumber} « ${course.title} » ajouté avec " + when (count) {
            0 -> "aucune échéance de révision."
            1 -> "son échéance de révision."
            else -> "ses $count échéances de révision."
        }

    /** `null` si le formulaire est refusé (titre ou chapitre vide). */
    fun create(
        courses: List<Course>,
        revisions: List<Revision>,
        intervals: List<Int>,
        form: Form,
        now: Instant,
        zone: ZoneId,
    ): Created? {
        if (!isValid(form)) return null
        val course = Course(
            id = "course-${now.toEpochMilli()}",
            subjectId = form.subjectId!!,
            chapter = form.chapter.trim(),
            courseNumber = courseNumber(courses, form.subjectId, form.chapter),
            title = form.title.trim(),
            reference = form.reference,
            date = form.date,
            description = form.description.trim().ifEmpty { null },
            createdAt = now.atZone(zone).toLocalDate().toString(),
        )
        val newRevisions = AdaptiveAlgorithm.generateInitialRevisions(course, intervals)
        return Created(
            course = course,
            newRevisions = newRevisions,
            courses = listOf(course) + courses,
            revisions = revisions + newRevisions,
            toast = Toast("Nouveau cours créé !", createdToastMessage(course, newRevisions.size), ToastType.SUCCESS),
        )
    }

    /** Retire le cours et toutes ses révisions ; les créneaux Pronote ne changent pas. */
    fun delete(courses: List<Course>, revisions: List<Revision>, courseId: String): Deleted = Deleted(
        courses = courses.filter { it.id != courseId },
        revisions = revisions.filter { it.courseId != courseId },
        toast = Toast("Cours supprimé", "Le cours et son calendrier ont été supprimés.", ToastType.INFO),
    )
}
