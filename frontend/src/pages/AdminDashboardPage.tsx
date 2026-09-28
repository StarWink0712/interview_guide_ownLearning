import { Activity, BarChart3, BrainCircuit, ClipboardList, Gauge, SearchCode } from 'lucide-react';
import { Link } from 'react-router-dom';
import AdminNav from '../components/admin/AdminNav';

const cards = [
  {
    to: '/admin/ai-traces',
    title: 'AI 调用观测',
    description: '查看调用日志、结构化输出失败和 RAG trace。',
    icon: SearchCode,
    tone: 'from-sky-500 to-cyan-500',
  },
  {
    to: '/admin/rag-eval',
    title: 'RAG 评测',
    description: '创建评测集，比较 TopK/minScore 策略效果。',
    icon: BarChart3,
    tone: 'from-emerald-500 to-teal-500',
  },
  {
    to: '/admin/async-tasks',
    title: '异步任务',
    description: '排查 Redis Stream 任务、失败原因、重试与取消。',
    icon: ClipboardList,
    tone: 'from-amber-500 to-orange-500',
  },
  {
    to: '/admin/monitoring',
    title: '指标面板',
    description: '聚合 AI、RAG、异步任务、语音面试关键指标。',
    icon: Gauge,
    tone: 'from-indigo-500 to-blue-500',
  },
  {
    to: '/admin/interview-adaptive',
    title: '自适应洞察',
    description: '查看能力画像、追问原因和难度调整轨迹。',
    icon: BrainCircuit,
    tone: 'from-rose-500 to-pink-500',
  },
];

export default function AdminDashboardPage() {
  return (
    <div className="mx-auto max-w-7xl">
      <AdminNav />

      <section className="overflow-hidden rounded-3xl border border-slate-100 bg-slate-950 p-8 text-white shadow-xl dark:border-slate-700">
        <div className="max-w-3xl">
          <div className="mb-5 inline-flex items-center gap-2 rounded-full bg-white/10 px-3 py-1 text-sm text-slate-200">
            <Activity className="h-4 w-4" />
            面向调试、演示和质量验证
          </div>
          <h1 className="text-3xl font-bold tracking-tight md:text-4xl">
            前端管理控制台
          </h1>
          <p className="mt-4 max-w-2xl text-sm leading-7 text-slate-300">
            把后端的 AI 调用观测、RAG 评测、Redis Stream 任务治理和业务指标串到一个入口里。
            这里不做复杂权限系统，优先让功能能被看见、点到、验证到。
          </p>
        </div>
      </section>

      <section className="mt-8 grid gap-5 md:grid-cols-2 xl:grid-cols-3">
        {cards.map(card => (
          <Link
            key={card.to}
            to={card.to}
            className="group rounded-2xl border border-slate-100 bg-white p-5 shadow-sm transition-all hover:-translate-y-1 hover:shadow-lg dark:border-slate-700 dark:bg-slate-800"
          >
            <div className={`mb-5 flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br ${card.tone} text-white shadow-lg`}>
              <card.icon className="h-6 w-6" />
            </div>
            <h2 className="text-lg font-bold text-slate-900 dark:text-white">{card.title}</h2>
            <p className="mt-2 text-sm leading-6 text-slate-500 dark:text-slate-400">
              {card.description}
            </p>
            <span className="mt-4 inline-flex text-sm font-semibold text-primary-600 group-hover:translate-x-1 dark:text-primary-400">
              进入页面 →
            </span>
          </Link>
        ))}
      </section>
    </div>
  );
}
