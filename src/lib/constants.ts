import { Subject, AppSettings } from '../types';

export const DEFAULT_SUBJECTS: Subject[] = [
  {
    id: 'maths',
    name: 'Mathématiques',
    color: '#38BDF8', // Sky Blue
    description: 'Algèbre, Géométrie, Arithmétique, Probabilités',
  },
  {
    id: 'francais',
    name: 'Français',
    color: '#EF4444', // Rouge
    description: 'Grammaire, Littérature, Rédaction, Orthographe',
  },
  {
    id: 'physique-chimie',
    name: 'Physique-chimie',
    color: '#6366F1', // Bleu Indigo distinct des maths
    description: 'Énergie, Mouvement, Organisation de la matière',
  },
  {
    id: 'histoire-geo',
    name: 'Histoire-géographie',
    color: '#94A3B8', // Gris
    description: 'Histoire, Géographie, EMC',
  },
  {
    id: 'espagnol',
    name: 'Espagnol',
    color: '#1D4ED8', // Bleu foncé
    description: 'Vocabulaire, Grammaire, Conjugaison',
  },
  {
    id: 'musique',
    name: 'Musique',
    color: '#84CC16', // Vert kaki / olive
    description: 'Éducation musicale et chant',
  },
  {
    id: 'svt',
    name: 'S.V.T.',
    color: '#14B8A6', // Turquoise
    description: 'Sciences de la Vie et de la Terre, Génétique',
  },
  {
    id: 'anglais',
    name: 'Anglais',
    color: '#8B5CF6', // Violet
    description: 'Grammar, Vocabulary, Reading & Listening',
  },
  {
    id: 'techno',
    name: 'Technologie',
    color: '#B45309', // Marron / Ambre foncé
    description: 'Design, Informatique, Systèmes automatisés',
  },
  {
    id: 'arts-plastiques',
    name: 'Arts plastiques',
    color: '#EC4899', // Rose
    description: 'Pratiques artistiques, Histoire des arts',
  },
];

export const DEFAULT_INTERVALS = [0, 1, 2, 4, 7, 14, 25, 45, 60, 90];

export const PHASE_CONFIG = {
  encodage: {
    label: 'Découvrir',
    color: '#2563EB', // Blue
    bgLight: 'bg-blue-500/10',
    border: 'border-blue-500/30',
    text: 'text-blue-700',
    dot: 'bg-blue-500',
    description: 'Apprendre et comprendre un nouveau cours (J0)',
  },
  consolidation: {
    label: 'S’entraîner',
    color: '#F97316', // Orange
    bgLight: 'bg-orange-500/10',
    border: 'border-orange-500/30',
    text: 'text-orange-700',
    dot: 'bg-orange-500',
    description: 'Revoir pour fixer la mémorisation (J1, J2, J4)',
  },
  recuperation: {
    label: 'Se tester',
    color: '#10B981', // Green
    bgLight: 'bg-emerald-500/10',
    border: 'border-emerald-500/30',
    text: 'text-emerald-700',
    dot: 'bg-emerald-500',
    description: 'Rappel actif sans regarder le cours (J7 à J90)',
  },
} as const;

export const DEFAULT_SETTINGS: AppSettings = {
  intervals: DEFAULT_INTERVALS,
  sensitivity: 0.4,
  emergencyThreshold: 30,
  pronoteIcalUrl: '',
  soundEnabled: true,
  userName: 'Élève',
  userGrade: '3ème',
};
