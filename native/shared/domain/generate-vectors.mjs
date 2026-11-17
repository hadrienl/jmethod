#!/usr/bin/env node
/**
 * Generates the shared domain test vectors replayed by the Swift and JVM test suites.
 *
 * The real TypeScript sources in src/lib are bundled with esbuild (already a dev
 * dependency through Vite) and executed under a frozen environment:
 *   - process time zone Europe/Paris;
 *   - a fake global Date whose "now" is set explicitly before each call;
 *   - Math.random replaced by a constant (only initializeDefaultDataIfEmpty uses it);
 *   - an in-memory localStorage.
 *
 * Logic that is inline in React components (Today screen, score submission, score
 * preview) is copied below, verbatim in spirit, and guarded by source assertions so
 * the script fails when that code changes.
 *
 * Usage: npm run vectors:domain   (writes domain-vectors.json and constants.json)
 */
import { build } from 'esbuild';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// ---------------------------------------------------------------------------
// Frozen environment
// ---------------------------------------------------------------------------
process.env.TZ = 'Europe/Paris';
const RealDate = Date;
if (new RealDate(2026, 0, 15).getTimezoneOffset() !== -60 || new RealDate(2026, 6, 15).getTimezoneOffset() !== -120) {
  throw new Error('Time zone Europe/Paris could not be applied. Run with TZ=Europe/Paris.');
}

let fakeNow = RealDate.UTC(2026, 10, 17, 19, 0, 0);
class FrozenDate extends RealDate {
  constructor(...args) {
    if (args.length === 0) super(fakeNow);
    else super(...args);
  }
  static now() {
    return fakeNow;
  }
}
globalThis.Date = FrozenDate;
const setNow = (epochMs) => {
  fakeNow = epochMs;
};

const RANDOM_VALUE = 0.5;
Math.random = () => RANDOM_VALUE;

const memoryStorage = new Map();
globalThis.localStorage = {
  getItem: (key) => (memoryStorage.has(key) ? memoryStorage.get(key) : null),
  setItem: (key, value) => memoryStorage.set(key, String(value)),
  removeItem: (key) => memoryStorage.delete(key),
  clear: () => memoryStorage.clear(),
};

const warnings = [];
console.warn = (...args) => warnings.push(args.map(String).join(' '));
console.error = (...args) => warnings.push(args.map(String).join(' '));

// ---------------------------------------------------------------------------
// Load the TypeScript sources
// ---------------------------------------------------------------------------
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const rel = (p) => path.relative(root, p).split(path.sep).join('/');

const SOURCES = [
  'src/lib/adaptiveAlgorithm.ts',
  'src/lib/eveningProgramme.ts',
  'src/lib/icalParser.ts',
  'src/lib/constants.ts',
  'src/lib/storage.ts',
  'src/types/index.ts',
  'src/views/DashboardView.tsx',
  'src/App.tsx',
  'src/components/Navigation.tsx',
  'src/components/ScoreModal.tsx',
  'src/views/PlanningView.tsx',
  'src/components/RevisionDetailModal.tsx',
  'src/views/CoursesView.tsx',
  'src/components/AddCourseModal.tsx',
  'src/components/CourseDetailModal.tsx',
  'src/views/StatsView.tsx',
  'src/views/SettingsView.tsx',
];
const sourceText = Object.fromEntries(SOURCES.map((s) => [s, readFileSync(path.join(root, s), 'utf8')]));
const sourceHashes = Object.fromEntries(
  SOURCES.map((s) => [s, createHash('sha256').update(sourceText[s]).digest('hex')])
);

/** Fails when inline UI logic copied in this script no longer matches its source. */
function assertSource(file, snippets) {
  for (const snippet of snippets) {
    if (!sourceText[file].includes(snippet)) {
      throw new Error(`${file} changed: copied logic must be reviewed (missing: ${snippet})`);
    }
  }
}
assertSource('src/views/DashboardView.tsx', [
  'const { preparationDate, due, completed, overdue, visible } = getEveningProgramme(revisions, courses, todayStr);',
  'const total = due.length+completed.length;',
  'const progress = total ? Math.round(completed.length/total*100):0;',
  'const next = due[0];',
  'const events = pronoteEvents.filter(e=>e.date===preparationDate);',
  '{subjects.map(subject=>{',
  'const rows = visible.filter(r=>courseMap.get(r.courseId)?.subjectId===subject.id);',
  "const done=revision.status==='completed';",
  '{due.length === 0 && completed.length > 0 &&',
  "{nextSubject?.name ?? 'ton cours'}",
  "{completed.length?'Chaque séance compte. Continue comme ça !':total?'La première séance, c’est déjà un progrès.':'Aucune séance à préparer pour demain.'}",
  "{overdue.length} séance{overdue.length>1?'s':''} à reprendre. Pas de pression : avance une séance à la fois.",
  "format(parseISO(todayStr),'EEEE d MMMM',{locale:fr})",
  "format(parseISO(preparationDate), 'EEEE d MMMM', { locale: fr })",
]);
assertSource('src/App.tsx', [
  'const pendingCountToday = eveningProgramme.due.length - eveningProgramme.overdue.length;',
  'const overdueCount = eveningProgramme.overdue.length;',
  "if (!targetRev || targetRev.status === 'completed') return;",
  'const courseRevs = revisions.filter((r) => r.courseId === targetRev.courseId);',
  'const result = applyAdaptiveReview(courseRevs, revisionId, score, settings, todayStr, eveningProgramme.preparationDate);',
  'const otherRevisions = revisions.filter((r) => r.courseId !== targetRev.courseId);',
  'const newGlobalRevisions = [...otherRevisions, ...result.updatedRevisions];',
  'if (eveningProgramme.due.length > 0 && remainingProgramme.due.length === 0) {',
  "? 'Rattrapage programmé'",
  ': `Révision validée (${score}/100)`,',
  "const [todayStr, setTodayStr] = useState(() => format(new Date(), 'yyyy-MM-dd'));",
]);
assertSource('src/components/Navigation.tsx', ['const count = pendingCountToday + overdueCount;']);
assertSource('src/components/ScoreModal.tsx', [
  'const normalizedDelta = (score - 50) / 50;',
  'const factor = 1 + normalizedDelta * settings.sensitivity;',
  'const isEmergency = score < settings.emergencyThreshold;',
  '} else if (score < 50) {',
  '} else if (score > 50) {',
  'Difficultés constatées : les prochaines révisions seront rapprochées de -${percent}%.',
  'Très bonne maîtrise : les prochaines révisions seront espacées de +${percent}%.',
  'if (val < 30) return',
]);

const bundle = await build({
  stdin: {
    contents: `
      export * from './src/lib/adaptiveAlgorithm.ts';
      export * from './src/lib/eveningProgramme.ts';
      export * from './src/lib/icalParser.ts';
      export * from './src/lib/constants.ts';
      export * from './src/lib/storage.ts';
      export {
        format, parseISO, addDays, addWeeks, addMonths, startOfWeek, endOfWeek,
        startOfMonth, endOfMonth, eachDayOfInterval, isSameDay, isSameMonth,
      } from 'date-fns';
      export { fr } from 'date-fns/locale';
    `,
    resolveDir: root,
    loader: 'ts',
    sourcefile: 'domain-entry.ts',
  },
  bundle: true,
  format: 'esm',
  platform: 'node',
  write: false,
  logLevel: 'silent',
});
const lib = await import(
  `data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`
);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
const clone = (value) => (value === undefined ? undefined : JSON.parse(JSON.stringify(value)));

