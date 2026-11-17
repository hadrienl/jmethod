import React, { useState } from 'react';
import {
  format,
  startOfWeek,
  endOfWeek,
  eachDayOfInterval,
  addWeeks,
  subWeeks,
  isSameDay,
  startOfMonth,
  endOfMonth,
  addMonths,
  subMonths,
} from 'date-fns';
import { fr } from 'date-fns/locale';
import {
  ChevronLeft,
  ChevronRight,
  Filter,
  Calendar as CalendarIcon,
  CheckCircle2,
} from 'lucide-react';
import { Course, Revision, Subject, Phase, PronoteEvent } from '../types';
import { PHASE_CONFIG } from '../lib/constants';

interface PlanningViewProps {
  courses: Course[];
  revisions: Revision[];
  subjects: Subject[];
  pronoteEvents: PronoteEvent[];
  onOpenScoreModal: (revision: Revision) => void;
  onOpenCourseDetail: (course: Course) => void;
}

type ViewMode = 'week' | 'month' | 'day';

export const PlanningView: React.FC<PlanningViewProps> = ({
  courses,
  revisions,
  subjects,
  pronoteEvents,
  onOpenScoreModal,
  onOpenCourseDetail,
}) => {
  const [viewMode, setViewMode] = useState<ViewMode>('week');
  const [currentDate, setCurrentDate] = useState<Date>(new Date());
  const [selectedDay, setSelectedDay] = useState<Date>(new Date());
  const [selectedSubjectId, setSelectedSubjectId] = useState<string>('all');
  const [selectedPhase, setSelectedPhase] = useState<Phase | 'all'>('all');

  const coursesMap = new Map(courses.map((c) => [c.id, c]));
  const subjectsMap = new Map(subjects.map((s) => [s.id, s]));

  // Navigation handlers
  const handlePrev = () => {
    if (viewMode === 'week') {
      setCurrentDate((d) => subWeeks(d, 1));
      setSelectedDay((d) => subWeeks(d, 1));
    } else if (viewMode === 'month') {
      setCurrentDate((d) => subMonths(d, 1));
    } else {
      const prev = new Date(currentDate.getTime() - 86400000);
      setCurrentDate(prev);
      setSelectedDay(prev);
    }
  };

  const handleNext = () => {
    if (viewMode === 'week') {
      setCurrentDate((d) => addWeeks(d, 1));
      setSelectedDay((d) => addWeeks(d, 1));
    } else if (viewMode === 'month') {
      setCurrentDate((d) => addMonths(d, 1));
    } else {
      const next = new Date(currentDate.getTime() + 86400000);
      setCurrentDate(next);
      setSelectedDay(next);
    }
  };

  const handleToday = () => {
    const today = new Date();
    setCurrentDate(today);
    setSelectedDay(today);
  };

  // Filter revisions
  const filteredRevisions = revisions.filter((rev) => {
    if (selectedPhase !== 'all' && rev.phase !== selectedPhase) return false;
    if (selectedSubjectId !== 'all') {
      const course = coursesMap.get(rev.courseId);
      if (!course || course.subjectId !== selectedSubjectId) return false;
    }
    return true;
  });

  // Week days
  const weekStart = startOfWeek(currentDate, { weekStartsOn: 1 });
  const weekEnd = endOfWeek(currentDate, { weekStartsOn: 1 });
  const weekDays = eachDayOfInterval({ start: weekStart, end: weekEnd });

  // Month days
  const monthStart = startOfMonth(currentDate);
  const monthEnd = endOfMonth(currentDate);
  const monthDays = eachDayOfInterval({
    start: startOfWeek(monthStart, { weekStartsOn: 1 }),
    end: endOfWeek(monthEnd, { weekStartsOn: 1 }),
  });

  const selectedDayStr = format(selectedDay, 'yyyy-MM-dd');
  const selectedDayRevisions = filteredRevisions.filter((r) => r.scheduledDate === selectedDayStr);
  const selectedDayPronote = pronoteEvents.filter((ev) => ev.date === selectedDayStr);

  return (
    <div className="space-y-4 sm:space-y-6 animate-fade-in">
      {/* ================= HEADER & CONTROLS ================= */}
      <div className="bg-white/90 border border-slate-200 rounded-2xl p-4 sm:p-5 space-y-3">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div>
            <h1 className="text-xl sm:text-2xl font-black text-slate-900 tracking-tight flex items-center gap-2">
              <CalendarIcon className="w-5 h-5 text-sky-700" />
              <span>Planning</span>
            </h1>
            <p className="text-[11px] sm:text-xs text-slate-600">
              Visualisation temporelle et emploi du temps
            </p>
          </div>

          {/* Mode switch & Navigation arrows */}
          <div className="flex items-center justify-between sm:justify-end gap-2">
            {/* View selector */}
            <div className="flex bg-slate-50 p-1 rounded-xl border border-slate-200">
              {(['week', 'month', 'day'] as ViewMode[]).map((mode) => (
                <button
                  key={mode}
                  onClick={() => setViewMode(mode)}
                  className={`px-2.5 sm:px-3 py-1.5 rounded-lg text-xs font-semibold capitalize transition-all ${
                    viewMode === mode
                      ? 'bg-slate-100 text-slate-900 shadow-sm'
                      : 'text-slate-600 hover:text-slate-800'
                  }`}
                >
                  {mode === 'day' ? 'Jour' : mode === 'week' ? 'Semaine' : 'Mois'}
                </button>
              ))}
            </div>

            {/* Nav Arrows */}
            <div className="flex items-center gap-1 bg-slate-50 p-1 rounded-xl border border-slate-200">
              <button
                onClick={handlePrev}
                className="p-1.5 hover:bg-slate-100 rounded-lg text-slate-600 hover:text-slate-900"
              >
                <ChevronLeft className="w-4 h-4" />
              </button>
              <button
                onClick={handleToday}
                className="px-2 py-1 text-[11px] font-bold text-sky-700 hover:bg-slate-100 rounded-lg"
              >
                Auj.
              </button>
              <button
                onClick={handleNext}
                className="p-1.5 hover:bg-slate-100 rounded-lg text-slate-600 hover:text-slate-900"
              >
                <ChevronRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        </div>

        {/* Filters */}
        <div className="flex flex-wrap items-center gap-2 pt-2 border-t border-slate-200/80">
          <div className="flex items-center gap-1.5 flex-1 min-w-[140px]">
            <Filter className="w-3.5 h-3.5 text-slate-600 flex-shrink-0" />
            <select
              value={selectedSubjectId}
              onChange={(e) => setSelectedSubjectId(e.target.value)}
              className="w-full bg-slate-50 border border-slate-200 text-slate-900 text-xs rounded-lg px-2.5 py-1.5 focus:outline-none focus:border-sky-500"
            >
              <option value="all">Toutes les matières</option>
              {subjects.map((sub) => (
                <option key={sub.id} value={sub.id}>
                  {sub.name}
                </option>
              ))}
            </select>
          </div>

          <div className="flex items-center gap-1 overflow-x-auto pb-0.5">
            <button
              onClick={() => setSelectedPhase('all')}
              className={`px-2 py-1 rounded-lg text-xs font-semibold whitespace-nowrap ${
                selectedPhase === 'all'
                  ? 'bg-slate-100 text-slate-900 border border-slate-300'
                  : 'text-slate-600'
              }`}
            >
              Toutes
            </button>
            {(['encodage', 'consolidation', 'recuperation'] as Phase[]).map((phase) => {
              const cfg = PHASE_CONFIG[phase];
              const isSelected = selectedPhase === phase;
              return (
                <button
                  key={phase}
                  onClick={() => setSelectedPhase(phase)}
                  className={`px-2 py-1 rounded-lg text-xs font-semibold whitespace-nowrap flex items-center gap-1 ${
                    isSelected ? `text-slate-900 border ${cfg.border} ${cfg.bgLight}` : 'text-slate-600'
                  }`}
                >
                  <span className={`w-1.5 h-1.5 rounded-full ${cfg.dot}`} />
                  {cfg.label}
                </button>
              );
            })}
          </div>
        </div>
      </div>

      {/* ================= MOBILE-FIRST WEEK STRIP (FOR SCREENS < MD) ================= */}
      {viewMode === 'week' && (
        <div className="block md:hidden space-y-3">
          {/* 7 Days selector bar */}
          <div className="grid grid-cols-7 gap-1 bg-white/80 border border-slate-200 p-1.5 rounded-2xl">
            {weekDays.map((day) => {
              const dayStr = format(day, 'yyyy-MM-dd');
              const isToday = isSameDay(day, new Date());
              const isSelected = isSameDay(day, selectedDay);
              const count = filteredRevisions.filter((r) => r.scheduledDate === dayStr).length;

              return (
                <button
                  key={dayStr}
                  onClick={() => setSelectedDay(day)}
                  className={`flex flex-col items-center py-2 px-1 rounded-xl transition-all ${
                    isSelected
                      ? 'bg-violet-700 text-white font-bold shadow-md shadow-sky-500/30'
                      : isToday
                      ? 'bg-slate-100 text-sky-700 font-semibold'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  <span className="text-[10px] uppercase font-bold tracking-wider">
                    {format(day, 'EEE', { locale: fr })}
                  </span>
                  <span className="text-sm font-black font-mono mt-0.5">{format(day, 'd')}</span>
                  {count > 0 && (
                    <span
                      className={`w-1.5 h-1.5 rounded-full mt-1 ${
                        isSelected ? 'bg-white' : 'bg-sky-400'
                      }`}
                    />
                  )}
                </button>
              );
            })}
          </div>

          {/* Selected Day Agenda */}
          <div className="rounded-2xl border border-slate-200 bg-white/90 p-4 space-y-3">
            <div className="flex items-center justify-between pb-2 border-b border-slate-200">
              <span className="text-xs font-bold uppercase tracking-wider text-slate-700">
                {format(selectedDay, 'EEEE d MMMM yyyy', { locale: fr })}
              </span>
              <span className="text-xs font-mono font-bold text-sky-700">
                {selectedDayRevisions.length} révision(s)
              </span>
            </div>

            {/* Pronote classes for this day */}
            {selectedDayPronote.length > 0 && (
              <div className="space-y-1 mb-3">
                <span className="text-[10px] uppercase font-bold text-slate-600">
                  Cours Pronote :
                </span>
                <div className="grid grid-cols-2 gap-1.5">
                  {selectedDayPronote.map((ev) => (
                    <div
                      key={ev.id}
                      className="p-2 rounded-lg bg-slate-50 border border-slate-200 text-[11px] truncate"
                    >
                      <div className="font-mono text-slate-600 text-[10px]">
                        {ev.startTime} - {ev.endTime}
                      </div>
                      <div className="font-bold text-slate-900 truncate">{ev.subjectName}</div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Revisions list */}
            {selectedDayRevisions.length === 0 ? (
              <div className="text-center py-6 text-xs text-slate-500 italic">
                Aucune révision programmée pour ce jour.
              </div>
            ) : (
              <div className="space-y-2">
                {selectedDayRevisions.map((rev) => {
                  const course = coursesMap.get(rev.courseId);
                  const subject = course ? subjectsMap.get(course.subjectId) : null;
                  const phaseCfg = PHASE_CONFIG[rev.phase];
                  const isCompleted = rev.status === 'completed';

                  return (
                    <div
                      key={rev.id}
                      onClick={() => (course ? onOpenCourseDetail(course) : null)}
                      className="p-3 rounded-xl border border-slate-200 bg-slate-50 flex items-center justify-between"
                    >
                      <div className="flex items-center gap-2.5 min-w-0 pr-2">
                        <span
                          className="w-2 h-2 rounded-full flex-shrink-0"
                          style={{ backgroundColor: subject?.color || '#38BDF8' }}
                        />
                        <div className="min-w-0">
                          <div className="flex items-center gap-1.5">
                            <span className="font-mono font-bold text-xs text-sky-700">
                              C{course?.courseNumber}
                            </span>
                            <span
                              className={`text-[9px] font-mono px-1 rounded ${phaseCfg.bgLight} ${phaseCfg.text}`}
                            >
                              {rev.stepLabel}
                            </span>
                          </div>
                          <div className="text-xs font-bold text-slate-900 truncate">
                            {course?.title}
                          </div>
                        </div>
                      </div>

                      <div className="flex-shrink-0">
                        {isCompleted ? (
                          <span className="text-xs font-mono font-bold text-emerald-700 flex items-center gap-1">
                            <CheckCircle2 className="w-3.5 h-3.5" />
                            {rev.score}/100
                          </span>
                        ) : (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              onOpenScoreModal(rev);
                            }}
                            className="px-3 py-1.5 bg-violet-700 text-white rounded-lg text-xs font-bold"
                          >
                            Valider
                          </button>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      )}

      {/* ================= DESKTOP 7-COLUMNS WEEK VIEW (SCREENS >= MD) ================= */}
      {viewMode === 'week' && (
        <div className="hidden md:grid grid-cols-7 gap-3">
          {weekDays.map((day) => {
            const dayStr = format(day, 'yyyy-MM-dd');
            const isToday = isSameDay(day, new Date());
            const dayRevs = filteredRevisions.filter((r) => r.scheduledDate === dayStr);
            const dayPronote = pronoteEvents.filter((ev) => ev.date === dayStr);

            return (
              <div
                key={dayStr}
                className={`rounded-xl border p-3 min-h-[260px] flex flex-col justify-between transition-all ${
                  isToday
                    ? 'bg-white/90 border-sky-500/50 shadow-md ring-1 ring-sky-500/20'
                    : 'bg-slate-50/70 border-slate-200/80 hover:border-slate-300'
                }`}
              >
                <div>
                  <div className="flex items-center justify-between pb-2 mb-2 border-b border-slate-200">
                    <div>
                      <span className="text-[11px] font-bold uppercase tracking-wider text-slate-600 block">
                        {format(day, 'EEE', { locale: fr })}
                      </span>
                      <span
                        className={`text-base font-black font-mono ${
                          isToday ? 'text-sky-700' : 'text-slate-900'
                        }`}
                      >
                        {format(day, 'd')}
                      </span>
                    </div>
                    {dayRevs.length > 0 && (
                      <span className="text-[10px] font-bold px-1.5 py-0.5 rounded-full bg-slate-100 text-slate-700">
                        {dayRevs.length}
                      </span>
                    )}
                  </div>

                  {dayPronote.length > 0 && (
                    <div className="mb-2 space-y-1">
                      {dayPronote.slice(0, 2).map((ev) => (
                        <div
                          key={ev.id}
                          className="text-[10px] truncate px-1.5 py-0.5 rounded bg-white border border-slate-200 text-slate-600"
                        >
                          {ev.startTime} • {ev.subjectName}
                        </div>
                      ))}
                    </div>
                  )}

                  <div className="space-y-1.5">
                    {dayRevs.length === 0 ? (
                      <div className="text-[11px] text-slate-600 text-center py-4">Rien prévu</div>
                    ) : (
                      dayRevs.map((rev) => {
                        const course = coursesMap.get(rev.courseId);
                        const subject = course ? subjectsMap.get(course.subjectId) : null;
                        const phaseCfg = PHASE_CONFIG[rev.phase];
                        const isCompleted = rev.status === 'completed';

                        return (
                          <div
                            key={rev.id}
                            className={`p-2 rounded-lg border text-xs cursor-pointer transition-all ${
                              isCompleted
                                ? 'bg-white/40 border-slate-200 text-slate-500'
                                : 'bg-white border-slate-300/80 text-slate-800 hover:border-slate-500'
                            }`}
                            onClick={() => (course ? onOpenCourseDetail(course) : null)}
                          >
                            <div className="flex items-center justify-between mb-1">
                              <span
                                className="w-2 h-2 rounded-full"
                                style={{ backgroundColor: subject?.color || '#38BDF8' }}
                              />
                              <span
                                className={`text-[10px] font-mono px-1 rounded ${phaseCfg.bgLight} ${phaseCfg.text}`}
                              >
                                {rev.stepLabel}
                              </span>
                            </div>
                            <div className="font-bold text-slate-900 truncate text-[11px]">
                              {course ? `C${course.courseNumber} ${course.title}` : 'Cours'}
                            </div>

                            <div className="flex items-center justify-end mt-1">
                              {isCompleted ? (
                                <span className="text-[10px] font-mono text-emerald-700 font-bold flex items-center gap-0.5">
                                  <CheckCircle2 className="w-3 h-3" />
                                  {rev.score}/100
                                </span>
                              ) : (
                                <button
                                  type="button"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    onOpenScoreModal(rev);
                                  }}
                                  className="text-[10px] text-sky-700 hover:text-slate-900 font-semibold"
                                >
                                  Valider
                                </button>
                              )}
                            </div>
                          </div>
                        );
                      })
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* ================= MONTH VIEW ================= */}
      {viewMode === 'month' && (
        <div className="rounded-2xl border border-slate-200 bg-slate-50/80 p-3 sm:p-4">
          <div className="grid grid-cols-7 gap-1 text-center font-bold text-[10px] sm:text-xs uppercase text-slate-500 pb-2 mb-2 border-b border-slate-200">
            {['Lun', 'Mar', 'Mer', 'Jeu', 'Ven', 'Sam', 'Dim'].map((d) => (
              <div key={d}>{d}</div>
            ))}
          </div>

          <div className="grid grid-cols-7 gap-1">
            {monthDays.map((day) => {
              const dayStr = format(day, 'yyyy-MM-dd');
              const isToday = isSameDay(day, new Date());
              const dayRevs = filteredRevisions.filter((r) => r.scheduledDate === dayStr);

              return (
                <div
                  key={dayStr}
                  className={`min-h-[55px] sm:min-h-[75px] p-1 sm:p-1.5 rounded-lg border text-left flex flex-col justify-between ${
                    isToday
                      ? 'bg-sky-500/10 border-sky-500/40 text-slate-900'
                      : 'bg-white/40 border-slate-200/60 text-slate-700'
                  }`}
                >
                  <div className="flex items-center justify-between text-[10px] sm:text-[11px] font-mono">
                    <span className={isToday ? 'font-bold text-sky-700' : 'text-slate-600'}>
                      {format(day, 'd')}
                    </span>
                    {dayRevs.length > 0 && (
                      <span className="text-[9px] sm:text-[10px] font-bold text-sky-700">
                        {dayRevs.length}
                      </span>
                    )}
                  </div>

                  <div className="flex flex-wrap gap-0.5 sm:gap-1 mt-1">
                    {dayRevs.slice(0, 3).map((r) => {
                      const c = coursesMap.get(r.courseId);
                      const s = c ? subjectsMap.get(c.subjectId) : null;
                      return (
                        <span
                          key={r.id}
                          className="w-1.5 h-1.5 sm:w-2 sm:h-2 rounded-full"
                          style={{ backgroundColor: s?.color || '#38BDF8' }}
                        />
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* ================= DAY VIEW ================= */}
      {viewMode === 'day' && (
        <div className="rounded-2xl border border-slate-200 bg-white/80 p-4 sm:p-6 space-y-3">
          <h3 className="text-sm sm:text-base font-bold text-slate-900 capitalize">
            {format(currentDate, 'EEEE d MMMM yyyy', { locale: fr })}
          </h3>

          {filteredRevisions.filter(
            (r) => r.scheduledDate === format(currentDate, 'yyyy-MM-dd')
          ).length === 0 ? (
            <p className="text-xs text-slate-600 italic">Aucune révision pour cette journée.</p>
          ) : (
            <div className="space-y-2">
              {filteredRevisions
                .filter((r) => r.scheduledDate === format(currentDate, 'yyyy-MM-dd'))
                .map((rev) => {
                  const course = coursesMap.get(rev.courseId);
                  const subject = course ? subjectsMap.get(course.subjectId) : null;
                  const phaseCfg = PHASE_CONFIG[rev.phase];

                  return (
                    <div
                      key={rev.id}
                      className="p-3 rounded-xl border border-slate-200 bg-slate-50 flex items-center justify-between"
                    >
                      <div className="flex items-center gap-2.5 min-w-0 pr-2">
                        <span
                          className="w-2.5 h-2.5 rounded-full flex-shrink-0"
                          style={{ backgroundColor: subject?.color || '#38BDF8' }}
                        />
                        <div className="min-w-0">
                          <div className="text-xs sm:text-sm font-bold text-slate-900 truncate">
                            {course ? `C${course.courseNumber} : ${course.title}` : 'Cours'}
                          </div>
                          <div className="text-[11px] text-slate-600 truncate">
                            {subject?.name} • {course?.chapter}
                          </div>
                        </div>
                      </div>

                      <div className="flex items-center gap-2 flex-shrink-0">
                        <span
                          className={`text-[10px] sm:text-xs px-2 py-0.5 rounded-md font-mono font-bold ${phaseCfg.bgLight} ${phaseCfg.text} border ${phaseCfg.border}`}
                        >
                          {rev.stepLabel}
                        </span>
                        {rev.status === 'completed' ? (
                          <span className="text-emerald-700 font-mono font-bold text-xs sm:text-sm">
                            {rev.score}/100
                          </span>
                        ) : (
                          <button
                            onClick={() => onOpenScoreModal(rev)}
                            className="px-3 py-1.5 bg-violet-700 hover:bg-violet-800 active:scale-95 text-white rounded-lg text-xs font-semibold"
                          >
                            Valider
                          </button>
                        )}
                      </div>
                    </div>
                  );
                })}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
