import React from 'react';
import { format, parseISO } from 'date-fns';
import { fr } from 'date-fns/locale';
import { ArrowRight, Check, CheckCircle2, Plus, Sparkles, BookOpen, CalendarDays, MoveUpRight } from 'lucide-react';
import { Course, Revision, Subject, PronoteEvent } from '../types';
interface DashboardViewProps {
  todayStr: string; courses: Course[]; revisions: Revision[]; subjects: Subject[]; pronoteEvents: PronoteEvent[];
  onOpenScoreModal: (revision: Revision) => void;
  onOpenCourseDetail: (course: Course) => void;
  onOpenAddCourseForEvent: (event: PronoteEvent) => void;
}
const phases = {
  encodage: {label: 'Découvrir', hint: 'Comprends et reformule ton cours.', style: 'bg-blue-50 text-blue-800', symbol: '01'},
  consolidation: {label: 'S’entraîner', hint: 'Entraîne-toi à retrouver les idées clés.', style: 'bg-orange-50 text-orange-800', symbol: '02'},
  recuperation: {label: 'Se tester', hint: 'Essaie de te rappeler sans regarder.', style: 'bg-emerald-50 text-emerald-800', symbol: '03'},
};
export const DashboardView: React.FC<DashboardViewProps> = ({todayStr,courses,revisions,subjects,pronoteEvents,onOpenScoreModal,onOpenCourseDetail,onOpenAddCourseForEvent}) => {
  const courseMap = new Map(courses.map(c=>[c.id,c]));
  const subjectMap = new Map(subjects.map(s=>[s.id,s]));
  const due = revisions.filter(r=>r.scheduledDate<=todayStr && r.status!=='completed' && courseMap.has(r.courseId)).sort((a,b)=>a.scheduledDate.localeCompare(b.scheduledDate));
  const completed = revisions.filter(r=>r.completedDate===todayStr && r.status==='completed');
  const total = due.length+completed.length;
  const progress = total ? Math.round(completed.length/total*100):0;
  const next = due[0];
  const nextCourse = next && courseMap.get(next.courseId);
  const nextSubject = nextCourse && subjectMap.get(nextCourse.subjectId);
  const overdue = due.filter(r=>r.scheduledDate<todayStr).length;
  const visible = revisions.filter(r=>(r.scheduledDate<=todayStr && r.status!=='completed') || (r.completedDate===todayStr && r.status==='completed')).filter(r=>courseMap.has(r.courseId));
  const events = pronoteEvents.filter(e=>e.date===todayStr);
  return <div className="space-y-7 animate-fade-in">
    {due.length === 0 && completed.length > 0 && <div role="status" className="rounded-2xl border border-emerald-200 bg-emerald-50 p-5 text-emerald-950"><h2 className="font-extrabold text-xl">🎉 Toutes tes révisions sont terminées !</h2><p className="text-sm mt-2">Bravo pour tes séances d’aujourd’hui. Tu peux profiter de ta journée.</p></div>}
    <header className="flex items-start justify-between gap-4">
      <div><p className="eyebrow">{format(parseISO(todayStr),'EEEE d MMMM',{locale:fr})}</p><h1 className="text-3xl sm:text-4xl font-extrabold tracking-tight mt-2">Un petit pas, <span className="text-violet-700">chaque jour.</span></h1><p className="text-slate-600 mt-3 text-sm sm:text-base">Tes révisions sont prêtes. On y va à ton rythme ?</p></div>
      <span className="hidden sm:flex items-center gap-2 rounded-full border border-slate-200 bg-white px-4 py-2 text-sm font-semibold"><span className="w-2 h-2 rounded-full bg-emerald-500"/> Mon espace révision</span>
    </header>
    <section aria-label="Ton programme" className="grid lg:grid-cols-[1.65fr_1fr] gap-4">
      <div className="study-hero">
        <div className="relative z-10 max-w-md"><span className="inline-flex items-center gap-2 text-xs font-bold rounded-full bg-white/15 px-3 py-2"><Sparkles size={15}/> LE BON MOMENT POUR AVANCER</span>
          <h2 className="text-2xl sm:text-3xl font-extrabold mt-6 leading-tight">{nextCourse?'Une séance à la fois.':'Tu peux souffler !'}</h2>
          <p className="text-violet-100 mt-3 leading-relaxed text-sm">{nextCourse?<>Commence par <strong className="text-white">{nextSubject?.name ?? 'ton cours'}</strong>.<br/>{nextCourse.title}</>:'Tes révisions sont à jour. Profite de ta journée, tu l’as bien mérité.'}</p>
          {next && <button onClick={()=>onOpenScoreModal(next)} className="mt-6 inline-flex items-center gap-3 rounded-xl bg-lime-200 text-violet-950 px-5 py-3 font-bold text-sm min-h-12 hover:bg-lime-100">Faire le point sur ce cours <ArrowRight size={18}/></button>}
        </div>
        <div className="hero-art" aria-hidden="true"><div className="orbit orbit-one"/><div className="orbit orbit-two"/><div className="art-book"><BookOpen size={66} strokeWidth={1.5}/></div><span className="art-star">✦</span><span className="art-check"><Check size={24}/></span></div>
      </div>
      <div className="surface p-5 sm:p-6 flex flex-col justify-between"><div className="flex items-center justify-between"><h2 className="font-bold">Tes petits progrès</h2></div>
        <div className="flex items-end gap-2 mt-5"><span className="text-5xl font-extrabold tracking-tight">{completed.length}</span><span className="text-slate-600 pb-1">/ {total} séance{total>1?'s':''} terminée{total>1?'s':''}</span></div>
        <div role="progressbar" aria-label="Séances terminées aujourd’hui" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100} className="h-3 rounded-full bg-violet-100 mt-5 overflow-hidden"><div className="h-full bg-violet-600 rounded-full transition-all" style={{width:`${progress}%`}}/></div>
        <p className="text-sm text-slate-600 mt-4">{completed.length?'Chaque séance compte. Continue comme ça !':total?'La première séance, c’est déjà un progrès.':'Aucune séance prévue aujourd’hui.'}</p>
      </div>
    </section>
    <div className="grid sm:grid-cols-3 gap-3" aria-label="Comment réviser">
      {Object.values(phases).map(p=><div key={p.symbol} className="flex items-center gap-3 p-3"><span className={`flex items-center justify-center w-10 h-10 shrink-0 rounded-xl text-sm font-bold ${p.style}`}>{p.symbol}</span><div><p className="font-bold text-sm">{p.label}</p><p className="text-xs text-slate-600 mt-1">{p.hint}</p></div></div>)}
    </div>
    <section><div className="flex items-center justify-between mb-4 gap-3"><h2 className="text-xl font-extrabold">Dans ton programme <span className="text-sm text-slate-500 font-medium ml-2">{due.length} à faire</span></h2><span className="hidden sm:block text-xs text-slate-500">Des séances courtes, des idées qui restent.</span></div>
      {overdue>0 && <p className="rounded-xl bg-amber-50 text-amber-900 px-4 py-3 mb-4 text-sm">{overdue} séance{overdue>1?'s':''} à reprendre. Pas de pression : avance une séance à la fois.</p>}
      {!visible.length ? <div className="surface p-10 text-center"><CheckCircle2 className="mx-auto text-emerald-700 mb-3" size={32}/><h3 className="font-bold">Tout est à jour</h3><p className="text-sm text-slate-600 mt-2">Tu peux ajouter un cours depuis ton emploi du temps ci-dessous.</p></div>:
        <div className="grid xl:grid-cols-2 gap-4">{subjects.map(subject=>{
          const rows = visible.filter(r=>courseMap.get(r.courseId)?.subjectId===subject.id);
          if(!rows.length)return null;
          return <article key={subject.id} className="surface overflow-hidden"><header className="flex items-center gap-3 p-5 border-b border-slate-100"><span className="flex items-center justify-center w-10 h-10 rounded-xl bg-slate-50 border border-slate-200"><BookOpen size={20} style={{color:subject.color}}/></span><div className="flex-1"><h3 className="font-bold">{subject.name}</h3><p className="text-xs text-slate-500 mt-1">{rows.length} séance{rows.length>1?'s':''} au programme</p></div><span className="w-3 h-3 rounded-full" style={{background:subject.color}}/></header>
            <div className="divide-y divide-slate-100">{rows.map(revision=>{const course=courseMap.get(revision.courseId)!;const phase=phases[revision.phase];const done=revision.status==='completed';return <div key={revision.id} className={`p-4 sm:p-5 ${done?'bg-slate-50/70':''}`}><div className="flex items-center justify-between gap-2 mb-3"><span className={`text-xs font-bold px-2.5 py-1 rounded-md ${phase.style}`}>{phase.label} · {revision.stepLabel}</span>{done&&<span className="text-xs text-emerald-800 font-semibold flex items-center gap-1"><Check size={14}/> Fait · {revision.score}/100</span>}</div><button onClick={()=>onOpenCourseDetail(course)} className="text-left w-full group"><p className="text-xs text-slate-500">{course.chapter} · C{course.courseNumber}</p><h4 className="text-sm sm:text-base font-bold mt-1 group-hover:text-violet-700 leading-relaxed">{course.title}</h4></button>{!done&&<button onClick={()=>onOpenScoreModal(revision)} className="mt-3 flex items-center justify-between w-full rounded-xl bg-violet-50 text-violet-800 px-4 py-3 text-sm font-bold hover:bg-violet-100">Faire le point <ArrowRight size={16}/></button>}</div>})}</div>
          </article>;
        })}</div>}
    </section>
    <section className="surface p-5 sm:p-6"><div className="flex gap-3 items-center mb-5"><CalendarDays className="text-violet-700" size={23}/><div><h2 className="font-extrabold text-lg">Ta journée de cours</h2><p className="text-sm text-slate-600 mt-1">Depuis Pronote · Ajoute les cours que tu veux réviser.</p></div></div>
      {!events.length?<p className="text-sm text-slate-600">Aucun cours importé pour aujourd’hui.</p>:<div className="grid sm:grid-cols-2 xl:grid-cols-3 gap-3">{events.map(event=><article key={event.id} className={`rounded-xl border p-4 ${event.isCancelled?'bg-slate-50 border-dashed border-slate-300':'border-slate-200'}`}><div className="flex justify-between gap-2 text-xs text-slate-600 mb-3"><span>{event.startTime} – {event.endTime}</span>{event.isCancelled?<span className="text-rose-800 font-semibold">Annulé</span>:<span>{event.room}</span>}</div><h3 className="font-bold text-sm">{event.subjectName}</h3>{event.teacher&&<p className="text-xs text-slate-600 mt-2 leading-relaxed">{event.teacher}</p>}{!event.isCancelled&&<button onClick={()=>onOpenAddCourseForEvent(event)} className="flex w-full items-center justify-between text-violet-800 font-bold text-sm mt-3 py-2 min-h-11"><span className="flex items-center gap-2"><Plus size={16}/> Ajouter ce cours</span><MoveUpRight size={15}/></button>}</article>)}</div>}
    </section>
    <p className="text-center text-sm text-slate-500 pb-2">Pas besoin de tout apprendre en un jour. Juste un peu, chaque jour.</p>
  </div>;
};
