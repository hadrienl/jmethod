package fr.jmethod.app.domain

import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.model.*
import java.time.Instant
import java.time.LocalDate

/** Journal de référence : une ouverture fige des identifiants, seule leur validation du jour gagne une flamme. */
object RevisionStreak {
    private fun day(now: Instant) = now.atZone(AppClock.ZONE).toLocalDate()

    /** Les dates futures de validation ne font pas disparaître une obligation d'une journée sans ouverture. */
    fun requiredIds(document: AppDocument, date: String): List<String> {
        val preparation = LocalDate.parse(date).plusDays(1).toString()
        val courses = document.courses.mapTo(HashSet()) { it.id }
        return document.revisions.filter { revision ->
            revision.courseId in courses && (
                revision.status == RevisionStatus.COMPLETED && revision.completedDate == date ||
                revision.scheduledDate <= preparation && (revision.status != RevisionStatus.COMPLETED ||
                    revision.completedDate?.let { it > date } == true)
            )
        }.sortedBy { it.scheduledDate }.map { it.id }.distinct()
    }

    private fun freeze(document: AppDocument, date: String, stamp: String, close: Boolean): RevisionDay {
        val required = requiredIds(document, date)
        val done = document.revisions.filter { it.status == RevisionStatus.COMPLETED && it.completedDate == date }
            .mapTo(HashSet()) { it.id }
        val completed = required.filter { it in done }
        val status = when {
            required.isEmpty() -> RevisionDayStatus.REST
            completed.size == required.size -> RevisionDayStatus.EARNED
            close -> RevisionDayStatus.MISSED
            else -> RevisionDayStatus.PENDING
        }
        return RevisionDay(date, required, completed, stamp,
            finalizedAt = stamp.takeIf { status != RevisionDayStatus.PENDING }, status = status)
    }

    fun derived(state: StreakState): StreakState {
        var current = 0
        var best = 0
        state.days.sortedBy { it.date }.forEach {
            when (it.status) {
                RevisionDayStatus.EARNED -> { current++; best = maxOf(best, current) }
                RevisionDayStatus.MISSED -> current = 0
                else -> Unit
            }
        }
        return state.copy(currentCount = current, bestCount = best)
    }

    fun validate(state: StreakState) {
        require(state.days.isNotEmpty()) { "Le journal de révision est vide." }
        LocalDate.parse(state.trackingStartDate)
        LocalDate.parse(state.lastReconciledDate)
        require(state.days.first().date == state.trackingStartDate && state.days.last().date == state.lastReconciledDate)
        require(state.days.zipWithNext().all { (a,b) -> LocalDate.parse(a.date).plusDays(1).toString() == b.date })
        state.days.forEach { entry ->
            LocalDate.parse(entry.date)
            Instant.parse(entry.frozenAt)
            entry.finalizedAt?.let(Instant::parse)
            require(entry.requiredRevisionIds.all { it.isNotBlank() } && entry.requiredRevisionIds.distinct().size == entry.requiredRevisionIds.size)
            require(entry.completedRevisionIds.distinct().size == entry.completedRevisionIds.size && entry.requiredRevisionIds.containsAll(entry.completedRevisionIds))
            val allDone = entry.requiredRevisionIds.isNotEmpty() && entry.completedRevisionIds.size == entry.requiredRevisionIds.size
            require(when (entry.status) {
                RevisionDayStatus.REST -> entry.requiredRevisionIds.isEmpty() && entry.completedRevisionIds.isEmpty() && entry.finalizedAt != null
                RevisionDayStatus.EARNED -> allDone && entry.finalizedAt != null
                RevisionDayStatus.MISSED -> entry.requiredRevisionIds.isNotEmpty() && !allDone && entry.finalizedAt != null
                RevisionDayStatus.PENDING -> entry.requiredRevisionIds.isNotEmpty() && !allDone && entry.finalizedAt == null && entry.date == state.lastReconciledDate
            }) { "Une journée de révision est incohérente." }
        }
    }

