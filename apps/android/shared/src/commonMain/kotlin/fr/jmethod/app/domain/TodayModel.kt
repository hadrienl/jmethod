package fr.jmethod.app.domain

import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.Phase
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.RevisionStatus
import fr.jmethod.app.model.Subject
import fr.jmethod.app.model.info
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Modèle de l’écran Aujourd’hui, porté de la logique en ligne de `DashboardView.tsx`,
 * `App.tsx` et `Navigation.tsx` (suite de vecteurs `todayScreen`). Regroupement par matière
 * uniquement, dans l’ordre de la liste des matières ; l’étape n’est qu’un badge.
 */
data class TodayModel(
    val today: String,
    val preparationDate: String,
    val headerDate: String,
    val tomorrowDate: String,
    val pendingCountToday: Int,
    val overdueCount: Int,
    val badgeCount: Int,
    val due: List<Revision>,
    val completed: List<Revision>,
    val overdue: List<Revision>,
    val total: Int,
    val progress: Int,
    val progressSuffix: String,
    val progressMessage: String,
    val allDoneBanner: Boolean,
    val hero: Hero,
    val dueLabel: String,
    val overdueBanner: String?,
    val emptyProgramme: Boolean,
    val groups: List<Group>,
    val tomorrowEvents: List<TomorrowEvent>,
) {
    data class Hero(
        val title: String,
        val revision: Revision?,
        val course: Course?,
        val subjectLabel: String?,
        val courseTitle: String?,
    )

    data class Group(val subject: Subject, val countLabel: String, val rows: List<Row>)

    data class Row(
        val revision: Revision,
        val course: Course,
        val phase: Phase,
        val badge: String,
        val courseLine: String,
        val title: String,
        val done: Boolean,
        val doneLabel: String?,
        val canValidate: Boolean,
        /** Le cours a des documents : petite icône sur la ligne (section 8.3). */
        val hasDocuments: Boolean = false,
    )

    data class TomorrowEvent(
        val id: String,
        val timeRange: String,
        val subjectName: String,
        val cancelled: Boolean,
        /** « Annulé » ou la salle (éventuellement absente). */
        val trailing: String?,
        val teacher: String?,
        val canAddCourse: Boolean,
        /** Cours déjà créé pour cette matière et ce jour : « Voir le cours » au lieu d’« Ajouter ce cours ». */
        val courseId: String? = null,
    )

    companion object {
        private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

        fun formatDay(date: String): String = LocalDate.parse(date).format(dayFormat).lowercase(Locale.FRENCH)

        private fun plural(count: Int) = if (count > 1) "s" else ""

        fun build(
            today: String,
            courses: List<Course>,
            revisions: List<Revision>,
            subjects: List<Subject>,
            pronoteEvents: List<PronoteEvent>,
        ): TodayModel {
            val courseMap = courses.associateBy { it.id }
            val subjectMap = subjects.associateBy { it.id }
            val programme = EveningProgramme.of(revisions, courses, today)
            val total = programme.due.size + programme.completed.size
            val progress = if (total > 0) jsRound(programme.completed.size.toDouble() / total * 100) else 0
            val next = programme.due.firstOrNull()
            val nextCourse = next?.let { courseMap[it.courseId] }
            val nextSubject = nextCourse?.let { subjectMap[it.subjectId] }

            val groups = subjects.mapNotNull { subject ->
                val rows = programme.visible.filter { courseMap[it.courseId]?.subjectId == subject.id }
                if (rows.isEmpty()) return@mapNotNull null
                Group(
                    subject = subject,
                    countLabel = "${rows.size} séance${plural(rows.size)} au programme",
                    rows = rows.map { revision ->
                        val course = courseMap.getValue(revision.courseId)
                        val done = revision.status == RevisionStatus.COMPLETED
                        Row(
                            revision = revision,
                            course = course,
                            phase = revision.phase,
                            badge = "${revision.phase.info.label} · ${revision.stepLabel}",
                            courseLine = "${course.chapter} · C${course.courseNumber}",
                            title = course.title,
                            done = done,
                            doneLabel = if (done) "Fait · ${revision.score?.toString() ?: "undefined"}/100" else null,
                            canValidate = !done,
                            hasDocuments = RevisionSession.hasDocuments(course),
                        )
                    },
                )
            }

            val overdueCount = programme.overdue.size
            val pendingCountToday = programme.due.size - overdueCount
            return TodayModel(
                today = today,
                preparationDate = programme.preparationDate,
                headerDate = formatDay(today),
                tomorrowDate = formatDay(programme.preparationDate),
                pendingCountToday = pendingCountToday,
                overdueCount = overdueCount,
                badgeCount = pendingCountToday + overdueCount,
                due = programme.due,
                completed = programme.completed,
                overdue = programme.overdue,
                total = total,
                progress = progress,
                progressSuffix = "/ $total séance${plural(total)} terminée${plural(total)}",
                progressMessage = when {
                    programme.completed.isNotEmpty() -> "Chaque séance compte. Continue comme ça !"
                    total > 0 -> "La première séance, c’est déjà un progrès."
                    else -> "Aucune séance à préparer pour demain."
                },
                allDoneBanner = programme.due.isEmpty() && programme.completed.isNotEmpty(),
                hero = Hero(
                    title = if (nextCourse != null) "Une séance à la fois." else "Tu peux souffler !",
                    revision = if (nextCourse != null) next else null,
                    course = nextCourse,
                    subjectLabel = nextCourse?.let { nextSubject?.name ?: "ton cours" },
                    courseTitle = nextCourse?.title,
                ),
                dueLabel = "${programme.due.size} à faire",
                overdueBanner = if (overdueCount > 0) {
                    "$overdueCount séance${plural(overdueCount)} à reprendre. Pas de pression : avance une séance à la fois."
                } else {
                    null
                },
                emptyProgramme = programme.visible.isEmpty(),
                groups = groups,
                tomorrowEvents = pronoteEvents.filter { it.date == programme.preparationDate }.map { event ->
                    TomorrowEvent(
                        id = event.id,
                        timeRange = "${event.startTime} – ${event.endTime}",
                        subjectName = event.subjectName,
                        cancelled = event.isCancelled,
                        trailing = if (event.isCancelled) "Annulé" else event.room,
                        teacher = event.teacher?.takeIf { it.isNotEmpty() },
                        canAddCourse = !event.isCancelled,
                        courseId = RevisionSession.courseForSlot(event, courses, subjects)?.id,
                    )
                },
            )
        }
    }
}

