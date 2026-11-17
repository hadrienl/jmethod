import React from 'react';
import { LayoutDashboard, CalendarDays, BookMarked, BarChart3, Settings, Plus, Sparkles, ArrowUpRight } from 'lucide-react';
export type NavTab = 'dashboard' | 'planning' | 'courses' | 'stats' | 'settings';
interface NavigationProps {
  activeTab: NavTab;
  onSelectTab: (tab: NavTab) => void;
  pendingCountToday: number;
  overdueCount: number;
  onOpenAddCourse: () => void;
}
export const Navigation: React.FC<NavigationProps> = ({activeTab, onSelectTab, pendingCountToday, overdueCount, onOpenAddCourse}) => {
  const items = [
    {id: 'dashboard' as const, label: 'Aujourd’hui', icon: LayoutDashboard},
    {id: 'planning' as const, label: 'Planning', icon: CalendarDays},
    {id: 'courses' as const, label: 'Mes cours', icon: BookMarked},
    {id: 'stats' as const, label: 'Mes progrès', icon: BarChart3},
    {id: 'settings' as const, label: 'Réglages', icon: Settings},
  ];
  const count = pendingCountToday + overdueCount;
  const logo = <div className="flex items-center gap-3"><span className="brand-mark">j<span>•</span></span><div><span className="text-xl font-extrabold tracking-tight">J-Method</span><p className="text-xs text-slate-500">Un peu chaque jour.</p></div></div>;
  return <>
    <a href="#main-content" className="skip-link">Aller au contenu</a>
    <aside className="hidden md:flex fixed inset-y-0 left-0 z-30 w-64 flex-col border-r border-slate-200 bg-white px-5 py-8">
      <div className="px-2 mb-10">{logo}</div>
      <p className="px-3 mb-3 text-xs font-semibold tracking-widest text-slate-500 uppercase">Ton espace</p>
      <nav aria-label="Navigation principale" className="space-y-2">
        {items.map(({id,label,icon: Icon})=><button key={id} onClick={()=>onSelectTab(id)} aria-current={activeTab===id?'page':undefined} className={`nav-item ${activeTab===id?'is-active':''}`}><Icon size={20}/><span className="flex-1 text-left">{label}</span>{id==='dashboard' && count>0 && <span className="nav-count">{count}</span>}</button>)}
      </nav>
      <button onClick={onOpenAddCourse} className="primary-button mt-7"><Plus size={18}/> Ajouter un cours</button>
      <div className="mt-auto rounded-2xl bg-violet-50 p-4">
        <Sparkles className="text-violet-700 mb-3" size={24}/><p className="font-bold text-sm">À ton rythme.</p><p className="text-sm text-slate-600 mt-2 leading-relaxed">Les petites séances d’aujourd’hui font les grands progrès de demain.</p>
        <button onClick={()=>onSelectTab('stats')} className="flex items-center gap-2 text-violet-700 text-sm font-bold mt-4">Voir mes progrès <ArrowUpRight size={16}/></button>
      </div>
      <p className="text-xs text-slate-500 text-center mt-5">Ton cerveau te dira merci ✦</p>
    </aside>
    <header className="md:hidden flex items-center justify-between px-4 py-3 bg-white border-b border-slate-200 sticky top-0 z-30">{logo}<button onClick={onOpenAddCourse} className="primary-button !px-3"><Plus size={18}/><span>Cours</span></button></header>
    <nav aria-label="Navigation mobile" className="md:hidden fixed bottom-0 inset-x-0 z-40 flex justify-around bg-white border-t border-slate-200 bottom-nav-safe px-1 pt-2">
      {items.map(({id,label,icon: Icon})=><button key={id} onClick={()=>onSelectTab(id)} aria-current={activeTab===id?'page':undefined} className={`flex flex-col items-center justify-center gap-1 min-h-14 flex-1 rounded-xl text-[11px] font-semibold ${activeTab===id?'text-violet-800 bg-violet-50':'text-slate-600'}`}><Icon size={21}/>{label}</button>)}
    </nav>
  </>;
};
