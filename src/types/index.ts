export type Phase = 'encodage' | 'consolidation' | 'recuperation';

export interface Subject {
  id: string;
  name: string;
  color: string; // Hex color
  description?: string;
  isCustom?: boolean;
}

export interface Course {
  id: string;
  subjectId: string;
  chapter: string;
  courseNumber: number; // e.g. 1 for C1, 2 for C2
  title: string;
  date: string; // YYYY-MM-DD (initial class date / J0)
  description?: string;
  createdAt: string;
}

export interface Revision {
  id: string;
  courseId: string;
  stepIndex: number; // 0 for J0, 1 for J1, etc.
  stepLabel: string; // 'J0', 'J1', 'J2', 'J4', etc.
  scheduledDate: string; // YYYY-MM-DD
  completedDate?: string; // YYYY-MM-DD
  score?: number; // 0 to 100
  status: 'pending' | 'completed' | 'overdue';
  phase: Phase;
  adjustedIntervalDays?: number; // How many days from previous revision
  isEmergencySession?: boolean; // For < 30 score follow-up
}

export interface PronoteEvent {
  id: string;
  subjectName: string;
  matchedSubjectId?: string;
  date: string; // YYYY-MM-DD
  startTime: string; // HH:mm
  endTime: string; // HH:mm
  room?: string;
  teacher?: string;
  isCancelled: boolean;
  isCourseCreated?: boolean;
}

export interface AppSettings {
  intervals: number[]; // [0, 1, 2, 4, 7, 14, 25, 45, 60, 90]
  sensitivity: number; // 0.1 to 1.0 (default 0.4)
  emergencyThreshold: number; // default 30
  pronoteIcalUrl: string;
  lastPronoteSync?: string;
  soundEnabled: boolean;
  userName: string;
  userGrade: string; // e.g. "3ème"
}

export interface DayRevisionGroup {
  subject: Subject;
  chapter: string;
  encodage: { course: Course; revision: Revision }[];
  consolidation: { course: Course; revision: Revision }[];
  recuperation: { course: Course; revision: Revision }[];
}