/** Validation d’une séance (`App.handleScoreSubmit`, suite `scoreSubmit`). */
object ScoreSubmission {
    enum class ToastType(val wire: String) { SUCCESS("success"), WARNING("warning") }

    data class Toast(val title: String, val message: String, val type: ToastType)

    data class Outcome(
        val today: String,
        val preparationDate: String?,
        val ignored: Boolean,
        val revisions: List<Revision>,
        val celebrate: Boolean,
        val toast: Toast?,
    )

    fun submit(
        courses: List<Course>,
        revisions: List<Revision>,
        settings: AppSettings,
        revisionId: String,
        score: Int,
        now: Instant,
        zone: ZoneId,
    ): Outcome {
        val today = now.atZone(zone).toLocalDate().toString()
        val target = revisions.firstOrNull { it.id == revisionId }
        if (target == null || target.status == RevisionStatus.COMPLETED) {
            return Outcome(today, null, ignored = true, revisions = revisions, celebrate = false, toast = null)
        }
        val programme = EveningProgramme.of(revisions, courses, today)
        val courseRevisions = revisions.filter { it.courseId == target.courseId }
        val result = AdaptiveAlgorithm.applyAdaptiveReview(
            courseRevisions, revisionId, score, settings, today, programme.preparationDate, now, zone,
        )
        val updated = revisions.filter { it.courseId != target.courseId } + result.updatedRevisions
        val remaining = EveningProgramme.of(updated, courses, today)
        val emergency = result.explanation.actionType == AdaptiveAlgorithm.ActionType.EMERGENCY
        return Outcome(
            today = today,
            preparationDate = programme.preparationDate,
            ignored = false,
            revisions = updated,
            celebrate = programme.due.isNotEmpty() && remaining.due.isEmpty(),
            toast = Toast(
                title = if (emergency) "Rattrapage programmé" else "Révision validée ($score/100)",
                message = result.explanation.message,
                type = if (emergency) ToastType.WARNING else ToastType.SUCCESS,
            ),
        )
    }
}

/** Aperçu en direct de l’autoévaluation (`ScoreModal`, suite `scorePreview`). */
data class ScorePreview(
    val tone: Tone,
    val title: String,
    val description: String,
    val scoreBand: Band,
    val confettiOnSubmit: Boolean,
) {
    enum class Tone(val wire: String) { EMERGENCY("emergency"), SHORTENED("shortened"), EXPANDED("expanded"), EXACT("exact") }
    enum class Band(val wire: String) { ROSE("rose"), AMBER("amber"), SKY("sky"), EMERALD("emerald"), INDIGO("indigo") }

    companion object {
        fun of(score: Int, sensitivity: Double, emergencyThreshold: Int): ScorePreview {
            val factor = 1 + ((score - 50) / 50.0) * sensitivity
            val band = when {
                score < 30 -> Band.ROSE
                score < 50 -> Band.AMBER
                score == 50 -> Band.SKY
                score < 85 -> Band.EMERALD
                else -> Band.INDIGO
            }
            val confetti = score >= 75
            return when {
                score < emergencyThreshold -> ScorePreview(
                    Tone.EMERGENCY, "Rattrapage nécessaire",
                    "Oubli important : une session de rattrapage immédiate sera programmée pour demain (J+1).", band, confetti,
                )
                score < 50 -> ScorePreview(
                    Tone.SHORTENED, "Révision rapprochée",
                    "Difficultés constatées : les prochaines révisions seront rapprochées de -${jsRound((1 - factor) * 100)}%.", band, confetti,
                )
                score > 50 -> ScorePreview(
                    Tone.EXPANDED, "Révision espacée",
                    "Très bonne maîtrise : les prochaines révisions seront espacées de +${jsRound((factor - 1) * 100)}%.", band, confetti,
                )
                else -> ScorePreview(
                    Tone.EXACT, "On garde le même rythme",
                    "Tu retrouves les idées principales. Ta prochaine séance garde le même espacement.", band, confetti,
                )
            }
        }
    }
}
