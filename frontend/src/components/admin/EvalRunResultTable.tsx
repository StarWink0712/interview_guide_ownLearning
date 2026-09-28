import type { RagEvalRun } from '../../api/aiObservability';
import StatusBadge from './StatusBadge';

type EvalRunResultTableProps = {
  runs: RagEvalRun[];
  onSelectRun?: (run: RagEvalRun) => void;
};

function parseStrategy(strategyJson: string): string {
  try {
    const strategy = JSON.parse(strategyJson) as { topK?: number; minScore?: number };
    return `TopK ${strategy.topK ?? '-'} / minScore ${strategy.minScore ?? '-'}`;
  } catch {
    return strategyJson || '-';
  }
}

export default function EvalRunResultTable({ runs, onSelectRun }: EvalRunResultTableProps) {
  if (!runs.length) {
    return (
      <div className="rounded-xl border border-dashed border-slate-200 p-8 text-center text-sm text-slate-500 dark:border-slate-700 dark:text-slate-400">
        暂无评测运行结果
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded-2xl border border-slate-100 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <table className="min-w-full divide-y divide-slate-100 text-sm dark:divide-slate-700">
        <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500 dark:bg-slate-900/60 dark:text-slate-400">
          <tr>
            <th className="px-4 py-3 text-left">Run</th>
            <th className="px-4 py-3 text-left">策略</th>
            <th className="px-4 py-3 text-left">状态</th>
            <th className="px-4 py-3 text-left">命中率</th>
            <th className="px-4 py-3 text-left">平均延迟</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 dark:divide-slate-700">
          {runs.map(run => {
            const hitRate = run.totalCases > 0
              ? `${Math.round((run.matchedCases / run.totalCases) * 100)}%`
              : '-';
            return (
              <tr
                key={run.id}
                onClick={() => onSelectRun?.(run)}
                className="cursor-pointer transition-colors hover:bg-slate-50 dark:hover:bg-slate-700/60"
              >
                <td className="px-4 py-3 font-semibold text-slate-800 dark:text-slate-100">
                  #{run.id}
                </td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-300">
                  {parseStrategy(run.strategyJson)}
                </td>
                <td className="px-4 py-3"><StatusBadge status={run.status} /></td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-300">
                  {run.matchedCases}/{run.totalCases} ({hitRate})
                </td>
                <td className="px-4 py-3 text-slate-500">{run.avgLatencyMs ?? 0}ms</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
