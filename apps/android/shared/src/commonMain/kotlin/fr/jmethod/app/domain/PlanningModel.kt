package fr.jmethod.app.domain

import fr.jmethod.app.model.Course
import fr.jmethod.app.model.Phase
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.RevisionStatus
import fr.jmethod.app.model.Subject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class PlanningMode { DAY, WEEK, MONTH }

/**
 * Modèle du Planning, porté de la logique en ligne de `PlanningView.tsx` (suites `planning` et
 * `planningNavigate`). Les chaînes de date sont en minuscules comme le web ; l’affichage applique
 * [sentenceCase] (écart D11). Les étapes portent les libellés d’Aujourd’hui (écart D10).
 */
data class PlanningModel(
    val period: String,
    val days: List<Day>,
) {
    data class Day(
        val date: String,
        val heading: String,
        /** `EEEE d MMMM`, libellé d’accessibilité de la section du jour. */
        val accessibilityHeading: String,
        val countLabel: String,
        val isToday: Boolean,
        /** Mode mois seulement : `null` en jour et en semaine. */
        val outsideMonth: Boolean?,
        val timetableSummary: String?,
        val events: List<EventLine>,
        val tiles: List<Tile>,
        val empty: String?,
    )

    data class EventLine(
        val event: PronoteEvent,
        val id: String,
        val label: String,
        val subjectId: String?,
        /** Couleur de la matière appariée, si elle existe encore. */
        val subjectColor: String?,
        /** Un créneau maintenu peut devenir un cours (bouton « Ajouter ce cours »). */
        val canAddCourse: Boolean,
    )

    data class Tile(
        val revision: Revision,
        val course: Course,
        val subject: Subject,
        val revisionId: String,
        val subjectName: String,
        val stepLabel: String,
        val phaseLine: String,
        val title: String,
        val chapter: String,
        val done: Boolean,
        val footer: String,
        val accessibilityLabel: String,
    )

    companion object {
        const val EMPTY_DAY = "Aucune séance prévue."

        fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        /** Jours affichés : la date seule, la semaine du lundi au dimanche, ou les semaines entières du mois. */
        fun daysOf(mode: PlanningMode, date: LocalDate): List<LocalDate> = when (mode) {
            PlanningMode.DAY -> listOf(date)
            PlanningMode.WEEK -> weekStart(date).let { start -> (0L..6L).map { start.plusDays(it) } }
            PlanningMode.MONTH -> {
                val start = weekStart(date.withDayOfMonth(1))
                val end = weekStart(date.with(TemporalAdjusters.lastDayOfMonth())).plusDays(6)
                generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
            }
        }

        /** ±1 jour, semaine ou mois (`addMonths` ramène au dernier jour du mois). */
        fun navigate(mode: PlanningMode, date: LocalDate, direction: Int): LocalDate = when (mode) {
            PlanningMode.DAY -> date.plusDays(direction.toLong())
            PlanningMode.WEEK -> date.plusWeeks(direction.toLong())
            PlanningMode.MONTH -> date.plusMonths(direction.toLong())
        }

        fun period(mode: PlanningMode, date: LocalDate): String = when (mode) {
            PlanningMode.MONTH -> FrenchDates.monthYear(date)
            PlanningMode.DAY -> FrenchDates.weekdayDayMonthYear(date)
            PlanningMode.WEEK -> weekStart(date).let { "${FrenchDates.dayMonthShort(it)} – ${FrenchDates.dayMonthShortYear(it.plusDays(6))}" }
        }

        /** Révisions visibles : cours existant, matière existante, puis filtres matière et étape. */
        fun visibleRevisions(
            revisions: List<Revision>,
            courseMap: Map<String, Course>,
            subjectMap: Map<String, Subject>,
            subjectId: String?,
            phase: Phase?,
        ): List<Revision> = revisions.filter { revision ->
            val course = courseMap[revision.courseId]
            course != null && course.subjectId in subjectMap &&
                (subjectId == null || course.subjectId == subjectId) &&
                (phase == null || revision.phase == phase)
        }

        fun build(
            today: LocalDate,
            mode: PlanningMode,
            date: LocalDate,
            subjectId: String?,
            phase: Phase?,
            courses: List<Course>,
            revisions: List<Revision>,
            subjects: List<Subject>,
            pronoteEvents: List<PronoteEvent>,
        ): PlanningModel {
            val courseMap = courses.associateBy { it.id }
            val subjectMap = subjects.associateBy { it.id }
            val filtered = visibleRevisions(revisions, courseMap, subjectMap, subjectId, phase)
            val days = daysOf(mode, date).map { day ->
                val key = day.toString()
                // Tri stable : non terminées d’abord, puis stepIndex croissant.
                val dayRevisions = filtered.filter { it.scheduledDate == key }.sortedWith(
                    compareBy<Revision> { if (it.status == RevisionStatus.COMPLETED) 1 else 0 }.thenBy { it.stepIndex },
                )
                val events = pronoteEvents.filter { it.date == key && (subjectId == null || it.matchedSubjectId == subjectId) }
                val isToday = day == today
                Day(
                    date = key,
                    heading = if (mode == PlanningMode.MONTH) FrenchDates.weekdayShortDay(day) else FrenchDates.weekdayDayMonthShort(day),
                    accessibilityHeading = FrenchDates.weekdayDayMonth(day),
                    countLabel = "${dayRevisions.size} séance${if (dayRevisions.size > 1) "s" else ""}${if (isToday) " · Aujourd’hui" else ""}",
                    isToday = isToday,
                    outsideMonth = if (mode == PlanningMode.MONTH) day.month != date.month || day.year != date.year else null,
                    timetableSummary = if (events.isNotEmpty()) "Emploi du temps · ${events.size} cours" else null,
                    events = events.map { event ->
                        EventLine(
                            event = event,
                            id = event.id,
                            label = "${event.startTime} · ${event.subjectName}${if (event.isCancelled) " · Annulé" else ""}",
                            subjectId = event.matchedSubjectId,
                            subjectColor = event.matchedSubjectId?.let { subjectMap[it]?.color },
                            canAddCourse = !event.isCancelled,
                        )
                    },
                    tiles = dayRevisions.map { revision ->
                        val course = courseMap.getValue(revision.courseId)
                        val subject = subjectMap.getValue(course.subjectId)
                        val done = revision.status == RevisionStatus.COMPLETED
                        Tile(
                            revision = revision,
                            course = course,
                            subject = subject,
                            revisionId = revision.id,
                            subjectName = subject.name,
                            stepLabel = revision.stepLabel,
                            phaseLine = "${revision.phaseLabel} · ${RevisionProgrammes.references(revision, courses)}",
                            title = course.title,
                            chapter = course.chapter,
                            done = done,
                            footer = if (done) "Terminée · ${revision.score?.toString() ?: "—"}/100" else "Voir la séance →",
                            accessibilityLabel = "${subject.name}, ${revision.stepLabel}, ${revision.phaseLabel}, " +
                                "${RevisionProgrammes.references(revision, courses)} ${course.title}${if (done) ", terminée" else ""}",
                        )
                    },
                    empty = if (dayRevisions.isEmpty()) EMPTY_DAY else null,
                )
            }
            return PlanningModel(period(mode, date), days)
        }
    }
}

