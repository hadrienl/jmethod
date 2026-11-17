import { useState } from "react";
import { BookOpen, Download, ExternalLink, LoaderCircle } from "lucide-react";
import { pronoteApi } from "../lib/pronoteApi";

interface Attachment {
  id?: string;
  name: string;
  kind: "file" | "link";
  url?: string;
}
interface Content {
  id: string;
  subjectName: string;
  date: string;
  title: string;
  description: string;
  category: string;
  attachments: Attachment[];
}
interface Homework {
  id: string;
  subjectName: string;
  dueDate: string;
  description: string;
  done: boolean;
  attachments: Attachment[];
}
interface Notebook {
  from: string;
  to: string;
  lessonCount: number;
  contents: Content[];
  homework: Homework[];
  attachmentCount: number;
  fetchedAt: string;
  skippedEntries?: number;
}
const dateLabel = (value: string) =>
  new Date(`${value}T12:00:00`).toLocaleDateString("fr-FR");

export function PronoteNotebookPreview() {
  const [data, setData] = useState<Notebook | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [tab, setTab] = useState<"contents" | "homework">("contents");
  const [expanded, setExpanded] = useState(false);
  const [downloading, setDownloading] = useState<string | null>(null);

  async function load() {
    setBusy(true);
    setError("");
    try {
      setData(await pronoteApi<Notebook>("notebook", {}));
      setExpanded(false);
    } catch (error) {
      setError(
        error instanceof Error
          ? error.message
          : "Lecture du cahier de textes impossible.",
      );
    } finally {
      setBusy(false);
    }
  }

  async function download(file: Attachment) {
    if (!file.id) return;
    setDownloading(file.id);
    setError("");
    try {
      const response = await fetch(
        `/api/pronote/attachment/${encodeURIComponent(file.id)}`,
        {
          headers: { "X-JMethod-Client": "local-poc" },
          credentials: "same-origin",
        },
      );
      if (!response.ok) {
        const body = await response.json();
        throw new Error(body.error ?? "Document indisponible.");
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = file.name;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      setError(
        error instanceof Error ? error.message : "Document indisponible.",
      );
    } finally {
      setDownloading(null);
    }
  }

  function attachments(files: Attachment[]) {
    return files.length ? (
      <ul className="space-y-2 mt-3">
        {files.map((file, index) => (
          <li key={file.id ?? index} className="min-w-0">
            {file.kind === "link" &&
            file.url &&
            /^https?:\/\//i.test(file.url) ? (
              <a
                href={file.url}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex gap-2 items-center text-sm text-violet-800 underline break-all"
              >
                <ExternalLink size={15} className="shrink-0" />
                {file.name}
              </a>
            ) : (
              <button
                type="button"
                disabled={!!downloading}
                onClick={() => void download(file)}
                className="flex gap-2 items-center text-left text-sm text-violet-800 rounded-xl bg-violet-50 px-3 py-2 max-w-full disabled:opacity-50"
              >
                {downloading === file.id ? (
                  <LoaderCircle size={16} className="animate-spin shrink-0" />
                ) : (
                  <Download size={16} className="shrink-0" />
                )}
                <span className="break-all">{file.name}</span>
              </button>
            )}
          </li>
        ))}
      </ul>
    ) : null;
  }

  const rows = data ? (tab === "contents" ? data.contents : data.homework) : [];
  return (
    <section
      className="border-t border-slate-200 pt-5 space-y-4"
      aria-labelledby="pronote-notebook-title"
    >
      <div>
        <h3
          id="pronote-notebook-title"
          className="font-bold flex items-center gap-2"
        >
          <BookOpen size={18} /> Vérifier les cours disponibles
        </h3>
        <p className="text-sm text-slate-600 mt-2">
          Lis les contenus publiés dans le cahier de textes, les devoirs et
          leurs pièces jointes. Cet aperçu ne crée pas encore de fiches de
          révision.
        </p>
      </div>
      <button
        type="button"
        onClick={() => void load()}
        disabled={busy}
        className="rounded-xl border border-violet-300 text-violet-900 bg-violet-50 px-4 py-3 inline-flex gap-2 items-center font-bold text-sm disabled:opacity-50"
      >
        {busy ? (
          <LoaderCircle size={18} className="animate-spin" />
        ) : (
          <BookOpen size={18} />
        )}{" "}
        {busy
          ? "Lecture du cahier de textes…"
          : data
            ? "Actualiser le cahier de textes"
            : "Lire le cahier de textes"}
      </button>
      {error && (
        <p
          role="alert"
          className="text-sm rounded-xl bg-rose-50 text-rose-900 p-3"
        >
          {error}
        </p>
      )}
      {data && (
        <>
          <div role="status" className="rounded-xl bg-slate-50 p-4 text-sm">
            <p className="font-bold">
              {data.contents.length} contenus de cours · {data.homework.length}{" "}
              devoirs · {data.attachmentCount} pièces jointes et liens
            </p>
            <p className="text-xs text-slate-600 mt-2">
              Du {dateLabel(data.from)} au {dateLabel(data.to)} ·{" "}
              {data.lessonCount} créneaux consultés
            </p>
          </div>
          {!!data.skippedEntries && (
            <p className="text-sm text-amber-900">
              {data.skippedEntries} entrée(s) sans date exploitable n’ont pas
              été affichées.
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            {(["contents", "homework"] as const).map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={tab === value}
                onClick={() => {
                  setTab(value);
                  setExpanded(false);
                }}
                className={`rounded-xl px-4 py-2 font-semibold text-sm ${tab === value ? "bg-violet-100 text-violet-900" : "border border-slate-200 text-slate-600"}`}
              >
                {value === "contents"
                  ? `Cours publiés (${data.contents.length})`
                  : `Devoirs (${data.homework.length})`}
              </button>
            ))}
          </div>
          {!rows.length ? (
            <p className="text-sm text-slate-600">
              Aucun{" "}
              {tab === "contents" ? "contenu de cours publié" : "devoir publié"}{" "}
              sur cette période. Les créneaux d’emploi du temps ne contiennent
              pas forcément de leçon.
            </p>
          ) : (
            <div className="space-y-3">
              {rows.slice(0, expanded ? undefined : 8).map((row) => (
                <details
                  key={row.id}
                  className="border border-slate-200 rounded-2xl p-4 min-w-0"
                >
                  <summary className="cursor-pointer text-sm font-semibold leading-relaxed">
                    <span>{row.subjectName}</span>
                    <span className="text-xs text-slate-600 font-normal ml-2">
                      {"date" in row
                        ? dateLabel(row.date)
                        : `Pour le ${dateLabel(row.dueDate)}`}
                    </span>
                    <span className="block mt-2">
                      {"title" in row
                        ? row.title || "Séance publiée sans titre"
                        : row.description.slice(0, 130) || "Travail à faire"}
                    </span>
                  </summary>
                  <div className="mt-3 space-y-2 text-sm text-slate-700">
                    {"category" in row && row.category && (
                      <p className="text-xs text-slate-600">{row.category}</p>
                    )}
                    <p className="whitespace-pre-wrap break-words leading-relaxed">
                      {row.description || "Aucune description publiée."}
                    </p>
                    {"done" in row && (
                      <p className="text-xs text-slate-600">
                        Statut Pronote : {row.done ? "fait" : "à faire"}
                      </p>
                    )}
                    {attachments(row.attachments)}
                  </div>
                </details>
              ))}
            </div>
          )}
          {rows.length > 8 && (
            <button
              type="button"
              onClick={() => setExpanded((value) => !value)}
              className="text-sm font-semibold text-violet-800 rounded-xl px-3 py-2"
            >
              {expanded
                ? "Réduire la liste"
                : `Afficher les ${rows.length - 8} autres`}
            </button>
          )}
        </>
      )}
    </section>
  );
}
