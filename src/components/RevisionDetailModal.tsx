import { useEffect, useRef, useState } from "react";
import { format, parseISO } from "date-fns";
import { fr } from "date-fns/locale";
import { CheckCircle2, X } from "lucide-react";
import { Course, Revision, Subject } from "../types";

export const REVISION_PHASE_LABELS = {
  encodage: "Encodage",
  consolidation: "Consolidation",
  recuperation: "Récupération",
};

interface Props {
  revision: Revision;
  course: Course;
  subject: Subject;
  revisions: Revision[];
  onClose: () => void;
  onValidate: (revisionId: string, score: number) => void;
}

export function RevisionDetailModal({
  revision,
  course,
  subject,
  revisions,
  onClose,
  onValidate,
}: Props) {
  const [score, setScore] = useState(50);
  const [confirming, setConfirming] = useState(false);
  const dialogRef = useRef<HTMLDivElement>(null);
  const history = revisions
    .filter(
      (r) =>
        r.courseId === course.id &&
        r.id !== revision.id &&
        r.status === "completed" &&
        (revision.status !== "completed" ||
          (r.completedDate ?? r.scheduledDate) <=
            (revision.completedDate ?? revision.scheduledDate)),
    )
    .sort(
      (a, b) =>
        (b.completedDate ?? b.scheduledDate).localeCompare(
          a.completedDate ?? a.scheduledDate,
        ) || b.stepIndex - a.stepIndex,
    );

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const dialog = dialogRef.current;
    dialog?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const listener = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      }
      if (event.key !== "Tab" || !dialog) return;
      const items = Array.from(
        dialog.querySelectorAll<HTMLElement>(
          "button:not(:disabled), input, summary",
        ),
      ).filter((el) => el.getClientRects().length > 0);
      const first = items[0],
        last = items[items.length - 1];
      if (
        event.shiftKey &&
        (document.activeElement === first || document.activeElement === dialog)
      ) {
        event.preventDefault();
        last?.focus();
      } else if (
        !event.shiftKey &&
        (document.activeElement === last || document.activeElement === dialog)
      ) {
        event.preventDefault();
        first?.focus();
      }
    };
    document.addEventListener("keydown", listener);
    return () => {
      document.removeEventListener("keydown", listener);
      document.body.style.overflow = previousOverflow;
      previous?.focus();
    };
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 bg-slate-950/60 flex items-end sm:items-center justify-center sm:p-5">
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="revision-detail-title"
        tabIndex={-1}
        className="bg-white rounded-t-3xl sm:rounded-3xl w-full max-w-2xl max-h-[92dvh] overflow-y-auto p-5 sm:p-7 space-y-5"
      >
        <header className="flex justify-between gap-4 items-start">
          <div>
            <p className="font-bold" style={{ color: subject.color }}>
              {subject.name}
            </p>
            <h2
              id="revision-detail-title"
              className="text-xl sm:text-2xl font-extrabold mt-2"
            >
              C{course.courseNumber} · {course.title}
            </h2>
            <p className="text-sm text-slate-600 mt-2">
              Chapitre : {course.chapter}
            </p>
          </div>
          <button
            onClick={onClose}
            aria-label="Fermer le détail de la séance"
            className="shrink-0 rounded-xl p-2 hover:bg-slate-100"
          >
            <X size={20} />
          </button>
        </header>
        <div className="flex flex-wrap gap-3 items-center rounded-2xl bg-slate-50 border border-slate-200 p-4">
          <span
            className="font-extrabold text-lg"
            style={{ color: subject.color }}
          >
            {revision.stepLabel}
          </span>
          <span className="font-semibold">
            {REVISION_PHASE_LABELS[revision.phase]}
          </span>
          <span className="text-sm text-slate-600">
            Prévue le{" "}
            {format(parseISO(revision.scheduledDate), "d MMMM yyyy", {
              locale: fr,
            })}
          </span>
        </div>
        {course.description && (
          <section>
            <h3 className="font-bold text-sm mb-2">À revoir</h3>
            <p className="text-sm text-slate-700 whitespace-pre-wrap leading-relaxed">
              {course.description}
            </p>
          </section>
        )}
        <details className="rounded-2xl border border-slate-200 p-4">
          <summary className="font-semibold cursor-pointer py-2">
            Sessions précédentes ({history.length})
          </summary>
          {history.length ? (
            <ol className="divide-y divide-slate-100 mt-3">
              {history.map((r) => (
                <li
                  key={r.id}
                  className="py-3 flex flex-wrap justify-between gap-2 text-sm"
                >
                  <span>
                    <strong style={{ color: subject.color }}>
                      {r.stepLabel}
                    </strong>{" "}
                    · {REVISION_PHASE_LABELS[r.phase]}
                  </span>
                  <span className="text-slate-600">
                    {format(
                      parseISO(r.completedDate ?? r.scheduledDate),
                      "dd/MM/yyyy",
                    )}{" "}
                    · {r.score !== undefined ? `${r.score}/100` : "Sans note"}
                  </span>
                </li>
              ))}
            </ol>
          ) : (
            <p className="text-sm text-slate-600 mt-3">
              Aucune session terminée avant celle-ci.
            </p>
          )}
        </details>
        {revision.status === "completed" ? (
          <p className="rounded-xl bg-emerald-50 text-emerald-900 p-4 flex items-center gap-2">
            <CheckCircle2 size={20} /> Séance terminée
            {revision.completedDate
              ? ` le ${format(parseISO(revision.completedDate), "dd/MM/yyyy")}`
              : ""}{" "}
            · {revision.score ?? "—"}/100
          </p>
        ) : (
          <>
            <section className="rounded-2xl bg-slate-50 p-4 space-y-3">
              <label
                htmlFor="revision-detail-score"
                className="font-semibold text-sm block"
              >
                Ta mémorisation : {score}/100
              </label>
              <input
                id="revision-detail-score"
                type="range"
                min="0"
                max="100"
                step="5"
                value={score}
                onChange={(e) => {
                  setScore(Number(e.target.value));
                  setConfirming(false);
                }}
                className="w-full accent-violet-700"
              />
              <p className="text-xs text-slate-600">
                50 conserve le rythme prévu. Ta note ajuste les prochaines
                révisions.
              </p>
            </section>
            {confirming ? (
              <section
                role="group"
                aria-labelledby="validation-question"
                className="rounded-2xl bg-violet-50 border border-violet-200 p-4"
              >
                <h3 id="validation-question" className="font-bold">
                  Terminer cette séance avec {score}/100 ?
                </h3>
                <p className="text-sm text-slate-600 mt-2">
                  Elle sera enregistrée comme faite aujourd’hui et les
                  prochaines dates seront ajustées.
                </p>
                <div className="flex flex-wrap gap-3 mt-4">
                  <button
                    autoFocus
                    onClick={() => {
                      onValidate(revision.id, score);
                      onClose();
                    }}
                    className="primary-button"
                  >
                    Confirmer la validation
                  </button>
                  <button
                    onClick={() => setConfirming(false)}
                    className="px-4 py-2 rounded-xl border border-slate-300 bg-white"
                  >
                    Revenir
                  </button>
                </div>
              </section>
            ) : (
              <button
                onClick={() => setConfirming(true)}
                className="primary-button w-full"
              >
                <CheckCircle2 size={18} /> Valider la séance
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
}
