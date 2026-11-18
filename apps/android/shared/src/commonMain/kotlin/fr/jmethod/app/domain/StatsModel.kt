package fr.jmethod.app.domain

import fr.jmethod.app.model.Course
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.RevisionStatus
import fr.jmethod.app.model.Subject
import java.time.LocalDate

/**
 * Mes progrès, porté de `StatsView.tsx` (suite `stats`) avec les écarts D3 (vraie série de
 * régularité), D4 (« — » sans score), D5 (seules les séances terminées notées comptent),
 * D6 (`score: null` = pas de score, déjà vrai au décodage) et D7 (accords).
 */
data class StatsModel(
    val completionRate: Int,
    val completionLine: String,
    /** `null` sans aucune séance notée (D4). */
    val overallAvgScore: Int?,
    val overallAvgLabel: String,
    val balanceLabel: String,
    val streakDays: Int,
    val streakLabel: String,
    val streakLine: String,
    val coursesCount: Int,
    val subjectsLine: String,
    val weakSubjects: List<SubjectRow>,
    val subjects: List<SubjectRow>,
) {
    enum class Tone(val wire: String) { GOOD("good"), WEAK("weak") }

    data class SubjectRow(
        val subject: Subject,
        val coursesCount: Int,
        val totalRevs: Int,
        val completedRevs: Int,
        val rate: Int,
        val avgScore: Int?,
        val line: String,
        val averageTone: Tone?,
    )

    companion object {
        /** D5 : une note compte seulement si la séance est terminée. */
        private fun scored(revisions: List<Revision>) =
            revisions.filter { it.status == RevisionStatus.COMPLETED && it.score != null }.map { it.score!! }

        private fun average(scores: List<Int>): Int? = if (scores.isEmpty()) null else jsRound(scores.sum().toDouble() / scores.size)

        /**
         * D3 : jours consécutifs ayant au moins une séance validée (`completedDate`), en remontant depuis
         * aujourd’hui, ou depuis hier si rien n’est encore validé aujourd’hui.
         */
        fun streak(revisions: List<Revision>, today: LocalDate): Int {
            val days = revisions.filter { it.status == RevisionStatus.COMPLETED }
                .mapNotNull { it.completedDate?.let(FrenchDates::parse) }.toHashSet()
            var day = if (today in days) today else today.minusDays(1)
            var count = 0
            while (day in days) {
                count++
                day = day.minusDays(1)
            }
            return count
        }

        fun build(courses: List<Course>, revisions: List<Revision>, subjects: List<Subject>, today: LocalDate): StatsModel {
            val courseMap = courses.associateBy { it.id }
            val completed = revisions.count { it.status == RevisionStatus.COMPLETED }
            val overall = average(scored(revisions))
            val rows = subjects.mapNotNull { subject ->
                val subjectCourses = courses.count { it.subjectId == subject.id }
                if (subjectCourses == 0) return@mapNotNull null
                val subjectRevisions = revisions.filter { courseMap[it.courseId]?.subjectId == subject.id }
                val done = subjectRevisions.count { it.status == RevisionStatus.COMPLETED }
                val avg = average(scored(subjectRevisions))
                SubjectRow(
                    subject = subject,
                    coursesCount = subjectCourses,
                    totalRevs = subjectRevisions.size,
                    completedRevs = done,
                    rate = if (subjectRevisions.isNotEmpty()) jsRound(done.toDouble() / subjectRevisions.size * 100) else 0,
                    avgScore = avg,
                    line = "($subjectCourses cours • $done/${subjectRevisions.size} J)",
                    averageTone = avg?.let { if (it >= 50) Tone.GOOD else Tone.WEAK },
                )
            }
            val streak = streak(revisions, today)
            return StatsModel(
                completionRate = if (revisions.isNotEmpty()) jsRound(completed.toDouble() / revisions.size * 100) else 0,
                completionLine = "$completed sur ${revisions.size} faites",
                overallAvgScore = overall,
                overallAvgLabel = overall?.toString() ?: "—",
                balanceLabel = when {
                    overall == null -> "Pas encore de note"
                    overall >= 50 -> "Au-dessus de l’équilibre"
                    else -> "Sous l’équilibre"
                },
                streakDays = streak,
                streakLabel = "$streak j",
                streakLine = if (streak == 0) "Valide une séance pour commencer" else "${plural(streak, "Jour consécutif", "Jours consécutifs")} avec une séance",
                coursesCount = courses.size,
                subjectsLine = "Sur ${rows.size} ${plural(rows.size, "matière")}",
                weakSubjects = rows.filter { it.avgScore != null && it.avgScore < 50 },
                subjects = rows,
            )
        }
    }
}
