package fr.jmethod.app.domain

import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.RevisionStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

@Serializable
data class TodayWidgetVisit(
    val lastVisit: String,
    val returnDay: String? = null,
    val selection: List<String> = emptyList(),
    val removedCount: Int = 0,
) {
    fun opened(day: String): TodayWidgetVisit {
        val previous = runCatching { LocalDate.parse(lastVisit) }.getOrNull()
        val current = LocalDate.parse(day)
        if (previous != null && current <= previous) return this
        val returned = previous != null && ChronoUnit.DAYS.between(previous, current) >= 3
        return copy(lastVisit = day, returnDay = day.takeIf { returned })
    }

    fun reconcile(due: List<Revision>, revisions: List<Revision>, courses: Set<String>): TodayWidgetVisit {
        val available = due.mapTo(mutableSetOf()) { it.id }
        revisions.filter { it.status == RevisionStatus.COMPLETED && it.courseId in courses }.mapTo(available) { it.id }
        val unique = selection.distinct()
        val kept = unique.filter { it in available }
        return copy(selection = kept, removedCount = removedCount.coerceAtLeast(0) + unique.size - kept.size)
    }
}

enum class TodayWidgetMode(val art: String) {
    REST("rest"), READY("ready"), FOCUS("focus"), PROGRESS("motion"), DONE("joy"),
    RETURN("ready"), RESUME("focus"), PLAN("focus"), PLAN_ACTIVE("motion"), PLAN_DONE("joy"), NEW_WORK("focus"),
}

data class TodayWidgetState(
    val mode: TodayWidgetMode,
    val next: Revision?,
    val remaining: Int,
    val completed: Int,
    val overdue: Int,
    val outsidePlan: Int,
    val removedFromPlan: Int,
) {
    companion object {
        fun build(
            day: String, due: List<Revision>, completed: List<Revision>, overdue: List<Revision>,
            finishedIds: Set<String>, visit: TodayWidgetVisit?, flameEarned: Boolean,
        ): TodayWidgetState {
            val selection = visit?.selection.orEmpty().distinct()
            val selected = orderedSelection(selection, due)
            val selectedComplete = selection.count { it in finishedIds }
            val outside = due.size - selected.size
            val returning = visit?.returnDay == day
            val mode = when {
                selected.isNotEmpty() -> TodayWidgetMode.PLAN_ACTIVE
                selectedComplete > 0 && outside > 0 -> TodayWidgetMode.PLAN_DONE
                due.isEmpty() && completed.isNotEmpty() -> TodayWidgetMode.DONE
                flameEarned && due.isNotEmpty() -> TodayWidgetMode.NEW_WORK
                returning && overdue.size >= 4 -> TodayWidgetMode.PLAN
                returning && overdue.isNotEmpty() -> TodayWidgetMode.RESUME
                returning -> TodayWidgetMode.RETURN
                due.isEmpty() -> TodayWidgetMode.REST
                completed.isNotEmpty() -> TodayWidgetMode.PROGRESS
                due.size < 4 -> TodayWidgetMode.READY
                else -> TodayWidgetMode.FOCUS
            }
            val next = when (mode) {
                TodayWidgetMode.PLAN_ACTIVE -> selected.firstOrNull()
                TodayWidgetMode.RESUME -> overdue.firstOrNull()
                TodayWidgetMode.PLAN, TodayWidgetMode.PLAN_DONE -> null
                else -> due.firstOrNull()
            }
            return TodayWidgetState(
                mode, next, if (mode == TodayWidgetMode.PLAN_ACTIVE) selected.size else due.size,
                if (mode == TodayWidgetMode.PLAN_ACTIVE || mode == TodayWidgetMode.PLAN_DONE) selectedComplete else completed.size,
                overdue.size, outside, visit?.removedCount?.coerceAtLeast(0) ?: 0,
            )
        }

        fun orderedSelection(ids: List<String>, due: List<Revision>): List<Revision> {
            val byId = due.associateBy { it.id }
            return ids.distinct().mapNotNull(byId::get)
        }

        fun suggested(due: List<Revision>, overdue: List<Revision>): List<String> =
            (overdue + due).distinctBy { it.id }.take(3).map { it.id }
    }
}
