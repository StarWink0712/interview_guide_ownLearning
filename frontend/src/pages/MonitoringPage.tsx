import { useEffect, useMemo, useState } from 'react';
import { Activity, Bot, DatabaseZap, Loader2, Mic2, RefreshCw, Workflow } from 'lucide-react';
import { aiObservabilityApi, type AiInvocationLog } from '../api/aiObservability';
import { monitoringApi, type BusinessMetricsSummary } from '../api/monitoring';
import { getErrorMessage } from '../api/request';
import AdminNav from '../components/admin/AdminNav';
import MetricCard from '../components/admin/MetricCard';
import StatusBadge from '../components/admin/StatusBadge';

interface AggregateRow {
  key: string;
  count: number;
  successCount: number;
  avgLatency: number;
}

export default function MonitoringPage() {
  const [summary, setSummary] = useState<BusinessMetricsSummary | null>(null);
  const [logs, setLogs] = useState<AiInvocationLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const loadData = async () => {
    setLoading(true);
    setError('');
    try {
      const [summaryData, invocationLogs] = await Promise.all([
        monitoringApi.getBusinessSummary(),
        aiObservabilityApi.listInvocations().catch(() => []),
      ]);
      setSummary(summaryData);
      setLogs(invocationLogs);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const scenarioRows = useMemo(
    () => aggregateLogs(logs, log => log.scenario || 'unknown'),
    [logs],
  );
  const providerRows = useMemo(
    () => aggregateLogs(logs, log => `${log.providerId || 'unknown'} / ${log.model || '-'}`),
    [logs],
  );
  const failures = logs.filter(log => !log.success).slice(0, 8);

  return (
    <div className="mx-auto max-w-7xl">
      <AdminNav />
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">业务指标面板</h1>
          <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
            聚合最近窗口内 AI、RAG、异步任务与语音面试的关键质量指标。
          </p>
        </div>
        <button
          onClick={loadData}
          className="inline-flex items-center gap-2 rounded-xl bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700"
        >
          <RefreshCw className="h-4 w-4" />
          刷新
        </button>
      </div>

      {error && <div className="mb-4 rounded-xl bg-rose-50 p-3 text-sm text-rose-700 dark:bg-rose-900/30 dark:text-rose-300">{error}</div>}

      {loading ? (
        <div className="flex h-64 items-center justify-center rounded-2xl bg-white dark:bg-slate-800">
          <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
        </div>
      ) : (
        <>
          <div className="mb-6 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            <MetricCard title="AI 调用成功率" value={`${Math.round((summary?.aiSuccessRate ?? 0) * 100)}%`}
                        description={`调用 ${summary?.aiInvocationCount ?? 0} 次`}
                        tone="green" icon={<Bot className="h-5 w-5" />} />
            <MetricCard title="RAG 平均命中" value={(summary?.ragAverageHitCount ?? 0).toFixed(1)}
                        description={`查询 ${summary?.ragQueryCount ?? 0} 次`}
                        tone="blue" icon={<DatabaseZap className="h-5 w-5" />} />
            <MetricCard title="异步失败任务" value={summary?.failedTaskCount ?? 0}
                        description={`最近任务 ${summary?.recentTaskCount ?? 0} 个`}
                        tone={(summary?.failedTaskCount ?? 0) > 0 ? 'rose' : 'slate'}
                        icon={<Workflow className="h-5 w-5" />} />
            <MetricCard title="语音轮次延迟" value={`${Math.round(summary?.voiceAverageTurnLatencyMs ?? 0)}ms`}
                        description="平均端到端延迟"
                        tone="amber" icon={<Mic2 className="h-5 w-5" />} />
          </div>

          <div className="grid gap-6 xl:grid-cols-2">
            <AggregateTable title="按 scenario 聚合" rows={scenarioRows} />
            <AggregateTable title="按 provider/model 聚合" rows={providerRows} />
          </div>

          <section className="mt-6 rounded-2xl border border-slate-100 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
            <div className="mb-4 flex items-center gap-2">
              <Activity className="h-5 w-5 text-primary-500" />
              <h2 className="font-bold text-slate-900 dark:text-white">最近结构化/AI 失败</h2>
            </div>
            <div className="space-y-3">
              {failures.map(log => (
                <a
                  key={log.id}
                  href={`/admin/ai-traces?traceId=${encodeURIComponent(log.traceId)}`}
                  className="block rounded-xl border border-slate-100 p-4 transition-colors hover:bg-slate-50 dark:border-slate-700 dark:hover:bg-slate-700/60"
                >
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div>
                      <p className="font-semibold text-slate-900 dark:text-white">{log.scenario}</p>
                      <p className="font-mono text-xs text-slate-400">{log.traceId}</p>
                    </div>
                    <StatusBadge status={false} />
                  </div>
                  {log.errorMessage && (
                    <p className="mt-2 line-clamp-2 text-sm text-rose-600 dark:text-rose-300">
                      {log.errorMessage}
                    </p>
                  )}
                </a>
              ))}
              {!failures.length && <p className="text-sm text-slate-500">最近没有失败记录，挺好，系统今天心情不错。</p>}
            </div>
          </section>
        </>
      )}
    </div>
  );
}

function AggregateTable({ title, rows }: { title: string; rows: AggregateRow[] }) {
  return (
    <section className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <h2 className="mb-4 font-bold text-slate-900 dark:text-white">{title}</h2>
      <div className="overflow-hidden rounded-xl border border-slate-100 dark:border-slate-700">
        <table className="min-w-full divide-y divide-slate-100 text-sm dark:divide-slate-700">
          <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500 dark:bg-slate-900/60">
            <tr>
              <th className="px-4 py-3 text-left">维度</th>
              <th className="px-4 py-3 text-left">调用量</th>
              <th className="px-4 py-3 text-left">成功率</th>
              <th className="px-4 py-3 text-left">平均耗时</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 dark:divide-slate-700">
            {rows.map(row => (
              <tr key={row.key}>
                <td className="px-4 py-3 font-semibold text-slate-800 dark:text-slate-100">{row.key}</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-300">{row.count}</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-300">
                  {Math.round((row.successCount / row.count) * 100)}%
                </td>
                <td className="px-4 py-3 text-slate-500">{Math.round(row.avgLatency)}ms</td>
              </tr>
            ))}
            {!rows.length && (
              <tr><td colSpan={4} className="px-4 py-8 text-center text-slate-500">暂无数据</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function aggregateLogs(logs: AiInvocationLog[], getKey: (log: AiInvocationLog) => string): AggregateRow[] {
  const map = new Map<string, { count: number; successCount: number; latency: number }>();
  logs.forEach(log => {
    const key = getKey(log);
    const current = map.get(key) ?? { count: 0, successCount: 0, latency: 0 };
    current.count += 1;
    current.successCount += log.success ? 1 : 0;
    current.latency += log.latencyMs ?? 0;
    map.set(key, current);
  });
  return Array.from(map.entries())
    .map(([key, value]) => ({
      key,
      count: value.count,
      successCount: value.successCount,
      avgLatency: value.count > 0 ? value.latency / value.count : 0,
    }))
    .sort((a, b) => b.count - a.count);
}