/** Calendar arithmetic used only to build fixtures (not the code under test). */
function shift(ymd, days) {
  const [y, m, d] = ymd.split('-').map(Number);
  return new RealDate(RealDate.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** Epoch milliseconds of a Europe/Paris wall-clock time. */
function paris(ymd, hm = '20:00') {
  const [y, m, d] = ymd.split('-').map(Number);
  const [hh, mm] = hm.split(':').map(Number);
  return new RealDate(y, m - 1, d, hh, mm, 0, 0).getTime();
}
const nowField = (epochMs) => ({ epochMs, utc: new RealDate(epochMs).toISOString() });

const SETTINGS = clone(lib.DEFAULT_SETTINGS);
const settingsWith = (patch) => ({ ...clone(SETTINGS), ...patch });
const SUBJECTS = clone(lib.DEFAULT_SUBJECTS);

function course(id, subjectId, date, extra = {}) {
  return {
    id,
    subjectId,
    chapter: extra.chapter ?? 'Chapitre de test',
    courseNumber: extra.courseNumber ?? 1,
    title: extra.title ?? `Cours de test ${id}`,
    date,
    createdAt: extra.createdAt ?? date,
  };
}

/** Initial schedule, the first `completed` steps validated the evening before their date. */
function schedule(c, intervals, completed = 0, score = 60) {
  return lib.generateInitialRevisions(c, intervals).map((r, i) =>
    i < completed ? { ...r, status: 'completed', completedDate: shift(r.scheduledDate, -1), score } : r
  );
}

function capture(fn) {
  try {
    return { expected: clone(fn()) };
  } catch (err) {
    return { expectedError: { message: String(err.message) } };
  }
}

const ids = (list) => list.map((r) => r.id);

// ---------------------------------------------------------------------------
// getPhaseForStep
// ---------------------------------------------------------------------------
const phaseCases = [-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12].map((stepIndex) => ({
  name: `step_${stepIndex}`,
  input: { stepIndex },
  expected: lib.getPhaseForStep(stepIndex),
}));

// ---------------------------------------------------------------------------
// generateInitialRevisions
// ---------------------------------------------------------------------------
const generateSpecs = [
  ['default_intervals', course('course-gen-a', 'maths', '2026-11-18'), lib.DEFAULT_INTERVALS],
  ['custom_intervals', course('course-gen-b', 'francais', '2026-11-18'), [0, 3, 10, 30]],
  ['month_change', course('course-gen-c', 'svt', '2026-01-29'), lib.DEFAULT_INTERVALS],
  ['february_to_march', course('course-gen-d', 'svt', '2026-02-26'), lib.DEFAULT_INTERVALS],
  ['year_change', course('course-gen-e', 'anglais', '2026-12-28'), lib.DEFAULT_INTERVALS],
  ['dst_spring_2026', course('course-gen-f', 'techno', '2026-03-27'), lib.DEFAULT_INTERVALS],
  ['dst_autumn_2026', course('course-gen-g', 'musique', '2026-10-23'), lib.DEFAULT_INTERVALS],
  ['leap_year_2028', course('course-gen-h', 'maths', '2028-02-26'), [0, 1, 2, 3, 4]],
  ['non_zero_first_interval', course('course-gen-i', 'maths', '2026-11-18'), [2, 1, 7]],
  ['duplicate_intervals', course('course-gen-j', 'maths', '2026-11-18'), [0, 1, 1, 4]],
  ['empty_intervals', course('course-gen-k', 'maths', '2026-11-18'), []],
];
const generateCases = generateSpecs.map(([name, c, intervals]) => ({
  name,
  input: { course: clone(c), intervals: clone(intervals) },
  expected: clone(lib.generateInitialRevisions(c, intervals)),
}));

// ---------------------------------------------------------------------------
// applyAdaptiveReview — single calls
// ---------------------------------------------------------------------------
const reviewCases = [];
function reviewCase(name, { revisions, revisionId, score, settings = SETTINGS, actual, reference, now, note }) {
  const input = { revisions: clone(revisions), revisionId, score, settings: clone(settings) };
  if (actual !== undefined) input.actualCompletionDate = actual;
  if (reference !== undefined) input.schedulingReferenceDate = reference;
  const nowMs = now ?? paris(actual ?? '2026-11-17');
  setNow(nowMs);
  const args = [clone(revisions), revisionId, score, clone(settings)];
  if (actual !== undefined) args.push(actual);
  if (reference !== undefined) {
    if (actual === undefined) args.push(undefined);
    args.push(reference);
  }
  reviewCases.push({
    name,
    ...(note ? { note } : {}),
    now: nowField(nowMs),
    input,
    ...capture(() => lib.applyAdaptiveReview(...args)),
  });
}

// Evening convention: course on the 18th, J0 prepared on the 17th evening.
const base = course('course-a', 'maths', '2026-11-18');
const baseRevs = schedule(base, lib.DEFAULT_INTERVALS);
for (const score of [0, 29, 30, 50, 70, 100]) {
  reviewCase(`j0_default_score_${score}`, {
    revisions: baseRevs,
    revisionId: 'course-a-rev-0',
    score,
    actual: '2026-11-17',
    reference: '2026-11-18',
  });
}

// Sensitivity matrix on J4 (stepIndex 3, base gap 3 days to J7) and J14 (stepIndex 5, gap 11 to J25).
for (const sensitivity of [0.1, 0.4, 1.0]) {
  for (const score of [0, 29, 30, 50, 70, 100]) {
    reviewCase(`j4_sensitivity_${sensitivity}_score_${score}`, {
      revisions: schedule(base, lib.DEFAULT_INTERVALS, 3),
      revisionId: 'course-a-rev-3',
      score,
      settings: settingsWith({ sensitivity }),
      actual: '2026-11-21',
      reference: '2026-11-22',
    });
  }
  for (const score of [30, 70, 100]) {
    reviewCase(`j14_sensitivity_${sensitivity}_score_${score}`, {
      revisions: schedule(base, lib.DEFAULT_INTERVALS, 5),
      revisionId: 'course-a-rev-5',
      score,
      settings: settingsWith({ sensitivity }),
      actual: '2026-12-01',
      reference: '2026-12-02',
    });
  }
}

reviewCase('score_51_sensitivity_0.1_rounds_to_plus_0_percent', {
  revisions: schedule(base, lib.DEFAULT_INTERVALS, 2),
  revisionId: 'course-a-rev-2',
  score: 51,
  settings: settingsWith({ sensitivity: 0.1 }),
  actual: '2026-11-19',
  reference: '2026-11-20',
});
reviewCase('rounding_tie_2.5_rounds_up', {
  note: 'gap 2 days * factor 1.25 = 2.5, JavaScript Math.round gives 3 (half up, not half even)',
  revisions: schedule(base, lib.DEFAULT_INTERVALS, 2),
  revisionId: 'course-a-rev-2',
  score: 75,
  settings: settingsWith({ sensitivity: 0.5 }),
  actual: '2026-11-19',
  reference: '2026-11-20',
});
reviewCase('rounding_large_gap_sensitivity_0.1_score_100', {
  revisions: schedule(base, lib.DEFAULT_INTERVALS, 8),
  revisionId: 'course-a-rev-8',
  score: 100,
  settings: settingsWith({ sensitivity: 0.1 }),
  actual: '2027-01-16',
  reference: '2027-01-17',
});
reviewCase('custom_threshold_50_score_45_is_emergency', {
  revisions: schedule(base, lib.DEFAULT_INTERVALS, 1),
  revisionId: 'course-a-rev-1',
  score: 45,
  settings: settingsWith({ emergencyThreshold: 50 }),
  actual: '2026-11-18',
  reference: '2026-11-19',
});
reviewCase('threshold_0_score_0_is_not_emergency', {
  revisions: schedule(base, lib.DEFAULT_INTERVALS, 1),
  revisionId: 'course-a-rev-1',
  score: 0,
  settings: settingsWith({ emergencyThreshold: 0 }),
  actual: '2026-11-18',
  reference: '2026-11-19',
});
reviewCase('last_step_completed', {
  revisions: schedule(base, lib.DEFAULT_INTERVALS, 9),
  revisionId: 'course-a-rev-9',
  score: 80,
  actual: '2027-02-15',
  reference: '2027-02-16',
});
reviewCase('last_step_below_threshold_still_creates_emergency', {
  revisions: schedule(base, lib.DEFAULT_INTERVALS, 9),
  revisionId: 'course-a-rev-9',
  score: 10,
  actual: '2027-02-15',
  reference: '2027-02-16',
});
reviewCase('unknown_revision_throws', {
  revisions: baseRevs,
  revisionId: 'course-a-rev-99',
  score: 50,
  actual: '2026-11-17',
  reference: '2026-11-18',
});
reviewCase('late_validation_reschedules_from_reference_date', {
  note: 'J2 planned 2026-11-20, validated 2026-11-25 evening',
  revisions: schedule(base, lib.DEFAULT_INTERVALS, 2),
  revisionId: 'course-a-rev-2',
  score: 60,
  actual: '2026-11-25',
  reference: '2026-11-26',
});
reviewCase('early_validation_reschedules_from_reference_date', {
  note: 'J7 planned 2026-11-25, validated 2026-11-21 evening',
  revisions: schedule(base, lib.DEFAULT_INTERVALS, 4),
  revisionId: 'course-a-rev-4',
  score: 60,
  actual: '2026-11-21',
  reference: '2026-11-22',
});
reviewCase('default_dates_from_clock_after_midnight', {
  note: 'actualCompletionDate and schedulingReferenceDate omitted: both default to the local date of now (00:30 Paris, still the 17th in UTC)',
  revisions: baseRevs,
  revisionId: 'course-a-rev-0',
  score: 70,
  now: paris('2026-11-18', '00:30'),
});
reviewCase('only_actual_date_given_reference_defaults_to_it', {
  revisions: baseRevs,
  revisionId: 'course-a-rev-0',
  score: 70,
  actual: '2026-11-17',
});
reviewCase('reference_without_actual_date', {
  note: 'actualCompletionDate passed as undefined so it defaults to now; schedulingReferenceDate given',
  revisions: baseRevs,
  revisionId: 'course-a-rev-0',
  score: 70,
  reference: '2026-11-18',
  now: paris('2026-11-17', '21:15'),
});

// Calendar boundaries: course built so that the validated step lands on the boundary.
const boundary = (name, courseDate, step, actual, score, sensitivity = 0.4) => {
  const c = course(`course-${name.replace(/_/g, '-')}`, 'histoire-geo', courseDate);
  reviewCase(name, {
    revisions: schedule(c, lib.DEFAULT_INTERVALS, step),
    revisionId: `${c.id}-rev-${step}`,
    score,
    settings: settingsWith({ sensitivity }),
    actual,
    reference: shift(actual, 1),
  });
};
boundary('month_change_january_to_february', '2026-01-27', 3, '2026-01-30', 70);
boundary('month_change_february_to_march', '2026-02-24', 3, '2026-02-27', 50);
boundary('year_change', '2026-12-27', 3, '2026-12-30', 100);
boundary('year_change_emergency_on_new_year', '2026-12-28', 2, '2026-12-30', 10);
boundary('dst_spring_reference_on_change_day', '2026-03-26', 3, '2026-03-29', 50);
boundary('dst_spring_reference_day_before', '2026-03-25', 3, '2026-03-28', 100);
boundary('dst_spring_emergency', '2026-03-27', 1, '2026-03-28', 5);
boundary('dst_autumn_reference_on_change_day', '2026-10-22', 3, '2026-10-25', 50);
boundary('dst_autumn_reference_day_before', '2026-10-21', 3, '2026-10-24', 0);
boundary('dst_autumn_expanded', '2026-10-21', 3, '2026-10-24', 90, 1.0);

// Custom intervals and settings/schedule mismatches.
const custom = course('course-custom', 'francais', '2026-11-18');
reviewCase('custom_intervals_expanded', {
  revisions: schedule(custom, [0, 3, 10, 30], 1),
  revisionId: 'course-custom-rev-1',
  score: 80,
  settings: settingsWith({ intervals: [0, 3, 10, 30] }),
  actual: '2026-11-20',
  reference: '2026-11-21',
});
reviewCase('custom_intervals_shortened', {
  revisions: schedule(custom, [0, 3, 10, 30], 1),
  revisionId: 'course-custom-rev-1',
  score: 35,
  settings: settingsWith({ intervals: [0, 3, 10, 30] }),
  actual: '2026-11-20',
  reference: '2026-11-21',
});
reviewCase('settings_intervals_shorter_than_schedule', {
  note: 'course scheduled with default intervals, settings now [0, 2, 5]: missing indexes fall back (?? 0 and current + 1)',
  revisions: schedule(base, lib.DEFAULT_INTERVALS, 4),
  revisionId: 'course-a-rev-4',
  score: 50,
  settings: settingsWith({ intervals: [0, 2, 5] }),
  actual: '2026-11-24',
  reference: '2026-11-25',
});
reviewCase('unsorted_input_is_sorted_by_step_index', {
  revisions: [...schedule(base, lib.DEFAULT_INTERVALS, 1)].reverse(),
  revisionId: 'course-a-rev-1',
  score: 60,
  actual: '2026-11-18',
  reference: '2026-11-19',
});
{
  const revs = schedule(base, lib.DEFAULT_INTERVALS, 0);
  revs[2] = { ...revs[2], status: 'completed', completedDate: '2026-11-17', score: 90 };
  reviewCase('completed_later_step_is_overwritten', {
    note: 'J2 already completed out of order: validating J0 picks J1 as next pending, then rewrites J2 and every later step, including the completed one',
    revisions: revs,
    revisionId: 'course-a-rev-0',
    score: 100,
    actual: '2026-11-17',
    reference: '2026-11-18',
  });
}
{
  const revs = schedule(base, lib.DEFAULT_INTERVALS, 1);
  revs[1 + 1] = { ...revs[2], status: 'overdue' };
  reviewCase('overdue_status_is_not_next_pending', {
    note: "a revision with status 'overdue' (imported data) is not 'pending': J4 becomes the next step (gap J1->J4) and the overdue J2 keeps its date",
    revisions: revs,
    revisionId: 'course-a-rev-1',
    score: 50,
    actual: '2026-11-18',
    reference: '2026-11-19',
  });
}
{
  // Emergency already present (from a J1 at 10/100), then validated with a good score.
  setNow(paris('2026-11-18'));
  const first = lib.applyAdaptiveReview(schedule(base, lib.DEFAULT_INTERVALS, 1), 'course-a-rev-1', 10, SETTINGS, '2026-11-18', '2026-11-19');
  const emergency = first.updatedRevisions.find((r) => r.isEmergencySession);
  reviewCase('validate_emergency_session', {
    revisions: first.updatedRevisions,
    revisionId: emergency.id,
    score: 80,
    actual: '2026-11-19',
    reference: '2026-11-20',
  });
  reviewCase('validate_emergency_session_again_below_threshold', {
    revisions: first.updatedRevisions,
    revisionId: emergency.id,
    score: 20,
    actual: '2026-11-19',
    reference: '2026-11-20',
    now: paris('2026-11-19', '19:45'),
  });
  reviewCase('validate_regular_step_while_emergency_pending', {
    note: 'the pending emergency session sits between J1 and J2; validating J2 leaves it untouched',
    revisions: first.updatedRevisions,
    revisionId: 'course-a-rev-2',
    score: 50,
    actual: '2026-11-19',
    reference: '2026-11-20',
  });
}

// ---------------------------------------------------------------------------
// applyAdaptiveReview — chains (evening workflow)
// ---------------------------------------------------------------------------
function chain(name, description, c, settings, steps) {
  let revs = schedule(c, settings.intervals);
  const initialRevisions = clone(revs);
  const outSteps = [];
  for (const step of steps) {
    const pending = revs
      .filter((r) => r.status !== 'completed')
      .sort((a, b) => a.scheduledDate.localeCompare(b.scheduledDate));
    const target = step.revisionId ? revs.find((r) => r.id === step.revisionId) : pending[0];
    if (!target) break;
    const today = shift(target.scheduledDate, -1 + (step.delayDays ?? 0));
    const reference = shift(today, 1);
    const nowMs = paris(today, step.time ?? '20:00');
    setNow(nowMs);
    const result = lib.applyAdaptiveReview(clone(revs), target.id, step.score, settings, today, reference);
    outSteps.push({
      now: nowField(nowMs),
      revisionId: target.id,
      score: step.score,
      actualCompletionDate: today,
      schedulingReferenceDate: reference,
      expected: clone(result),
    });
    revs = result.updatedRevisions;
  }
  return { name, description, settings: clone(settings), course: clone(c), initialRevisions, steps: outSteps };
}
const chainCases = [
  chain(
    'all_steps_at_50',
    'Every step validated at 50/100 the evening before its date: the default schedule is reproduced.',
    course('course-chain-a', 'maths', '2026-11-18'),
    SETTINGS,
    Array.from({ length: 10 }, () => ({ score: 50 }))
  ),
  chain(
    'mixed_scores_emergencies_dst_autumn',
    'Starts 2026-10-19, crosses the end of summer time (2026-10-25), two emergency sessions, late and early validations.',
    course('course-chain-b', 'physique-chimie', '2026-10-19'),
    SETTINGS,
    [
      { score: 100 },
      { score: 20 },
      { score: 60 },
      { score: 70, delayDays: 2 },
      { score: 0 },
      { score: 30 },
      { score: 50, delayDays: -1 },
      { score: 85 },
      { score: 40, delayDays: 3 },
      { score: 90 },
      { score: 100 },
      { score: 65 },
    ]
  ),
  chain(
    'year_end_to_spring_sensitivity_1',
    'Custom intervals, sensitivity 1.0, from 2026-12-20 through the new year and the start of summer time (2027-03-28).',
    course('course-chain-c', 'anglais', '2026-12-20'),
    settingsWith({ intervals: [0, 1, 3, 7, 14, 30, 60, 90], sensitivity: 1.0 }),
    [
      { score: 50 },
      { score: 80 },
      { score: 10 },
      { score: 50 },
      { score: 100 },
      { score: 25 },
      { score: 40 },
      { score: 75 },
      { score: 60 },
      { score: 100 },
    ]
  ),
  chain(
    'february_threshold_50_sensitivity_0.1',
    'Threshold 50 and sensitivity 0.1, from 2026-01-25 across February and the start of summer time (2026-03-29).',
    course('course-chain-d', 'svt', '2026-01-25'),
    settingsWith({ sensitivity: 0.1, emergencyThreshold: 50 }),
    [
      { score: 45 },
      { score: 55 },
      { score: 50 },
      { score: 49 },
      { score: 60 },
      { score: 100 },
      { score: 0, delayDays: 1 },
      { score: 50 },
      { score: 95 },
      { score: 70 },
      { score: 50 },
      { score: 50 },
    ]
  ),
];

// ---------------------------------------------------------------------------
// getEveningProgramme
// ---------------------------------------------------------------------------
const rev = (id, courseId, scheduledDate, extra = {}) => ({
  id,
  courseId,
  stepIndex: extra.stepIndex ?? 0,
  stepLabel: extra.stepLabel ?? `J${extra.stepIndex ?? 0}`,
  scheduledDate,
  status: extra.status ?? 'pending',
  phase: extra.phase ?? lib.getPhaseForStep(extra.stepIndex ?? 0),
  adjustedIntervalDays: extra.adjustedIntervalDays ?? 0,
  ...(extra.completedDate ? { completedDate: extra.completedDate } : {}),
  ...(extra.score !== undefined ? { score: extra.score } : {}),
  ...(extra.isEmergencySession ? { isEmergencySession: true } : {}),
});
const progCourses = [
  course('course-p1', 'maths', '2026-11-10'),
  course('course-p2', 'francais', '2026-11-12'),
  course('course-p3', 'svt', '2026-11-14'),
];
const programmeCases = [];
function programmeCase(name, revisions, courses, today, note) {
  const out = lib.getEveningProgramme(clone(revisions), clone(courses), today);
  programmeCases.push({
    name,
    ...(note ? { note } : {}),
    input: { today, courses: clone(courses), revisions: clone(revisions) },
    expected: {
      preparationDate: out.preparationDate,
      due: ids(out.due),
      completed: ids(out.completed),
      overdue: ids(out.overdue),
      visible: ids(out.visible),
    },
  });
}
programmeCase(
  'mixed_overdue_due_completed_and_deleted_course',
  [
    rev('r-future', 'course-p1', '2026-11-19', { stepIndex: 2 }),
    rev('r-due-tomorrow', 'course-p1', '2026-11-18', { stepIndex: 1 }),
    rev('r-overdue', 'course-p2', '2026-11-15', { stepIndex: 1 }),
    rev('r-due-today', 'course-p3', '2026-11-17', { stepIndex: 0 }),
    rev('r-done-tonight', 'course-p2', '2026-11-18', { stepIndex: 2, status: 'completed', completedDate: '2026-11-17', score: 70 }),
    rev('r-done-yesterday', 'course-p3', '2026-11-16', { stepIndex: 1, status: 'completed', completedDate: '2026-11-16', score: 55 }),
    rev('r-done-in-advance', 'course-p1', '2026-11-22', { stepIndex: 3, status: 'completed', completedDate: '2026-11-17', score: 90 }),
    rev('r-status-overdue', 'course-p3', '2026-11-16', { stepIndex: 2, status: 'overdue' }),
    rev('r-emergency', 'course-p2', '2026-11-18', { stepIndex: 1, stepLabel: 'J1* (Rattrapage)', isEmergencySession: true, phase: 'consolidation', adjustedIntervalDays: 1 }),
    rev('r-deleted-course', 'course-deleted', '2026-11-16'),
    rev('r-deleted-course-done', 'course-deleted', '2026-11-17', { status: 'completed', completedDate: '2026-11-17', score: 40 }),
  ],
  progCourses,
  '2026-11-17'
);
programmeCase(
  'same_date_keeps_input_order',
  [
    rev('r-b', 'course-p2', '2026-11-18'),
    rev('r-a', 'course-p1', '2026-11-18'),
    rev('r-c', 'course-p3', '2026-11-14'),
    rev('r-d', 'course-p3', '2026-11-18'),
    rev('r-e', 'course-p1', '2026-11-14'),
  ],
  progCourses,
  '2026-11-17',
  'due is sorted by scheduledDate with a stable sort; ties keep input order'
);
programmeCase(
  'completed_today_listed_after_due_in_input_order',
  [
    rev('r-done-2', 'course-p2', '2026-11-18', { status: 'completed', completedDate: '2026-11-17', score: 50 }),
    rev('r-due', 'course-p1', '2026-11-18'),
    rev('r-done-1', 'course-p1', '2026-11-15', { status: 'completed', completedDate: '2026-11-17', score: 80 }),
  ],
  progCourses,
  '2026-11-17'
);
programmeCase(
  'everything_done_tonight',
  [
    rev('r-1', 'course-p1', '2026-11-18', { status: 'completed', completedDate: '2026-11-17', score: 60 }),
    rev('r-2', 'course-p1', '2026-11-25', { stepIndex: 1 }),
  ],
  progCourses,
  '2026-11-17'
);
programmeCase('empty', [], progCourses, '2026-11-17');
programmeCase('no_courses_hides_everything', [rev('r-1', 'course-p1', '2026-11-18')], [], '2026-11-17');
for (const [name, today] of [
  ['month_change_january', '2026-01-31'],
  ['month_change_february', '2026-02-28'],
  ['year_change', '2026-12-31'],
  ['dst_spring_eve', '2026-03-28'],
  ['dst_spring_day', '2026-03-29'],
  ['dst_autumn_eve', '2026-10-24'],
  ['dst_autumn_day', '2026-10-25'],
]) {
  const prep = shift(today, 1);
  programmeCase(
    name,
    [
      rev(`${name}-overdue`, 'course-p1', shift(today, -3)),
      rev(`${name}-today`, 'course-p1', today, { stepIndex: 1 }),
      rev(`${name}-prep`, 'course-p2', prep),
      rev(`${name}-after`, 'course-p3', shift(prep, 1)),
      rev(`${name}-done`, 'course-p3', prep, { stepIndex: 1, status: 'completed', completedDate: today, score: 65 }),
      rev(`${name}-done-before`, 'course-p2', today, { stepIndex: 1, status: 'completed', completedDate: shift(today, -1), score: 65 }),
    ],
    progCourses,
    today
  );
}

// ---------------------------------------------------------------------------
// matchSubject
// ---------------------------------------------------------------------------
const subjectSets = {
  default: SUBJECTS,
  defaultReversed: [...SUBJECTS].reverse(),
  withCustom: [
    ...SUBJECTS,
    { id: 'eps', name: 'EPS', color: '#F59E0B', isCustom: true },
    { id: 'custom-latin', name: 'Latin', color: '#0EA5E9', isCustom: true },
    { id: 'custom-musique', name: 'Éducation musicale', color: '#22C55E', isCustom: true },
  ],
  empty: [],
};
const matchSpecs = [
  ['MATHEMATIQUES', 'default'],
  ['Mathématiques', 'default'],
  ['Mathématiques (decomposed accent)', 'default'],
  ['MATHS', 'default'],
  ['math', 'default'],
  ['AP MATHS', 'default'],
  ['FRANCAIS', 'default'],
  ['Français', 'default'],
  ['FRANÇAIS', 'default'],
  ['HIST.-GEO.', 'default'],
  ['HISTOIRE-GEOGRAPHIE', 'default'],
  ['HISTOIRE & GEOGRAPHIE', 'default'],
  ['Histoire-géographie', 'default'],
  ['H-G', 'default'],
  ['EMC', 'default'],
  ['ENSEIGN. MORAL & CIVIQUE', 'default'],
  ['ANGLAIS LV1', 'default'],
  ['LV1 ANGLAIS', 'default'],
  ['ESPAGNOL LV2', 'default'],
  ['ALLEMAND LV2', 'default'],
  ['PHYSIQUE-CHIMIE', 'default'],
  ['SCIENCES PHYSIQUES', 'default'],
  ['Chimie', 'default'],
  ['SPC', 'default'],
  ['S.V.T.', 'default'],
  ['SVT', 'default'],
  ['SCIENCES VIE & TERRE', 'default'],
  ['SCIENCES DE LA VIE ET DE LA TERRE', 'default'],
  ['TECHNOLOGIE', 'default'],
  ['TECHNO', 'default'],
  ['ARTS PLASTIQUES', 'default'],
  ['ARTS PLA.', 'default'],
  ['MUSIQUE', 'default'],
  ['EDUCATION MUSICALE', 'default'],
  ['CHANT CHORALE', 'default'],
  ['ED.PHYSIQUE & SPORT.', 'default'],
  ['EPS', 'default'],
  ['E.P.S.', 'default'],
  ['ÉDUCATION PHYSIQUE ET SPORTIVE', 'default'],
  ['GEOMETRIE', 'default'],
  ['HISTOIRE DES ARTS', 'default'],
  ['LATIN', 'default'],
  ['VIE DE CLASSE', 'default'],
  ['[ANNULÉ] ANGLAIS', 'default'],
  ['', 'default'],
  ['   ', 'default'],
  ['HISTOIRE DES ARTS', 'defaultReversed'],
  ['MATHS GEO', 'defaultReversed'],
  ['ED.PHYSIQUE & SPORT.', 'withCustom'],
  ['EPS', 'withCustom'],
  ['LATIN', 'withCustom'],
  ['EDUCATION MUSICALE', 'withCustom'],
  ['MATHEMATIQUES', 'empty'],
];
const matchCases = matchSpecs.map(([summary, set], i) => {
  const result = lib.matchSubject(summary, subjectSets[set]);
  return {
    name: `${String(i + 1).padStart(2, '0')}_${set}_${summary.trim() || 'blank'}`,
    input: { summary, subjects: set },
    expectedSubjectId: result ? result.id : null,
  };
});

// ---------------------------------------------------------------------------
// parseICalFeed
// ---------------------------------------------------------------------------
const crlf = (lines) => lines.join('\r\n');
const mainFeed = crlf([
  'BEGIN:VCALENDAR',
  'VERSION:2.0',
  'PRODID:-//Example School//Timetable Test//FR',
  'BEGIN:VTIMEZONE',
  'TZID:Europe/Paris',
  'BEGIN:STANDARD',
  'DTSTART:19701025T030000',
  'TZOFFSETFROM:+0200',
  'TZOFFSETTO:+0100',
  'END:STANDARD',
  'END:VTIMEZONE',
  'BEGIN:VEVENT',
  'UID:evt-utc-winter',
  'DTSTART:20261118T073000Z',
  'DTEND:20261118T083000Z',
  'SUMMARY:MATHEMATIQUES',
  'LOCATION:Salle 101',
  'DESCRIPTION:Professeur A',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-tzid-paris',
  'DTSTART;TZID=Europe/Paris:20261118T100000',
  'DTEND;TZID=Europe/Paris:20261118T110000',
  'SUMMARY:HIST.-GEO.',
  'LOCATION:Salle 12\\, bâtiment B',
  'DESCRIPTION:Professeur B',
  'STATUS:CONFIRMED',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-cancelled-status',
  'DTSTART;TZID=Europe/Paris:20261118T140000',
  'DTEND;TZID=Europe/Paris:20261118T150000',
  'SUMMARY:ANGLAIS LV1',
  'LOCATION:Salle 108',
  'STATUS:CANCELLED',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-cancelled-summary',
  'DTSTART:20261118T150000',
  'DTEND:20261118T160000',
  'SUMMARY:[ANNULÉ] ALLEMAND LV2',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-cancelled-word',
  'DTSTART:20261118T160000',
  'SUMMARY:Cours annulé',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-cancelled-unaccented',
  'DTSTART:20261118T163000',
  'DTEND:20261118T173000',
  'SUMMARY:ANNULE ESPAGNOL LV2',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-folded',
  'DTSTART;TZID=Europe/Paris:20261118T080000',
  'DTEND;TZID=Europe/Paris:20261118T',
  ' 090000',
  'SUMMARY:PHYSIQUE-',
  ' CHIMIE',
  'DESCRIPTION:Professeur C -',
  '\t travaux pratiques',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'DTSTART:20261118T1300',
  'DTEND:20261118T1400',
  'SUMMARY:  S.V.T.  ',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-other-tzid',
  'DTSTART;TZID=America/New_York:20261119T083000',
  'DTEND;TZID=America/New_York:20261119T093000',
  'SUMMARY:TECHNOLOGIE',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-utc-crosses-midnight',
  'DTSTART:20261119T233000Z',
  'DTEND:20261120T003000Z',
  'SUMMARY:MUSIQUE',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-all-day',
  'DTSTART;VALUE=DATE:20261120',
  'DTEND;VALUE=DATE:20261121',
  'SUMMARY:Sortie scolaire',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-invalid-date',
  'DTSTART:not-a-date',
  'SUMMARY:FRANCAIS',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-no-summary',
  'DTSTART:20261118T090000',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-summary-with-param',
  'DTSTART:20261118T091500',
  'SUMMARY;LANGUAGE=fr:FRANCAIS',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-same-start-b',
  'DTSTART:20261118T100000',
  'SUMMARY:ARTS PLASTIQUES',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:evt-location-param',
  'DTSTART:20261118T113000',
  'DTEND:20261118T123000',
  'SUMMARY:ED.PHYSIQUE & SPORT.',
  'LOCATION;ALTREP="x":Gymnase',
  'END:VEVENT',
  'END:VCALENDAR',
  '',
]);
const dstFeed = [
  'BEGIN:VCALENDAR',
  'BEGIN:VEVENT',
  'UID:dst-spring-floating-gap',
  'DTSTART:20260329T023000',
  'DTEND:20260329T033000',
  'SUMMARY:MATHEMATIQUES',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:dst-spring-utc',
  'DTSTART:20260329T013000Z',
  'DTEND:20260329T023000Z',
  'SUMMARY:FRANCAIS',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:dst-summer-utc',
  'DTSTART:20260330T060000Z',
  'DTEND:20260330T070000Z',
  'SUMMARY:SVT',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:dst-autumn-floating-ambiguous',
  'DTSTART:20261025T023000',
  'DTEND:20261025T033000',
  'SUMMARY:ANGLAIS',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:dst-autumn-utc-first',
  'DTSTART:20261025T003000Z',
  'SUMMARY:ESPAGNOL',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:dst-autumn-utc-second',
  'DTSTART:20261025T013000Z',
  'SUMMARY:TECHNO',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:year-end-utc',
  'DTSTART:20261231T233000Z',
  'SUMMARY:MUSIQUE',
  'END:VEVENT',
  'END:VCALENDAR',
].join('\n');
const truncatedFeed = [
  'BEGIN:VCALENDAR',
  'BEGIN:VEVENT',
  'UID:complete',
  'DTSTART:20261118T080000',
  'SUMMARY:SVT',
  'END:VEVENT',
  'BEGIN:VEVENT',
  'UID:never-closed',
  'DTSTART:20261118T090000',
  'SUMMARY:MATHEMATIQUES',
].join('\n');
const outsideEventFeed = [
  'SUMMARY:outside',
  'DTSTART:20261118T080000',
  'BEGIN:VEVENT',
  'DTSTART:20261118T080000',
  'SUMMARY:EMC',
  'END:VEVENT',
].join('\r\n');

const icalCases = [
  ['main_feed_crlf_timezones_cancelled_folded', mainFeed, 'default'],
  ['main_feed_with_custom_subjects', mainFeed, 'withCustom'],
  ['dst_transitions_and_year_end_lf', dstFeed, 'default'],
  ['truncated_feed_drops_unclosed_event', truncatedFeed, 'default'],
  ['properties_outside_vevent_are_ignored', outsideEventFeed, 'default'],
  ['empty_string', '', 'default'],
].map(([name, ics, set]) => {
  warnings.length = 0;
  const events = lib.parseICalFeed(ics, subjectSets[set]);
  return { name, input: { ics, subjects: set }, expected: clone(events), parseWarnings: warnings.length };
});

// generateSamplePronoteFeed depends only on the local clock: deterministic once "now" is frozen.
const sampleFeedCases = [
  ['evening_2026_11_17', paris('2026-11-17', '20:00')],
  ['new_year_eve_2026_12_31', paris('2026-12-31', '22:00')],
  ['dst_spring_eve_2026_03_28_late', paris('2026-03-28', '23:30')],
  ['just_after_midnight_utc_still_previous_day', paris('2026-11-18', '00:30')],
].map(([name, nowMs]) => {
  setNow(nowMs);
  const ics = lib.generateSamplePronoteFeed();
  return {
    name,
    now: nowField(nowMs),
    expectedIcs: ics,
    expectedEvents: clone(lib.parseICalFeed(ics, SUBJECTS)),
  };
});

// ---------------------------------------------------------------------------
// Demo data and export (storage.ts with in-memory localStorage)
// ---------------------------------------------------------------------------
const demoCases = [
  ['first_launch_2026_11_17', paris('2026-11-17', '20:00')],
  ['first_launch_2026_03_29', paris('2026-03-29', '18:00')],
].map(([name, nowMs]) => {
  memoryStorage.clear();
  setNow(nowMs);
  const data = lib.initializeDefaultDataIfEmpty();
  const exported = JSON.parse(lib.exportAllData());
  return {
    name,
    now: nowField(nowMs),
    mathRandom: RANDOM_VALUE,
    expected: clone(data),
    expectedExport: exported,
  };
});

// ---------------------------------------------------------------------------
// Inline UI logic copied from components (see assertSource above)
// ---------------------------------------------------------------------------
const TODAY_PHASES = {
  encodage: { label: 'Découvrir', hint: 'Comprends et reformule ton cours.', symbol: '01' },
  consolidation: { label: 'S’entraîner', hint: 'Entraîne-toi à retrouver les idées clés.', symbol: '02' },
  recuperation: { label: 'Se tester', hint: 'Essaie de te rappeler sans regarder.', symbol: '03' },
};
assertSource('src/views/DashboardView.tsx', [
  "encodage: {label: 'Découvrir', hint: 'Comprends et reformule ton cours.'",
  "consolidation: {label: 'S’entraîner', hint: 'Entraîne-toi à retrouver les idées clés.'",
  "recuperation: {label: 'Se tester', hint: 'Essaie de te rappeler sans regarder.'",
]);
const plural = (n, word) => `${word}${n > 1 ? 's' : ''}`;

/** DashboardView + App/Navigation counters. */
function todayScreen({ today, courses, revisions, subjects, pronoteEvents }) {
  const courseMap = new Map(courses.map((c) => [c.id, c]));
  const subjectMap = new Map(subjects.map((s) => [s.id, s]));
  const { preparationDate, due, completed, overdue, visible } = lib.getEveningProgramme(revisions, courses, today);
  const total = due.length + completed.length;
  const progress = total ? Math.round((completed.length / total) * 100) : 0;
  const next = due[0];
  const nextCourse = next && courseMap.get(next.courseId);
  const nextSubject = nextCourse && subjectMap.get(nextCourse.subjectId);
  const events = pronoteEvents.filter((e) => e.date === preparationDate);
  const groups = [];
  for (const subject of subjects) {
    const rows = visible.filter((r) => courseMap.get(r.courseId)?.subjectId === subject.id);
    if (!rows.length) continue;
    groups.push({
      subjectId: subject.id,
      subjectName: subject.name,
      countLabel: `${rows.length} ${plural(rows.length, 'séance')} au programme`,
      rows: rows.map((r) => {
        const c = courseMap.get(r.courseId);
        const done = r.status === 'completed';
        return {
          revisionId: r.id,
          courseId: r.courseId,
          phase: r.phase,
          badge: `${TODAY_PHASES[r.phase].label} · ${r.stepLabel}`,
          courseLine: `${c.chapter} · C${c.courseNumber}`,
          title: c.title,
          done,
          doneLabel: done ? `Fait · ${r.score}/100` : null,
          canValidate: !done,
        };
      }),
    });
  }
  const pendingCountToday = due.length - overdue.length;
  return {
    preparationDate,
    headerDate: lib.format(lib.parseISO(today), 'EEEE d MMMM', { locale: lib.fr }),
    tomorrowDate: lib.format(lib.parseISO(preparationDate), 'EEEE d MMMM', { locale: lib.fr }),
    navigation: { pendingCountToday, overdueCount: overdue.length, badgeCount: pendingCountToday + overdue.length },
    due: ids(due),
    completed: ids(completed),
    overdue: ids(overdue),
    total,
    progress,
    progressSuffix: `/ ${total} ${plural(total, 'séance')} ${plural(total, 'terminée')}`,
    progressMessage: completed.length
      ? 'Chaque séance compte. Continue comme ça !'
      : total
        ? 'La première séance, c’est déjà un progrès.'
        : 'Aucune séance à préparer pour demain.',
    allDoneBanner: due.length === 0 && completed.length > 0,
    hero: nextCourse
      ? {
          title: 'Une séance à la fois.',
          revisionId: next.id,
          courseId: nextCourse.id,
          subjectLabel: nextSubject?.name ?? 'ton cours',
          courseTitle: nextCourse.title,
        }
      : { title: 'Tu peux souffler !', revisionId: null },
    dueLabel: `${due.length} à faire`,
    overdueBanner: overdue.length
      ? `${overdue.length} ${plural(overdue.length, 'séance')} à reprendre. Pas de pression : avance une séance à la fois.`
      : null,
    emptyProgramme: visible.length === 0,
    groups,
    tomorrowEvents: events.map((e) => ({
      id: e.id,
      timeRange: `${e.startTime} – ${e.endTime}`,
      subjectName: e.subjectName,
      cancelled: e.isCancelled,
      trailing: e.isCancelled ? 'Annulé' : (e.room ?? null),
      teacher: e.teacher ?? null,
      canAddCourse: !e.isCancelled,
    })),
  };
}

/** App.handleScoreSubmit, with todayStr derived from the frozen clock. */
function scoreSubmit({ courses, revisions, settings, revisionId, score }) {
  const todayStr = lib.format(new Date(), 'yyyy-MM-dd');
  const eveningProgramme = lib.getEveningProgramme(revisions, courses, todayStr);
  const targetRev = revisions.find((r) => r.id === revisionId);
  if (!targetRev || targetRev.status === 'completed') {
    return { today: todayStr, ignored: true, revisions: ids(revisions), celebrate: false, toast: null };
  }
  const courseRevs = revisions.filter((r) => r.courseId === targetRev.courseId);
  const result = lib.applyAdaptiveReview(courseRevs, revisionId, score, settings, todayStr, eveningProgramme.preparationDate);
  const otherRevisions = revisions.filter((r) => r.courseId !== targetRev.courseId);
  const newGlobalRevisions = [...otherRevisions, ...result.updatedRevisions];
  const remainingProgramme = lib.getEveningProgramme(newGlobalRevisions, courses, todayStr);
  const celebrate = eveningProgramme.due.length > 0 && remainingProgramme.due.length === 0;
  const emergency = result.explanation.actionType === 'emergency';
  return {
    today: todayStr,
    preparationDate: eveningProgramme.preparationDate,
    ignored: false,
    revisions: clone(newGlobalRevisions),
    celebrate,
    toast: {
      title: emergency ? 'Rattrapage programmé' : `Révision validée (${score}/100)`,
      message: result.explanation.message,
      type: emergency ? 'warning' : 'success',
    },
  };
}

/** ScoreModal live preview. */
function scorePreview(score, settings) {
  const normalizedDelta = (score - 50) / 50;
  const factor = 1 + normalizedDelta * settings.sensitivity;
  const isEmergency = score < settings.emergencyThreshold;
  let tone = 'exact';
  let title = 'On garde le même rythme';
  let description = 'Tu retrouves les idées principales. Ta prochaine séance garde le même espacement.';
  if (isEmergency) {
    tone = 'emergency';
    title = 'Rattrapage nécessaire';
    description = 'Oubli important : une session de rattrapage immédiate sera programmée pour demain (J+1).';
  } else if (score < 50) {
    const percent = Math.round((1 - factor) * 100);
    tone = 'shortened';
    title = 'Révision rapprochée';
    description = `Difficultés constatées : les prochaines révisions seront rapprochées de -${percent}%.`;
  } else if (score > 50) {
    const percent = Math.round((factor - 1) * 100);
    tone = 'expanded';
    title = 'Révision espacée';
    description = `Très bonne maîtrise : les prochaines révisions seront espacées de +${percent}%.`;
  }
  const band = score < 30 ? 'rose' : score < 50 ? 'amber' : score === 50 ? 'sky' : score < 85 ? 'emerald' : 'indigo';
  return { tone, title, description, scoreBand: band, confettiOnSubmit: score >= 75 };
}
assertSource('src/components/ScoreModal.tsx', [
  "if (val < 30) return 'text-rose-700';",
  "if (val < 50) return 'text-amber-700';",
  "if (val === 50) return 'text-sky-700';",
  "if (val < 85) return 'text-emerald-700';",
  "return 'text-indigo-700';",
  'if (score >= 75 &&',
]);

// Today screen fixtures.
const todayCourses = [
  course('course-t-maths-1', 'maths', '2026-11-10', { chapter: 'Arithmétique', courseNumber: 1, title: 'Cours de test maths 1' }),
  course('course-t-maths-2', 'maths', '2026-11-18', { chapter: 'Arithmétique', courseNumber: 2, title: 'Cours de test maths 2' }),
  course('course-t-pc-1', 'physique-chimie', '2026-11-16', { chapter: 'Énergie', courseNumber: 1, title: 'Cours de test physique 1' }),
  course('course-t-fr-1', 'francais', '2026-11-03', { chapter: 'Grammaire', courseNumber: 3, title: 'Cours de test français 3' }),
  course('course-t-orphan', 'subject-removed', '2026-11-18', { chapter: 'Sans matière', courseNumber: 1, title: 'Cours de matière supprimée' }),
];
const todayRevisions = [
  ...schedule(todayCourses[0], lib.DEFAULT_INTERVALS, 3),
  ...schedule(todayCourses[1], lib.DEFAULT_INTERVALS),
  ...schedule(todayCourses[2], lib.DEFAULT_INTERVALS, 1).map((r) =>
    r.stepIndex === 1 ? { ...r, status: 'completed', completedDate: '2026-11-17', score: 45 } : r
  ),
  ...schedule(todayCourses[3], lib.DEFAULT_INTERVALS, 4),
  ...schedule(todayCourses[4], lib.DEFAULT_INTERVALS),
];
setNow(paris('2026-11-17', '20:00'));
const tomorrowEvents = lib.parseICalFeed(
  crlf([
    'BEGIN:VCALENDAR',
    'BEGIN:VEVENT', 'UID:t-1', 'DTSTART:20261118T080000', 'DTEND:20261118T090000', 'SUMMARY:MATHEMATIQUES', 'LOCATION:Salle 101', 'DESCRIPTION:Professeur A', 'END:VEVENT',
    'BEGIN:VEVENT', 'UID:t-2', 'DTSTART:20261118T090000', 'DTEND:20261118T100000', 'SUMMARY:[ANNULÉ] ANGLAIS LV1', 'LOCATION:Salle 108', 'STATUS:CANCELLED', 'END:VEVENT',
    'BEGIN:VEVENT', 'UID:t-3', 'DTSTART:20261117T140000', 'DTEND:20261117T150000', 'SUMMARY:FRANCAIS', 'END:VEVENT',
    'BEGIN:VEVENT', 'UID:t-4', 'DTSTART:20261118T140000', 'DTEND:20261118T150000', 'SUMMARY:LATIN', 'END:VEVENT',
    'BEGIN:VEVENT', 'UID:t-5', 'DTSTART:20261119T080000', 'DTEND:20261119T090000', 'SUMMARY:SVT', 'END:VEVENT',
    'END:VCALENDAR',
  ]),
  SUBJECTS
);
const todayCases = [];
function todayCase(name, input, note) {
  todayCases.push({ name, ...(note ? { note } : {}), input: clone(input), expected: clone(todayScreen(input)) });
}
todayCase(
  'mixed_programme',
  { today: '2026-11-17', courses: todayCourses, revisions: todayRevisions, subjects: SUBJECTS, pronoteEvents: tomorrowEvents },
  'groups follow the order of subjects; the course whose subject was removed counts in totals but has no group'
);
todayCase(
  'subjects_order_drives_groups',
  { today: '2026-11-17', courses: todayCourses, revisions: todayRevisions, subjects: [...SUBJECTS].reverse(), pronoteEvents: [] }
);
{
  const doneAll = todayRevisions.map((r) =>
    r.status !== 'completed' && r.scheduledDate <= '2026-11-18' && r.courseId !== 'course-t-orphan'
      ? { ...r, status: 'completed', completedDate: '2026-11-17', score: 75 }
      : r
  ).filter((r) => r.courseId !== 'course-t-orphan');
  todayCase('all_done_tonight', {
    today: '2026-11-17',
    courses: todayCourses.slice(0, 4),
    revisions: doneAll,
    subjects: SUBJECTS,
    pronoteEvents: tomorrowEvents,
  });
}
todayCase('nothing_to_do', { today: '2026-11-17', courses: [], revisions: [], subjects: SUBJECTS, pronoteEvents: [] });
todayCase('single_overdue_only', {
  today: '2026-11-17',
  courses: [todayCourses[3]],
  revisions: [rev('r-old', 'course-t-fr-1', '2026-11-10', { stepIndex: 4 })],
  subjects: SUBJECTS,
  pronoteEvents: [],
});
{
  // 1 completed out of 8 → 12.5 % → Math.round → 13.
  const c = course('course-t-round', 'svt', '2026-11-18', { chapter: 'Génétique', courseNumber: 4, title: 'Cours de test arrondi' });
  const revs = Array.from({ length: 8 }, (_, i) =>
    rev(`r-round-${i}`, c.id, '2026-11-18', i === 0 ? { status: 'completed', completedDate: '2026-11-17', score: 50 } : {})
  );
  todayCase('progress_rounding_half_up', { today: '2026-11-17', courses: [c], revisions: revs, subjects: SUBJECTS, pronoteEvents: [] });
}
todayCase('year_change', {
  today: '2026-12-31',
  courses: [course('course-t-ny', 'anglais', '2027-01-01')],
  revisions: schedule(course('course-t-ny', 'anglais', '2027-01-01'), lib.DEFAULT_INTERVALS),
  subjects: SUBJECTS,
  pronoteEvents: [],
});

// Score submission scenarios (App.handleScoreSubmit).
const submitCases = [];
function submitCase(name, nowMs, input, note) {
  setNow(nowMs);
  submitCases.push({ name, ...(note ? { note } : {}), now: nowField(nowMs), input: clone(input), expected: scoreSubmit(clone(input)) });
}
const submitBase = { courses: todayCourses, revisions: todayRevisions, settings: SETTINGS };
submitCase('validate_j0_at_50_keeps_others_pending', paris('2026-11-17'), { ...submitBase, revisionId: 'course-t-maths-2-rev-0', score: 50 },
  'J0 of the 18th done on the 17th evening: J1 lands on the 19th; global order becomes other courses then this course sorted by stepIndex');
submitCase('validate_overdue_with_emergency', paris('2026-11-17', '21:10'), { ...submitBase, revisionId: 'course-t-fr-1-rev-4', score: 10 });
submitCase('already_completed_is_ignored', paris('2026-11-17'), { ...submitBase, revisionId: 'course-t-pc-1-rev-1', score: 90 });
submitCase('unknown_revision_is_ignored', paris('2026-11-17'), { ...submitBase, revisionId: 'missing', score: 90 });
{
  const c = course('course-t-last', 'maths', '2026-11-18');
  const revs = schedule(c, lib.DEFAULT_INTERVALS);
  submitCase('last_due_triggers_celebration', paris('2026-11-17'), { courses: [c], revisions: revs, settings: SETTINGS, revisionId: 'course-t-last-rev-0', score: 80 });
  submitCase('last_due_emergency_also_celebrates', paris('2026-11-17'), { courses: [c], revisions: revs, settings: SETTINGS, revisionId: 'course-t-last-rev-0', score: 0 },
    'the emergency session targets preparationDate + 1, so it is not due tonight');
  submitCase('validate_after_midnight_uses_new_day', paris('2026-11-18', '00:20'), { courses: [c], revisions: revs, settings: SETTINGS, revisionId: 'course-t-last-rev-0', score: 50 },
    'todayStr follows the local clock: at 00:20 the 18th is today, preparation date the 19th');
}

const previewCases = [];
for (const [sensitivity, emergencyThreshold, scores] of [
  [0.4, 30, [0, 25, 29, 30, 45, 49, 50, 51, 55, 75, 84, 85, 100]],
  [0.1, 30, [30, 51, 100]],
  [1.0, 30, [30, 70, 100]],
  [0.4, 60, [55, 60, 0]],
  [0.4, 0, [0]],
]) {
  for (const score of scores) {
    previewCases.push({
      name: `sens_${sensitivity}_threshold_${emergencyThreshold}_score_${score}`,
      input: { score, sensitivity, emergencyThreshold },
      expected: scorePreview(score, { sensitivity, emergencyThreshold }),
    });
  }
}

// ===========================================================================
// Milestones 2 to 4: Planning, Mes cours, Mes progrès, Réglages
// All logic below is inline in React components; it is copied and guarded.
// ===========================================================================
assertSource('src/views/PlanningView.tsx', [
  'const weekStart = startOfWeek(date, { weekStartsOn: 1 });',
  'start: startOfWeek(startOfMonth(date), { weekStartsOn: 1 }),',
  'end: endOfWeek(endOfMonth(date), { weekStartsOn: 1 }),',
  'eachDayOfInterval({ start: weekStart, end: addDays(weekStart, 6) })',
  'course &&',
  'subjectMap.has(course.subjectId) &&',
  '(subjectId === "all" || course.subjectId === subjectId) &&',
  '(phase === "all" || r.phase === phase)',
  '.filter((r) => r.scheduledDate === key)',
  'Number(a.status === "completed") -',
  'Number(b.status === "completed") ||',
  'a.stepIndex - b.stepIndex,',
  '(subjectId === "all" || e.matchedSubjectId === subjectId),',
  '? addMonths(d, direction)',
  '? addWeeks(d, direction)',
  ': addDays(d, direction),',
  '? format(date, "MMMM yyyy", { locale: fr })',
  '? format(date, "EEEE d MMMM yyyy", { locale: fr })',
  ': `${format(weekStart, "d MMM", { locale: fr })} – ${format(addDays(weekStart, 6), "d MMM yyyy", { locale: fr })}`;',
  '{format(day, mode === "month" ? "EEE d" : "EEEE d MMM", {',
  '{isSameDay(day, new Date()) ? " · Aujourd’hui" : ""}',
  "${mode === \"month\" && !isSameMonth(day, date) ? \"outside-month\" : \"\"}",
  'Emploi du temps · {events.length} cours',
  '<span>{e.startTime} · </span>',
  '{e.isCancelled && " · Annulé"}',
  '{REVISION_PHASE_LABELS[revision.phase]} · C{course.courseNumber}',
  '<CheckCircle2 size={14} /> Terminée · {revision.score ?? "—"}/100',
  'aria-label={`${subject.name}, ${revision.stepLabel}, ${REVISION_PHASE_LABELS[revision.phase]}, C${course.courseNumber} ${course.title}${done ? ", terminée" : ""}`}',
  'Aucune séance prévue.',
  '{selected && selectedCourse && selectedSubject && (',
]);
assertSource('src/components/RevisionDetailModal.tsx', [
  'encodage: "Encodage",',
  'consolidation: "Consolidation",',
  'recuperation: "Récupération",',
  'r.courseId === course.id &&',
  'r.id !== revision.id &&',
  'r.status === "completed" &&',
  '(revision.status !== "completed" ||',
  '(r.completedDate ?? r.scheduledDate) <=',
  '(revision.completedDate ?? revision.scheduledDate)),',
  '(b.completedDate ?? b.scheduledDate).localeCompare(',
  ') || b.stepIndex - a.stepIndex,',
  '· {r.score !== undefined ? `${r.score}/100` : "Sans note"}',
  'Sessions précédentes ({history.length})',
  'Aucune session terminée avant celle-ci.',
  '{format(parseISO(revision.scheduledDate), "d MMMM yyyy", {',
  '? ` le ${format(parseISO(revision.completedDate), "dd/MM/yyyy")}`',
  '· {revision.score ?? "—"}/100',
  'C{course.courseNumber} · {course.title}',
  'Terminer cette séance avec {score}/100 ?',
]);
assertSource('src/views/CoursesView.tsx', [
  "if (selectedSubjectId !== 'all' && c.subjectId !== selectedSubjectId) return false;",
  'if (search.trim()) {',
  'const q = search.toLowerCase();',
  'c.title.toLowerCase().includes(q) ||',
  'c.chapter.toLowerCase().includes(q) ||',
  '`c${c.courseNumber}`.toLowerCase().includes(q)',
  'const subjCourses = filteredCourses.filter((c) => c.subjectId === subj.id);',
  'if (!chaptersMap.has(c.chapter)) chaptersMap.set(c.chapter, []);',
  'courses: cList.sort((a, b) => a.courseNumber - b.courseNumber),',
  "const completedRevs = courseRevs.filter((r) => r.status === 'completed');",
  '? Math.round((completedRevs.length / courseRevs.length) * 100)',
  "const nextPending = courseRevs.find((r) => r.status === 'pending');",
  'const scoredRevs = courseRevs.filter((r) => r.score !== undefined);',
  '? scoredRevs[scoredRevs.length - 1].score',
  'Mes cours ({courses.length})',
  '({chapters.reduce((acc, ch) => acc + ch.courses.length, 0)} cours)',
  '{completedRevs.length} / {courseRevs.length} J',
  "{format(parseISO(course.date), 'dd/MM/yyyy', { locale: fr })}",
  "{format(parseISO(nextPending.scheduledDate), 'dd/MM')}",
  'Prochain : {nextPending.stepLabel}',
  'Dernier score : {lastScore}/100',
]);
assertSource('src/components/AddCourseModal.tsx', [
  "const [subjectId, setSubjectId] = useState<string>(subjects[0]?.id || 'maths');",
  "const [date, setDate] = useState<string>(format(new Date(), 'yyyy-MM-dd'));",
  'if (initialSubjectId) setSubjectId(initialSubjectId);',
  'if (initialDate) setDate(initialDate);',
  'if (initialTitle) setTitle(initialTitle);',
  "else setTitle('');",
  "setDescription('');",
  'const subjectCourses = courses.filter((c) => c.subjectId === subjectId);',
  'const existingChapters = Array.from(new Set(subjectCourses.map((c) => c.chapter)));',
  '{existingChapters.length > 0 && !chapter && (',
  '{existingChapters.slice(0, 3).map((ch) => (',
  'if (!chapter.trim()) {',
  'c.chapter.trim().toLowerCase() === chapter.trim().toLowerCase()',
  'const maxNum = Math.max(...matches.map((c) => c.courseNumber), 0);',
  'setCourseNumber(maxNum + 1);',
  'if (!title.trim() || !chapter.trim()) return;',
  'chapter: chapter.trim(),',
  'title: title.trim(),',
  'description: description.trim() || undefined,',
]);
assertSource('src/App.tsx', [
  'const newId = `course-${Date.now()}`;',
  "createdAt: format(new Date(), 'yyyy-MM-dd'),",
  'const newRevisions = generateInitialRevisions(newCourse, settings.intervals);',
  'const updatedCourses = [newCourse, ...courses];',
  'const updatedRevisions = [...revisions, ...newRevisions];',
  '`C${newCourse.courseNumber} « ${newCourse.title} » ajouté avec ses 10 échéances de révision.`,',
  'const updatedCourses = courses.filter((c) => c.id !== courseId);',
  'const updatedRevisions = revisions.filter((r) => r.courseId !== courseId);',
  "showToast('Cours supprimé', 'Le cours et son calendrier ont été supprimés.', 'info');",
  'subjectId: ev.matchedSubjectId || subjects[0]?.id,',
  'date: ev.date,',
  'title: ev.subjectName,',
  'const success = importAllData(jsonStr);',
  "showToast('Erreur d’importation', 'Le fichier fourni est invalide.', 'warning');",
]);
assertSource('src/components/CourseDetailModal.tsx', [
  'const sortedRevisions = [...revisions].sort((a, b) => a.stepIndex - b.stepIndex);',
  "const completedCount = sortedRevisions.filter((r) => r.status === 'completed').length;",
  'const progressPercent = Math.round((completedCount / sortedRevisions.length) * 100);',
  'const scores = sortedRevisions.filter((r) => r.score !== undefined).map((r) => r.score as number);',
  'const averageScore = scores.length > 0 ? Math.round(scores.reduce((a, b) => a + b, 0) / scores.length) : null;',
  '{rev.score ?? 50}/100',
  "Créé le {format(parseISO(course.date), 'dd MMMM yyyy', { locale: fr })}",
  "Prévu : {format(parseISO(rev.scheduledDate), 'dd MMM yyyy', { locale: fr })}",
  "• Fait le {format(parseISO(rev.completedDate), 'dd/MM')}",
  '{completedCount} / {sortedRevisions.length} effectuées ({progressPercent}%)',
  'if (confirm(`Es-tu sûr de vouloir supprimer définitivement le cours C${course.courseNumber} : ${course.title} ?`)) {',
  "const isOverdue = rev.status === 'overdue';",
]);
assertSource('src/views/StatsView.tsx', [
  "const completedRevisions = revisions.filter((r) => r.status === 'completed');",
  'const scoredRevisions = revisions.filter((r) => r.score !== undefined);',
  'scoredRevisions.reduce((acc, r) => acc + (r.score ?? 0), 0) / scoredRevisions.length',
  ': 50;',
  'revisions.length > 0 ? Math.round((completedRevisions.length / revisions.length) * 100) : 0;',
  'const subjCourses = courses.filter((c) => c.subjectId === subj.id);',
  'return c?.subjectId === subj.id;',
  '? Math.round(subjScored.reduce((a, b) => a + (b.score ?? 0), 0) / subjScored.length)',
  'rate: subjRevs.length > 0 ? Math.round((subjCompleted.length / subjRevs.length) * 100) : 0,',
  '.filter((s) => s.coursesCount > 0);',
  'const weakSubjects = subjectStats.filter((s) => s.avgScore !== null && s.avgScore < 50);',
  '{completedRevisions.length} sur {revisions.length} faites',
  "{overallAvgScore >= 50 ? 'Au-dessus de l’équilibre' : 'Sous l’équilibre'}",
  '>5 j</div>',
  'Sur {subjectStats.length} matières',
  '({st.coursesCount} cours • {st.completedRevs}/{st.totalRevs} J)',
]);
assertSource('src/views/SettingsView.tsx', [
  'pronoteIcalUrl: icalUrl.trim(),',
  'intervals: [...intervals].sort((a, b) => a - b),',
  'const val = parseInt(newIntervalVal, 10);',
  'if (!isNaN(val) && val >= 0 && !intervals.includes(val)) {',
  'const updated = [...intervals, val].sort((a, b) => a - b);',
  'if (intervals.length > 2) {',
  'setIntervals(intervals.filter((i) => i !== val));',
  'setIntervals(DEFAULT_INTERVALS);',
  '{val !== 0 && (',
  'Impact max : ±{Math.round(sensitivity * 100)}%',
  'min="0.1"',
  'max="0.8"',
  'step="0.05"',
  'min="10"',
  'max="40"',
  'onChange={(e) => setEmergencyThreshold(parseInt(e.target.value, 10))}',
  's.id === subj.id ? { ...s, color: e.target.value } : s',
]);
assertSource('src/lib/storage.ts', [
  "INITIALIZED: 'jmethod_initialized_v1',",
  'if (!data.courses || !data.revisions) return false;',
  'if (data.subjects) saveSubjects(data.subjects);',
  'if (data.settings) saveSettings(data.settings);',
  'return raw ? { ...DEFAULT_SETTINGS, ...JSON.parse(raw) } : DEFAULT_SETTINGS;',
]);

const fixtures = {};
const fmt = (ymd, pattern, locale = true) =>
  lib.format(lib.parseISO(ymd), pattern, locale ? { locale: lib.fr } : undefined);
const ymdOf = (d) => lib.format(d, 'yyyy-MM-dd');

// ---------------------------------------------------------------------------
// Planning (PlanningView + RevisionDetailModal)
// ---------------------------------------------------------------------------
const REVISION_PHASE_LABELS = { encodage: 'Encodage', consolidation: 'Consolidation', recuperation: 'Récupération' };

function planningView({ revisions, courses, subjects, pronoteEvents }, { mode, date, subjectId = 'all', phase = 'all' }) {
  const anchor = lib.parseISO(date);
  const now = new Date();
  const courseMap = new Map(courses.map((c) => [c.id, c]));
  const subjectMap = new Map(subjects.map((s) => [s.id, s]));
  const filtered = revisions.filter((r) => {
    const course = courseMap.get(r.courseId);
    return (
      course &&
      subjectMap.has(course.subjectId) &&
      (subjectId === 'all' || course.subjectId === subjectId) &&
      (phase === 'all' || r.phase === phase)
    );
  });
  const weekStart = lib.startOfWeek(anchor, { weekStartsOn: 1 });
  const days =
    mode === 'day'
      ? [anchor]
      : mode === 'week'
        ? lib.eachDayOfInterval({ start: weekStart, end: lib.addDays(weekStart, 6) })
        : lib.eachDayOfInterval({
            start: lib.startOfWeek(lib.startOfMonth(anchor), { weekStartsOn: 1 }),
            end: lib.endOfWeek(lib.endOfMonth(anchor), { weekStartsOn: 1 }),
          });
  const period =
    mode === 'month'
      ? lib.format(anchor, 'MMMM yyyy', { locale: lib.fr })
      : mode === 'day'
        ? lib.format(anchor, 'EEEE d MMMM yyyy', { locale: lib.fr })
        : `${lib.format(weekStart, 'd MMM', { locale: lib.fr })} – ${lib.format(lib.addDays(weekStart, 6), 'd MMM yyyy', { locale: lib.fr })}`;
  return {
    period,
    days: days.map((day) => {
      const key = ymdOf(day);
      const dayRevisions = filtered
        .filter((r) => r.scheduledDate === key)
        .sort((a, b) => Number(a.status === 'completed') - Number(b.status === 'completed') || a.stepIndex - b.stepIndex);
      const events = pronoteEvents.filter(
        (e) => e.date === key && (subjectId === 'all' || e.matchedSubjectId === subjectId)
      );
      const isToday = lib.isSameDay(day, now);
      const out = {
        date: key,
        heading: lib.format(day, mode === 'month' ? 'EEE d' : 'EEEE d MMM', { locale: lib.fr }),
        countLabel: `${dayRevisions.length} séance${dayRevisions.length > 1 ? 's' : ''}${isToday ? ' · Aujourd’hui' : ''}`,
        isToday,
      };
      if (mode === 'month') out.outsideMonth = !lib.isSameMonth(day, anchor);
      if (events.length) {
        out.timetableSummary = `Emploi du temps · ${events.length} cours`;
        out.events = events.map((e) => ({
          id: e.id,
          label: `${e.startTime} · ${e.subjectName}${e.isCancelled ? ' · Annulé' : ''}`,
          subjectId: subjectMap.has(e.matchedSubjectId ?? '') ? e.matchedSubjectId : null,
        }));
      }
      if (dayRevisions.length) {
        out.tiles = dayRevisions.map((r) => {
          const course = courseMap.get(r.courseId);
          const subject = subjectMap.get(course.subjectId);
          const done = r.status === 'completed';
          return {
            revisionId: r.id,
            subjectName: subject.name,
            stepLabel: r.stepLabel,
            phaseLine: `${REVISION_PHASE_LABELS[r.phase]} · C${course.courseNumber}`,
            title: course.title,
            chapter: course.chapter,
            done,
            footer: done ? `Terminée · ${r.score ?? '—'}/100` : 'Voir la séance →',
            accessibilityLabel: `${subject.name}, ${r.stepLabel}, ${REVISION_PHASE_LABELS[r.phase]}, C${course.courseNumber} ${course.title}${done ? ', terminée' : ''}`,
          };
        });
      } else {
        out.empty = 'Aucune séance prévue.';
      }
      return out;
    }),
  };
}

const planCourses = [
  course('course-pl-maths', 'maths', '2026-03-23', { chapter: 'Algèbre', courseNumber: 1, title: 'Cours de test équations' }),
  course('course-pl-fr', 'francais', '2026-03-29', { chapter: 'Grammaire', courseNumber: 2, title: 'Cours de test subordonnées' }),
  course('course-pl-pc', 'physique-chimie', '2026-10-19', { chapter: 'Énergie', courseNumber: 1, title: 'Cours de test énergie' }),
  course('course-pl-svt', 'svt', '2026-12-28', { chapter: 'Génétique', courseNumber: 3, title: 'Cours de test ADN' }),
  course('course-pl-orphan', 'subject-removed', '2026-03-25', { chapter: 'Ancienne matière', courseNumber: 1, title: 'Cours de matière supprimée' }),
];
const planRevisions = [
  ...schedule(planCourses[0], lib.DEFAULT_INTERVALS, 3, 72).map((r) =>
    r.stepIndex === 2 ? (({ score, ...rest }) => rest)(r) : r
  ),
  ...schedule(planCourses[1], lib.DEFAULT_INTERVALS, 1, 40),
  rev('course-pl-fr-emerg-1774810800000', 'course-pl-fr', '2026-03-30', {
    stepIndex: 0, stepLabel: 'J0* (Rattrapage)', phase: 'consolidation', isEmergencySession: true, adjustedIntervalDays: 1,
  }),
  ...schedule(planCourses[2], lib.DEFAULT_INTERVALS, 2, 85),
  ...schedule(planCourses[3], lib.DEFAULT_INTERVALS, 0),
  ...schedule(planCourses[4], lib.DEFAULT_INTERVALS, 0),
].map((r) => (r.id === 'course-pl-maths-rev-6' ? { ...r, status: 'overdue' } : r));
// Out-of-order completion on the same day as a pending one: course-pl-maths J4 done early.
planRevisions.splice(
  planRevisions.findIndex((r) => r.id === 'course-pl-maths-rev-3'),
  1,
  { ...planRevisions.find((r) => r.id === 'course-pl-maths-rev-3'), scheduledDate: '2026-03-30', status: 'completed', completedDate: '2026-03-29', score: 90 }
);
const planEvents = lib.parseICalFeed(
  [
    'BEGIN:VCALENDAR',
    'BEGIN:VEVENT', 'UID:pl-1', 'DTSTART:20260330T080000', 'DTEND:20260330T090000', 'SUMMARY:MATHEMATIQUES', 'END:VEVENT',
    'BEGIN:VEVENT', 'UID:pl-2', 'DTSTART:20260330T100000', 'DTEND:20260330T110000', 'SUMMARY:[ANNULÉ] FRANCAIS', 'STATUS:CANCELLED', 'END:VEVENT',
    'BEGIN:VEVENT', 'UID:pl-3', 'DTSTART:20260330T140000', 'DTEND:20260330T150000', 'SUMMARY:LATIN', 'END:VEVENT',
    'BEGIN:VEVENT', 'UID:pl-4', 'DTSTART:20261025T080000', 'DTEND:20261025T090000', 'SUMMARY:PHYSIQUE-CHIMIE', 'END:VEVENT',
    'BEGIN:VEVENT', 'UID:pl-5', 'DTSTART:20261231T090000', 'DTEND:20261231T100000', 'SUMMARY:SVT', 'END:VEVENT',
    'END:VCALENDAR',
  ].join('\n'),
  SUBJECTS
);
fixtures.planning = { courses: planCourses, revisions: planRevisions, subjects: SUBJECTS, pronoteEvents: planEvents };

const planningCases = [];
function planningCase(name, nowMs, view, note) {
  setNow(nowMs);
  planningCases.push({
    name,
    ...(note ? { note } : {}),
    now: nowField(nowMs),
    fixture: 'planning',
    input: view,
    expected: clone(planningView(fixtures.planning, view)),
  });
}
const nowMarch30 = paris('2026-03-30', '17:00');
planningCase('day_dst_monday_all', nowMarch30, { mode: 'day', date: '2026-03-30' },
  'pending first, then completed; stepIndex ascending; the orphan-subject course is hidden');
planningCase('day_subject_filter_maths', nowMarch30, { mode: 'day', date: '2026-03-30', subjectId: 'maths' },
  'events are filtered by matchedSubjectId too');
planningCase('day_phase_filter_consolidation', nowMarch30, { mode: 'day', date: '2026-03-30', phase: 'consolidation' },
  'phase filter does not apply to timetable events');
planningCase('day_not_today', nowMarch30, { mode: 'day', date: '2026-03-31' });
planningCase('week_of_dst_spring_from_thursday', nowMarch30, { mode: 'week', date: '2026-03-26' });
planningCase('week_from_sunday_starts_previous_monday', nowMarch30, { mode: 'week', date: '2026-03-29' });
planningCase('week_dst_autumn', paris('2026-10-25', '10:00'), { mode: 'week', date: '2026-10-25' });
planningCase('week_across_year_end', paris('2026-12-31', '18:00'), { mode: 'week', date: '2026-12-31' });
planningCase('week_filters_subject_and_phase', nowMarch30, { mode: 'week', date: '2026-03-30', subjectId: 'francais', phase: 'consolidation' });
planningCase('month_march_2026_starts_sunday', nowMarch30, { mode: 'month', date: '2026-03-15' });
planningCase('month_february_2026', nowMarch30, { mode: 'month', date: '2026-02-10' });
planningCase('month_june_2026_starts_monday', nowMarch30, { mode: 'month', date: '2026-06-30' });
planningCase('month_october_2026_dst', paris('2026-10-25', '10:00'), { mode: 'month', date: '2026-10-01' });
planningCase('month_december_2026_spills_into_2027', paris('2026-12-31', '18:00'), { mode: 'month', date: '2026-12-31', subjectId: 'svt' });

const navigateCases = [];
for (const [mode, date, direction] of [
  ['day', '2026-03-28', 1],
  ['day', '2026-03-30', -1],
  ['day', '2026-12-31', 1],
  ['week', '2026-03-26', 1],
  ['week', '2026-10-29', -1],
  ['week', '2026-12-30', 1],
  ['month', '2026-01-31', 1],
  ['month', '2026-03-31', -1],
  ['month', '2026-12-15', 1],
  ['month', '2028-03-31', -1],
]) {
  const fn = mode === 'month' ? lib.addMonths : mode === 'week' ? lib.addWeeks : lib.addDays;
  navigateCases.push({
    name: `${mode}_${date}_${direction > 0 ? 'next' : 'previous'}`,
    input: { mode, date, direction },
    expected: ymdOf(fn(lib.parseISO(date), direction)),
  });
}

function revisionDetail(revisions, courses, subjects, revisionId) {
  const revision = revisions.find((r) => r.id === revisionId);
  const course = courses.find((c) => c.id === revision.courseId);
  const subject = subjects.find((s) => s.id === course?.subjectId);
  if (!course || !subject) return { opens: false };
  const history = revisions
    .filter(
      (r) =>
        r.courseId === course.id &&
        r.id !== revision.id &&
        r.status === 'completed' &&
        (revision.status !== 'completed' ||
          (r.completedDate ?? r.scheduledDate) <= (revision.completedDate ?? revision.scheduledDate))
    )
    .sort(
      (a, b) =>
        (b.completedDate ?? b.scheduledDate).localeCompare(a.completedDate ?? a.scheduledDate) || b.stepIndex - a.stepIndex
    );
  return {
    opens: true,
    subjectName: subject.name,
    title: `C${course.courseNumber} · ${course.title}`,
    chapterLine: `Chapitre : ${course.chapter}`,
    stepLabel: revision.stepLabel,
    phaseLabel: REVISION_PHASE_LABELS[revision.phase],
    plannedLine: `Prévue le ${fmt(revision.scheduledDate, 'd MMMM yyyy')}`,
    description: course.description ?? null,
    historyTitle: `Sessions précédentes (${history.length})`,
    history: history.map((r) => ({
      revisionId: r.id,
      label: `${r.stepLabel} · ${REVISION_PHASE_LABELS[r.phase]}`,
      detail: `${fmt(r.completedDate ?? r.scheduledDate, 'dd/MM/yyyy', false)} · ${r.score !== undefined ? `${r.score}/100` : 'Sans note'}`,
    })),
    historyEmpty: history.length ? null : 'Aucune session terminée avant celle-ci.',
    completedLine:
      revision.status === 'completed'
        ? `Séance terminée${revision.completedDate ? ` le ${fmt(revision.completedDate, 'dd/MM/yyyy', false)}` : ''} · ${revision.score ?? '—'}/100`
        : null,
    canValidate: revision.status !== 'completed',
  };
}
const detailCourse = course('course-rd', 'histoire-geo', '2026-11-02', {
  chapter: 'Révolution', courseNumber: 4, title: 'Cours de test révolution',
});
detailCourse.description = 'Notes de test\nsur deux lignes';
const detailRevs = schedule(detailCourse, lib.DEFAULT_INTERVALS, 5, 65).map((r) => {
  if (r.stepIndex === 1) return { ...r, completedDate: '2026-11-04', score: 20 }; // late, same day as J2
  if (r.stepIndex === 2) return { ...r, completedDate: '2026-11-04', score: 55 };
  if (r.stepIndex === 3) return (({ completedDate, ...rest }) => rest)(r); // completed without completedDate
  if (r.stepIndex === 4) return (({ score, ...rest }) => rest)(r); // completed without score
  return r;
});
detailRevs.splice(2, 0, rev('course-rd-emerg-1', 'course-rd', '2026-11-05', {
  stepIndex: 1, stepLabel: 'J1* (Rattrapage)', phase: 'consolidation', isEmergencySession: true,
  status: 'completed', completedDate: '2026-11-04', score: 60, adjustedIntervalDays: 1,
}));
const detailOther = schedule(course('course-rd-other', 'histoire-geo', '2026-11-02'), lib.DEFAULT_INTERVALS, 3, 99);
fixtures.revisionDetail = {
  courses: [detailCourse, course('course-rd-other', 'histoire-geo', '2026-11-02'), course('course-rd-orphan', 'subject-removed', '2026-11-02')],
  revisions: [...detailRevs, ...detailOther, ...schedule(course('course-rd-orphan', 'subject-removed', '2026-11-02'), [0, 1])],
  subjects: SUBJECTS,
};
const revisionDetailCases = [
  ['pending_revision_lists_all_completed', 'course-rd-rev-5'],
  ['completed_revision_lists_earlier_or_same_day', 'course-rd-rev-2'],
  ['completed_without_completed_date_uses_scheduled_date', 'course-rd-rev-3'],
  ['completed_without_score', 'course-rd-rev-4'],
  ['first_step_has_empty_history', 'course-rd-rev-0'],
  ['emergency_session', 'course-rd-emerg-1'],
  ['orphan_subject_does_not_open', 'course-rd-orphan-rev-0'],
].map(([name, revisionId]) => ({
  name,
  fixture: 'revisionDetail',
  input: { revisionId },
  expected: clone(revisionDetail(fixtures.revisionDetail.revisions, fixtures.revisionDetail.courses, SUBJECTS, revisionId)),
}));

// ---------------------------------------------------------------------------
// Mes cours (CoursesView, CourseDetailModal, AddCourseModal, App handlers)
// ---------------------------------------------------------------------------
function coursesList({ courses, revisions, subjects }, { search = '', subjectId = 'all' }) {
  const filteredCourses = courses.filter((c) => {
    if (subjectId !== 'all' && c.subjectId !== subjectId) return false;
    if (search.trim()) {
      const q = search.toLowerCase();
      return c.title.toLowerCase().includes(q) || c.chapter.toLowerCase().includes(q) || `c${c.courseNumber}`.toLowerCase().includes(q);
    }
    return true;
  });
  const groups = [];
  for (const subj of subjects) {
    const subjCourses = filteredCourses.filter((c) => c.subjectId === subj.id);
    if (!subjCourses.length) continue;
    const chaptersMap = new Map();
    for (const c of subjCourses) {
      if (!chaptersMap.has(c.chapter)) chaptersMap.set(c.chapter, []);
      chaptersMap.get(c.chapter).push(c);
    }
    const chapters = Array.from(chaptersMap.entries()).map(([chapter, list]) => ({
      chapter,
      courses: list.sort((a, b) => a.courseNumber - b.courseNumber),
    }));
    groups.push({
      subjectId: subj.id,
      countLabel: `(${chapters.reduce((acc, ch) => acc + ch.courses.length, 0)} cours)`,
      chapters: chapters.map(({ chapter, courses: list }) => ({
        chapter,
        courses: list.map((c) => {
          const courseRevs = revisions.filter((r) => r.courseId === c.id);
          const completedRevs = courseRevs.filter((r) => r.status === 'completed');
          const progress = courseRevs.length > 0 ? Math.round((completedRevs.length / courseRevs.length) * 100) : 0;
          const nextPending = courseRevs.find((r) => r.status === 'pending');
          const scoredRevs = courseRevs.filter((r) => r.score !== undefined);
          const lastScore = scoredRevs.length > 0 ? scoredRevs[scoredRevs.length - 1].score : undefined;
          return {
            courseId: c.id,
            badge: `C${c.courseNumber}`,
            dateLine: `Cours du ${fmt(c.date, 'dd/MM/yyyy')}`,
            lastScoreLine: lastScore !== undefined ? `Dernier score : ${lastScore}/100` : null,
            counter: `${completedRevs.length} / ${courseRevs.length} J`,
            progress,
            next: nextPending ? { label: `Prochain : ${nextPending.stepLabel}`, date: fmt(nextPending.scheduledDate, 'dd/MM', false) } : null,
          };
        }),
      })),
    });
  }
  return { header: `Mes cours (${courses.length})`, empty: groups.length === 0, groups };
}
const listCourses = [
  course('course-l-fr-new', 'francais', '2026-11-16', { chapter: 'grammaire', courseNumber: 1, title: 'Cours de test accord' }),
  course('course-l-maths-10', 'maths', '2026-11-10', { chapter: 'Arithmétique', courseNumber: 10, title: 'Cours de test PGCD' }),
  course('course-l-maths-2', 'maths', '2026-10-01', { chapter: 'Arithmétique', courseNumber: 2, title: 'Cours de test nombres premiers' }),
  course('course-l-pc', 'physique-chimie', '2026-11-12', { chapter: 'Énergie', courseNumber: 1, title: 'Cours de test énergie cinétique' }),
  course('course-l-fr-old', 'francais', '2026-10-05', { chapter: 'Grammaire', courseNumber: 3, title: 'Cours de test relatives' }),
  course('course-l-maths-1', 'maths', '2026-09-20', { chapter: 'Géométrie', courseNumber: 1, title: 'Cours de test Thalès' }),
  course('course-l-orphan', 'subject-removed', '2026-11-01', { chapter: 'Ancien', courseNumber: 1, title: 'Cours de matière supprimée' }),
];
setNow(paris('2026-11-17'));
const listRevisions = [
  ...schedule(listCourses[1], lib.DEFAULT_INTERVALS, 4, 70),
  ...schedule(listCourses[2], lib.DEFAULT_INTERVALS, 10, 80),
  ...lib.applyAdaptiveReview(schedule(listCourses[3], lib.DEFAULT_INTERVALS, 1, 60), 'course-l-pc-rev-1', 15, SETTINGS, '2026-11-12', '2026-11-13').updatedRevisions,
  ...schedule(listCourses[4], lib.DEFAULT_INTERVALS, 6, 45).map((r) => (r.stepIndex === 6 ? { ...r, status: 'overdue' } : r)),
  ...schedule(listCourses[5], lib.DEFAULT_INTERVALS, 7, 90).map((r) => (r.stepIndex === 0 ? { ...r, score: 30 } : r)),
  ...schedule(listCourses[6], lib.DEFAULT_INTERVALS, 2, 50),
];
// Last score is taken in array order: move J0 of course-l-maths-1 (score 30) to the end.
listRevisions.push(listRevisions.splice(listRevisions.findIndex((r) => r.id === 'course-l-maths-1-rev-0'), 1)[0]);
fixtures.coursesList = { courses: listCourses, revisions: listRevisions, subjects: SUBJECTS };
const coursesListCases = [
  ['no_filter', {}],
  ['subject_filter', { subjectId: 'maths' }],
  ['search_course_number_c1_matches_c10', { search: 'c1' }],
  ['search_upper_case', { search: 'GRAM' }],
  ['search_trailing_space_is_not_trimmed', { search: 'test ' }],
  ['search_trailing_space_no_match', { search: 'thalès ' }],
  ['search_only_spaces_is_ignored', { search: '   ' }],
  ['search_without_accent_does_not_match', { search: 'energie' }],
  ['search_with_accent', { search: 'Énergie' }],
  ['filter_and_search_no_result', { subjectId: 'svt', search: 'test' }],
  ['orphan_subject_never_listed', { search: 'supprimée' }],
].map(([name, filter]) => ({
  name,
  fixture: 'coursesList',
  input: filter,
  expected: clone(coursesList(fixtures.coursesList, filter)),
}));

function courseDetail(c, subjects, revisions) {
  const subject = subjects.find((s) => s.id === c.subjectId) ?? null;
  const sortedRevisions = [...revisions].sort((a, b) => a.stepIndex - b.stepIndex);
  const completedCount = sortedRevisions.filter((r) => r.status === 'completed').length;
  const progressPercent = Math.round((completedCount / sortedRevisions.length) * 100);
  const scores = sortedRevisions.filter((r) => r.score !== undefined).map((r) => r.score);
  const averageScore = scores.length > 0 ? Math.round(scores.reduce((a, b) => a + b, 0) / scores.length) : null;
  return {
    subjectLabel: subject?.name || 'Matière',
    createdLine: `Créé le ${fmt(c.date, 'dd MMMM yyyy')}`,
    averageLine: averageScore !== null ? `Moyenne des séances : ${averageScore}/100` : null,
    notes: c.description ?? null,
    progressLine: `${completedCount} / ${sortedRevisions.length} effectuées (${progressPercent}%)`,
    progressPercent: Number.isNaN(progressPercent) ? 'NaN' : progressPercent,
    timeline: sortedRevisions.map((r) => ({
      revisionId: r.id,
      stepLabel: r.stepLabel,
      phaseLabel: lib.PHASE_CONFIG[r.phase].label,
      emergencyBadge: !!r.isEmergencySession,
      state: r.status === 'completed' ? 'completed' : r.status === 'overdue' ? 'overdue' : 'pending',
      plannedLine: `Prévu : ${fmt(r.scheduledDate, 'dd MMM yyyy')}`,
      doneLine: r.completedDate ? `• Fait le ${fmt(r.completedDate, 'dd/MM', false)}` : null,
      right: r.status === 'completed' ? `${r.score ?? 50}/100` : 'Valider',
    })),
    deleteConfirm: `Es-tu sûr de vouloir supprimer définitivement le cours C${c.courseNumber} : ${c.title} ?`,
  };
}
const courseDetailCases = [
  ['with_emergency_and_overdue', 'course-l-pc'],
  ['overdue_status', 'course-l-fr-old'],
  ['last_score_and_average', 'course-l-maths-1'],
  ['orphan_subject_label_fallback', 'course-l-orphan'],
  ['no_revisions_gives_nan_percent', 'course-l-fr-new'],
].map(([name, courseId]) => {
  const c = listCourses.find((x) => x.id === courseId);
  return {
    name,
    fixture: 'coursesList',
    input: { courseId },
    expected: clone(courseDetail(c, SUBJECTS, listRevisions.filter((r) => r.courseId === courseId))),
  };
});

/** AddCourseModal numbering and validation, then App.handleAddCourse. */
function courseCreate({ courses, revisions, settings, form }) {
  const subjectCourses = courses.filter((c) => c.subjectId === form.subjectId);
  const existingChapters = Array.from(new Set(subjectCourses.map((c) => c.chapter)));
  let courseNumber = 1;
  if (form.chapter.trim()) {
    const matches = courses.filter(
      (c) => c.subjectId === form.subjectId && c.chapter.trim().toLowerCase() === form.chapter.trim().toLowerCase()
    );
    courseNumber = matches.length === 0 ? 1 : Math.max(...matches.map((c) => c.courseNumber), 0) + 1;
  }
  const base = {
    courseNumber,
    submitLabel: `Créer le cours C${courseNumber}`,
    chapterSuggestions: existingChapters.length > 0 && !form.chapter ? existingChapters.slice(0, 3) : [],
  };
  if (!form.title.trim() || !form.chapter.trim()) return { ...base, created: false };
  const courseData = {
    subjectId: form.subjectId,
    chapter: form.chapter.trim(),
    courseNumber,
    title: form.title.trim(),
    date: form.date,
    description: form.description.trim() || undefined,
  };
  const newCourse = { ...courseData, id: `course-${Date.now()}`, createdAt: lib.format(new Date(), 'yyyy-MM-dd') };
  const newRevisions = lib.generateInitialRevisions(newCourse, settings.intervals);
  return {
    ...base,
    created: true,
    course: clone(newCourse),
    newRevisions: clone(newRevisions),
    courseOrder: ids([newCourse, ...courses]),
    revisionOrder: ids([...revisions, ...newRevisions]),
    toast: {
      title: 'Nouveau cours créé !',
      message: `C${newCourse.courseNumber} « ${newCourse.title} » ajouté avec ses 10 échéances de révision.`,
      type: 'success',
    },
  };
}
const courseCreateCases = [];
function createCase(name, nowMs, form, settings = SETTINGS, note) {
  setNow(nowMs);
  courseCreateCases.push({
    name,
    ...(note ? { note } : {}),
    now: nowField(nowMs),
    fixture: 'coursesList',
    input: { settings: clone(settings), form },
    expected: courseCreate({ courses: listCourses, revisions: listRevisions, settings, form }),
  });
}
const nowCreate = paris('2026-11-17', '18:30');
const form = (patch) => ({ subjectId: 'maths', chapter: '', title: '', date: '2026-11-18', description: '', ...patch });
createCase('new_chapter_starts_at_c1', nowCreate, form({ chapter: 'Statistiques', title: 'Cours de test moyenne' }));
createCase('existing_chapter_takes_max_plus_one', nowCreate, form({ chapter: 'Arithmétique', title: 'Cours de test fractions' }),
  SETTINGS, 'C2 and C10 exist: next is C11 (max + 1, gaps are not filled)');
createCase('chapter_match_ignores_case_and_spaces', nowCreate, form({ subjectId: 'francais', chapter: '  GRAMMAIRE ', title: '  Cours de test COD  ', description: '  notes  ' }),
  SETTINGS, 'matches both "grammaire" (C1) and "Grammaire" (C3): C4; chapter and title stored trimmed, but listed as a third chapter group');
createCase('chapter_match_does_not_fold_accents', nowCreate, form({ subjectId: 'physique-chimie', chapter: 'Energie', title: 'Cours de test' }));
createCase('same_chapter_other_subject_is_independent', nowCreate, form({ subjectId: 'svt', chapter: 'Arithmétique', title: 'Cours de test' }));
createCase('custom_intervals_and_empty_description', nowCreate, form({ subjectId: 'anglais', chapter: 'Vocabulary', title: 'Cours de test', date: '2026-12-30', description: '   ' }),
  settingsWith({ intervals: [0, 2, 9] }), 'the toast still says 10 échéances');
createCase('missing_title_is_rejected', nowCreate, form({ chapter: 'Arithmétique', title: '   ' }));
createCase('missing_chapter_is_rejected_and_suggests_chapters', nowCreate, form({ chapter: '', title: 'Cours de test' }),
  SETTINGS, 'suggestions: distinct chapters of the subject in course order, first three, only while the chapter field is empty');
createCase('created_after_midnight', paris('2026-11-18', '00:10'), form({ chapter: 'Géométrie', title: 'Cours de test', date: '2026-11-17' }),
  SETTINGS, 'createdAt is the local date of now; J0 may be in the past');

const prefillCases = [
  ['matched_event', planEvents.find((e) => e.id === 'pl-1'), SUBJECTS],
  ['cancelled_event_has_no_button_but_prefill_rule_is_the_same', planEvents.find((e) => e.id === 'pl-2'), SUBJECTS],
  ['unmatched_event_falls_back_to_first_subject', planEvents.find((e) => e.id === 'pl-3'), SUBJECTS],
  ['unmatched_event_with_no_subject', planEvents.find((e) => e.id === 'pl-3'), []],
].map(([name, ev, subjects]) => ({
  name,
  input: { event: clone(ev), subjects: subjects === SUBJECTS ? 'default' : 'empty' },
  expected: { subjectId: ev.matchedSubjectId || subjects[0]?.id || null, date: ev.date, title: ev.subjectName },
}));

const courseDeleteCases = ['course-l-pc', 'course-l-fr-new', 'missing-course'].map((courseId) => ({
  name: `delete_${courseId}`,
  fixture: 'coursesList',
  input: { courseId },
  expected: {
    courses: ids(listCourses.filter((c) => c.id !== courseId)),
    revisions: ids(listRevisions.filter((r) => r.courseId !== courseId)),
    toast: { title: 'Cours supprimé', message: 'Le cours et son calendrier ont été supprimés.', type: 'info' },
  },
}));

// ---------------------------------------------------------------------------
// Mes progrès (StatsView)
// ---------------------------------------------------------------------------
function stats({ courses, revisions, subjects }) {
  const coursesMap = new Map(courses.map((c) => [c.id, c]));
  const completedRevisions = revisions.filter((r) => r.status === 'completed');
  const scoredRevisions = revisions.filter((r) => r.score !== undefined);
  const overallAvgScore =
    scoredRevisions.length > 0
      ? Math.round(scoredRevisions.reduce((acc, r) => acc + (r.score ?? 0), 0) / scoredRevisions.length)
      : 50;
  const completionRate = revisions.length > 0 ? Math.round((completedRevisions.length / revisions.length) * 100) : 0;
  const subjectStats = subjects
    .map((subj) => {
      const subjCourses = courses.filter((c) => c.subjectId === subj.id);
      const subjRevs = revisions.filter((r) => coursesMap.get(r.courseId)?.subjectId === subj.id);
      const subjCompleted = subjRevs.filter((r) => r.status === 'completed');
      const subjScored = subjRevs.filter((r) => r.score !== undefined);
      const avgScore =
        subjScored.length > 0 ? Math.round(subjScored.reduce((a, b) => a + (b.score ?? 0), 0) / subjScored.length) : null;
      return {
        subjectId: subj.id,
        coursesCount: subjCourses.length,
        totalRevs: subjRevs.length,
        completedRevs: subjCompleted.length,
        rate: subjRevs.length > 0 ? Math.round((subjCompleted.length / subjRevs.length) * 100) : 0,
        avgScore,
      };
    })
    .filter((s) => s.coursesCount > 0);
  const weakSubjects = subjectStats.filter((s) => s.avgScore !== null && s.avgScore < 50);
  return {
    completionRate,
    completionLine: `${completedRevisions.length} sur ${revisions.length} faites`,
    overallAvgScore,
    balanceLabel: overallAvgScore >= 50 ? 'Au-dessus de l’équilibre' : 'Sous l’équilibre',
    streakLabel: '5 j',
    coursesCount: courses.length,
    subjectsLine: `Sur ${subjectStats.length} matières`,
    weakSubjects: weakSubjects.map((s) => s.subjectId),
    subjects: subjectStats.map((s) => ({
      ...s,
      line: `(${s.coursesCount} cours • ${s.completedRevs}/${s.totalRevs} J)`,
      averageTone: s.avgScore === null ? null : s.avgScore >= 50 ? 'good' : 'weak',
    })),
  };
}
const statsCases = [];
function statsCase(name, input, note) {
  statsCases.push({ name, ...(note ? { note } : {}), input: clone(input), expected: stats(input) });
}
statsCase('no_data', { courses: [], revisions: [], subjects: SUBJECTS }, 'average defaults to 50 with no score; rate 0');
{
  const c = course('course-s-late', 'svt', '2026-10-01');
  statsCase('only_overdue_pending', { courses: [c], revisions: schedule(c, lib.DEFAULT_INTERVALS), subjects: SUBJECTS });
}
statsCase('courses_list_fixture', { courses: listCourses, revisions: listRevisions, subjects: SUBJECTS },
  'the orphan-subject course counts in global rate, average and course count but has no subject row');
{
  const a = course('course-s-a', 'maths', '2026-11-01');
  const b = course('course-s-b', 'anglais', '2026-11-01');
  const revs = [
    rev('s-1', 'course-s-a', '2026-11-01', { status: 'completed', completedDate: '2026-11-01', score: 49 }),
    rev('s-2', 'course-s-a', '2026-11-02', { status: 'completed', completedDate: '2026-11-02', score: 50 }),
    rev('s-3', 'course-s-b', '2026-11-01', { status: 'completed', completedDate: '2026-11-01', score: 45 }),
    rev('s-4', 'course-s-b', '2026-11-02', { status: 'completed', completedDate: '2026-11-02', score: 54 }),
    rev('s-5', 'course-s-b', '2026-11-03', { status: 'completed', completedDate: '2026-11-03', score: 0 }),
    rev('s-6', 'course-s-b', '2026-11-04', { score: 100 }),
    rev('s-7', 'course-s-b', '2026-11-05', { status: 'completed', completedDate: '2026-11-05' }),
  ];
  statsCase('rounding_and_weak_subjects', { courses: [a, b], revisions: revs, subjects: SUBJECTS },
    'maths 49.5 rounds to 50 (not weak); a score on a pending revision counts in averages; a completed revision without score does not');
  statsCase('subject_without_revisions_listed_with_null_average',
    { courses: [a, b, course('course-s-c', 'techno', '2026-11-01')], revisions: revs, subjects: SUBJECTS });
  statsCase('score_null_from_import_counts_as_zero',
    { courses: [a], revisions: [rev('s-n', 'course-s-a', '2026-11-01', { status: 'completed', completedDate: '2026-11-01' }), { ...rev('s-m', 'course-s-a', '2026-11-02', { status: 'completed', completedDate: '2026-11-02' }), score: null }], subjects: SUBJECTS },
    'JSON null is !== undefined in TS: it is counted as a score of 0 (probably accidental, see README)');
}

// ---------------------------------------------------------------------------
// Réglages (SettingsView)
// ---------------------------------------------------------------------------
const addInterval = (intervals, raw) => {
  const val = parseInt(raw, 10);
  return !isNaN(val) && val >= 0 && !intervals.includes(val) ? [...intervals, val].sort((a, b) => a - b) : intervals;
};
const removeInterval = (intervals, val) => (intervals.length > 2 ? intervals.filter((i) => i !== val) : intervals);
const settingsCases = [];
for (const [intervals, raw] of [
  [lib.DEFAULT_INTERVALS, '120'],
  [lib.DEFAULT_INTERVALS, '3'],
  [lib.DEFAULT_INTERVALS, '4'],
  [lib.DEFAULT_INTERVALS, '-1'],
  [lib.DEFAULT_INTERVALS, ''],
  [lib.DEFAULT_INTERVALS, 'abc'],
  [lib.DEFAULT_INTERVALS, '12abc'],
  [lib.DEFAULT_INTERVALS, '1.9'],
  [lib.DEFAULT_INTERVALS, ' 30'],
  [lib.DEFAULT_INTERVALS, '+5'],
  [lib.DEFAULT_INTERVALS, '-0'],
  [lib.DEFAULT_INTERVALS, '1e3'],
  [[0, 2, 9], '0'],
]) {
  settingsCases.push({
    name: `add_interval_${JSON.stringify(raw)}`,
    input: { action: 'addInterval', intervals: clone(intervals), value: raw },
    expected: { intervals: addInterval(intervals, raw) },
  });
}
for (const [intervals, val] of [
  [lib.DEFAULT_INTERVALS, 45],
  [lib.DEFAULT_INTERVALS, 13],
  [[0, 7, 30], 7],
  [[0, 7], 7],
  [[0, 7, 30], 0],
]) {
  settingsCases.push({
    name: `remove_interval_${val}_from_${intervals.length}`,
    input: { action: 'removeInterval', intervals: clone(intervals), value: val },
    expected: { intervals: removeInterval(intervals, val), removeButtonShown: val !== 0 },
  });
}
settingsCases.push({
  name: 'reset_intervals',
  input: { action: 'resetIntervals', intervals: [0, 3] },
  expected: { intervals: clone(lib.DEFAULT_INTERVALS) },
});
for (const [name, current, edit] of [
  ['save_sorts_intervals_and_trims_url', SETTINGS, { icalUrl: '  https://example.invalid/agenda.ics  ', sensitivity: 0.65, emergencyThreshold: 15, intervals: [0, 30, 2, 9] }],
  ['save_keeps_other_fields', { ...SETTINGS, lastPronoteSync: '2026-11-17T18:00:00.000Z', userName: 'Élève test', soundEnabled: false }, { icalUrl: '', sensitivity: 0.1, emergencyThreshold: 40, intervals: [0, 1] }],
]) {
  settingsCases.push({
    name,
    input: { action: 'save', settings: clone(current), edit },
    expected: {
      settings: { ...clone(current), pronoteIcalUrl: edit.icalUrl.trim(), sensitivity: edit.sensitivity, emergencyThreshold: edit.emergencyThreshold, intervals: [...edit.intervals].sort((a, b) => a - b) },
    },
  });
}
for (const sensitivity of [0.1, 0.15, 0.4, 0.45, 0.8]) {
  settingsCases.push({
    name: `sensitivity_label_${sensitivity}`,
    input: { action: 'sensitivityLabel', sensitivity },
    expected: { label: `Impact max : ±${Math.round(sensitivity * 100)}%`, valueText: String(sensitivity) },
  });
}
for (const threshold of [10, 30, 40]) {
  settingsCases.push({
    name: `threshold_label_${threshold}`,
    input: { action: 'thresholdLabel', emergencyThreshold: threshold },
    expected: { label: `Seuil d'urgence (Rattrapage J+1) : < ${threshold}/100` },
  });
}
settingsCases.push({
  name: 'subject_color_change_keeps_order_and_other_fields',
  input: { action: 'subjectColor', subjects: 'default', subjectId: 'svt', color: '#123abc' },
  expected: { subjects: SUBJECTS.map((s) => (s.id === 'svt' ? { ...s, color: '#123abc' } : s)) },
});
assertSource('src/views/SettingsView.tsx', [
  "Seuil d'urgence (Rattrapage J+1) :{' '}",
  '<strong className="text-rose-700 font-mono">&lt; {emergencyThreshold}/100</strong>',
]);

// ---------------------------------------------------------------------------
// Import / export (storage.ts, then a reload = initializeDefaultDataIfEmpty)
// ---------------------------------------------------------------------------
function seedStorage(state) {
  memoryStorage.clear();
  lib.saveCourses(state.courses);
  lib.saveRevisions(state.revisions);
  lib.saveSubjects(state.subjects);
  lib.savePronoteEvents(state.pronoteEvents);
  lib.saveSettings(state.settings);
  localStorage.setItem('jmethod_initialized_v1', 'true');
}
const smallCourse = course('course-ie', 'maths', '2026-11-10', { chapter: 'Algèbre', title: 'Cours de test import' });
fixtures.importBefore = {
  courses: [smallCourse],
  revisions: schedule(smallCourse, [0, 1, 3], 1, 70),
  subjects: SUBJECTS.slice(0, 3),
  pronoteEvents: planEvents.slice(0, 2),
  settings: settingsWith({ sensitivity: 0.6, userName: 'Avant import' }),
};
const otherCourse = course('course-ie-2', 'svt', '2026-11-12', { chapter: 'Cellule', title: 'Cours de test exporté' });
const exportedState = {
  courses: [otherCourse],
  revisions: schedule(otherCourse, [0, 2], 1, 55),
  subjects: [...SUBJECTS.slice(5), { id: 'custom-latin', name: 'Latin', color: '#0EA5E9', isCustom: true }],
  pronoteEvents: [],
  settings: settingsWith({ intervals: [0, 2], emergencyThreshold: 20 }),
};
setNow(paris('2026-11-17', '19:30'));
seedStorage(exportedState);
const exportedText = lib.exportAllData();
const importExportCases = [];
function importCase(name, text, note) {
  setNow(paris('2026-11-17', '20:00'));
  seedStorage(fixtures.importBefore);
  const accepted = lib.importAllData(text);
  const reloaded = lib.initializeDefaultDataIfEmpty();
  importExportCases.push({
    name,
    ...(note ? { note } : {}),
    fixture: 'importBefore',
    input: { json: text },
    expected: {
      accepted,
      toast: accepted ? null : { title: 'Erreur d’importation', message: 'Le fichier fourni est invalide.', type: 'warning' },
      reloadsPage: accepted,
      stateAfterReload: clone(reloaded),
    },
  });
}
importExportCases.push({
  name: 'export_format',
  now: nowField(paris('2026-11-17', '19:30')),
  input: { state: clone(exportedState) },
  expected: { json: exportedText, parsed: JSON.parse(exportedText) },
  note: 'JSON.stringify(data, null, 2); pronoteEvents are not exported; settings are the merged settings',
});
importCase('roundtrip_full_export', exportedText, 'pronoteEvents of the previous state are kept');
importCase('invalid_json_rejected', '{ not json');
importCase('json_null_rejected', 'null');
importCase('missing_revisions_rejected', JSON.stringify({ courses: [] }));
importCase('missing_courses_rejected', JSON.stringify({ revisions: [] }));
importCase('empty_arrays_accepted', JSON.stringify({ courses: [], revisions: [] }), 'subjects and settings are kept when absent');
importCase('partial_settings_merged_with_defaults_on_load', JSON.stringify({ courses: [], revisions: [], settings: { sensitivity: 0.25 } }),
  'settings are stored as given and merged over DEFAULT_SETTINGS when loaded');
importCase('empty_subjects_array_replaces_subjects', JSON.stringify({ courses: [], revisions: [], subjects: [] }));
importCase('null_subjects_and_settings_are_ignored', JSON.stringify({ courses: [], revisions: [], subjects: null, settings: null }));
importCase('extra_fields_ignored', JSON.stringify({ version: '9.9', courses: [], revisions: [], pronoteEvents: [{ id: 'x' }], other: 1 }));
importCase('courses_object_is_accepted', JSON.stringify({ courses: {}, revisions: {} }),
  'no shape validation: any truthy value passes (probably accidental)');
importCase('wrong_shape_string_is_accepted', JSON.stringify({ courses: 'oops', revisions: 1 }),
  'no shape validation: any truthy value passes (probably accidental)');

// ---------------------------------------------------------------------------
// Write files
// ---------------------------------------------------------------------------
const header = {
  schemaVersion: 1,
  generator: 'native/shared/domain/generate-vectors.mjs',
  regenerate: 'npm run vectors:domain',
  timeZone: 'Europe/Paris',
  containsPersonalData: false,
  sources: sourceHashes,
};

const vectors = {
  ...header,
  subjectSets,
  fixtures,
  suites: {
    getPhaseForStep: phaseCases,
    generateInitialRevisions: generateCases,
    applyAdaptiveReview: reviewCases,
    applyAdaptiveReviewChains: chainCases,
    getEveningProgramme: programmeCases,
    matchSubject: matchCases,
    parseICalFeed: icalCases,
    generateSamplePronoteFeed: sampleFeedCases,
    initializeDefaultData: demoCases,
    todayScreen: todayCases,
    scoreSubmit: submitCases,
    scorePreview: previewCases,
    planning: planningCases,
    planningNavigate: navigateCases,
    revisionDetail: revisionDetailCases,
    coursesList: coursesListCases,
    courseDetail: courseDetailCases,
    courseCreate: courseCreateCases,
    courseCreatePrefill: prefillCases,
    courseDelete: courseDeleteCases,
    stats: statsCases,
    settingsUpdate: settingsCases,
    importExport: importExportCases,
  },
};

const constants = {
  ...header,
  DEFAULT_SUBJECTS: lib.DEFAULT_SUBJECTS,
  DEFAULT_INTERVALS: lib.DEFAULT_INTERVALS,
  DEFAULT_SETTINGS: lib.DEFAULT_SETTINGS,
  PHASE_CONFIG: lib.PHASE_CONFIG,
  TODAY_PHASES,
  SCORE_PRESETS: [
    { val: 0, label: '0', sub: 'Pas encore' },
    { val: 25, label: '25', sub: 'Difficile' },
    { val: 50, label: '50', sub: 'Ça revient' },
    { val: 75, label: '75', sub: 'Maîtrisé' },
    { val: 100, label: '100', sub: 'Parfait' },
  ],
  SCORE_SLIDER: { min: 0, max: 100, step: 5, initial: 50 },
  REVISION_PHASE_LABELS,
  SETTINGS_SLIDERS: {
    sensitivity: { min: 0.1, max: 0.8, step: 0.05 },
    emergencyThreshold: { min: 10, max: 40, step: 5 },
  },
  INTERVALS_REMOVE_REQUIRES_MORE_THAN: 2,
};
assertSource('src/components/ScoreModal.tsx', [
  "{ val: 0, label: '0', sub: 'Pas encore' },",
  "{ val: 25, label: '25', sub: 'Difficile' },",
  "{ val: 50, label: '50', sub: 'Ça revient' },",
  "{ val: 75, label: '75', sub: 'Maîtrisé' },",
  "{ val: 100, label: '100', sub: 'Parfait' },",
  'step="5"',
  'useState<number>(50)',
]);

/** Pretty JSON where small objects and arrays stay on one line (stable, diff-friendly). */
function stringify(value, indent = '') {
  const flat = JSON.stringify(value);
  if (value === null || typeof value !== 'object' || flat.length + indent.length <= 280) return flat;
  const inner = `${indent}  `;
  if (Array.isArray(value)) {
    if (!value.length) return '[]';
    return `[\n${value.map((item) => inner + stringify(item, inner)).join(',\n')}\n${indent}]`;
  }
  const entries = Object.entries(value).filter(([, v]) => v !== undefined);
  if (!entries.length) return '{}';
  return `{\n${entries.map(([k, v]) => `${inner}${JSON.stringify(k)}: ${stringify(v, inner)}`).join(',\n')}\n${indent}}`;
}
const write = (file, data) => writeFileSync(path.join(here, file), `${stringify(JSON.parse(JSON.stringify(data)))}\n`);
write('domain-vectors.json', vectors);
write('constants.json', constants);

const counts = Object.entries(vectors.suites).map(([k, v]) => `${k}=${v.length}`).join(' ');
console.log(`Wrote ${rel(path.join(here, 'domain-vectors.json'))} and constants.json (${counts})`);
