import React from 'react';
import { Calendar, Sparkles, ShieldCheck } from 'lucide-react';

interface PronoteTutorialModalProps {
  isOpen: boolean;
  onClose: () => void;
  onUseSampleDemo: () => void;
}

export const PronoteTutorialModal: React.FC<PronoteTutorialModalProps> = ({
  isOpen,
  onClose,
  onUseSampleDemo,
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4 bg-slate-950/60 backdrop-blur-md animate-fade-in">
      <div className="relative w-full max-w-xl bg-white border-t sm:border border-slate-200 rounded-t-3xl sm:rounded-2xl shadow-2xl p-5 sm:p-6 overflow-hidden max-h-[92vh] overflow-y-auto pb-8 sm:pb-6">
        {/* Mobile drag handle */}
        <div className="w-12 h-1 bg-slate-200 rounded-full mx-auto mb-4 sm:hidden opacity-60" />
        {/* Glow */}
        <div className="absolute -top-20 -left-20 w-44 h-44 rounded-full blur-3xl opacity-20 bg-violet-700 pointer-events-none" />

        {/* Header */}
        <div className="flex items-start justify-between mb-5">
          <div className="flex items-center gap-3">
            <div className="p-2.5 bg-sky-500/10 border border-sky-500/20 rounded-xl text-sky-700">
              <Calendar className="w-6 h-6" />
            </div>
            <div>
              <h3 className="text-xl font-bold text-slate-900 tracking-tight">
                Connecter ton Pronote via iCal
              </h3>
              <p className="text-xs text-slate-600">
                Guide simple en 4 étapes pour récupérer ton vrai emploi du temps
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-slate-600 hover:text-slate-900 p-1 rounded-lg hover:bg-slate-100 transition-colors"
          >
            ✕
          </button>
        </div>

        {/* Security badge */}
        <div className="flex items-center gap-2.5 p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-700 text-xs mb-5">
          <ShieldCheck className="w-5 h-5 flex-shrink-0" />
          <span>
            <strong>100% Sécurisé & Officiel :</strong> Tu n’as pas besoin de donner ton mot de passe.
            Pronote génère un lien de calendrier en lecture seule.
          </span>
        </div>

        {/* Steps */}
        <div className="space-y-3.5 mb-6">
          {/* Step 1 */}
          <div className="flex items-start gap-3 p-3.5 rounded-xl bg-slate-50/60 border border-slate-200">
            <div className="w-6 h-6 rounded-full bg-violet-700 text-slate-950 flex items-center justify-center text-xs font-black flex-shrink-0">
              1
            </div>
            <div className="text-xs text-slate-700 space-y-1">
              <div className="font-bold text-slate-900 text-sm">Connecte-toi à Pronote sur ordinateur</div>
              <p className="text-slate-600">
                Ouvre ton navigateur habituel (sur PC, Mac ou tablette) et connecte-toi à Pronote via ton ENT de collège (Educonnect, MonBureauNumérique, etc.).
              </p>
            </div>
          </div>

          {/* Step 2 */}
          <div className="flex items-start gap-3 p-3.5 rounded-xl bg-slate-50/60 border border-slate-200">
            <div className="w-6 h-6 rounded-full bg-violet-700 text-slate-950 flex items-center justify-center text-xs font-black flex-shrink-0">
              2
            </div>
            <div className="text-xs text-slate-700 space-y-1">
              <div className="font-bold text-slate-900 text-sm">Trouve le bouton de synchronisation agenda</div>
              <p className="text-slate-600">
                En haut à droite de ton écran Pronote, clique sur ton nom ou sur l’icône <strong>« Paramètres » / « Mes données »</strong>, puis sélectionne l’onglet <strong>« Emploi du temps »</strong>.
              </p>
              <p className="text-slate-600">
                Repère le bouton <strong>« Synchroniser avec un agenda extérieur »</strong> ou l'icône calendrier iCal.
              </p>
            </div>
          </div>

          {/* Step 3 */}
          <div className="flex items-start gap-3 p-3.5 rounded-xl bg-slate-50/60 border border-slate-200">
            <div className="w-6 h-6 rounded-full bg-violet-700 text-slate-950 flex items-center justify-center text-xs font-black flex-shrink-0">
              3
            </div>
            <div className="text-xs text-slate-700 space-y-1">
              <div className="font-bold text-slate-900 text-sm">Copie le lien d'abonnement iCal</div>
              <p className="text-slate-600">
                Pronote te donne un lien unique qui ressemble à :
              </p>
              <div className="p-2 rounded bg-white border border-slate-300/60 font-mono text-[11px] text-sky-700 break-all select-all">
                https://075...pronote.toutatice.fr/mon_agenda.ics?token=...
              </div>
              <p className="text-[11px] text-slate-500">
                Clique sur <em>« Copier le lien »</em>.
              </p>
            </div>
          </div>

          {/* Step 4 */}
          <div className="flex items-start gap-3 p-3.5 rounded-xl bg-slate-50/60 border border-slate-200">
            <div className="w-6 h-6 rounded-full bg-violet-700 text-slate-950 flex items-center justify-center text-xs font-black flex-shrink-0">
              4
            </div>
            <div className="text-xs text-slate-700 space-y-1">
              <div className="font-bold text-slate-900 text-sm">Colle-le dans J-Method</div>
              <p className="text-slate-600">
                Colle ce lien dans le champ de configuration dans l’onglet <strong>Paramètres</strong> de J-Method, puis clique sur <strong>Synchroniser</strong> !
              </p>
            </div>
          </div>
        </div>

        {/* Quick Demo Test Option */}
        <div className="p-4 rounded-xl bg-gradient-to-r from-sky-50 to-violet-50 border border-sky-500/30 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div>
            <div className="text-xs font-bold text-sky-700 flex items-center gap-1.5 mb-0.5">
              <Sparkles className="w-4 h-4 text-amber-700" />
              Tu n'as pas ton lien Pronote sous la main ?
            </div>
            <p className="text-[11px] text-slate-700">
              Charge instantanément un exemple d’emploi du temps de 3ème pour tester l'app.
            </p>
          </div>
          <button
            type="button"
            onClick={() => {
              onUseSampleDemo();
              onClose();
            }}
            className="px-3.5 py-2 text-xs font-semibold text-slate-950 bg-sky-400 hover:bg-sky-300 rounded-lg whitespace-nowrap transition-colors flex items-center justify-center gap-1.5"
          >
            Tester avec l'exemple
          </button>
        </div>

        {/* Close button */}
        <div className="flex justify-end mt-5 pt-3 border-t border-slate-200">
          <button
            type="button"
            onClick={onClose}
            className="px-5 py-2 text-xs font-medium text-slate-900 bg-slate-100 hover:bg-slate-200 rounded-xl transition-colors"
          >
            J'ai compris
          </button>
        </div>
      </div>
    </div>
  );
};
