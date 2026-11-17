import { addDays, format, parseISO } from 'date-fns';
import { Course, Revision, Phase, AppSettings } from '../types';

/**
 * Returns the cognitive phase corresponding to a step index
 */
export function getPhaseForStep(stepIndex: number): Phase {
  if (stepIndex === 0) return 'encodage'; // J0
  if (stepIndex <= 3) return 'consolidation'; // J1, J2, J4
  return 'recuperation'; // J7, J14, J25, J45, J60, J90
}

/**
 * Generates initial schedule of revisions for a newly created course
 */
export function generateInitialRevisions(course: Course, intervals: number[]): Revision[] {
  const initialDate = parseISO(course.date);

  return intervals.map((intervalDays, index) => {
    const scheduledDate = addDays(initialDate, intervalDays);
    const stepLabel = `J${intervalDays}`;
    const phase = getPhaseForStep(index);

    return {
      id: `${course.id}-rev-${index}`,
      courseId: course.id,
      stepIndex: index,
      stepLabel,
      scheduledDate: format(scheduledDate, 'yyyy-MM-dd'),
      status: 'pending',
      phase,
      adjustedIntervalDays: index === 0 ? 0 : intervalDays - intervals[index - 1],
    };
  });
}

export interface AdaptationResult {
  updatedRevisions: Revision[];
  explanation: {
    score: number;
    factor: number;
    message: string;
    actionType: 'emergency' | 'shortened' | 'exact' | 'expanded';
    nextDate?: string;
  };
}

/**
 * Applies the adaptive spaced-repetition algorithm when a student scores a revision
 */
export function applyAdaptiveReview(
  allRevisionsForCourse: Revision[],
  revisionId: string,
  score: number,
  settings: AppSettings,
  actualCompletionDate: string = format(new Date(), 'yyyy-MM-dd')
): AdaptationResult {
  const revisions = [...allRevisionsForCourse].sort((a, b) => a.stepIndex - b.stepIndex);
  const targetIndex = revisions.findIndex((r) => r.id === revisionId);

  if (targetIndex === -1) {
    throw new Error(`Revision ${revisionId} non trouvée`);
  }

  const currentRev = { ...revisions[targetIndex] };
  currentRev.completedDate = actualCompletionDate;
  currentRev.score = score;
  currentRev.status = 'completed';
  revisions[targetIndex] = currentRev;

  // 1. Calculate the adjustment factor based on score (Point pivot = 50)
  // factor = 1 + ((score - 50) / 50) * sensitivity
  // score 50 => factor 1.0 (exact base interval)
  // score 100 with sens 0.4 => factor 1.4 (+40% space)
  // score 0 with sens 0.4 => factor 0.6 (-40% space)
  const normalizedDelta = (score - 50) / 50; // Between -1.0 and +1.0
  const factor = 1 + normalizedDelta * settings.sensitivity;

  const nextPendingIndex = revisions.findIndex(
    (r, idx) => idx > targetIndex && r.status === 'pending' && !r.isEmergencySession
  );

  let actionType: 'emergency' | 'shortened' | 'exact' | 'expanded' = 'exact';
  let message = '';
  let nextDateStr: string | undefined;

  const completionDateObj = parseISO(actualCompletionDate);

  // 2. Check for emergency session if score < emergencyThreshold (default 30)
  if (score < settings.emergencyThreshold) {
    actionType = 'emergency';
    // Create an emergency revision tomorrow (J+1)
    const emergencyDate = addDays(completionDateObj, 1);
    const emergencyDateStr = format(emergencyDate, 'yyyy-MM-dd');
    nextDateStr = emergencyDateStr;

    const emergencyRevision: Revision = {
      id: `${currentRev.courseId}-emerg-${Date.now()}`,
      courseId: currentRev.courseId,
      stepIndex: currentRev.stepIndex,
      stepLabel: `${currentRev.stepLabel}* (Rattrapage)`,
      scheduledDate: emergencyDateStr,
      status: 'pending',
      phase: 'consolidation',
      isEmergencySession: true,
      adjustedIntervalDays: 1,
    };

    // Insert emergency revision right after current
    revisions.splice(targetIndex + 1, 0, emergencyRevision);
    message = `Score difficile (${score}/100) : Une session de rattrapage immédiate est programmée pour demain afin de consolider l'ancrage.`;
  } else if (nextPendingIndex !== -1) {
    // 3. Normal adaptive rescheduling of future steps
    const nextRev = { ...revisions[nextPendingIndex] };

    // Standard theoretical gap between this step and next step
    const currentIntervalVal = settings.intervals[currentRev.stepIndex] ?? 0;
    const nextIntervalVal = settings.intervals[nextRev.stepIndex] ?? currentIntervalVal + 1;
    const defaultStepGap = Math.max(1, nextIntervalVal - currentIntervalVal);

    // Apply multiplier factor to this step gap
    const adaptedGap = Math.max(1, Math.round(defaultStepGap * factor));
    const nextDate = addDays(completionDateObj, adaptedGap);
    nextDateStr = format(nextDate, 'yyyy-MM-dd');

    nextRev.scheduledDate = nextDateStr;
    nextRev.adjustedIntervalDays = adaptedGap;
    revisions[nextPendingIndex] = nextRev;

    // Proportionally adjust any subsequent steps so order remains consistent
    let lastDate = nextDate;
    for (let i = nextPendingIndex + 1; i < revisions.length; i++) {
      const stepGap = Math.max(
        1,
        (settings.intervals[revisions[i].stepIndex] ?? 0) -
          (settings.intervals[revisions[i - 1].stepIndex] ?? 0)
      );
      const scaledStepGap = Math.max(1, Math.round(stepGap * factor));
      lastDate = addDays(lastDate, scaledStepGap);
      revisions[i] = {
        ...revisions[i],
        scheduledDate: format(lastDate, 'yyyy-MM-dd'),
        adjustedIntervalDays: scaledStepGap,
      };
    }

    if (score === 50) {
      actionType = 'exact';
      message = `Niveau idéal (50/100) : Intervalle d'origine parfaitement conservé (${adaptedGap} jours). Prochaine révision le ${nextDateStr}.`;
    } else if (score > 50) {
      actionType = 'expanded';
      const percent = Math.round((factor - 1) * 100);
      message = `Excellente maîtrise (${score}/100) : Intervalle espacé de +${percent}% (${adaptedGap} jours au lieu de ${defaultStepGap}j). Prochaine révision le ${nextDateStr}.`;
    } else {
      actionType = 'shortened';
      const percent = Math.round((1 - factor) * 100);
      message = `Difficultés constatées (${score}/100) : Intervalle rapproché de -${percent}% (${adaptedGap} jours au lieu de ${defaultStepGap}j). Prochaine révision le ${nextDateStr}.`;
    }
  } else {
    // Last step achieved!
    message = `Bravo ! Tu as validé le cycle complet de révisions de ce cours avec la note de ${score}/100 !`;
  }

  return {
    updatedRevisions: revisions,
    explanation: {
      score,
      factor,
      message,
      actionType,
      nextDate: nextDateStr,
    },
  };
}
