package fr.jmethod.app.domain

import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.Phase
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.RevisionStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/** Port à l’identique de `src/lib/adaptiveAlgorithm.ts` (vérifié par les vecteurs partagés). */
object AdaptiveAlgorithm {

    fun phaseForStep(stepIndex: Int): Phase = when {
        stepIndex == 0 -> Phase.ENCODAGE
        stepIndex <= 3 -> Phase.CONSOLIDATION
        else -> Phase.RECUPERATION
    }

    fun generateInitialRevisions(course: Course, intervals: List<Int>): List<Revision> {
        val initial = LocalDate.parse(course.date)
        return intervals.mapIndexed { index, days ->
            Revision(
                id = "${course.id}-rev-$index",
                courseId = course.id,
                stepIndex = index,
                stepLabel = "J$days",
                scheduledDate = initial.plusDays(days.toLong()).toString(),
                status = RevisionStatus.PENDING,
                phase = phaseForStep(index),
                adjustedIntervalDays = if (index == 0) 0 else days - intervals[index - 1],
            )
        }
    }

    enum class ActionType(val wire: String) { EMERGENCY("emergency"), SHORTENED("shortened"), EXACT("exact"), EXPANDED("expanded") }

    data class Explanation(
        val score: Int,
        val factor: Double,
        val message: String,
        val actionType: ActionType,
        val nextDate: String?,
    )

    data class Result(val updatedRevisions: List<Revision>, val explanation: Explanation)

    /** `settings.intervals[i] ?? fallback` : index hors limites → valeur de repli. */
    private fun List<Int>.at(index: Int): Int? = getOrNull(index)

    /**
     * Applique l’algorithme adaptatif après une autoévaluation.
     *
     * @param actualCompletionDate date réelle de validation (historique) ; par défaut, la date locale de [now].
     * @param schedulingReferenceDate jour préparé, point de départ de la replanification ; par défaut [actualCompletionDate].
     * @param now horloge injectée : id d’un rattrapage `<courseId>-emerg-<epochMs>` comme `Date.now()` côté web.
     */
    fun applyAdaptiveReview(
        allRevisionsForCourse: List<Revision>,
        revisionId: String,
        score: Int,
        settings: AppSettings,
        actualCompletionDate: String? = null,
        schedulingReferenceDate: String? = null,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.of("Europe/Paris"),
    ): Result {
        val completionDate = actualCompletionDate ?: now.atZone(zone).toLocalDate().toString()
        val referenceDate = schedulingReferenceDate ?: completionDate
        val revisions = allRevisionsForCourse.sortedBy { it.stepIndex }.toMutableList()
        val targetIndex = revisions.indexOfFirst { it.id == revisionId }
        require(targetIndex != -1) { "Revision $revisionId non trouvée" }

        val currentRev = revisions[targetIndex].copy(
            completedDate = completionDate,
            score = score,
            status = RevisionStatus.COMPLETED,
        )
        revisions[targetIndex] = currentRev

        val normalizedDelta = (score - 50) / 50.0
        val factor = 1 + normalizedDelta * settings.sensitivity

        val nextPendingIndex = revisions.withIndex().indexOfFirst { (idx, r) ->
            idx > targetIndex && r.status == RevisionStatus.PENDING && r.isEmergencySession != true
        }

        var actionType = ActionType.EXACT
        val message: String
        var nextDateStr: String? = null
        val reference = LocalDate.parse(referenceDate)

        if (score < settings.emergencyThreshold) {
            actionType = ActionType.EMERGENCY
            val emergencyDate = reference.plusDays(1).toString()
            nextDateStr = emergencyDate
            val emergency = Revision(
                id = "${currentRev.courseId}-emerg-${now.toEpochMilli()}",
                courseId = currentRev.courseId,
                stepIndex = currentRev.stepIndex,
                stepLabel = "${currentRev.stepLabel}* (Rattrapage)",
                scheduledDate = emergencyDate,
                status = RevisionStatus.PENDING,
                phase = Phase.CONSOLIDATION,
                isEmergencySession = true,
                adjustedIntervalDays = 1,
            )
            revisions.add(targetIndex + 1, emergency)
            message = "Score difficile ($score/100) : Une session de rattrapage immédiate est programmée pour demain afin de consolider l'ancrage."
        } else if (nextPendingIndex != -1) {
            val nextRev = revisions[nextPendingIndex]
            val intervals = settings.intervals
            val currentIntervalVal = intervals.at(currentRev.stepIndex) ?: 0
            val nextIntervalVal = intervals.at(nextRev.stepIndex) ?: (currentIntervalVal + 1)
            val defaultStepGap = max(1, nextIntervalVal - currentIntervalVal)

            val adaptedGap = max(1, jsRound(defaultStepGap * factor))
            val nextDate = reference.plusDays(adaptedGap.toLong())
            nextDateStr = nextDate.toString()
            revisions[nextPendingIndex] = nextRev.copy(scheduledDate = nextDateStr, adjustedIntervalDays = adaptedGap)

            var lastDate = nextDate
            for (i in nextPendingIndex + 1 until revisions.size) {
                val stepGap = max(
                    1,
                    (intervals.at(revisions[i].stepIndex) ?: 0) - (intervals.at(revisions[i - 1].stepIndex) ?: 0),
                )
                val scaledStepGap = max(1, jsRound(stepGap * factor))
                lastDate = lastDate.plusDays(scaledStepGap.toLong())
                revisions[i] = revisions[i].copy(scheduledDate = lastDate.toString(), adjustedIntervalDays = scaledStepGap)
            }

            // Écart D7 : accord du nombre de jours (« 1 jour », le web écrit « 1 jours »).
            message = when {
                score == 50 -> {
                    actionType = ActionType.EXACT
                    "Niveau idéal (50/100) : Intervalle d'origine parfaitement conservé ($adaptedGap ${plural(adaptedGap, "jour")}). Prochaine révision le $nextDateStr."
                }
                score > 50 -> {
                    actionType = ActionType.EXPANDED
                    val percent = jsRound((factor - 1) * 100)
                    "Excellente maîtrise ($score/100) : Intervalle espacé de +$percent% ($adaptedGap ${plural(adaptedGap, "jour")} au lieu de ${defaultStepGap}j). Prochaine révision le $nextDateStr."
                }
                else -> {
                    actionType = ActionType.SHORTENED
                    val percent = jsRound((1 - factor) * 100)
                    "Difficultés constatées ($score/100) : Intervalle rapproché de -$percent% ($adaptedGap ${plural(adaptedGap, "jour")} au lieu de ${defaultStepGap}j). Prochaine révision le $nextDateStr."
                }
            }
        } else {
            message = "Bravo ! Tu as validé le cycle complet de révisions de ce cours avec la note de $score/100 !"
        }

        return Result(revisions, Explanation(score, factor, message, actionType, nextDateStr))
    }
}
