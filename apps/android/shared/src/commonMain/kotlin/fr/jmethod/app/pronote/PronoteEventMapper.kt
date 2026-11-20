package fr.jmethod.app.pronote

import fr.jmethod.app.domain.SubjectMatcher
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.model.Subject

/**
 * Conversion des créneaux Pronote en `PronoteEvent` du web (cf. `normalize_lessons` et
 * `onPronoteSynced`) : matière appariée, annulations conservées, doublons exacts retirés.
 */
object PronoteEventMapper {

    fun toEvents(lessons: List<Lesson>, subjects: List<Subject>): List<PronoteEvent> = lessons
        .mapNotNull { lesson ->
            if (lesson.start.length < 16) return@mapNotNull null
            val subjectName = lesson.subject.ifBlank { "Cours" }
            PronoteEvent(
                id = "pronote-${lesson.id}",
                subjectName = subjectName,
                matchedSubjectId = SubjectMatcher.match(subjectName, subjects)?.id,
                date = lesson.start.take(10),
                startTime = lesson.start.substring(11, 16),
                endTime = if (lesson.end.length >= 16) lesson.end.substring(11, 16) else "",
                room = lesson.room,
                teacher = lesson.teacher,
                isCancelled = lesson.cancelled,
            )
        }
        .distinct()
        .sortedWith(compareBy({ it.date }, { it.startTime }, { it.id }))
}
