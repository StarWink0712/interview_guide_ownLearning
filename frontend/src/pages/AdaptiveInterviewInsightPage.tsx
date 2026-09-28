import { useState } from 'react';
import { BrainCircuit, Loader2, Search } from 'lucide-react';
import { historyApi, type InterviewDetail } from '../api/history';
import { getErrorMessage } from '../api/request';
import AdminNav from '../components/admin/AdminNav';
import MetricCard from '../components/admin/MetricCard';
import RadarChart from '../components/RadarChart';

export default function AdaptiveInterviewInsightPage() {
  const [sessionId, setSessionId] = useState('');
  const [detail, setDetail] = useState<InterviewDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const loadDetail = async () => {
    if (!sessionId.trim()) return;
    setLoading(true);
    setError('');
    try {
      const data = await historyApi.getInterviewDetail(sessionId.trim());
      setDetail(data);
    } catch (err) {
      setError(getErrorMessage(err));
      setDetail(null);
    } finally {
      setLoading(false);
    }
  };

  const dimensions = detail?.abilityProfile?.dimensions ?? [];
  const radarData = dimensions.map(item => ({
    subject: item.category,
    score: item.score,
    fullMark: 100,
  }));
  const trace = detail?.adaptiveTrace ?? [];

  return (
    <div className="mx-auto max-w-7xl">
      <AdminNav />
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-900 dark:text-white">自适应面试洞察</h1>
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
          输入一次面试 sessionId，查看能力画像、追问理由和自适应决策轨迹。
        </p>
      </div>

      <div className="mb-6 flex gap-3 rounded-2xl border border-slate-100 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <input
          value={sessionId}
          onChange={e => setSessionId(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && loadDetail()}
          placeholder="输入面试 sessionId"
          className="min-w-0 flex-1 rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900"
        />
        <button
          onClick={loadDetail}
          disabled={loading || !sessionId.trim()}
          className="inline-flex items-center gap-2 rounded-xl bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700 disabled:opacity-50"
        >
          {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Search className="h-4 w-4" />}
          查询
        </button>
      </div>

      {error && <div className="mb-4 rounded-xl bg-rose-50 p-3 text-sm text-rose-700 dark:bg-rose-900/30 dark:text-rose-300">{error}</div>}

      {!detail ? (
        <div className="rounded-2xl border border-dashed border-slate-200 p-10 text-center text-sm text-slate-500 dark:border-slate-700 dark:text-slate-400">
          这里会复用面试报告中的 adaptiveTrace 与 abilityProfile。完成一次自适应面试后，用 sessionId 查询即可。
        </div>
      ) : (
        <div className="space-y-6">
          <div className="grid gap-4 md:grid-cols-3">
            <MetricCard title="整体得分" value={detail.overallScore ?? '-'} tone="green" />
            <MetricCard title="能力维度" value={dimensions.length} tone="blue" />
            <MetricCard title="决策节点" value={trace.length} tone="amber" icon={<BrainCircuit className="h-5 w-5" />} />
          </div>

          <div className="grid gap-6 xl:grid-cols-[420px_minmax(0,1fr)]">
            <section className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
              <h2 className="mb-4 font-bold text-slate-900 dark:text-white">能力画像</h2>
              {radarData.length ? (
                <>
                  <RadarChart data={radarData} height={320} />
                  <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">
                    {detail.abilityProfile?.summary || '暂无总结'}
                  </p>
                </>
              ) : (
                <p className="text-sm text-slate-500">这次面试暂无能力画像。</p>
              )}
            </section>

            <section className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
              <h2 className="mb-4 font-bold text-slate-900 dark:text-white">自适应决策时间线</h2>
              <div className="space-y-4">
                {trace.map(item => (
                  <div key={item.questionIndex} className="rounded-xl border border-slate-100 p-4 dark:border-slate-700">
                    <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                      <p className="font-semibold text-slate-900 dark:text-white">
                        Q{item.questionIndex + 1} · {item.category}
                      </p>
                      <span className="rounded-full bg-primary-50 px-2.5 py-1 text-xs font-semibold text-primary-700 dark:bg-primary-900/30 dark:text-primary-300">
                        {item.adaptiveDecision?.action}
                      </span>
                    </div>
                    <p className="text-sm text-slate-600 dark:text-slate-300">
                      评分：{item.turnEvaluation?.score ?? '-'}，深度：{item.turnEvaluation?.depthLevel ?? '-'}
                    </p>
                    <p className="mt-2 text-sm leading-6 text-slate-500 dark:text-slate-400">
                      {item.adaptiveDecision?.reason || '暂无决策原因'}
                    </p>
                    {item.adaptiveDecision?.nextQuestion && (
                      <p className="mt-3 rounded-xl bg-slate-50 p-3 text-sm text-slate-700 dark:bg-slate-900/60 dark:text-slate-200">
                        下一题：{item.adaptiveDecision.nextQuestion}
                      </p>
                    )}
                  </div>
                ))}
                {!trace.length && <p className="text-sm text-slate-500">这次面试暂无自适应决策记录。</p>}
              </div>
            </section>
          </div>
        </div>
      )}
    </div>
  );
}
