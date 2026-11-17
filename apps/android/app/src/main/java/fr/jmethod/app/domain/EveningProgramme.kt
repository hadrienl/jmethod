package fr.jmethod.app.domain

import fr.jmethod.app.model.Course
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.RevisionStatus
import java.time.LocalDate

/**
 * Port de `src/lib/eveningProgramme.ts` : le soir prépare le jour calendaire suivant,
 * l’historique garde la date réelle de validation.
 */
data class EveningProgramme(
    val preparationDate: String,
    val due: List<Revision>,
    val completed: List<Revision>,
    val overdue: List<Revision>,
    val visible: List<Revision>,
) {
    companion object {
        fun of(revisions: List<Revision>, courses: List<Course>, today: String): EveningProgramme {
            val preparationDate = LocalDate.parse(today).plusDays(1).toString()
            val courseIds = courses.mapTo(HashSet()) { it.id }
            val valid = revisions.filter { it.courseId in courseIds }
            val due = valid
                .filter { it.status != RevisionStatus.COMPLETED && it.scheduledDate <= preparationDate }
                .sortedWith { a, b -> a.scheduledDate.compareTo(b.scheduledDate) }
            val completed = valid.filter { it.status == RevisionStatus.COMPLETED && it.completedDate == today }
            return EveningProgramme(
                preparationDate = preparationDate,
                due = due,
                completed = completed,
                overdue = due.filter { it.scheduledDate < preparationDate },
                visible = due + completed,
            )
        }
    }
}
