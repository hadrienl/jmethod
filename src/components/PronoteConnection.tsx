import { useEffect, useRef, useState } from "react";
import {
  CheckCircle2,
  LoaderCircle,
  QrCode,
  RefreshCw,
  Unplug,
} from "lucide-react";
import { PronoteEvent } from "../types";

type QR = { url: string; login: string; jeton: string };
interface Connection {
  connected: boolean;
  accountName?: string;
  lastSync?: string;
  eventCount?: number;
  children?: { id: string; name: string }[];
  selectedChildId?: string;
  events?: PronoteEvent[];
}

async function api(
  path: string,
  payload?: unknown,
  signal?: AbortSignal,
): Promise<Connection> {
  let response: Response;
  try {
    response = await fetch(`/api/pronote/${path}`, {
      method: payload === undefined ? "GET" : "POST",
      headers: {
        "X-JMethod-Client": "local-poc",
        ...(payload !== undefined
          ? { "Content-Type": "application/json" }
          : {}),
      },
      credentials: "same-origin",
      body: payload === undefined ? undefined : JSON.stringify(payload),
      signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new Error(
      "Le service de connexion local est indisponible. Relance le POC, puis réessaie.",
    );
  }
  let data: Connection & { error?: string };
  try {
    data = await response.json();
  } catch {
    throw new Error(
      "Le service de connexion local est indisponible. Relance le POC, puis réessaie.",
    );
  }
  if (!response.ok)
    throw new Error(data.error ?? "La connexion Pronote a échoué.");
  return data;
}

export async function decodeQrImage(file: File): Promise<QR> {
  const { default: jsQR } = await import("jsqr");
  if (file.size > 10 * 1024 * 1024)
    throw new Error("Choisis une image de moins de 10 Mo.");
  const url = URL.createObjectURL(file);
  try {
    const image = new Image();
    image.src = url;
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve();
      image.onerror = () =>
        reject(
          new Error(
            "Cette image est illisible. Utilise une capture PNG ou JPEG.",
          ),
        );
    });
    if (
      !image.naturalWidth ||
      !image.naturalHeight ||
      image.naturalWidth * image.naturalHeight > 40_000_000
    )
      throw new Error(
        "Cette image est trop grande. Recadre la capture autour du QR code.",
      );
    const ratio = Math.min(
      1,
      2000 / Math.max(image.naturalWidth, image.naturalHeight),
    );
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(image.naturalWidth * ratio);
    canvas.height = Math.round(image.naturalHeight * ratio);
    const context = canvas.getContext("2d", { willReadFrequently: true });
    if (!context)
      throw new Error("Impossible de lire cette image dans ce navigateur.");
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height);
    const decoded = jsQR(pixels.data, pixels.width, pixels.height, {
      inversionAttempts: "attemptBoth",
    });
    canvas.width = canvas.height = 0;
    if (!decoded)
      throw new Error(
        "Aucun QR code trouvé. Importe une capture nette du QR Pronote.",
      );
    let qr: QR;
    try {
      qr = JSON.parse(decoded.data);
    } catch {
      throw new Error("Ce QR code ne correspond pas à un compte Pronote.");
    }
    if (
      !qr ||
      typeof qr.url !== "string" ||
      typeof qr.login !== "string" ||
      typeof qr.jeton !== "string"
    )
      throw new Error("Ce QR code ne correspond pas à un compte Pronote.");
    return qr;
  } finally {
    URL.revokeObjectURL(url);
  }
}

