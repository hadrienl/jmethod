import { format, subDays } from 'date-fns';
import { Course, Revision, Subject, PronoteEvent, AppSettings } from '../types';
import { DEFAULT_SUBJECTS, DEFAULT_SETTINGS } from './constants';
import { generateInitialRevisions } from './adaptiveAlgorithm';
import { parseICalFeed, generateSamplePronoteFeed } from './icalParser';

const STORAGE_KEYS = {
  COURSES: 'jmethod_courses_v1',
  REVISIONS: 'jmethod_revisions_v1',
  SUBJECTS: 'jmethod_subjects_v1',
  PRONOTE_EVENTS: 'jmethod_pronote_events_v1',
  SETTINGS: 'jmethod_settings_v1',
  INITIALIZED: 'jmethod_initialized_v1',
};

export function loadSettings(): AppSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.SETTINGS);
    return raw ? { ...DEFAULT_SETTINGS, ...JSON.parse(raw) } : DEFAULT_SETTINGS;
  } catch {
    return DEFAULT_SETTINGS;
  }
}

export function saveSettings(settings: AppSettings): void {
  localStorage.setItem(STORAGE_KEYS.SETTINGS, JSON.stringify(settings));
}

export function loadSubjects(): Subject[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.SUBJECTS);
    return raw ? JSON.parse(raw) : DEFAULT_SUBJECTS;
  } catch {
    return DEFAULT_SUBJECTS;
  }
}

export function saveSubjects(subjects: Subject[]): void {
  localStorage.setItem(STORAGE_KEYS.SUBJECTS, JSON.stringify(subjects));
}

export function loadCourses(): Course[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.COURSES);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export function saveCourses(courses: Course[]): void {
  localStorage.setItem(STORAGE_KEYS.COURSES, JSON.stringify(courses));
}

export function loadRevisions(): Revision[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.REVISIONS);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export function saveRevisions(revisions: Revision[]): void {
  localStorage.setItem(STORAGE_KEYS.REVISIONS, JSON.stringify(revisions));
}

export function loadPronoteEvents(): PronoteEvent[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.PRONOTE_EVENTS);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export function savePronoteEvents(events: PronoteEvent[]): void {
  localStorage.setItem(STORAGE_KEYS.PRONOTE_EVENTS, JSON.stringify(events));
}

/**
 * Generates rich initial mock data directly matching the user's specification (Section 6 & 7)
 */
