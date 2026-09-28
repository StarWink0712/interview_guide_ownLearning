import type { ReactNode } from 'react';

type MetricCardProps = {
  title: string;
  value: ReactNode;
  description?: string;
  tone?: 'blue' | 'green' | 'amber' | 'rose' | 'slate';
  icon?: ReactNode;
};

const toneMap = {
  blue: 'from-sky-50 to-blue-50 text-sky-700 dark:from-sky-950/40 dark:to-blue-950/30 dark:text-sky-300',
  green: 'from-emerald-50 to-teal-50 text-emerald-700 dark:from-emerald-950/40 dark:to-teal-950/30 dark:text-emerald-300',
  amber: 'from-amber-50 to-orange-50 text-amber-700 dark:from-amber-950/40 dark:to-orange-950/30 dark:text-amber-300',
  rose: 'from-rose-50 to-red-50 text-rose-700 dark:from-rose-950/40 dark:to-red-950/30 dark:text-rose-300',
  slate: 'from-slate-50 to-slate-100 text-slate-700 dark:from-slate-800 dark:to-slate-900 dark:text-slate-200',
};

export default function MetricCard({
  title,
  value,
  description,
  tone = 'slate',
  icon,
}: MetricCardProps) {
  return (
    <div className={`rounded-2xl border border-white/70 bg-gradient-to-br p-5 shadow-sm dark:border-slate-700 ${toneMap[tone]}`}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-medium opacity-80">{title}</p>
          <div className="mt-3 text-3xl font-bold tracking-tight">{value}</div>
        </div>
        {icon && (
          <div className="rounded-xl bg-white/70 p-2.5 shadow-sm dark:bg-slate-900/50">
            {icon}
          </div>
        )}
      </div>
      {description && <p className="mt-3 text-xs opacity-75">{description}</p>}
    </div>
  );
}