export function PronoteConnection({
  onSynced,
}: {
  onSynced: (events: PronoteEvent[], syncedAt: string) => void;
}) {
  const local = ["localhost", "127.0.0.1", "[::1]"].includes(
    window.location.hostname,
  );
  const [connection, setConnection] = useState<Connection>({
    connected: false,
  });
  const [checking, setChecking] = useState(local);
  const [busy, setBusy] = useState(false);
  const [qr, setQr] = useState<QR | null>(null);
  const [pin, setPin] = useState("");
  const [filename, setFilename] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!local) return;
    const controller = new AbortController();
    api("status", undefined, controller.signal)
      .then(setConnection)
      .catch((error) => {
        if (error.name !== "AbortError") setError(error.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setChecking(false);
      });
    return () => controller.abort();
  }, [local]);

  async function readFile(file?: File) {
    setQr(null);
    setError("");
    setNotice("");
    setFilename("");
    if (!file) return;
    setBusy(true);
    try {
      setQr(await decodeQrImage(file));
      setFilename(file.name);
    } catch (error) {
      setError(error instanceof Error ? error.message : "Image invalide.");
    } finally {
      setBusy(false);
    }
  }

  async function run(
    action: "connect" | "sync" | "disconnect",
    childId?: string,
  ) {
    setBusy(true);
    setError("");
    setNotice("");
    const submittedPin = pin;
    if (action === "connect") setPin("");
    try {
      const result = await api(
        action,
        action === "connect"
          ? { qr, pin: submittedPin }
          : childId
            ? { childId }
            : {},
      );
      setConnection(result);
      if (result.events) {
        onSynced(result.events, result.lastSync ?? new Date().toISOString());
        setNotice(
          `${result.events.length} créneaux importés pour les 14 prochains jours.`,
        );
      }
      if (action === "connect") {
        setQr(null);
        setFilename("");
        if (input.current) input.current.value = "";
      }
      if (action === "disconnect")
        setNotice(
          "Pronote est déconnecté. Les cours déjà importés restent sur cet appareil.",
        );
    } catch (error) {
      setError(
        error instanceof Error ? error.message : "La connexion a échoué.",
      );
      if (action === "sync") {
        try {
          setConnection(await api("status"));
        } catch {
          /* Preserve the current state and cached courses. */
        }
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <section
      className="surface p-5 sm:p-6 space-y-4"
      aria-labelledby="pronote-qr-title"
    >
      <header className="flex gap-3 items-start">
        <span className="rounded-xl bg-violet-100 text-violet-800 p-2">
          <QrCode size={24} />
        </span>
        <div>
          <h2 id="pronote-qr-title" className="text-lg font-extrabold">
            Connecter Pronote par QR code
          </h2>
          <p className="text-sm text-slate-600 mt-1">
            Retrouve ton emploi du temps sans saisir ton mot de passe.
          </p>
        </div>
      </header>
      {!local ? (
        <p className="rounded-xl bg-violet-50 p-4 text-sm text-violet-950">
          La connexion QR est disponible dans la version locale du POC. La
          version GitHub Pages reste accessible avec le mode démo et le lien
          iCal.
        </p>
      ) : (
        <>
          {checking ? (
            <p
              role="status"
              className="flex gap-2 items-center text-sm text-slate-600"
            >
              <LoaderCircle className="animate-spin" size={18} /> Vérification
              de la connexion…
            </p>
          ) : connection.connected ? (
            <>
              <div className="rounded-2xl bg-emerald-50 border border-emerald-200 p-4">
                <p className="font-bold text-emerald-950 flex gap-2 items-center">
                  <CheckCircle2 size={18} /> Pronote connecté
                </p>
                {connection.accountName && (
                  <p className="text-sm text-slate-700 mt-2">
                    {connection.accountName}
                  </p>
                )}
                <p className="text-xs text-slate-600 mt-2">
                  {connection.eventCount ?? 0} créneaux ·{" "}
                  {connection.lastSync
                    ? `Dernière synchronisation le ${new Date(connection.lastSync).toLocaleString("fr-FR")}`
                    : "Prêt à synchroniser"}
                </p>
              </div>
              {(connection.children?.length ?? 0) > 1 && (
                <label className="block text-sm font-semibold">
                  Enfant
                  <select
                    className="block mt-2 border border-slate-300 rounded-xl px-3 py-2 w-full"
                    disabled={busy}
                    value={connection.selectedChildId ?? ""}
                    onChange={(e) => void run("sync", e.target.value)}
                  >
                    {connection.children?.map((child) => (
                      <option key={child.id} value={child.id}>
                        {child.name}
                      </option>
                    ))}
                  </select>
                </label>
              )}
              <div className="flex flex-wrap gap-3">
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void run("sync")}
                  className="primary-button disabled:opacity-50"
                >
                  {busy ? (
                    <LoaderCircle size={18} className="animate-spin" />
                  ) : (
                    <RefreshCw size={18} />
                  )}{" "}
                  Synchroniser l’emploi du temps
                </button>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void run("disconnect")}
                  className="inline-flex gap-2 items-center rounded-xl border border-slate-300 px-4 py-2 text-sm disabled:opacity-50"
                >
                  <Unplug size={18} /> Déconnecter Pronote
                </button>
              </div>
            </>
          ) : (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                if (qr && /^\d{4}$/.test(pin) && !busy) void run("connect");
              }}
              className="space-y-4"
            >
              <ol className="list-decimal pl-5 text-sm text-slate-700 space-y-2">
                <li>
                  Dans Pronote sur le web, ouvre « QR code de l’application »,
                  près de ton nom.
                </li>
                <li>
                  Choisis un code temporaire à quatre chiffres et génère le QR
                  code.
                </li>
                <li>Fais une capture du QR, puis importe-la ci-dessous.</li>
              </ol>
              <label
                className="block text-sm font-semibold"
                htmlFor="pronote-qr-image"
              >
                Capture du QR code
                <input
                  ref={input}
                  id="pronote-qr-image"
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  disabled={busy}
                  onChange={(e) => void readFile(e.target.files?.[0])}
                  className="block mt-2 w-full max-w-full text-sm rounded-xl border border-slate-300 p-3 file:mr-3 file:rounded-lg file:border-0 file:bg-violet-100 file:px-3 file:py-2 file:text-violet-900"
                />
              </label>
              {qr && (
                <p className="text-sm text-emerald-800" role="status">
                  QR code reconnu : {filename}
                </p>
              )}
              <label
                htmlFor="pronote-qr-pin"
                className="block text-sm font-semibold"
              >
                Code temporaire à quatre chiffres
                <input
                  id="pronote-qr-pin"
                  type="password"
                  inputMode="numeric"
                  autoComplete="off"
                  maxLength={4}
                  pattern="[0-9]{4}"
                  value={pin}
                  onChange={(e) =>
                    setPin(e.target.value.replace(/\D/g, "").slice(0, 4))
                  }
                  disabled={busy}
                  className="block mt-2 w-40 rounded-xl border border-slate-300 bg-white px-4 py-3 tracking-[.4em]"
                  aria-describedby="pronote-qr-hint"
                />
              </label>
              <p id="pronote-qr-hint" className="text-xs text-slate-600">
                Le même code que tu viens de choisir dans Pronote. Le QR doit
                être récent.
              </p>
              <button
                type="submit"
                disabled={!qr || !/^\d{4}$/.test(pin) || busy}
                className="primary-button disabled:opacity-50"
              >
                {busy ? (
                  <LoaderCircle size={18} className="animate-spin" />
                ) : (
                  <QrCode size={18} />
                )}{" "}
                {busy ? "Connexion en cours…" : "Connecter mon compte Pronote"}
              </button>
            </form>
          )}
          <p className="text-xs text-slate-600">
            Connexion non officielle en lecture seule. La session reste sur ce
            Mac jusqu’à sa déconnexion, son expiration ou l’arrêt du POC.
          </p>
        </>
      )}
      {error && (
        <p
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 text-rose-900 p-3 text-sm"
        >
          {error}
        </p>
      )}
      {notice && (
        <p
          role="status"
          className="rounded-xl border border-emerald-200 bg-emerald-50 text-emerald-900 p-3 text-sm"
        >
          {notice}
        </p>
      )}
    </section>
  );
}
