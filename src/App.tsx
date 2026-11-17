import React, { useState, useEffect } from 'react';
import { format } from 'date-fns';
import { Navigation, NavTab } from './components/Navigation';
import { DashboardView } from './views/DashboardView';
import { PlanningView } from './views/PlanningView';
import { CoursesView } from './views/CoursesView';
import { StatsView } from './views/StatsView';
import { SettingsView } from './views/SettingsView';
import { CompletionFireworks } from './components/CompletionFireworks';
import { ScoreModal } from './components/ScoreModal';
import { AddCourseModal } from './components/AddCourseModal';
import { CourseDetailModal } from './components/CourseDetailModal';
import { PronoteTutorialModal } from './components/PronoteTutorialModal';
import {
  initializeDefaultDataIfEmpty,
  saveCourses,
  saveRevisions,
  saveSubjects,
  savePronoteEvents,
  saveSettings,
  exportAllData,
  importAllData,
} from './lib/storage';
import { getEveningProgramme } from './lib/eveningProgramme';
import { applyAdaptiveReview, generateInitialRevisions } from './lib/adaptiveAlgorithm';
import { parseICalFeed, generateSamplePronoteFeed } from './lib/icalParser';
import { Course, Revision, Subject, PronoteEvent, AppSettings } from './types';
import { CheckCircle2, AlertCircle } from 'lucide-react';