    /** Arrière-plan : clôture le passé avant mutation, mais n'ouvre pas la journée fonctionnelle courante. */
    fun reconcile(document: AppDocument, now: Instant, openCurrent: Boolean = false): AppDocument {
        val today = day(now)
        val stamp = AppDocumentCodec.isoMillis(now)
        val existing = document.streakState
        if (existing == null) {
            if (!openCurrent) return document
            val first = freeze(document, today.toString(), stamp, close = false)
            return document.copy(schemaVersion = 3, streakState = derived(StreakState(today.toString(), today.toString(), days = listOf(first))))
        }
        validate(existing)
        val latest = LocalDate.parse(existing.lastReconciledDate)
        // Un recul d'horloge ne réinsère ni ne rouvre de journée, et ne modifie pas le résultat déjà acquis.
        if (today < latest) return document.copy(schemaVersion = 3, streakState = derived(existing))
        val days = existing.days.toMutableList()
        var date = latest
        while (date < today) {
            val key = date.toString()
            val index = days.indexOfLast { it.date == key }
            if (index < 0) days += freeze(document, key, stamp, close = true)
            else if (days[index].status == RevisionDayStatus.PENDING) {
                days[index] = days[index].copy(status = RevisionDayStatus.MISSED, finalizedAt = stamp)
            }
            date = date.plusDays(1)
        }
        if (openCurrent && days.none { it.date == today.toString() }) days += freeze(document, today.toString(), stamp, close = false)
        return document.copy(schemaVersion = 3, streakState = derived(existing.copy(lastReconciledDate = days.last().date, days = days)))
    }

    /** À appeler dans la même transaction que la validation, après ouverture et avant toute publication. */
    fun completed(document: AppDocument, revisionId: String, now: Instant): AppDocument {
        val reconciled = reconcile(document, now)
        val state = reconciled.streakState ?: return reconciled
        val today = day(now).toString()
        val entry = state.days.lastOrNull { it.date == today } ?: return reconciled
        if (entry.status != RevisionDayStatus.PENDING || revisionId !in entry.requiredRevisionIds) return reconciled
        if (document.revisions.none { it.id == revisionId && it.status == RevisionStatus.COMPLETED && it.completedDate == today }) return reconciled
        val done = (entry.completedRevisionIds + revisionId).toSet()
        val complete = entry.requiredRevisionIds.all { it in done }
        val updated = entry.copy(completedRevisionIds = entry.requiredRevisionIds.filter { it in done },
            status = if (complete) RevisionDayStatus.EARNED else RevisionDayStatus.PENDING,
            finalizedAt = if (complete) AppDocumentCodec.isoMillis(now) else null)
        return reconciled.copy(streakState = derived(state.copy(days = state.days.map { if (it.date == today) updated else it })))
    }

    /** « 0 journée », « 1 journée », « 2 journées » : accord français, 0 et 1 au singulier. */
    fun days(count: Int) = "$count journée${if (count > 1) "s" else ""}"

    data class Summary(val count: Int, val record: Int, val status: String, val title: String, val message: String, val remaining: Int)

    fun summary(state: StreakState?, date: String): Summary {
        val normalized = state?.let(::derived)
        val count = normalized?.currentCount ?: 0
        val record = normalized?.bestCount ?: 0
        val entry = normalized?.days?.firstOrNull { it.date == date }
        val remaining = entry?.requiredRevisionIds?.count { it !in entry.completedRevisionIds } ?: 0
        if (entry == null) return Summary(count, record, "waiting", "${days(count)} de révision", "Le suivi reprendra à la prochaine journée de révision.", 0)
        val interrupted = normalized?.days?.lastOrNull { it.date < date }?.status == RevisionDayStatus.MISSED
        return when (entry.status) {
            RevisionDayStatus.EARNED -> Summary(count, record, "earned", "Flamme gagnée !", "Toutes tes révisions sont terminées : série de ${days(count)} !", 0)
            RevisionDayStatus.REST -> Summary(count, record, "rest", "Flamme en pause", "Aucun J aujourd’hui, ta série est conservée.", 0)
            RevisionDayStatus.MISSED -> Summary(count, record, "missed", "Série interrompue", "Une journée de révision est restée incomplète. Tu peux recommencer une nouvelle série.", remaining)
            RevisionDayStatus.PENDING -> Summary(count, record, "pending", if (interrupted) "Une nouvelle série commence ici" else "À compléter",
                "Encore $remaining révision${if (remaining > 1) "s" else ""} pour gagner la flamme du jour.", remaining)
        }
    }
}