/**
 * Détail d’une séance, porté de `RevisionDetailModal.tsx` (suite `revisionDetail`). `null` quand
 * le cours ou la matière manque : le web n’ouvre alors pas le détail.
 */
data class RevisionDetailModel(
    val revision: Revision,
    val course: Course,
    val subject: Subject,
    val subjectName: String,
    val title: String,
    val chapterLine: String,
    val stepLabel: String,
    val phaseLabel: String,
    val plannedLine: String,
    val description: String?,
    val historyTitle: String,
    val history: List<HistoryRow>,
    val historyEmpty: String?,
    val completedLine: String?,
    val canValidate: Boolean,
    val members: List<Course> = emptyList(),
) {
    data class HistoryRow(val revisionId: String, val label: String, val detail: String)

    companion object {
        fun build(revisionId: String, courses: List<Course>, revisions: List<Revision>, subjects: List<Subject>): RevisionDetailModel? {
            val revision = revisions.firstOrNull { it.id == revisionId } ?: return null
            val course = courses.firstOrNull { it.id == revision.courseId } ?: return null
            val subject = subjects.firstOrNull { it.id == course.subjectId } ?: return null
            val completed = revision.status == RevisionStatus.COMPLETED
            val reference = revision.completedDate ?: revision.scheduledDate
            val history = revisions
                .filter {
                    (if (revision.programmeId == null) it.courseId == course.id else it.programmeId == revision.programmeId) && it.id != revision.id && it.status == RevisionStatus.COMPLETED &&
                        (!completed || (it.completedDate ?: it.scheduledDate) <= reference)
                }
                .sortedWith { a, b ->
                    val byDate = (b.completedDate ?: b.scheduledDate).compareTo(a.completedDate ?: a.scheduledDate)
                    if (byDate != 0) byDate else b.stepIndex - a.stepIndex
                }
            return RevisionDetailModel(
                revision = revision,
                course = course,
                subject = subject,
                subjectName = subject.name,
                title = "${RevisionProgrammes.references(revision, courses)} · ${course.title}",
                chapterLine = "Chapitre : ${course.chapter}",
                stepLabel = revision.stepLabel,
                phaseLabel = revision.phaseLabel,
                plannedLine = "Prévue le ${FrenchDates.format(revision.scheduledDate, FrenchDates::dayMonthYear)}",
                description = course.description?.takeIf { it.isNotEmpty() },
                historyTitle = "Sessions précédentes (${history.size})",
                history = history.map {
                    HistoryRow(
                        revisionId = it.id,
                        label = "${it.stepLabel} · ${it.phaseLabel}",
                        detail = "${FrenchDates.format(it.completedDate ?: it.scheduledDate, FrenchDates::numeric)} · " +
                            (it.score?.let { score -> "$score/100" } ?: "Sans note"),
                    )
                },
                historyEmpty = if (history.isEmpty()) "Aucune session terminée avant celle-ci." else null,
                completedLine = if (completed) {
                    val date = revision.completedDate?.let { " le ${FrenchDates.format(it, FrenchDates::numeric)}" }.orEmpty()
                    "Séance terminée$date · ${revision.score?.toString() ?: "—"}/100"
                } else {
                    null
                },
                canValidate = !completed,
                members = (revision.courseIds ?: listOf(revision.courseId)).mapNotNull { id -> courses.firstOrNull { it.id == id } },
            )
        }
    }
}