export const App: React.FC = () => {
  const [celebration, setCelebration] = useState(0);
  const [activeTab, setActiveTab] = useState<NavTab>('dashboard');

  // Core state
  const [courses, setCourses] = useState<Course[]>([]);
  const [revisions, setRevisions] = useState<Revision[]>([]);
  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [pronoteEvents, setPronoteEvents] = useState<PronoteEvent[]>([]);
  const [settings, setSettings] = useState<AppSettings | null>(null);

  // Modals state
  const [scoreModalRev, setScoreModalRev] = useState<Revision | null>(null);
  const [isAddCourseOpen, setIsAddCourseOpen] = useState(false);
  const [addCoursePrefill, setAddCoursePrefill] = useState<{
    subjectId?: string;
    date?: string;
    title?: string;
  }>({});
  const [detailCourse, setDetailCourse] = useState<Course | null>(null);
  const [isPronoteTutorialOpen, setIsPronoteTutorialOpen] = useState(false);

  // Toast feedback state
  const [toast, setToast] = useState<{
    title: string;
    message: string;
    type: 'success' | 'info' | 'warning';
  } | null>(null);

  const showToast = (title: string, message: string, type: 'success' | 'info' | 'warning' = 'success') => {
    setToast({ title, message, type });
    setTimeout(() => setToast(null), 4500);
  };

  // Initial load
  useEffect(() => {
    const data = initializeDefaultDataIfEmpty();
    setCourses(data.courses);
    setRevisions(data.revisions);
    setSubjects(data.subjects);
    setPronoteEvents(data.pronoteEvents);
    setSettings(data.settings);
  }, []);

  const [todayStr, setTodayStr] = useState(() => format(new Date(), 'yyyy-MM-dd'));
  useEffect(() => {
    const timer = window.setInterval(() => setTodayStr(format(new Date(), 'yyyy-MM-dd')), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const eveningProgramme = getEveningProgramme(revisions, courses, todayStr);
  const pendingCountToday = eveningProgramme.due.length - eveningProgramme.overdue.length;
  const overdueCount = eveningProgramme.overdue.length;

  // HANDLER: Submit score and run adaptive algorithm
  const handleScoreSubmit = (revisionId: string, score: number) => {
    if (!settings) return;

    const targetRev = revisions.find((r) => r.id === revisionId);
    if (!targetRev || targetRev.status === 'completed') return;

    const courseRevs = revisions.filter((r) => r.courseId === targetRev.courseId);
    const result = applyAdaptiveReview(courseRevs, revisionId, score, settings, todayStr, eveningProgramme.preparationDate);

    const otherRevisions = revisions.filter((r) => r.courseId !== targetRev.courseId);

    // Collect all updated plus newly inserted (like emergency revisions)
    const newGlobalRevisions = [...otherRevisions, ...result.updatedRevisions];

    const remainingProgramme = getEveningProgramme(newGlobalRevisions, courses, todayStr);
    if (eveningProgramme.due.length > 0 && remainingProgramme.due.length === 0) {
      setCelebration(value => value + 1);
    }
    setRevisions(newGlobalRevisions);
    saveRevisions(newGlobalRevisions);

    // Show explanation toast
    showToast(
      result.explanation.actionType === 'emergency'
        ? 'Rattrapage programmé'
        : `Révision validée (${score}/100)`,
      result.explanation.message,
      result.explanation.actionType === 'emergency' ? 'warning' : 'success'
    );
  };

  // HANDLER: Add new course
  const handleAddCourse = (courseData: Omit<Course, 'id' | 'createdAt'>) => {
    if (!settings) return;

    const newId = `course-${Date.now()}`;
    const newCourse: Course = {
      ...courseData,
      id: newId,
      createdAt: format(new Date(), 'yyyy-MM-dd'),
    };

    const newRevisions = generateInitialRevisions(newCourse, settings.intervals);

    const updatedCourses = [newCourse, ...courses];
    const updatedRevisions = [...revisions, ...newRevisions];

    setCourses(updatedCourses);
    setRevisions(updatedRevisions);
    saveCourses(updatedCourses);
    saveRevisions(updatedRevisions);

    showToast(
      'Nouveau cours créé !',
      `C${newCourse.courseNumber} « ${newCourse.title} » ajouté avec ses 10 échéances de révision.`,
      'success'
    );
  };

  // HANDLER: Delete course
  const handleDeleteCourse = (courseId: string) => {
    const updatedCourses = courses.filter((c) => c.id !== courseId);
    const updatedRevisions = revisions.filter((r) => r.courseId !== courseId);

    setCourses(updatedCourses);
    setRevisions(updatedRevisions);
    saveCourses(updatedCourses);
    saveRevisions(updatedRevisions);

    showToast('Cours supprimé', 'Le cours et son calendrier ont été supprimés.', 'info');
  };

  // HANDLER: Sync Pronote (URL or sample demo)
  const handleSyncPronote = async (url: string) => {
    if (!url.trim()) {
      // Use demo feed if empty
      const sampleEvents = parseICalFeed(generateSamplePronoteFeed(), subjects);
      setPronoteEvents(sampleEvents);
      savePronoteEvents(sampleEvents);
      showToast(
        'Synchronisation Pronote effectuée',
        `${sampleEvents.length} créneaux d'emploi du temps chargés avec succès.`,
        'success'
      );
      return;
    }

    try {
      // Attempt fetch (can be blocked by CORS in pure browser without proxy)
      const res = await fetch(url);
      if (!res.ok) throw new Error('Impossible de télécharger le flux');
      const text = await res.text();
      const parsed = parseICalFeed(text, subjects);
      setPronoteEvents(parsed);
      savePronoteEvents(parsed);
      showToast(
        'Synchronisation Pronote réussie',
        `${parsed.length} cours synchronisés depuis ton lien Pronote.`,
        'success'
      );
    } catch {
      // In web browser without CORS proxy, alert user and offer fallback
      showToast(
        'Note sur le flux web Pronote',
        'Le lien a bien été enregistré. (En mode web direct, le navigateur peut bloquer le flux externe sans proxy CORS. Un exemple réaliste est actif).',
        'info'
      );
    }
  };

  // HANDLER: Use sample Pronote feed from tutorial
  const handleUseSampleDemo = () => {
    const sampleEvents = parseICalFeed(generateSamplePronoteFeed(), subjects);
    setPronoteEvents(sampleEvents);
    savePronoteEvents(sampleEvents);
    showToast(
      'Emploi du temps démo 3ème chargé',
      'Tu peux voir les cours du jour dans ton tableau de bord et les transformer en fiches de révision !',
      'success'
    );
  };

  // HANDLER: Reset demo data
  const handleResetDemoData = () => {
    localStorage.clear();
    const data = initializeDefaultDataIfEmpty();
    setCourses(data.courses);
    setRevisions(data.revisions);
    setSubjects(data.subjects);
    setPronoteEvents(data.pronoteEvents);
    setSettings(data.settings);
    showToast('Réinitialisation terminée', 'Le jeu de test complet a été rechargé.', 'info');
  };

  // HANDLER: Export JSON
  const handleExportData = () => {
    const jsonStr = exportAllData();
    const blob = new Blob([jsonStr], { type: 'application/json' });
    const href = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = href;
    a.download = `jmethod-sauvegarde-${format(new Date(), 'yyyy-MM-dd')}.json`;
    a.click();
    URL.revokeObjectURL(href);
    showToast('Export réussi', 'Fichier de sauvegarde téléchargé.', 'success');
  };

  // HANDLER: Import JSON
  const handleImportData = (jsonStr: string) => {
    const success = importAllData(jsonStr);
    if (success) {
      window.location.reload();
    } else {
      showToast('Erreur d’importation', 'Le fichier fourni est invalide.', 'warning');
    }
  };

  if (!settings) return null;

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 flex flex-col md:flex-row">
      {/* Navigation bar (Sidebar on desktop, bottom tabs on mobile) */}
      <Navigation
        activeTab={activeTab}
        onSelectTab={setActiveTab}
        pendingCountToday={pendingCountToday}
        overdueCount={overdueCount}
        onOpenAddCourse={() => {
          setAddCoursePrefill({});
          setIsAddCourseOpen(true);
        }}
      />

      {/* Main Content Area */}
      <main id="main-content" tabIndex={-1} className={`flex-1 min-w-0 md:pl-[18rem] min-h-screen p-3 sm:p-5 md:p-8 pb-28 md:pb-8 ${activeTab === 'planning' ? 'max-w-none' : 'max-w-7xl mx-auto'} w-full`}>
        {/* Floating Toast Notification */}
        {toast && (
          <div className="fixed top-16 md:top-5 left-3 right-3 md:left-auto md:right-5 z-50 max-w-sm mx-auto p-4 rounded-2xl bg-white border border-slate-300 shadow-2xl flex items-start gap-3 animate-fade-in">
            {toast.type === 'warning' ? (
              <AlertCircle className="w-5 h-5 text-amber-700 flex-shrink-0 mt-0.5" />
            ) : (
              <CheckCircle2 className="w-5 h-5 text-emerald-700 flex-shrink-0 mt-0.5" />
            )}
            <div>
              <div className="text-xs font-bold text-slate-900 mb-0.5">{toast.title}</div>
              <div className="text-xs text-slate-700 leading-relaxed">{toast.message}</div>
            </div>
          </div>
        )}

        {/* Tab Views */}
        {activeTab === 'dashboard' && (
          <DashboardView
            todayStr={todayStr}
            courses={courses}
            revisions={revisions}
            subjects={subjects}
            pronoteEvents={pronoteEvents}
            onOpenScoreModal={(rev) => setScoreModalRev(rev)}
            onOpenCourseDetail={(c) => setDetailCourse(c)}
            onOpenAddCourseForEvent={(ev) => {
              setAddCoursePrefill({
                subjectId: ev.matchedSubjectId || subjects[0]?.id,
                date: ev.date,
                title: ev.subjectName,
              });
              setIsAddCourseOpen(true);
            }}
          />
        )}

        {activeTab === 'planning' && (
          <PlanningView
            courses={courses}
            revisions={revisions}
            subjects={subjects}
            pronoteEvents={pronoteEvents}
            onValidateRevision={handleScoreSubmit}
          />
        )}

        {activeTab === 'courses' && (
          <CoursesView
            courses={courses}
            revisions={revisions}
            subjects={subjects}
            onOpenAddCourse={() => {
              setAddCoursePrefill({});
              setIsAddCourseOpen(true);
            }}
            onOpenCourseDetail={(c) => setDetailCourse(c)}
          />
        )}

        {activeTab === 'stats' && (
          <StatsView courses={courses} revisions={revisions} subjects={subjects} />
        )}

        {activeTab === 'settings' && (
          <SettingsView
            settings={settings}
            subjects={subjects}
            onUpdateSettings={(newSettings) => {
              setSettings(newSettings);
              saveSettings(newSettings);
            }}
            onUpdateSubjects={(newSubjects) => {
              setSubjects(newSubjects);
              saveSubjects(newSubjects);
            }}
            onOpenPronoteTutorial={() => setIsPronoteTutorialOpen(true)}
            onSyncPronote={handleSyncPronote}
            onExportData={handleExportData}
            onImportData={handleImportData}
            onResetDemoData={handleResetDemoData}
          />
        )}
      </main>

      <CompletionFireworks trigger={celebration}/>

      {/* MODALS */}
      {/* 1. Score Modal */}
      {scoreModalRev && (
        <ScoreModal
          isOpen={true}
          onClose={() => setScoreModalRev(null)}
          revision={scoreModalRev}
          course={courses.find((c) => c.id === scoreModalRev.courseId) || null}
          subject={
            subjects.find(
              (s) => s.id === courses.find((c) => c.id === scoreModalRev.courseId)?.subjectId
            ) || null
          }
          settings={settings}
          onSubmit={handleScoreSubmit}
        />
      )}

      {/* 2. Add Course Modal */}
      <AddCourseModal
        isOpen={isAddCourseOpen}
        onClose={() => setIsAddCourseOpen(false)}
        subjects={subjects}
        courses={courses}
        initialSubjectId={addCoursePrefill.subjectId}
        initialDate={addCoursePrefill.date}
        initialTitle={addCoursePrefill.title}
        onAddCourse={handleAddCourse}
      />

      {/* 3. Course Detail Modal */}
      {detailCourse && (
        <CourseDetailModal
          isOpen={true}
          onClose={() => setDetailCourse(null)}
          course={detailCourse}
          subject={subjects.find((s) => s.id === detailCourse.subjectId) || null}
          revisions={revisions.filter((r) => r.courseId === detailCourse.id)}
          onOpenScoreModal={(rev) => {
            setDetailCourse(null);
            setScoreModalRev(rev);
          }}
          onDeleteCourse={handleDeleteCourse}
        />
      )}

      {/* 4. Pronote Tutorial Modal */}
      <PronoteTutorialModal
        isOpen={isPronoteTutorialOpen}
        onClose={() => setIsPronoteTutorialOpen(false)}
        onUseSampleDemo={handleUseSampleDemo}
      />
    </div>
  );
};

export default App;
