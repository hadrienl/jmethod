import { useCallback, useState } from "react";
import {
  addDays,
  addMonths,
  addWeeks,
  eachDayOfInterval,
  endOfMonth,
  endOfWeek,
  format,
  isSameDay,
  isSameMonth,
  startOfMonth,
  startOfWeek,
} from "date-fns";
import { fr } from "date-fns/locale";
import { CheckCircle2, ChevronLeft, ChevronRight } from "lucide-react";
import { Course, Phase, PronoteEvent, Revision, Subject } from "../types";
import {
  RevisionDetailModal,
  REVISION_PHASE_LABELS,
} from "../components/RevisionDetailModal";

interface PlanningViewProps {
  courses: Course[];
  revisions: Revision[];
  subjects: Subject[];
  pronoteEvents: PronoteEvent[];
  onValidateRevision: (revisionId: string, score: number) => void;
}
type Mode = "week" | "month" | "day";

export function PlanningView({
  courses,
  revisions,
  subjects,
  pronoteEvents,
  onValidateRevision,
}: PlanningViewProps) {
  const [mode, setMode] = useState<Mode>("week");
  const [date, setDate] = useState(new Date());
  const [layout, setLayout] = useState<"rows" | "columns">("rows");
  const [subjectId, setSubjectId] = useState("all");
  const [phase, setPhase] = useState<Phase | "all">("all");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const closeDetail = useCallback(() => setSelectedId(null), []);
  const courseMap = new Map(courses.map((c) => [c.id, c]));
  const subjectMap = new Map(subjects.map((s) => [s.id, s]));
  const filtered = revisions.filter((r) => {
    const course = courseMap.get(r.courseId);
    return (
      course &&
      subjectMap.has(course.subjectId) &&
      (subjectId === "all" || course.subjectId === subjectId) &&
      (phase === "all" || r.phase === phase)
    );
  });
  const selected = revisions.find((r) => r.id === selectedId);
  const selectedCourse = selected && courseMap.get(selected.courseId);
  const selectedSubject =
    selectedCourse && subjectMap.get(selectedCourse.subjectId);
  const weekStart = startOfWeek(date, { weekStartsOn: 1 });
  const days =
    mode === "day"
      ? [date]
      : mode === "week"
        ? eachDayOfInterval({ start: weekStart, end: addDays(weekStart, 6) })
        : eachDayOfInterval({
            start: startOfWeek(startOfMonth(date), { weekStartsOn: 1 }),
            end: endOfWeek(endOfMonth(date), { weekStartsOn: 1 }),
          });
  const move = (direction: number) =>
    setDate((d) =>
      mode === "month"
        ? addMonths(d, direction)
        : mode === "week"
          ? addWeeks(d, direction)
          : addDays(d, direction),
    );
  const period =
    mode === "month"
      ? format(date, "MMMM yyyy", { locale: fr })
      : mode === "day"
        ? format(date, "EEEE d MMMM yyyy", { locale: fr })
        : `${format(weekStart, "d MMM", { locale: fr })} – ${format(addDays(weekStart, 6), "d MMM yyyy", { locale: fr })}`;

  const tile = (revision: Revision) => {
    const course = courseMap.get(revision.courseId)!;
    const subject = subjectMap.get(course.subjectId)!;
    const done = revision.status === "completed";
    return (
      <button
        key={revision.id}
        onClick={() => setSelectedId(revision.id)}
        className={`planning-tile ${done ? "is-completed" : ""}`}
        style={{ borderLeftColor: subject.color }}
        aria-label={`${subject.name}, ${revision.stepLabel}, ${REVISION_PHASE_LABELS[revision.phase]}, C${course.courseNumber} ${course.title}${done ? ", terminée" : ""}`}
      >
        <span
          className="flex items-start justify-between gap-2 font-bold text-sm"
          style={{ color: subject.color }}
        >
          <span>{subject.name}</span>
          <span className="shrink-0 rounded-md bg-slate-50 px-2 py-1 text-xs">
            {revision.stepLabel}
          </span>
        </span>
        <span className="block text-xs text-slate-600 mt-2">
          {REVISION_PHASE_LABELS[revision.phase]} · C{course.courseNumber}
        </span>
        <span className="block font-bold text-sm leading-relaxed mt-2">
          {course.title}
        </span>
        <span className="block text-xs text-slate-600 mt-1">
          {course.chapter}
        </span>
        {done ? (
          <span className="flex items-center gap-1 text-xs text-emerald-800 font-semibold mt-3">
            <CheckCircle2 size={14} /> Terminée · {revision.score ?? "—"}/100
          </span>
        ) : (
          <span className="block text-xs font-semibold text-violet-800 mt-3">
            Voir la séance →
          </span>
        )}
      </button>
    );
  };

  return (
    <div className="space-y-5">
      <header className="space-y-4">
        <div className="flex flex-wrap justify-between gap-4 items-center">
          <div>
            <h1 className="text-2xl font-extrabold">Planning</h1>
            <p className="text-sm text-slate-600 mt-1">
              Les matières, les étapes et les séances à revoir.
            </p>
          </div>
          <div
            className="flex rounded-xl bg-white border border-slate-200 p-1"
            aria-label="Période affichée"
          >
            {(["day", "week", "month"] as Mode[]).map((value) => (
              <button
                key={value}
                onClick={() => setMode(value)}
                aria-pressed={mode === value}
                className={`px-4 rounded-lg text-sm font-semibold ${mode === value ? "bg-violet-100 text-violet-900" : "text-slate-600"}`}
              >
                {value === "day"
                  ? "Jour"
                  : value === "week"
                    ? "Semaine"
                    : "Mois"}
              </button>
            ))}
          </div>
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <button
              onClick={() => move(-1)}
              aria-label="Période précédente"
              className="p-2 rounded-xl border border-slate-200 bg-white"
            >
              <ChevronLeft size={20} />
            </button>
            <button
              onClick={() => setDate(new Date())}
              className="px-3 text-sm font-semibold text-violet-800"
            >
              Aujourd’hui
            </button>
            <button
              onClick={() => move(1)}
              aria-label="Période suivante"
              className="p-2 rounded-xl border border-slate-200 bg-white"
            >
              <ChevronRight size={20} />
            </button>
          </div>
          <h2 className="font-bold capitalize" aria-live="polite">
            {period}
          </h2>
          {mode === "week" && (
            <div
              className="flex gap-1 border border-slate-200 bg-white rounded-xl p-1"
              aria-label="Disposition de la semaine"
            >
              {(["rows", "columns"] as const).map((value) => (
                <button
                  key={value}
                  onClick={() => setLayout(value)}
                  aria-pressed={layout === value}
                  className={`rounded-lg px-3 text-sm ${layout === value ? "bg-violet-100 text-violet-900 font-bold" : "text-slate-600"}`}
                >
                  {value === "rows" ? "Jours en lignes" : "Colonnes"}
                </button>
              ))}
            </div>
          )}
        </div>
        <div className="flex flex-wrap items-end gap-3 border-b border-slate-200 pb-4">
          <label className="text-xs font-semibold text-slate-600 space-y-1">
            <span className="block">Matière</span>
            <select
              value={subjectId}
              onChange={(e) => setSubjectId(e.target.value)}
              style={{ color: subjectMap.get(subjectId)?.color }}
              className="min-h-11 rounded-xl border border-slate-300 bg-white px-3 text-sm max-w-full"
            >
              <option value="all">Toutes les matières</option>
              {subjects.map((s) => (
                <option key={s.id} value={s.id} style={{ color: s.color }}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="text-xs font-semibold text-slate-600 space-y-1">
            <span className="block">Étape</span>
            <select
              value={phase}
              onChange={(e) => setPhase(e.target.value as Phase | "all")}
              className="min-h-11 rounded-xl border border-slate-300 bg-white px-3 text-sm"
            >
              <option value="all">Toutes les étapes</option>
              {Object.entries(REVISION_PHASE_LABELS).map(([id, label]) => (
                <option key={id} value={id}>
                  {label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </header>
      {mode === "week" && (
        <p className="text-xs text-slate-600">
          {layout === "rows"
            ? "Les jours se lisent de haut en bas. Fais défiler les séances vers la droite si nécessaire."
            : "Fais défiler vers la droite pour parcourir les jours."}{" "}
          Clique sur une tuile pour ouvrir la séance.
        </p>
      )}
      <div
        className={
          mode === "month" || (mode === "week" && layout === "columns")
            ? "planning-scroll"
            : ""
        }
      >
        <div
          className={
            mode === "month"
              ? "planning-month"
              : mode === "week" && layout === "columns"
                ? "planning-columns"
                : "planning-rows"
          }
        >
          {days.map((day) => {
            const key = format(day, "yyyy-MM-dd");
            const dayRevisions = filtered
              .filter((r) => r.scheduledDate === key)
              .sort(
                (a, b) =>
                  Number(a.status === "completed") -
                    Number(b.status === "completed") ||
                  a.stepIndex - b.stepIndex,
              );
            const events = pronoteEvents.filter(
              (e) =>
                e.date === key &&
                (subjectId === "all" || e.matchedSubjectId === subjectId),
            );
            return (
              <section
                key={key}
                className={`planning-day ${isSameDay(day, new Date()) ? "is-today" : ""} ${mode === "month" && !isSameMonth(day, date) ? "outside-month" : ""}`}
                aria-label={format(day, "EEEE d MMMM", { locale: fr })}
              >
                <header className="planning-day-heading">
                  <h3 className="font-bold capitalize">
                    {format(day, mode === "month" ? "EEE d" : "EEEE d MMM", {
                      locale: fr,
                    })}
                  </h3>
                  <p className="text-xs text-slate-600 mt-1">
                    {dayRevisions.length} séance
                    {dayRevisions.length > 1 ? "s" : ""}
                    {isSameDay(day, new Date()) ? " · Aujourd’hui" : ""}
                  </p>
                </header>
                <div className="planning-day-content">
                  {events.length > 0 && (
                    <details className="text-xs text-slate-600 mb-3">
                      <summary className="cursor-pointer py-2">
                        Emploi du temps · {events.length} cours
                      </summary>
                      <div className="flex flex-wrap gap-2 mt-2">
                        {events.map((e) => (
                          <p
                            key={e.id}
                            className="rounded-lg border border-slate-200 p-2 bg-white"
                          >
                            <span>{e.startTime} · </span>
                            <span
                              style={{
                                color: subjectMap.get(e.matchedSubjectId ?? "")
                                  ?.color,
                              }}
                            >
                              {e.subjectName}
                            </span>
                            {e.isCancelled && " · Annulé"}
                          </p>
                        ))}
                      </div>
                    </details>
                  )}
                  {dayRevisions.length ? (
                    <div
                      className="planning-sessions"
                      tabIndex={0}
                      role="region"
                      aria-label={`Séances du ${format(day, "d MMMM", { locale: fr })}`}
                    >
                      {dayRevisions.map(tile)}
                    </div>
                  ) : (
                    <p className="text-sm text-slate-500 py-4">
                      Aucune séance prévue.
                    </p>
                  )}
                </div>
              </section>
            );
          })}
        </div>
      </div>
      {selected && selectedCourse && selectedSubject && (
        <RevisionDetailModal
          key={selected.id}
          revision={selected}
          course={selectedCourse}
          subject={selectedSubject}
          revisions={revisions}
          onClose={closeDetail}
          onValidate={onValidateRevision}
        />
      )}
    </div>
  );
}
