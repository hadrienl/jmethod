import React from 'react';
import { format, parseISO } from 'date-fns';
import { fr } from 'date-fns/locale';
import { CheckCircle2, Clock, Trash2, Calendar, BookOpen, Sparkles } from 'lucide-react';
import { Course, Revision, Subject } from '../types';
import { PHASE_CONFIG } from '../lib/constants';

interface CourseDetailModalProps {
  isOpen: boolean;
  onClose: () => void;
  course: Course | null;
  subject: Subject | null;
  revisions: Revision[];
  onOpenScoreModal: (revision: Revision) => void;
  onDeleteCourse: (courseId: string) => void;
}

export const CourseDetailModal: React.FC<CourseDetailModalProps> = ({
  isOpen,
  onClose,
  course,
  subject,
  revisions,
  onOpenScoreModal,
  onDeleteCourse,
}) => {
  if (!isOpen || !course) return null;

  const sortedRevisions = [...revisions].sort((a, b) => a.stepIndex - b.stepIndex);
  const completedCount = sortedRevisions.filter((r) => r.status === 'completed').length;
  const progressPercent = Math.round((completedCount / sortedRevisions.length) * 100);

  const scores = sortedRevisions.filter((r) => r.score !== undefined).map((r) => r.score as number);
  const averageScore = scores.length > 0 ? Math.round(scores.reduce((a, b) => a + b, 0) / scores.length) : null;

  const handleDelete = () => {
    if (confirm(`Es-tu sûr de vouloir supprimer définitivement le cours C${course.courseNumber} : ${course.title} ?`)) {
      onDeleteCourse(course.id);
      onClose();
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4 bg-slate-950/60 backdrop-blur-md animate-fade-in">
      <div className="relative w-full max-w-2xl bg-white border-t sm:border border-slate-200 rounded-t-3xl sm:rounded-2xl shadow-2xl p-5 sm:p-6 overflow-hidden max-h-[92vh] overflow-y-auto pb-8 sm:pb-6">
        {/* Mobile drag handle */}
        <div className="w-12 h-1 bg-slate-200 rounded-full mx-auto mb-4 sm:hidden opacity-60" />
        {/* Glow accent */}
        <div
          className="absolute -top-24 -right-24 w-52 h-52 rounded-full blur-3xl opacity-20 pointer-events-none"
          style={{ backgroundColor: subject?.color || '#38BDF8' }}
        />

        {/* Header */}
        <div className="flex items-start justify-between mb-4">
          <div>
            <div className="flex items-center gap-2 mb-1.5">
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
              <span className="text-xs text-slate-600">
                Chapitre : <strong className="text-slate-800">{course.chapter}</strong>
              </span>
            </div>
            <h2 className="text-2xl font-black text-slate-900 tracking-tight flex items-center gap-2">
              <span className="text-sky-700 font-mono">C{course.courseNumber}</span>
              <span>{course.title}</span>
            </h2>
            <div className="flex items-center gap-4 text-xs text-slate-600 mt-1">
              <span className="flex items-center gap-1">
                <Calendar className="w-3.5 h-3.5" />
                Créé le {format(parseISO(course.date), 'dd MMMM yyyy', { locale: fr })}
              </span>
              {averageScore !== null && (
                <span className="flex items-center gap-1 text-slate-800 font-medium">
                  <Sparkles className="w-3.5 h-3.5 text-amber-700" />
                  Moyenne des séances : <strong className="text-slate-900">{averageScore}/100</strong>
                </span>
              )}
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-slate-600 hover:text-slate-900 p-1 rounded-lg hover:bg-slate-100 transition-colors"
          >
            ✕
          </button>
        </div>

        {/* Course notes if any */}
        {course.description && (
          <div className="bg-slate-50/60 border border-slate-200/80 rounded-xl p-3 mb-5 text-xs text-slate-700">
            <div className="font-semibold text-slate-600 mb-1 flex items-center gap-1.5">
              <BookOpen className="w-3.5 h-3.5" /> Notes de cours
            </div>
            {course.description}
          </div>
        )}

        {/* Progress Bar */}
        <div className="mb-6 bg-slate-50 border border-slate-200 rounded-xl p-4">
          <div className="flex items-center justify-between text-xs mb-2">
            <span className="font-semibold text-slate-700">Cycle des révisions</span>
            <span className="font-mono font-bold text-sky-700">
              {completedCount} / {sortedRevisions.length} effectuées ({progressPercent}%)
            </span>
          </div>
          <div className="w-full h-2.5 bg-slate-100 rounded-full overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-violet-700 to-emerald-500 rounded-full transition-all duration-500"
              style={{ width: `${progressPercent}%` }}
            />
          </div>
        </div>

        {/* Timeline of Revisions */}
        <div className="mb-6">
          <h4 className="text-xs font-bold uppercase tracking-wider text-slate-600 mb-3">
            Calendrier personnalisé des J
          </h4>

          <div className="space-y-2">
            {sortedRevisions.map((rev) => {
              const phaseCfg = PHASE_CONFIG[rev.phase];
              const isCompleted = rev.status === 'completed';
              const isOverdue = rev.status === 'overdue';

              return (
                <div
                  key={rev.id}
                  className={`flex items-center justify-between p-3 rounded-xl border transition-all ${
                    isCompleted
                      ? 'bg-slate-50/40 border-slate-200/60 text-slate-600'
                      : isOverdue
                      ? 'bg-rose-500/10 border-rose-500/30 text-slate-900'
                      : 'bg-slate-50 border-slate-200 text-slate-800'
                  }`}
                >
                  <div className="flex items-center gap-3">
                    <span
                      className={`w-10 text-center font-mono font-bold text-xs py-1 rounded-lg border ${phaseCfg.border} ${phaseCfg.bgLight} ${phaseCfg.text}`}
                    >
                      {rev.stepLabel}
                    </span>
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-semibold text-slate-900">{phaseCfg.label}</span>
                        {rev.isEmergencySession && (
                          <span className="text-[10px] bg-rose-500/20 text-rose-700 border border-rose-500/30 px-1.5 py-0.2 rounded font-medium">
                            Rattrapage
                          </span>
                        )}
                      </div>
                      <div className="text-[11px] text-slate-600 flex items-center gap-2 mt-0.5">
                        <span className="flex items-center gap-1">
                          <Clock className="w-3 h-3" />
                          Prévu : {format(parseISO(rev.scheduledDate), 'dd MMM yyyy', { locale: fr })}
                        </span>
                        {rev.completedDate && (
                          <span className="text-emerald-700">
                            • Fait le {format(parseISO(rev.completedDate), 'dd/MM')}
                          </span>
                        )}
                      </div>
                    </div>
                  </div>

                  {/* Right side: Score or Action */}
                  <div className="flex items-center gap-2">
                    {isCompleted ? (
                      <div className="flex items-center gap-1.5 bg-white border border-slate-300/60 px-2.5 py-1 rounded-lg">
                        <CheckCircle2 className="w-4 h-4 text-emerald-700" />
                        <span className="text-xs font-mono font-bold text-slate-900">
                          {rev.score ?? 50}/100
                        </span>
                      </div>
                    ) : (
                      <button
                        onClick={() => onOpenScoreModal(rev)}
                        className="px-3 py-1.5 text-xs font-semibold bg-sky-500/20 hover:bg-violet-700 text-sky-700 hover:text-white border border-sky-500/30 rounded-lg transition-all"
                      >
                        Valider
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* Footer actions */}
        <div className="flex items-center justify-between pt-4 border-t border-slate-200">
          <button
            type="button"
            onClick={handleDelete}
            className="flex items-center gap-1.5 text-xs text-rose-700 hover:text-rose-700 hover:bg-rose-500/10 px-3 py-2 rounded-xl transition-colors"
          >
            <Trash2 className="w-4 h-4" />
            Supprimer ce cours
          </button>
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 text-sm font-medium text-slate-700 hover:text-slate-900 bg-slate-100 hover:bg-slate-200 rounded-xl transition-colors"
          >
            Fermer
          </button>
        </div>
      </div>
    </div>
  );
};
