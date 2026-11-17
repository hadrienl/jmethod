import React from 'react';
import {
  BarChart3,
  CheckCircle2,
  BookOpen,
  AlertTriangle,
  Flame,
  Award,
  Sparkles,
} from 'lucide-react';
import { Course, Revision, Subject } from '../types';

interface StatsViewProps {
  courses: Course[];
  revisions: Revision[];
  subjects: Subject[];
}

export const StatsView: React.FC<StatsViewProps> = ({ courses, revisions, subjects }) => {
  const coursesMap = new Map(courses.map((c) => [c.id, c]));

  const completedRevisions = revisions.filter((r) => r.status === 'completed');
  const scoredRevisions = revisions.filter((r) => r.score !== undefined);

  // Overall average score
  const overallAvgScore =
    scoredRevisions.length > 0
      ? Math.round(
          scoredRevisions.reduce((acc, r) => acc + (r.score ?? 0), 0) / scoredRevisions.length
        )
      : 50;

  // Completion rate
  const completionRate =
    revisions.length > 0 ? Math.round((completedRevisions.length / revisions.length) * 100) : 0;

  // Stats by subject
  const subjectStats = subjects
    .map((subj) => {
      const subjCourses = courses.filter((c) => c.subjectId === subj.id);
      const subjRevs = revisions.filter((r) => {
        const c = coursesMap.get(r.courseId);
        return c?.subjectId === subj.id;
      });
      const subjCompleted = subjRevs.filter((r) => r.status === 'completed');
      const subjScored = subjRevs.filter((r) => r.score !== undefined);
      const avgScore =
        subjScored.length > 0
          ? Math.round(subjScored.reduce((a, b) => a + (b.score ?? 0), 0) / subjScored.length)
          : null;

      return {
        subject: subj,
        coursesCount: subjCourses.length,
        totalRevs: subjRevs.length,
        completedRevs: subjCompleted.length,
        rate: subjRevs.length > 0 ? Math.round((subjCompleted.length / subjRevs.length) * 100) : 0,
        avgScore,
      };
    })
    .filter((s) => s.coursesCount > 0);

  // Weak points (Subjects needing more attention, where avgScore < 50 or completion rate is low)
  const weakSubjects = subjectStats.filter((s) => s.avgScore !== null && s.avgScore < 50);

  return (
    <div className="space-y-6 pb-20 md:pb-8 animate-fade-in">
      {/* Header */}
      <div className="bg-white/90 border border-slate-200 rounded-2xl p-6">
        <h1 className="text-2xl font-black text-slate-900 tracking-tight flex items-center gap-2.5">
          <BarChart3 className="w-6 h-6 text-sky-700" />
          Mes progrès
        </h1>
        <p className="text-xs text-slate-600 mt-0.5">
          Analyse de ta régularité, de tes scores d'apprentissage et de tes marges de progression
        </p>
      </div>

      {/* Top 4 Metric Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-2.5 sm:gap-4">
        {/* Card 1 */}
        <div className="p-3.5 sm:p-5 rounded-xl sm:rounded-2xl bg-white/80 border border-slate-200">
          <div className="flex items-center justify-between text-slate-600 text-[10px] sm:text-xs font-semibold uppercase tracking-wider mb-1.5">
            <span>Taux de réalisation</span>
            <CheckCircle2 className="w-3.5 h-3.5 sm:w-4 sm:h-4 text-emerald-700 flex-shrink-0" />
          </div>
          <div className="text-2xl sm:text-3xl font-black font-mono text-slate-900 mb-0.5">{completionRate}%</div>
          <div className="text-[10px] sm:text-[11px] text-slate-600 leading-tight">
            {completedRevisions.length} sur {revisions.length} faites
          </div>
        </div>

        {/* Card 2 */}
        <div className="p-3.5 sm:p-5 rounded-xl sm:rounded-2xl bg-white/80 border border-slate-200">
          <div className="flex items-center justify-between text-slate-600 text-[10px] sm:text-xs font-semibold uppercase tracking-wider mb-1.5">
            <span>Score moyen</span>
            <Award className="w-3.5 h-3.5 sm:w-4 sm:h-4 text-amber-700 flex-shrink-0" />
          </div>
          <div className="text-2xl sm:text-3xl font-black font-mono text-sky-700 mb-0.5">
            {overallAvgScore}
            <span className="text-slate-500 text-sm sm:text-base">/100</span>
          </div>
          <div className="text-[10px] sm:text-[11px] text-slate-600 leading-tight">
            {overallAvgScore >= 50 ? 'Au-dessus de l’équilibre' : 'Sous l’équilibre'}
          </div>
        </div>

        {/* Card 3 */}
        <div className="p-3.5 sm:p-5 rounded-xl sm:rounded-2xl bg-white/80 border border-slate-200">
          <div className="flex items-center justify-between text-slate-600 text-[10px] sm:text-xs font-semibold uppercase tracking-wider mb-1.5">
            <span>Régularité</span>
            <Flame className="w-3.5 h-3.5 sm:w-4 sm:h-4 text-orange-700 fill-orange-400 flex-shrink-0" />
          </div>
          <div className="text-2xl sm:text-3xl font-black font-mono text-orange-700 mb-0.5">5 j</div>
          <div className="text-[10px] sm:text-[11px] text-slate-600 leading-tight">Série consécutive</div>
        </div>

        {/* Card 4 */}
        <div className="p-3.5 sm:p-5 rounded-xl sm:rounded-2xl bg-white/80 border border-slate-200">
          <div className="flex items-center justify-between text-slate-600 text-[10px] sm:text-xs font-semibold uppercase tracking-wider mb-1.5">
            <span>Cours suivis</span>
            <BookOpen className="w-3.5 h-3.5 sm:w-4 sm:h-4 text-indigo-700 flex-shrink-0" />
          </div>
          <div className="text-2xl sm:text-3xl font-black font-mono text-slate-900 mb-0.5">{courses.length}</div>
          <div className="text-[10px] sm:text-[11px] text-slate-600 leading-tight">Sur {subjectStats.length} matières</div>
        </div>
      </div>

      {/* WEAK POINTS ALERT (If any) */}
      {weakSubjects.length > 0 && (
        <div className="p-5 rounded-2xl bg-amber-500/10 border border-amber-500/30 space-y-2">
          <div className="flex items-center gap-2 text-amber-700 font-bold text-sm">
            <AlertTriangle className="w-5 h-5" />
            Matières nécessitant davantage de travail
          </div>
          <p className="text-xs text-amber-700/80">
            Certaines matières affichent un score moyen en dessous du point d'équilibre (50/100).
            L'algorithme a automatiquement resserré leurs échéances pour t'aider à combler les
            lacunes :
          </p>
          <div className="flex flex-wrap gap-2 pt-1">
            {weakSubjects.map((ws) => (
              <span
                key={ws.subject.id}
                className="px-3 py-1 rounded-lg text-xs font-bold bg-amber-500/20 text-amber-700 border border-amber-500/30 flex items-center gap-2"
              >
                <span>{ws.subject.name}</span>
                <span className="font-mono text-slate-900">({ws.avgScore}/100)</span>
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Breakdown by Subject */}
      <div className="rounded-2xl border border-slate-200 bg-white/90 p-6 space-y-4">
        <h2 className="text-base font-bold text-slate-900 tracking-tight flex items-center gap-2">
          <Sparkles className="w-4 h-4 text-sky-700" />
          Progression et performance par matière
        </h2>

        <div className="space-y-4">
          {subjectStats.map((st) => (
            <div key={st.subject.id} className="p-4 rounded-xl border border-slate-200 bg-slate-50">
              <div className="flex items-center justify-between mb-2">
                <div className="flex items-center gap-2">
                  <span
                    className="w-3 h-3 rounded-full"
                    style={{ backgroundColor: st.subject.color }}
                  />
                  <span className="font-bold text-slate-900 text-sm">{st.subject.name}</span>
                  <span className="text-xs text-slate-500">
                    ({st.coursesCount} cours • {st.completedRevs}/{st.totalRevs} J)
                  </span>
                </div>

                <div className="flex items-center gap-4">
                  {st.avgScore !== null && (
                    <span className="text-xs font-mono font-bold text-slate-700">
                      Moyenne :{' '}
                      <strong
                        className={
                          st.avgScore >= 50
                            ? 'text-emerald-700 font-extrabold'
                            : 'text-amber-700 font-extrabold'
                        }
                      >
                        {st.avgScore}/100
                      </strong>
                    </span>
                  )}
                  <span className="text-xs font-mono font-bold text-sky-700">{st.rate}%</span>
                </div>
              </div>

              {/* Progress bar */}
              <div className="w-full h-2 bg-slate-100 rounded-full overflow-hidden">
                <div
                  className="h-full rounded-full transition-all duration-500"
                  style={{
                    width: `${st.rate}%`,
                    backgroundColor: st.subject.color,
                  }}
                />
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
