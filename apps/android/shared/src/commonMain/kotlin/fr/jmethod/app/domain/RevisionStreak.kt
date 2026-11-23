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

    /**
     * Séances figées que la mutation [before] → [after], décidée par l'app (adaptation après une validation, rythme
     * réappliqué aux programmes en cours), a sorties du programme du jour : elles ne conditionnent plus la flamme.
     * Une séance recréée sous un autre identifiant (même programme, même étape) et encore due reste exigée sous ce
     * nouvel identifiant. Ce qui manquait déjà avant la mutation (suppression ou report de l'élève) reste bloquant.
     */
    fun releaseMovedByApp(before: AppDocument, after: AppDocument, now: Instant): AppDocument {
        val state = after.streakState ?: return after
        val today = day(now)
        val index = state.days.indexOfLast { it.date == today.toString() }
        if (index < 0 || state.days[index].status != RevisionDayStatus.PENDING) return after
        val entry = state.days[index]
        val preparation = today.plusDays(1).toString()
        fun due(revision: Revision?) = revision != null && (revision.status == RevisionStatus.COMPLETED || revision.scheduledDate <= preparation)
        fun step(revision: Revision) = if (revision.isEmergencySession == true || revision.programmeId == null || revision.rhythmStepId == null) null
            else revision.programmeId to revision.rhythmStepId
        // Secours pour les programmes migrés (étapes `legacy-N`) : même programme, même nombre de jours.
        fun offset(revision: Revision) = if (revision.isEmergencySession == true || revision.programmeId == null || revision.nominalOffsetDays == null) null
            else revision.programmeId to revision.nominalOffsetDays
        val previous = before.revisions.associateBy { it.id }
        val current = after.revisions.associateBy { it.id }
        val created = after.revisions.filter { it.id !in previous }
        val required = entry.requiredRevisionIds.mapNotNull { id ->
            val old = previous[id]
            when {
                !due(old) -> id // Absente ou repoussée avant cette mutation : décision de l'élève, reste bloquante.
                due(current[id]) -> id
                current[id] != null -> null // Repoussée par l'app.
                else -> (step(old!!)?.let { key -> created.firstOrNull { step(it) == key } }
                    ?: offset(old)?.let { key -> created.firstOrNull { offset(it) == key } })?.takeIf { due(it) }?.id
            }
        }.distinct()
        if (required == entry.requiredRevisionIds) return after
        val completed = entry.completedRevisionIds.filter { it in required }
        val stamp = AppDocumentCodec.isoMillis(now)
        val updated = when {
            required.isEmpty() -> entry.copy(requiredRevisionIds = emptyList(), completedRevisionIds = emptyList(), status = RevisionDayStatus.REST, finalizedAt = stamp)
            completed.size == required.size -> entry.copy(requiredRevisionIds = required, completedRevisionIds = completed, status = RevisionDayStatus.EARNED, finalizedAt = stamp)
            else -> entry.copy(requiredRevisionIds = required, completedRevisionIds = completed)
        }
        return after.copy(streakState = derived(state.copy(days = state.days.toMutableList().also { it[index] = updated })))
    }

    data class Summary(val count: Int, val record: Int, val status: String, val title: String, val message: String, val remaining: Int)

    fun summary(state: StreakState?, date: String): Summary {
        val normalized = state?.let(::derived)
        val count = normalized?.currentCount ?: 0
        val record = normalized?.bestCount ?: 0
        val entry = normalized?.days?.firstOrNull { it.date == date }
        val remaining = entry?.requiredRevisionIds?.count { it !in entry.completedRevisionIds } ?: 0
        if (entry == null) return Summary(count, record, "waiting", "${days(count)} de révision", "Le suivi reprendra à la prochaine journée de révision.", 0)
        // Les pauses ne masquent pas une interruption : on regarde la dernière journée qui comptait.
        val interrupted = normalized?.days?.lastOrNull { it.date < date && it.status != RevisionDayStatus.REST }?.status == RevisionDayStatus.MISSED
        return when (entry.status) {
            RevisionDayStatus.EARNED -> Summary(count, record, "earned", "Flamme gagnée !", "Toutes tes révisions sont terminées : série de ${days(count)} !", 0)
            RevisionDayStatus.REST -> if (interrupted) Summary(count, record, "rest", "Série interrompue", "Aucun J aujourd’hui. Ta prochaine journée de révision lancera une nouvelle série.", 0)
                else Summary(count, record, "rest", "Flamme en pause", "Aucun J aujourd’hui, ta série est conservée.", 0)
            RevisionDayStatus.MISSED -> Summary(count, record, "missed", "Série interrompue", "Une journée de révision est restée incomplète. Tu peux recommencer une nouvelle série.", remaining)
            RevisionDayStatus.PENDING -> Summary(count, record, "pending", if (interrupted) "Une nouvelle série commence ici" else "À compléter",
                "Encore $remaining révision${if (remaining > 1) "s" else ""} pour gagner la flamme du jour.", remaining)
        }
    }
}
