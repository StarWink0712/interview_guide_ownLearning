import { CheckCircle, CircleDashed, Clock3, RefreshCw, XCircle } from 'lucide-react';
import type { ComponentType } from 'react';

type StatusBadgeProps = {
  status?: string | boolean;
  label?: string;
};

const statusMap: Record<string, {
  label: string;
  className: string;
  icon: ComponentType<{ className?: string }>;
}> = {
  PENDING: {
    label: '等待中',
    className: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
    icon: CircleDashed,
  },
  PROCESSING: {
    label: '处理中',
    className: 'bg-sky-50 text-sky-700 dark:bg-sky-900/30 dark:text-sky-300',
    icon: RefreshCw,
  },
  RUNNING: {
    label: '运行中',
    className: 'bg-sky-50 text-sky-700 dark:bg-sky-900/30 dark:text-sky-300',
    icon: RefreshCw,
  },
  COMPLETED: {
    label: '已完成',
    className: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300',
    icon: CheckCircle,
  },
  SUCCESS: {
    label: '成功',
    className: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300',
    icon: CheckCircle,
  },
  FAILED: {
    label: '失败',
    className: 'bg-rose-50 text-rose-700 dark:bg-rose-900/30 dark:text-rose-300',
    icon: XCircle,
  },
  CANCELLED: {
    label: '已取消',
    className: 'bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
    icon: Clock3,
  },
  DEAD_LETTER: {
    label: '死信',
    className: 'bg-red-100 text-red-800 dark:bg-red-950/40 dark:text-red-200',
    icon: XCircle,
  },
};

export default function StatusBadge({ status, label }: StatusBadgeProps) {
  const key = typeof status === 'boolean'
    ? (status ? 'SUCCESS' : 'FAILED')
    : (status || 'PENDING').toUpperCase();
  const config = statusMap[key] ?? statusMap.PENDING;
  const Icon = config.icon;

  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${config.className}`}>
      <Icon className={`h-3.5 w-3.5 ${key === 'PROCESSING' || key === 'RUNNING' ? 'animate-spin' : ''}`} />
      {label || config.label}
    </span>
  );
}
