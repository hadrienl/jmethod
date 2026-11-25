package fr.jmethod.app.domain

import fr.jmethod.app.model.AppDocument
import fr.jmethod.app.model.RevisionStatus

enum class SuccessSound {
    REVISION, HOMEWORK, EVENING, RESUME, FLAME, RECORD, PROGRAMME;

    val asset: String get() = "success_${name.lowercase()}"

    companion object {
        fun afterRevision(before: AppDocument, after: AppDocument, revisionId: String, day: String, eveningDone: Boolean): SuccessSound? {
            if (!after.settings.soundEnabled) return null
            val revision = before.revisions.firstOrNull { it.id == revisionId } ?: return null
            if (revision.status == RevisionStatus.COMPLETED) return null
            val completedIds = after.revisions.filter { it.status == RevisionStatus.COMPLETED }.mapTo(mutableSetOf()) { it.id }
            if (revisionId !in completedIds) return null
            val programmeDone = revision.programmeId?.let { programmeId ->
                after.revisions.none { it.programmeId == programmeId && it.status != RevisionStatus.COMPLETED }
            } == true
            val old = RevisionStreak.summary(before.streakState, day)
            val new = RevisionStreak.summary(after.streakState, day)
            val flame = old.status != "earned" && new.status == "earned"
            val selection = before.todayWidgetVisit?.selection.orEmpty()
            return when {
                programmeDone -> PROGRAMME
                flame && old.record > 0 && new.record > old.record -> RECORD
                eveningDone -> EVENING
                revisionId in selection && selection.all { it in completedIds } -> RESUME
                flame -> FLAME
                else -> REVISION
            }
        }
    }
}
