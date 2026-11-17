import React, { useState, useEffect } from 'react';
import { format } from 'date-fns';
import { Plus, BookOpen, Calendar, Hash } from 'lucide-react';
import { Course, Subject } from '../types';

interface AddCourseModalProps {
  isOpen: boolean;
  onClose: () => void;
  subjects: Subject[];
  courses: Course[];
  initialSubjectId?: string;
  initialDate?: string;
  initialTitle?: string;
  onAddCourse: (courseData: Omit<Course, 'id' | 'createdAt'>) => void;
}

export const AddCourseModal: React.FC<AddCourseModalProps> = ({
  isOpen,
  onClose,
  subjects,
  courses,
  initialSubjectId,
  initialDate,
  initialTitle,
  onAddCourse,
}) => {
  const [subjectId, setSubjectId] = useState<string>(subjects[0]?.id || 'maths');
  const [chapter, setChapter] = useState<string>('');
  const [title, setTitle] = useState<string>('');
  const [date, setDate] = useState<string>(format(new Date(), 'yyyy-MM-dd'));
  const [description, setDescription] = useState<string>('');
  const [courseNumber, setCourseNumber] = useState<number>(1);

  // Sync initial props when opened
  useEffect(() => {
    if (isOpen) {
      if (initialSubjectId) setSubjectId(initialSubjectId);
      if (initialDate) setDate(initialDate);
      if (initialTitle) setTitle(initialTitle);
      else setTitle('');
      setDescription('');
    }
  }, [isOpen, initialSubjectId, initialDate, initialTitle]);

  // Existing chapters for this subject
  const subjectCourses = courses.filter((c) => c.subjectId === subjectId);
  const existingChapters = Array.from(new Set(subjectCourses.map((c) => c.chapter)));

  // Auto-calculate next courseNumber when chapter or subject changes
  useEffect(() => {
    if (!chapter.trim()) {
      setCourseNumber(1);
      return;
    }

    const matches = courses.filter(
      (c) =>
        c.subjectId === subjectId &&
        c.chapter.trim().toLowerCase() === chapter.trim().toLowerCase()
    );

    if (matches.length === 0) {
      setCourseNumber(1);
    } else {
      const maxNum = Math.max(...matches.map((c) => c.courseNumber), 0);
      setCourseNumber(maxNum + 1);
    }
  }, [subjectId, chapter, courses]);

  if (!isOpen) return null;

  const currentSubject = subjects.find((s) => s.id === subjectId) || subjects[0];

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!title.trim() || !chapter.trim()) return;

    onAddCourse({
      subjectId,
      chapter: chapter.trim(),
      courseNumber,
      title: title.trim(),
      date,
      description: description.trim() || undefined,
    });

    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4 bg-slate-950/60 backdrop-blur-md animate-fade-in">
      <div className="relative w-full max-w-lg bg-white border-t sm:border border-slate-200 rounded-t-3xl sm:rounded-2xl shadow-2xl p-5 sm:p-6 overflow-hidden max-h-[92vh] overflow-y-auto pb-8 sm:pb-6">
        {/* Mobile drag handle */}
        <div className="w-12 h-1 bg-slate-200 rounded-full mx-auto mb-4 sm:hidden opacity-60" />
        {/* Glow accent */}
        <div
          className="absolute -top-20 -right-20 w-44 h-44 rounded-full blur-3xl opacity-20 pointer-events-none"
          style={{ backgroundColor: currentSubject?.color || '#38BDF8' }}
        />

        {/* Modal Title */}
        <div className="flex items-center justify-between mb-5">
          <div className="flex items-center gap-2.5">
            <div className="p-2 bg-sky-500/10 border border-sky-500/20 rounded-xl text-sky-700">
              <Plus className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-lg font-bold text-slate-900">Ajouter un nouveau cours</h3>
              <p className="text-xs text-slate-600">Génère automatiquement les 10 échéances de révision</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-slate-600 hover:text-slate-900 p-1 rounded-lg hover:bg-slate-100 transition-colors"
          >
            ✕
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Matière */}
          <div>
            <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-2">
              Matière
            </label>
            <div className="flex flex-wrap gap-1.5 max-h-36 overflow-y-auto p-1 bg-slate-50/50 rounded-xl border border-slate-200/80">
              {subjects.map((sub) => {
                const isSelected = sub.id === subjectId;
                return (
                  <button
                    key={sub.id}
                    type="button"
                    onClick={() => setSubjectId(sub.id)}
                    className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-all flex items-center gap-1.5 ${
                      isSelected
                        ? 'text-slate-900 shadow-md'
                        : 'text-slate-600 hover:text-slate-800 hover:bg-slate-100/60'
                    }`}
                    style={
                      isSelected
                        ? {
                            backgroundColor: `${sub.color}30`,
                            border: `1px solid ${sub.color}`,
                            color: sub.color,
                          }
                        : {}
                    }
                  >
                    <span
                      className="w-2 h-2 rounded-full flex-shrink-0"
                      style={{ backgroundColor: sub.color }}
                    />
                    {sub.name}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Chapitre & Course Number */}
          <div className="grid grid-cols-3 gap-3">
            <div className="col-span-2">
              <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                Chapitre
              </label>
              <div className="relative">
                <input
                  type="text"
                  required
                  placeholder="ex: Arithmétique, Guerre Froide..."
                  value={chapter}
                  onChange={(e) => setChapter(e.target.value)}
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl px-3.5 py-2 text-sm text-slate-900 placeholder-slate-500 focus:outline-none focus:border-sky-500"
                />
              </div>

              {/* Suggestions from existing chapters */}
              {existingChapters.length > 0 && !chapter && (
                <div className="flex flex-wrap gap-1 mt-1.5">
                  <span className="text-[10px] text-slate-500">Chapitres existants :</span>
                  {existingChapters.slice(0, 3).map((ch) => (
                    <button
                      key={ch}
                      type="button"
                      onClick={() => setChapter(ch)}
                      className="text-[10px] px-1.5 py-0.5 rounded bg-slate-100 text-slate-700 hover:bg-slate-200"
                    >
                      {ch}
                    </button>
                  ))}
                </div>
              )}
            </div>

            <div>
              <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                Numéro
              </label>
              <div className="flex items-center justify-center bg-slate-50 border border-slate-200 rounded-xl px-3 py-2 text-sm font-mono font-bold text-sky-700">
                <Hash className="w-3.5 h-3.5 mr-0.5 text-slate-500" />
                C{courseNumber}
              </div>
              <span className="text-[10px] text-slate-500 text-center block mt-1">Attribué auto</span>
            </div>
          </div>

          {/* Titre du cours */}
          <div>
            <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
              Titre de la leçon
            </label>
            <div className="relative">
              <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-slate-500">
                <BookOpen className="w-4 h-4" />
              </div>
              <input
                type="text"
                required
                placeholder="ex: Algorithme d'Euclide & PGCD"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-9 pr-3.5 py-2 text-sm text-slate-900 placeholder-slate-500 focus:outline-none focus:border-sky-500"
              />
            </div>
          </div>

          {/* Date du cours */}
          <div>
            <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
              Date du cours (J0)
            </label>
            <div className="relative">
              <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-slate-500">
                <Calendar className="w-4 h-4" />
              </div>
              <input
                type="date"
                required
                value={date}
                onChange={(e) => setDate(e.target.value)}
                className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-9 pr-3.5 py-2 text-sm text-slate-900 focus:outline-none focus:border-sky-500"
              />
            </div>
            <p className="text-[11px] text-slate-600 mt-1">
              La première révision (Encodage J0) est programmée ce jour-là.
            </p>
          </div>

          {/* Description facultative */}
          <div>
            <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
              Notes ou notions clés (Facultatif)
            </label>
            <div className="relative">
              <textarea
                rows={2}
                placeholder="ex: Définitions p. 45, exercices 12 et 14..."
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                className="w-full bg-slate-50 border border-slate-200 rounded-xl px-3 py-2 text-sm text-slate-900 placeholder-slate-500 focus:outline-none focus:border-sky-500 resize-none"
              />
            </div>
          </div>

          {/* Submit buttons */}
          <div className="flex items-center justify-end gap-3 pt-3 border-t border-slate-200">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm font-medium text-slate-600 hover:text-slate-900 hover:bg-slate-100 rounded-xl transition-colors"
            >
              Annuler
            </button>
            <button
              type="submit"
              className="flex items-center gap-2 px-5 py-2.5 text-sm font-semibold text-white bg-violet-700 hover:bg-violet-800 rounded-xl shadow-lg shadow-sky-500/20 transition-all hover:scale-[1.02] active:scale-[0.98]"
            >
              <Plus className="w-4 h-4" />
              Créer le cours C{courseNumber}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