export function initializeDefaultDataIfEmpty(): {
  courses: Course[];
  revisions: Revision[];
  subjects: Subject[];
  pronoteEvents: PronoteEvent[];
  settings: AppSettings;
} {
  const isInitialized = localStorage.getItem(STORAGE_KEYS.INITIALIZED);
  if (isInitialized) {
    return {
      courses: loadCourses(),
      revisions: loadRevisions(),
      subjects: loadSubjects(),
      pronoteEvents: loadPronoteEvents(),
      settings: loadSettings(),
    };
  }

  const today = new Date();
  const todayStr = format(today, 'yyyy-MM-dd');
  const yesterdayStr = format(subDays(today, 1), 'yyyy-MM-dd');
  const twoDaysAgoStr = format(subDays(today, 2), 'yyyy-MM-dd');
  const sevenDaysAgoStr = format(subDays(today, 7), 'yyyy-MM-dd');
  const fourteenDaysAgoStr = format(subDays(today, 14), 'yyyy-MM-dd');
  const twentyFiveDaysAgoStr = format(subDays(today, 25), 'yyyy-MM-dd');

  const demoCourses: Course[] = [
    // Mathématiques - Arithmétique (C1 to C16 like in user's prompt example)
    {
      id: 'course-maths-1',
      subjectId: 'maths',
      chapter: 'Arithmétique',
      courseNumber: 1,
      title: 'Division euclidienne & divisibilité',
      date: twentyFiveDaysAgoStr,
      description: 'Définition, quotient, reste et critères de divisibilité par 2, 3, 5, 9.',
      createdAt: twentyFiveDaysAgoStr,
    },
    {
      id: 'course-maths-2',
      subjectId: 'maths',
      chapter: 'Arithmétique',
      courseNumber: 2,
      title: 'Nombres premiers & décomposition',
      date: fourteenDaysAgoStr,
      description: 'Liste des nombres premiers < 30, algorithme de décomposition en facteurs.',
      createdAt: fourteenDaysAgoStr,
    },
    {
      id: 'course-maths-3',
      subjectId: 'maths',
      chapter: 'Arithmétique',
      courseNumber: 3,
      title: 'Fractions irréductibles',
      date: sevenDaysAgoStr,
      description: 'Simplification de fractions par décomposition en facteurs premiers.',
      createdAt: sevenDaysAgoStr,
    },
    {
      id: 'course-maths-5',
      subjectId: 'maths',
      chapter: 'Arithmétique',
      courseNumber: 5,
      title: 'Problèmes de PGCD dans la vie réelle',
      date: twoDaysAgoStr,
      description: 'Découpage de dalles et composition de paquets identiques.',
      createdAt: twoDaysAgoStr,
    },
    {
      id: 'course-maths-14',
      subjectId: 'maths',
      chapter: 'Arithmétique',
      courseNumber: 14,
      title: 'Algorithme d’Euclide avancé',
      date: todayStr,
      description: 'Méthode des soustractions successives et divisions en chaîne.',
      createdAt: todayStr,
    },

    // Physique-chimie - Poids et Masse (C1, C2, C4)
    {
      id: 'course-pc-1',
      subjectId: 'physique-chimie',
      chapter: 'Poids et Masse',
      courseNumber: 1,
      title: 'Distinction entre masse et poids',
      date: sevenDaysAgoStr,
      description: 'Relation P = m × g, unités kg et Newton.',
      createdAt: sevenDaysAgoStr,
    },
    {
      id: 'course-pc-2',
      subjectId: 'physique-chimie',
      chapter: 'Poids et Masse',
      courseNumber: 2,
      title: 'Mesure avec dynamomètre',
      date: yesterdayStr,
      description: 'Utilisation du dynamomètre et étalonnage du ressort.',
      createdAt: yesterdayStr,
    },
    {
      id: 'course-pc-4',
      subjectId: 'physique-chimie',
      chapter: 'Poids et Masse',
      courseNumber: 4,
      title: 'Gravitation universelle et astres',
      date: todayStr,
      description: 'Comparaison de g sur la Terre et sur la Lune.',
      createdAt: todayStr,
    },

    // Français - Grammaire (C3, C4)
    {
      id: 'course-fr-3',
      subjectId: 'francais',
      chapter: 'Grammaire',
      courseNumber: 3,
      title: 'Les propositions subordonnées relatives',
      date: twoDaysAgoStr,
      description: 'Pronoms relatifs simples et composés, antécédent.',
      createdAt: twoDaysAgoStr,
    },
  ];

  // Generate revisions for all demo courses
  let allRevisions: Revision[] = [];
  for (const c of demoCourses) {
    const revs = generateInitialRevisions(c, DEFAULT_SETTINGS.intervals);
    allRevisions = allRevisions.concat(revs);
  }

  // Pre-complete some past revisions to give realistic history and scores
  allRevisions = allRevisions.map((rev) => {
    // If revision date is in past before today
    if (rev.scheduledDate < todayStr) {
      // Simulate completed with good score
      return {
        ...rev,
        status: 'completed',
        completedDate: rev.scheduledDate,
        score: Math.floor(65 + Math.random() * 25), // 65 to 90
      };
    }
    // If today: pending
    if (rev.scheduledDate === todayStr) {
      return {
        ...rev,
        status: 'pending',
      };
    }
    // Future: pending
    return rev;
  });

  // Parse sample Pronote events
  const pronoteEvents = parseICalFeed(generateSamplePronoteFeed(), DEFAULT_SUBJECTS);

  // Save everything
  saveCourses(demoCourses);
  saveRevisions(allRevisions);
  saveSubjects(DEFAULT_SUBJECTS);
  savePronoteEvents(pronoteEvents);
  saveSettings(DEFAULT_SETTINGS);
  localStorage.setItem(STORAGE_KEYS.INITIALIZED, 'true');

  return {
    courses: demoCourses,
    revisions: allRevisions,
    subjects: DEFAULT_SUBJECTS,
    pronoteEvents,
    settings: DEFAULT_SETTINGS,
  };
}

export function exportAllData(): string {
  const data = {
    version: '1.0',
    exportDate: new Date().toISOString(),
    courses: loadCourses(),
    revisions: loadRevisions(),
    subjects: loadSubjects(),
    settings: loadSettings(),
  };
  return JSON.stringify(data, null, 2);
}

export function importAllData(jsonStr: string): boolean {
  try {
    const data = JSON.parse(jsonStr);
    if (!data.courses || !data.revisions) return false;

    if (data.courses) saveCourses(data.courses);
    if (data.revisions) saveRevisions(data.revisions);
    if (data.subjects) saveSubjects(data.subjects);
    if (data.settings) saveSettings(data.settings);
    return true;
  } catch (err) {
    console.error('Import error:', err);
    return false;
  }
}
