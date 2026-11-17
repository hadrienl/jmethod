import React, { useState, useEffect, useRef } from 'react';
import { CheckCircle2, AlertTriangle, TrendingUp, TrendingDown, Target } from 'lucide-react';
import confetti from 'canvas-confetti';
import { Revision, Course, Subject, AppSettings } from '../types';
import { PHASE_CONFIG } from '../lib/constants';

interface ScoreModalProps {
  isOpen: boolean;
  onClose: () => void;
  revision: Revision | null;
  course: Course | null;
  subject: Subject | null;
  settings: AppSettings;
  onSubmit: (revisionId: string, score: number) => void;
}

export const ScoreModal: React.FC<ScoreModalProps> = ({
  isOpen,
  onClose,
  revision,
  course,
  subject,
  settings,
  onSubmit,
}) => {
  const [score, setScore] = useState<number>(50);

  const dialogRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!isOpen) return;
    const previous = document.activeElement as HTMLElement | null;
    const dialog = dialogRef.current;
    dialog?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); onClose(); }
      if (event.key !== 'Tab' || !dialog) return;
      const elements = Array.from(dialog.querySelectorAll<HTMLElement>('button:not([disabled]), input, [tabindex="0"]'));
      const first = elements[0]; const last = elements[elements.length - 1];
      if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog)) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    document.addEventListener('keydown', onKeyDown);
    const oldOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.removeEventListener('keydown', onKeyDown); document.body.style.overflow = oldOverflow; previous?.focus(); };
  }, [isOpen, onClose]);

  if (!isOpen || !revision || !course) return null;

  const phaseConfig = PHASE_CONFIG[revision.phase];

  // Dynamic feedback calculations
  const normalizedDelta = (score - 50) / 50;
  const factor = 1 + normalizedDelta * settings.sensitivity;
  const isEmergency = score < settings.emergencyThreshold;

  let feedbackIcon = <Target className="w-5 h-5 text-sky-700" />;
  let feedbackTitle = 'On garde le même rythme';
  let feedbackColor = 'text-sky-700 bg-sky-500/10 border-sky-500/30';
  let feedbackDescription = 'Tu retrouves les idées principales. Ta prochaine séance garde le même espacement.';

  if (isEmergency) {
    feedbackIcon = <AlertTriangle className="w-5 h-5 text-rose-700" />;
    feedbackTitle = 'Rattrapage nécessaire';
    feedbackColor = 'text-rose-700 bg-rose-500/10 border-rose-500/30';
    feedbackDescription = 'Oubli important : une session de rattrapage immédiate sera programmée pour demain (J+1).';
  } else if (score < 50) {
    const percent = Math.round((1 - factor) * 100);
    feedbackIcon = <TrendingDown className="w-5 h-5 text-amber-700" />;
    feedbackTitle = 'Révision rapprochée';
    feedbackColor = 'text-amber-700 bg-amber-500/10 border-amber-500/30';
    feedbackDescription = `Difficultés constatées : les prochaines révisions seront rapprochées de -${percent}%.`;
  } else if (score > 50) {
    const percent = Math.round((factor - 1) * 100);
    feedbackIcon = <TrendingUp className="w-5 h-5 text-emerald-700" />;
    feedbackTitle = 'Révision espacée';
    feedbackColor = 'text-emerald-700 bg-emerald-500/10 border-emerald-500/30';
    feedbackDescription = `Très bonne maîtrise : les prochaines révisions seront espacées de +${percent}%.`;
  }

  const handleSubmit = () => {
    if (score >= 75 && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      confetti({
        particleCount: 50,
        spread: 60,
        origin: { y: 0.7 },
        colors: ['#38bdf8', '#10b981', '#f59e0b', '#8b5cf6'],
      });
    }
    onSubmit(revision.id, score);
    onClose();
  };

  const getScoreColorClass = (val: number) => {
    if (val < 30) return 'text-rose-700';
    if (val < 50) return 'text-amber-700';
    if (val === 50) return 'text-sky-700';
    if (val < 85) return 'text-emerald-700';
    return 'text-indigo-700';
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4 bg-slate-950/60 backdrop-blur-md animate-fade-in">
      <div ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby="score-title" tabIndex={-1} className="relative w-full max-w-lg bg-white border-t sm:border border-slate-200 rounded-t-3xl sm:rounded-2xl shadow-2xl p-5 sm:p-6 overflow-hidden max-h-[92vh] overflow-y-auto pb-8 sm:pb-6">
        {/* Mobile drag handle */}
        <div className="w-12 h-1 bg-slate-200 rounded-full mx-auto mb-4 sm:hidden opacity-60" />
        {/* Glow accent */}
        <div
          className="absolute -top-24 -right-24 w-48 h-48 rounded-full blur-3xl opacity-20 pointer-events-none"
          style={{ backgroundColor: subject?.color || '#38BDF8' }}
        />

        {/* Header */}
        <div className="flex items-start justify-between mb-5">
          <div>
            <div className="flex items-center gap-2 mb-1">
              <span
                className="px-2.5 py-0.5 rounded-full text-xs font-semibold uppercase tracking-wider"
                style={{
                  backgroundColor: `${subject?.color}20`,
                  color: subject?.color || '#38BDF8',
                  border: `1px solid ${subject?.color}40`,
                }}
              >
                {subject?.name || 'Matière'}
              </span>
              <span
                className={`px-2 py-0.5 rounded text-xs font-medium border ${phaseConfig.border} ${phaseConfig.bgLight} ${phaseConfig.text}`}
              >
                {phaseConfig.label} • {revision.stepLabel}
              </span>
            </div>
            <h3 id="score-title" className="text-xl font-bold text-slate-900 tracking-tight">
              C{course.courseNumber} : {course.title}
            </h3>
            <p className="text-xs text-slate-600 mt-0.5">Chapitre : {course.chapter}</p>
          </div>
          <button
            onClick={onClose}
            aria-label="Fermer l’autoévaluation"
            className="text-slate-600 hover:text-slate-900 p-1 rounded-lg hover:bg-slate-100 transition-colors"
          >
            ✕
          </button>
        </div>

        {/* Question prompt */}
        <div className="mb-6">
          <p className="text-sm text-slate-700 font-medium mb-1">
            De quoi te souviens-tu sans regarder ton cours ?
          </p>
          <p className="text-xs text-slate-600">
            Avec <span className="text-sky-700 font-semibold">50/100</span> on garde ton rythme actuel.
          </p>
        </div>

        {/* Score display & slider */}
        <div className="bg-slate-50/60 border border-slate-200/80 rounded-xl p-5 mb-5 text-center">
          <div className="flex items-baseline justify-center gap-1 mb-3">
            <span className={`text-5xl font-black font-mono tracking-tight ${getScoreColorClass(score)}`}>
              {score}
            </span>
            <span className="text-slate-500 font-bold text-lg">/ 100</span>
          </div>

          <input
            aria-label="Ton niveau de mémorisation sur 100"
            type="range"
            min="0"
            max="100"
            step="5"
            value={score}
            onChange={(e) => setScore(Number(e.target.value))}
            className="w-full h-2.5 bg-slate-100 rounded-lg appearance-none cursor-pointer accent-sky-400 focus:outline-none mb-4"
          />

          {/* Quick preset buttons */}
          <div className="grid grid-cols-5 gap-2">
            {[
              { val: 0, label: '0', sub: 'Pas encore' },
              { val: 25, label: '25', sub: 'Difficile' },
              { val: 50, label: '50', sub: 'Ça revient' },
              { val: 75, label: '75', sub: 'Maîtrisé' },
              { val: 100, label: '100', sub: 'Parfait' },
            ].map((preset) => (
              <button
                key={preset.val}
                type="button"
                onClick={() => setScore(preset.val)}
                className={`py-1.5 px-1 rounded-lg text-xs font-medium border transition-all ${
                  score === preset.val
                    ? 'border-sky-400 bg-sky-500/20 text-slate-900 shadow-sm ring-1 ring-sky-400'
                    : 'border-slate-200 bg-white text-slate-600 hover:border-slate-300 hover:text-slate-800'
                }`}
              >
                <div className="font-bold">{preset.label}</div>
                <div className="text-[10px] opacity-75">{preset.sub}</div>
              </button>
            ))}
          </div>
        </div>

        {/* Live algorithm adaptation preview */}
        <div className={`border rounded-xl p-3.5 mb-6 flex items-start gap-3 ${feedbackColor}`}>
          <div className="mt-0.5 flex-shrink-0">{feedbackIcon}</div>
          <div>
            <div className="text-xs font-bold uppercase tracking-wider mb-0.5">{feedbackTitle}</div>
            <div className="text-xs opacity-90 leading-relaxed">{feedbackDescription}</div>
          </div>
        </div>

        {/* Footer actions */}
        <div className="flex items-center justify-end gap-3">
          <button
            type="button"
            onClick={onClose}
            aria-label="Fermer l’autoévaluation"
            className="px-4 py-2 text-sm font-medium text-slate-600 hover:text-slate-900 hover:bg-slate-100 rounded-xl transition-colors"
          >
            Annuler
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            className="flex items-center gap-2 px-5 py-2.5 text-sm font-semibold text-white bg-gradient-to-r from-violet-700 to-violet-800 hover:from-violet-800 hover:to-violet-900 rounded-xl shadow-lg shadow-sky-500/20 transition-all hover:scale-[1.02] active:scale-[0.98]"
          >
            <CheckCircle2 className="w-4 h-4" />
            Valider la révision
          </button>
        </div>
      </div>
    </div>
  );
};
