import React, { useState } from 'react';
import { format, parseISO } from 'date-fns';
import { fr } from 'date-fns/locale';
import { BookMarked, Search, Plus, Calendar, Clock, Sparkles } from 'lucide-react';
import { Course, Revision, Subject } from '../types';

interface CoursesViewProps {
  courses: Course[];
  revisions: Revision[];
  subjects: Subject[];
  onOpenAddCourse: () => void;
  onOpenCourseDetail: (course: Course) => void;
}

export const CoursesView: React.FC<CoursesViewProps> = ({
  courses,
  revisions,
  subjects,
  onOpenAddCourse,
  onOpenCourseDetail,
}) => {
  const [search, setSearch] = useState<string>('');
  const [selectedSubjectId, setSelectedSubjectId] = useState<string>('all');

  // Filter courses
  const filteredCourses = courses.filter((c) => {
    if (selectedSubjectId !== 'all' && c.subjectId !== selectedSubjectId) return false;
    if (search.trim()) {
      const q = search.toLowerCase();
      return (
        c.title.toLowerCase().includes(q) ||
        c.chapter.toLowerCase().includes(q) ||
        `c${c.courseNumber}`.toLowerCase().includes(q)
      );
    }
    return true;
  });

  // Group by Subject then Chapter
  type ChapterGroup = {
    chapter: string;
    courses: Course[];
  };

  type SubjectGroup = {
    subject: Subject;
    chapters: ChapterGroup[];
  };

  const subjectGroups: SubjectGroup[] = [];

  subjects.forEach((subj) => {
    const subjCourses = filteredCourses.filter((c) => c.subjectId === subj.id);
    if (subjCourses.length === 0) return;

    // Group by chapter
    const chaptersMap = new Map<string, Course[]>();
    subjCourses.forEach((c) => {
      if (!chaptersMap.has(c.chapter)) chaptersMap.set(c.chapter, []);
      chaptersMap.get(c.chapter)!.push(c);
    });

    const chapters: ChapterGroup[] = Array.from(chaptersMap.entries()).map(([ch, cList]) => ({
      chapter: ch,
      courses: cList.sort((a, b) => a.courseNumber - b.courseNumber),
    }));

    subjectGroups.push({ subject: subj, chapters });
  });

  return (
    <div className="space-y-6 pb-20 md:pb-8 animate-fade-in">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-white/90 border border-slate-200 rounded-2xl p-4 md:p-6">
        <div>
          <h1 className="text-2xl font-black text-slate-900 tracking-tight flex items-center gap-2.5">
            <BookMarked className="w-6 h-6 text-sky-700" />
            Mes cours ({courses.length})
          </h1>
          <p className="text-xs text-slate-600 mt-0.5">
            Tous tes cours classés par matière et chapitre avec suivi individuel des révisions
          </p>
        </div>

        <button
          onClick={onOpenAddCourse}
          className="flex items-center justify-center gap-2 px-4 py-2 bg-violet-700 hover:bg-violet-800 text-white rounded-xl text-xs font-bold transition-all shadow-md shadow-sky-500/20"
        >
          <Plus className="w-4 h-4" />
          Nouveau cours
        </button>
      </div>

      {/* Search and Filters */}
      <div className="flex flex-col sm:flex-row gap-3">
        <div className="relative flex-1">
          <Search className="w-4 h-4 absolute left-3 top-3 text-slate-500" />
          <input
            type="text"
            placeholder="Rechercher un cours, un chapitre, C1, C14..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-9 pr-4 py-2.5 text-xs text-slate-900 placeholder-slate-500 focus:outline-none focus:border-sky-500"
          />
        </div>

        {/* Subject Filter Chips */}
        <div className="flex items-center gap-1.5 overflow-x-auto pb-1 max-w-full">
          <button
            onClick={() => setSelectedSubjectId('all')}
            className={`px-3 py-2 rounded-xl text-xs font-semibold whitespace-nowrap transition-all ${
              selectedSubjectId === 'all'
                ? 'bg-slate-100 text-slate-900 border border-slate-300'
                : 'bg-slate-50 text-slate-600 hover:text-slate-900 border border-slate-900'
            }`}
          >
            Toutes
          </button>
          {subjects.map((sub) => {
            const isSelected = selectedSubjectId === sub.id;
            return (
              <button
                key={sub.id}
                onClick={() => setSelectedSubjectId(sub.id)}
                className={`px-3 py-2 rounded-xl text-xs font-semibold whitespace-nowrap flex items-center gap-1.5 transition-all ${
                  isSelected
                    ? 'text-slate-900 border'
                    : 'bg-slate-50 text-slate-600 hover:text-slate-900 border border-slate-900'
                }`}
                style={
                  isSelected
                    ? {
                        backgroundColor: `${sub.color}20`,
                        borderColor: sub.color,
                        color: sub.color,
                      }
                    : {}
                }
              >
                <span className="w-2 h-2 rounded-full" style={{ backgroundColor: sub.color }} />
                {sub.name}
              </button>
            );
          })}
        </div>
      </div>

      {/* Courses List Grouped */}
      {subjectGroups.length === 0 ? (
        <div className="p-12 text-center rounded-2xl bg-white/40 border border-slate-200/60">
          <BookMarked className="w-12 h-12 text-slate-600 mx-auto mb-3" />
          <h3 className="text-sm font-bold text-slate-900">Aucun cours trouvé</h3>
          <p className="text-xs text-slate-600 mt-1">Essaie de modifier ta recherche ou tes filtres.</p>
        </div>
      ) : (
        <div className="space-y-6">
          {subjectGroups.map(({ subject, chapters }) => (
            <div
              key={subject.id}
              className="rounded-2xl border border-slate-200/80 bg-white/60 p-5 space-y-4"
            >
              {/* Subject Title */}
              <div className="flex items-center gap-2.5 pb-2 border-b border-slate-200">
                <span
                  className="w-3.5 h-3.5 rounded-full flex-shrink-0"
                  style={{ backgroundColor: subject.color }}
                />
                <h2 className="text-lg font-black text-slate-900 tracking-tight">{subject.name}</h2>
                <span className="text-xs text-slate-600 font-mono">
                  ({chapters.reduce((acc, ch) => acc + ch.courses.length, 0)} cours)
                </span>
              </div>

              {/* Chapters */}
              <div className="space-y-4">
                {chapters.map(({ chapter, courses: cList }) => (
                  <div key={chapter} className="space-y-2">
                    <h3 className="text-xs font-bold text-slate-600 uppercase tracking-wider pl-1">
                      Chapitre : <strong className="text-slate-800">{chapter}</strong>
                    </h3>

                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                      {cList.map((course) => {
                        const courseRevs = revisions.filter((r) => r.courseId === course.id);
                        const completedRevs = courseRevs.filter((r) => r.status === 'completed');
                        const progress =
                          courseRevs.length > 0
                            ? Math.round((completedRevs.length / courseRevs.length) * 100)
                            : 0;

                        // Next pending revision
                        const nextPending = courseRevs.find((r) => r.status === 'pending');

                        // Scores
                        const scoredRevs = courseRevs.filter((r) => r.score !== undefined);
                        const lastScore =
                          scoredRevs.length > 0
                            ? scoredRevs[scoredRevs.length - 1].score
                            : undefined;

                        return (
                          <div
                            key={course.id}
                            onClick={() => onOpenCourseDetail(course)}
                            className="p-4 rounded-xl border border-slate-200 bg-slate-50 hover:border-slate-300 cursor-pointer transition-all hover:scale-[1.01] flex flex-col justify-between"
                          >
                            <div>
                              <div className="flex items-center justify-between mb-2">
                                <span className="font-mono font-bold text-xs px-2 py-0.5 rounded bg-sky-500/10 text-sky-700 border border-sky-500/20">
                                  C{course.courseNumber}
                                </span>
                                {lastScore !== undefined && (
                                  <span className="text-[11px] font-mono font-bold text-emerald-700 flex items-center gap-1">
                                    <Sparkles className="w-3 h-3 text-amber-700" />
                                    Dernier score : {lastScore}/100
                                  </span>
                                )}
                              </div>

                              <h4 className="text-sm font-bold text-slate-900 tracking-tight line-clamp-1 mb-1">
                                {course.title}
                              </h4>

                              <div className="text-[11px] text-slate-500 flex items-center gap-1 mb-3">
                                <Calendar className="w-3 h-3" />
                                Cours du{' '}
                                {format(parseISO(course.date), 'dd/MM/yyyy', { locale: fr })}
                              </div>
                            </div>

                            {/* Progress bar & next step */}
                            <div className="pt-2 border-t border-slate-900">
                              <div className="flex items-center justify-between text-[11px] text-slate-600 mb-1">
                                <span>Progression</span>
                                <span className="font-mono font-bold text-slate-900">
                                  {completedRevs.length} / {courseRevs.length} J
                                </span>
                              </div>
                              <div className="w-full h-1.5 bg-slate-100 rounded-full overflow-hidden mb-2">
                                <div
                                  className="h-full bg-violet-700 rounded-full"
                                  style={{ width: `${progress}%` }}
                                />
                              </div>

                              {nextPending && (
                                <div className="text-[11px] text-slate-600 flex items-center justify-between">
                                  <span className="flex items-center gap-1">
                                    <Clock className="w-3 h-3 text-sky-700" />
                                    Prochain : {nextPending.stepLabel}
                                  </span>
                                  <span className="text-slate-500">
                                    {format(parseISO(nextPending.scheduledDate), 'dd/MM')}
                                  </span>
                                </div>
                              )}
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
