import { Activity, BarChart3, BrainCircuit, ClipboardList, Gauge, Home, SearchCode } from 'lucide-react';
import { NavLink } from 'react-router-dom';

const links = [
  { to: '/admin', label: '总览', icon: Home },
  { to: '/admin/ai-traces', label: 'AI Trace', icon: SearchCode },
  { to: '/admin/rag-eval', label: 'RAG 评测', icon: BarChart3 },
  { to: '/admin/async-tasks', label: '异步任务', icon: ClipboardList },
  { to: '/admin/monitoring', label: '指标面板', icon: Gauge },
  { to: '/admin/interview-adaptive', label: '自适应洞察', icon: BrainCircuit },
];

export default function AdminNav() {
  return (
    <div className="mb-6 rounded-2xl border border-slate-100 bg-white/80 p-2 shadow-sm backdrop-blur dark:border-slate-700 dark:bg-slate-900/70">
      <div className="mb-2 flex items-center gap-2 px-3 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200">
        <Activity className="h-4 w-4 text-primary-500" />
        管理控制台
      </div>
      <div className="flex flex-wrap gap-2">
        {links.map(link => (
          <NavLink
            key={link.to}
            to={link.to}
            end={link.to === '/admin'}
            className={({ isActive }) => `inline-flex items-center gap-2 rounded-xl px-3 py-2 text-sm font-medium transition-colors ${
              isActive
                ? 'bg-primary-600 text-white shadow-sm'
                : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'
            }`}
          >
            <link.icon className="h-4 w-4" />
            {link.label}
          </NavLink>
        ))}
      </div>
    </div>
  );
}
