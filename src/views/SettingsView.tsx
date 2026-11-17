import React, { useState } from 'react';
import {
  Settings as SettingsIcon,
  Calendar,
  Sliders,
  HelpCircle,
  Download,
  Upload,
  RefreshCw,
  Palette,
  CheckCircle2,
  AlertCircle,
} from 'lucide-react';
import { AppSettings, Subject, PronoteEvent } from '../types';
import { PronoteConnection } from '../components/PronoteConnection';
import { DEFAULT_INTERVALS } from '../lib/constants';

interface SettingsViewProps {
  settings: AppSettings;
  subjects: Subject[];
  onUpdateSettings: (newSettings: AppSettings) => void;
  onUpdateSubjects: (newSubjects: Subject[]) => void;
  onOpenPronoteTutorial: () => void;
  onSyncPronote: (url: string) => void;
  onPronoteSynced: (events: PronoteEvent[], syncedAt: string) => void;
  onExportData: () => void;
  onImportData: (jsonStr: string) => void;
  onResetDemoData: () => void;
}

export const SettingsView: React.FC<SettingsViewProps> = ({
  settings,
  subjects,
  onUpdateSettings,
  onUpdateSubjects,
  onOpenPronoteTutorial,
  onSyncPronote,
  onPronoteSynced,
  onExportData,
  onImportData,
  onResetDemoData,
}) => {
  const [icalUrl, setIcalUrl] = useState<string>(settings.pronoteIcalUrl);
  const [sensitivity, setSensitivity] = useState<number>(settings.sensitivity);
  const [emergencyThreshold, setEmergencyThreshold] = useState<number>(settings.emergencyThreshold);
  const [intervals, setIntervals] = useState<number[]>(settings.intervals);
  const [newIntervalVal, setNewIntervalVal] = useState<string>('');
  const [importError, setImportError] = useState<string | null>(null);
  const [saveSuccess, setSaveSuccess] = useState<boolean>(false);

  // Handle saving general settings
  const handleSaveSettings = () => {
    onUpdateSettings({
      ...settings,
      pronoteIcalUrl: icalUrl.trim(),
      sensitivity,
      emergencyThreshold,
      intervals: [...intervals].sort((a, b) => a - b),
    });
    setSaveSuccess(true);
    setTimeout(() => setSaveSuccess(false), 2500);
  };

  // Add custom interval
  const handleAddInterval = () => {
    const val = parseInt(newIntervalVal, 10);
    if (!isNaN(val) && val >= 0 && !intervals.includes(val)) {
      const updated = [...intervals, val].sort((a, b) => a - b);
      setIntervals(updated);
      setNewIntervalVal('');
    }
  };

  // Remove interval
  const handleRemoveInterval = (val: number) => {
    if (intervals.length > 2) {
      setIntervals(intervals.filter((i) => i !== val));
    }
  };

  // Reset to default intervals
  const handleResetIntervals = () => {
    setIntervals(DEFAULT_INTERVALS);
  };

  // File import
  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      try {
        const text = event.target?.result as string;
        onImportData(text);
        setImportError(null);
      } catch {
        setImportError('Fichier de sauvegarde invalide');
      }
    };
    reader.readAsText(file);
  };

  return (
    <div className="space-y-6 pb-20 md:pb-8 animate-fade-in max-w-4xl">
      {/* Header */}
      <div className="bg-white/90 border border-slate-200 rounded-2xl p-6">
        <h1 className="text-2xl font-black text-slate-900 tracking-tight flex items-center gap-2.5">
          <SettingsIcon className="w-6 h-6 text-sky-700" />
          Tes réglages
        </h1>
        <p className="text-xs text-slate-600 mt-0.5">
          Personnalise tes intervalles de J, la sensibilité de l’algorithme et la connexion Pronote
        </p>
      </div>

      {saveSuccess && (
        <div className="p-3.5 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-700 text-xs font-semibold flex items-center gap-2">
          <CheckCircle2 className="w-4 h-4" />
          Paramètres enregistrés avec succès !
        </div>
      )}

      <PronoteConnection onSynced={onPronoteSynced}/>

      <details className="rounded-2xl border border-slate-200 p-4 bg-white"><summary className="cursor-pointer font-semibold py-2">Autre méthode : lien iCal</summary>
      {/* 1. PRONOTE SYNCHRONIZATION VIA ICAL */}
      <div className="rounded-2xl border border-slate-200 bg-white/80 p-6 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-2 bg-sky-500/10 text-sky-700 rounded-xl">
              <Calendar className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-base font-bold text-slate-900">Connexion Pronote (Flux iCal)</h2>
              <p className="text-xs text-slate-600">
                Importe ton emploi du temps depuis ton lien Pronote
              </p>
            </div>
          </div>

          <button
            onClick={onOpenPronoteTutorial}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-slate-100 hover:bg-slate-200 text-sky-700 text-xs font-semibold rounded-lg transition-colors border border-slate-300"
          >
            <HelpCircle className="w-3.5 h-3.5" />
            Tutoriel pas à pas
          </button>
        </div>

        <div className="space-y-2">
          <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider">
            Lien d'abonnement iCal Pronote
          </label>
          <div className="flex flex-col sm:flex-row gap-2">
            <input
              type="url"
              placeholder="https://...pronote.../mon_agenda.ics?token=..."
              value={icalUrl}
              onChange={(e) => setIcalUrl(e.target.value)}
              className="flex-1 bg-slate-50 border border-slate-200 rounded-xl px-3.5 py-2.5 text-xs text-slate-900 placeholder-slate-600 focus:outline-none focus:border-sky-500 font-mono"
            />
            <button
              onClick={() => onSyncPronote(icalUrl)}
              className="flex items-center justify-center gap-2 px-4 py-2.5 bg-violet-700 hover:bg-violet-800 text-white rounded-xl text-xs font-bold transition-all shadow-md shadow-sky-500/20 whitespace-nowrap"
            >
              <RefreshCw className="w-3.5 h-3.5" />
              Synchroniser
            </button>
          </div>
          <p className="text-[11px] text-slate-600">
            Ce lien est en lecture seule et ne transmet aucun mot de passe. Il est actualisé en continu par Pronote.
          </p>
        </div>
      </div>

      </details>

      {/* 2. ADAPTIVE ALGORITHM SETTINGS */}
      <div className="rounded-2xl border border-slate-200 bg-white/80 p-6 space-y-5">
        <div className="flex items-center gap-2.5">
          <div className="p-2 bg-indigo-500/10 text-indigo-700 rounded-xl">
            <Sliders className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-base font-bold text-slate-900">Algorithme Adaptatif (Point pivot à 50)</h2>
            <p className="text-xs text-slate-600">
              Règle la dynamique d’espacement et les seuils de sécurité
            </p>
          </div>
        </div>

        {/* Sensitivity slider */}
        <div className="space-y-2 bg-slate-50 p-4 rounded-xl border border-slate-200">
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold text-slate-800">
              Sensibilité de l'espacement : <strong className="text-sky-700 font-mono">{sensitivity}</strong>
            </span>
            <span className="text-slate-600 font-mono text-[11px]">
              Impact max : ±{Math.round(sensitivity * 100)}%
            </span>
          </div>

          <input
            type="range"
            min="0.1"
            max="0.8"
            step="0.05"
            value={sensitivity}
            onChange={(e) => setSensitivity(parseFloat(e.target.value))}
            className="w-full h-2 bg-slate-100 rounded-lg appearance-none cursor-pointer accent-sky-400"
          />

          <div className="text-[11px] text-slate-600 flex items-center justify-between pt-1">
            <span>Doux (±10%)</span>
            <span className="text-sky-700 font-medium">Équilibré (±40%)</span>
            <span>Agressif (±80%)</span>
          </div>
        </div>

        {/* Emergency threshold */}
        <div className="space-y-2 bg-slate-50 p-4 rounded-xl border border-slate-200">
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold text-slate-800">
              Seuil d'urgence (Rattrapage J+1) :{' '}
              <strong className="text-rose-700 font-mono">&lt; {emergencyThreshold}/100</strong>
            </span>
          </div>

          <input
            type="range"
            min="10"
            max="40"
            step="5"
            value={emergencyThreshold}
            onChange={(e) => setEmergencyThreshold(parseInt(e.target.value, 10))}
            className="w-full h-2 bg-slate-100 rounded-lg appearance-none cursor-pointer accent-rose-400"
          />

          <p className="text-[11px] text-slate-600">
            Toute note inférieure à ce seuil déclenche immédiatement une séance de secours le lendemain (J+1).
          </p>
        </div>
      </div>

      {/* 3. INTERVALLES DES J */}
      <div className="rounded-2xl border border-slate-200 bg-white/80 p-6 space-y-4">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-base font-bold text-slate-900">Intervalles des J</h2>
            <p className="text-xs text-slate-600">
              Paliers de révision appliqués à chaque nouveau cours
            </p>
          </div>
          <button
            onClick={handleResetIntervals}
            className="text-xs text-sky-700 hover:text-sky-700 font-semibold"
          >
            Rétablir valeurs initiales
          </button>
        </div>

        {/* Existing intervals tags */}
        <div className="flex flex-wrap gap-2">
          {intervals.map((val) => (
            <div
              key={val}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-slate-50 border border-slate-200 text-xs font-mono font-bold text-slate-900"
            >
              <span>J{val}</span>
              {val !== 0 && (
                <button
                  onClick={() => handleRemoveInterval(val)}
                  className="text-slate-500 hover:text-rose-700 ml-1"
                >
                  ✕
                </button>
              )}
            </div>
          ))}
        </div>

        {/* Add interval */}
        <div className="flex items-center gap-2 pt-2">
          <input
            type="number"
            placeholder="Nouveau J (ex: 120)"
            value={newIntervalVal}
            onChange={(e) => setNewIntervalVal(e.target.value)}
            className="w-40 bg-slate-50 border border-slate-200 rounded-xl px-3 py-1.5 text-xs text-slate-900 placeholder-slate-600 focus:outline-none focus:border-sky-500 font-mono"
          />
          <button
            onClick={handleAddInterval}
            className="px-3 py-1.5 bg-slate-100 hover:bg-slate-200 text-slate-900 rounded-xl text-xs font-semibold"
          >
            Ajouter un palier
          </button>
        </div>
      </div>

      {/* 4. CODE COULEURS DES MATIERES */}
      <div className="rounded-2xl border border-slate-200 bg-white/80 p-6 space-y-4">
        <div className="flex items-center gap-2.5">
          <div className="p-2 bg-pink-500/10 text-pink-400 rounded-xl">
            <Palette className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-base font-bold text-slate-900">Les couleurs de tes matières</h2>
            <p className="text-xs text-slate-600">
              Choisis les couleurs qui t’aident à te repérer.
            </p>
          </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          {subjects.map((subj) => (
            <div
              key={subj.id}
              className="flex items-center justify-between p-3 rounded-xl bg-slate-50 border border-slate-200"
            >
              <div className="flex items-center gap-2.5">
                <input
                  type="color"
                  value={subj.color}
                  onChange={(e) => {
                    const updated = subjects.map((s) =>
                      s.id === subj.id ? { ...s, color: e.target.value } : s
                    );
                    onUpdateSubjects(updated);
                  }}
                  className="w-6 h-6 rounded cursor-pointer border-0 bg-transparent p-0"
                />
                <span className="text-xs font-bold text-slate-900">{subj.name}</span>
              </div>
              <span className="text-[11px] font-mono text-slate-600 uppercase">{subj.color}</span>
            </div>
          ))}
        </div>
      </div>

      {/* 5. BACKUP & EXPORT / IMPORT */}
      <div className="rounded-2xl border border-slate-200 bg-white/80 p-6 space-y-4">
        <h2 className="text-base font-bold text-slate-900">Sauvegarde & Export des données</h2>
        <p className="text-xs text-slate-600">
          Tes données sont stockées localement dans ton navigateur. Tu peux exporter une copie de
          sécurité à tout moment pour les conserver d'une année sur l'autre.
        </p>

        <div className="flex flex-wrap gap-3">
          <button
            onClick={onExportData}
            className="flex items-center gap-2 px-4 py-2 bg-slate-100 hover:bg-slate-200 text-slate-900 rounded-xl text-xs font-bold transition-all border border-slate-300"
          >
            <Download className="w-4 h-4" />
            Exporter mes données (JSON)
          </button>

          <label className="flex items-center gap-2 px-4 py-2 bg-slate-100 hover:bg-slate-200 text-slate-900 rounded-xl text-xs font-bold transition-all border border-slate-300 cursor-pointer">
            <Upload className="w-4 h-4" />
            Restaurer une sauvegarde
            <input type="file" accept=".json" onChange={handleFileChange} className="hidden" />
          </label>

          <button
            onClick={() => {
              if (
                confirm(
                  'Voulez-vous recharger le jeu d’exemple complet de 3ème (avec cours de démonstration) ?'
                )
              ) {
                onResetDemoData();
              }
            }}
            className="flex items-center gap-2 px-4 py-2 text-rose-700 hover:bg-rose-500/10 rounded-xl text-xs font-semibold transition-all ml-auto"
          >
            Recharger démo 3ème
          </button>
        </div>

        {importError && (
          <div className="text-xs text-rose-700 flex items-center gap-1.5 mt-2">
            <AlertCircle className="w-4 h-4" />
            {importError}
          </div>
        )}
      </div>

      {/* Save Button Bar (positioned safely above mobile bottom bar) */}
      <div className="sticky bottom-20 md:bottom-4 z-20 flex justify-end pt-2">
        <button
          onClick={handleSaveSettings}
          className="flex items-center justify-center gap-2 w-full sm:w-auto px-6 py-3.5 bg-violet-700 hover:bg-violet-800 active:scale-95 text-white rounded-xl text-sm font-bold shadow-xl shadow-sky-500/30 transition-all"
        >
          <CheckCircle2 className="w-4 h-4" />
          Enregistrer toutes les modifications
        </button>
      </div>
    </div>
  );
};
