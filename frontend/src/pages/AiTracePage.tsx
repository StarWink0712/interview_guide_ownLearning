import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Loader2, RefreshCw, Search } from 'lucide-react';
import {
  aiObservabilityApi,
  type AiInvocationLog,
  type RagTrace,
} from '../api/aiObservability';
import AdminNav from '../components/admin/AdminNav';
import RagChunkList from '../components/admin/RagChunkList';
import StatusBadge from '../components/admin/StatusBadge';
import TraceTimeline from '../components/admin/TraceTimeline';
import { getErrorMessage } from '../api/request';

type SuccessFilter = 'ALL' | 'SUCCESS' | 'FAILED';

export default function AiTracePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [invocations, setInvocations] = useState<AiInvocationLog[]>([]);
  const [recentTraces, setRecentTraces] = useState<RagTrace[]>([]);
  const [selectedInvocation, setSelectedInvocation] = useState<AiInvocationLog | null>(null);
  const [selectedTrace, setSelectedTrace] = useState<RagTrace | null>(null);
  const [traceIdInput, setTraceIdInput] = useState(searchParams.get('traceId') || '');
  const [scenario, setScenario] = useState('');
  const [provider, setProvider] = useState('');
  const [success, setSuccess] = useState<SuccessFilter>('ALL');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const loadData = async (traceId?: string) => {
    setLoading(true);
    setError('');
    try {
      const [logs, traces] = await Promise.all([
        aiObservabilityApi.listInvocations(traceId),
        aiObservabilityApi.listRecentRagTraces().catch(() => []),
      ]);
      setInvocations(logs);
      setRecentTraces(traces);
      if (traceId) {
        setSearchParams({ traceId });
        await loadTrace(traceId);
      }
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const loadTrace = async (traceId: string) => {
    try {
      const trace = await aiObservabilityApi.getRagTrace(traceId);
      setSelectedTrace(trace);
    } catch {
      setSelectedTrace(null);
    }
  };

  useEffect(() => {
    loadData(traceIdInput || undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const filteredInvocations = useMemo(() => {
    return invocations.filter(item => {
      if (scenario && !item.scenario?.toLowerCase().includes(scenario.toLowerCase())) return false;
      if (provider && item.providerId !== provider) return false;
      if (success === 'SUCCESS' && !item.success) return false;
      if (success === 'FAILED' && item.success) return false;
      const createdAt = item.createdAt ? new Date(item.createdAt).getTime() : 0;
      if (from && createdAt < new Date(from).getTime()) return false;
      if (to && createdAt > new Date(to).getTime()) return false;
      return true;
    });
  }, [from, invocations, provider, scenario, success, to]);

  const providers = useMemo(
    () => Array.from(new Set(invocations.map(item => item.providerId).filter(Boolean))),
    [invocations],
  );

  const handleSelectInvocation = async (invocation: AiInvocationLog) => {
    setSelectedInvocation(invocation);
    if (invocation.traceId) {
      await loadTrace(invocation.traceId);
    }
  };

  return (
    <div className="mx-auto max-w-7xl">
      <AdminNav />
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">AI 调用观测</h1>
          <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
            查看最近调用日志，按 scenario、success、provider、时间范围过滤，并联动 RAG trace。
          </p>
        </div>
        <button
          onClick={() => loadData(traceIdInput || undefined)}
          className="inline-flex items-center gap-2 rounded-xl bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700"
        >
          <RefreshCw className="h-4 w-4" />
          刷新
        </button>
      </div>

      <div className="mb-6 grid gap-3 rounded-2xl border border-slate-100 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800 md:grid-cols-6">
        <input value={scenario} onChange={e => setScenario(e.target.value)} placeholder="scenario"
               className="rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
        <select value={provider} onChange={e => setProvider(e.target.value)}
                className="rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900">
          <option value="">全部 provider</option>
          {providers.map(item => <option key={item} value={item}>{item}</option>)}
        </select>
        <select value={success} onChange={e => setSuccess(e.target.value as SuccessFilter)}
                className="rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900">
          <option value="ALL">全部状态</option>
          <option value="SUCCESS">成功</option>
          <option value="FAILED">失败</option>
        </select>
        <input type="datetime-local" value={from} onChange={e => setFrom(e.target.value)}
               className="rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
        <input type="datetime-local" value={to} onChange={e => setTo(e.target.value)}
               className="rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
        <div className="flex gap-2">
          <input value={traceIdInput} onChange={e => setTraceIdInput(e.target.value)} placeholder="traceId"
                 className="min-w-0 flex-1 rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
          <button onClick={() => loadData(traceIdInput || undefined)}
                  className="rounded-xl bg-slate-900 px-3 text-white dark:bg-white dark:text-slate-900">
            <Search className="h-4 w-4" />
          </button>
        </div>
      </div>

      {error && <div className="mb-4 rounded-xl bg-rose-50 p-3 text-sm text-rose-700 dark:bg-rose-900/30 dark:text-rose-300">{error}</div>}

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1.35fr)_minmax(380px,0.65fr)]">
        <section className="rounded-2xl border border-slate-100 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-800">
          <div className="border-b border-slate-100 p-4 dark:border-slate-700">
            <h2 className="font-bold text-slate-900 dark:text-white">调用日志</h2>
          </div>
          {loading ? (
            <div className="flex h-64 items-center justify-center"><Loader2 className="h-8 w-8 animate-spin text-primary-500" /></div>
          ) : (
            <div className="max-h-[680px] overflow-auto">
              <table className="min-w-full divide-y divide-slate-100 text-sm dark:divide-slate-700">
                <tbody className="divide-y divide-slate-100 dark:divide-slate-700">
                  {filteredInvocations.map(item => (
                    <tr key={item.id} onClick={() => handleSelectInvocation(item)}
                        className="cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/50">
                      <td className="px-4 py-3">
                        <div className="font-semibold text-slate-900 dark:text-white">{item.scenario || 'unknown'}</div>
                        <div className="font-mono text-xs text-slate-400">{item.traceId}</div>
                      </td>
                      <td className="px-4 py-3 text-slate-600 dark:text-slate-300">{item.providerId}/{item.model}</td>
                      <td className="px-4 py-3"><StatusBadge status={item.success} /></td>
                      <td className="px-4 py-3 text-slate-500">{item.latencyMs}ms</td>
                      <td className="px-4 py-3 text-slate-500">{new Date(item.createdAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!filteredInvocations.length && (
                <div className="p-10 text-center text-sm text-slate-500">暂无符合条件的调用日志</div>
              )}
            </div>
          )}
        </section>

        <aside className="space-y-6">
          <section className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
            <h2 className="mb-4 font-bold text-slate-900 dark:text-white">调用详情</h2>
            {selectedInvocation ? (
              <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                <p><span className="text-slate-400">ID:</span> #{selectedInvocation.id}</p>
                <p><span className="text-slate-400">promptHash:</span> {selectedInvocation.promptHash || '-'}</p>
                <p><span className="text-slate-400">attempt:</span> {selectedInvocation.attemptCount}</p>
                <p><span className="text-slate-400">streaming:</span> {selectedInvocation.streaming ? '是' : '否'}</p>
                <p><span className="text-slate-400">structured:</span> {selectedInvocation.structuredOutput ? '是' : '否'}</p>
                {selectedInvocation.errorMessage && (
                  <p className="rounded-xl bg-rose-50 p-3 text-rose-700 dark:bg-rose-900/30 dark:text-rose-300">
                    {selectedInvocation.errorMessage}
                  </p>
                )}
              </div>
            ) : <p className="text-sm text-slate-500">点击左侧调用日志查看详情。</p>}
          </section>

          <section className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
            <h2 className="mb-4 font-bold text-slate-900 dark:text-white">RAG Trace</h2>
            <TraceTimeline trace={selectedTrace} />
            {selectedTrace && <div className="mt-4"><RagChunkList chunks={selectedTrace.chunks || []} /></div>}
          </section>

          <section className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
            <h2 className="mb-4 font-bold text-slate-900 dark:text-white">最近 RAG Trace</h2>
            <div className="space-y-2">
              {recentTraces.slice(0, 6).map(trace => (
                <button key={trace.traceId} onClick={() => setSelectedTrace(trace)}
                        className="w-full rounded-xl bg-slate-50 p-3 text-left text-sm hover:bg-slate-100 dark:bg-slate-900/60 dark:hover:bg-slate-700">
                  <div className="font-semibold text-slate-800 dark:text-slate-100">{trace.question}</div>
                  <div className="mt-1 font-mono text-xs text-slate-400">{trace.traceId}</div>
                </button>
              ))}
              {!recentTraces.length && <p className="text-sm text-slate-500">暂无 RAG trace。</p>}
            </div>
          </section>
        </aside>
      </div>
    </div>
  );
}
