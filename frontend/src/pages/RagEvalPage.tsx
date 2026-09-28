import { useEffect, useMemo, useState } from 'react';
import { BarChart3, Loader2, Plus, Play } from 'lucide-react';
import { ragEvalApi, type RagEvalDataset, type RagEvalRun } from '../api/ragEval';
import { knowledgeBaseApi, type KnowledgeBaseItem } from '../api/knowledgebase';
import { getErrorMessage } from '../api/request';
import AdminNav from '../components/admin/AdminNav';
import EvalRunResultTable from '../components/admin/EvalRunResultTable';
import MetricCard from '../components/admin/MetricCard';
import StatusBadge from '../components/admin/StatusBadge';

export default function RagEvalPage() {
  const [datasets, setDatasets] = useState<RagEvalDataset[]>([]);
  const [datasetsHint, setDatasetsHint] = useState('');
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [datasetId, setDatasetId] = useState('');
  const [datasetName, setDatasetName] = useState('');
  const [datasetDescription, setDatasetDescription] = useState('');
  const [question, setQuestion] = useState('');
  const [kbIds, setKbIds] = useState('');
  const [keywords, setKeywords] = useState('');
  const [topK, setTopK] = useState('8');
  const [minScore, setMinScore] = useState('0.28');
  const [runs, setRuns] = useState<RagEvalRun[]>([]);
  const [selectedRun, setSelectedRun] = useState<RagEvalRun | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [successMessage, setSuccessMessage] = useState('');

  useEffect(() => {
    ragEvalApi.listDatasets()
      .then(setDatasets)
      .catch(() => setDatasetsHint('后端暂未提供评测集列表接口，可创建新评测集或直接输入已有评测集 ID。'));
    knowledgeBaseApi.getAllKnowledgeBases('time', 'COMPLETED')
      .then(setKnowledgeBases)
      .catch(() => setKnowledgeBases([]));
  }, []);

  const currentDatasetId = Number(datasetId);
  const latestRun = runs[0];
  const hitRate = latestRun && latestRun.totalCases > 0
    ? Math.round((latestRun.matchedCases / latestRun.totalCases) * 100)
    : 0;

  const selectedKbNames = useMemo(() => {
    const ids = parseNumberList(kbIds);
    return knowledgeBases
      .filter(kb => ids.includes(kb.id))
      .map(kb => kb.name)
      .join('、');
  }, [kbIds, knowledgeBases]);

  const runAction = async (action: () => Promise<void>) => {
    setLoading(true);
    setError('');
    setSuccessMessage('');
    try {
      await action();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const handleCreateDataset = () => runAction(async () => {
    const dataset = await ragEvalApi.createDataset({
      name: datasetName.trim(),
      description: datasetDescription.trim() || undefined,
    });
    setDatasets(prev => [dataset, ...prev.filter(item => item.id !== dataset.id)]);
    setDatasetId(String(dataset.id));
    setDatasetName('');
    setDatasetDescription('');
    setSuccessMessage(`已创建评测集 #${dataset.id}`);
  });

  const handleAddCase = () => runAction(async () => {
    await ragEvalApi.addCase(currentDatasetId, {
      question: question.trim(),
      knowledgeBaseIds: parseNumberList(kbIds),
      expectedKeywords: parseTextList(keywords),
    });
    setQuestion('');
    setKeywords('');
    setSuccessMessage('已添加评测用例');
  });

  const handleRunEval = () => runAction(async () => {
    const run = await ragEvalApi.runDataset(currentDatasetId, {
      topK: Number(topK),
      minScore: Number(minScore),
    });
    setRuns(prev => [run, ...prev.filter(item => item.id !== run.id)]);
    setSelectedRun(run);
    setSuccessMessage(`评测运行完成：Run #${run.id}`);
  });

  const loadRunById = (runId: string) => runAction(async () => {
    const run = await ragEvalApi.getRun(Number(runId));
    setRuns(prev => [run, ...prev.filter(item => item.id !== run.id)]);
    setSelectedRun(run);
  });

  return (
    <div className="mx-auto max-w-7xl">
      <AdminNav />
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-900 dark:text-white">RAG 评测中心</h1>
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
          用同一批问题比较不同 TopK/minScore 策略的关键词命中率和检索延迟。
        </p>
      </div>

      {(error || successMessage || datasetsHint) && (
        <div className="mb-4 space-y-2">
          {error && <div className="rounded-xl bg-rose-50 p-3 text-sm text-rose-700 dark:bg-rose-900/30 dark:text-rose-300">{error}</div>}
          {successMessage && <div className="rounded-xl bg-emerald-50 p-3 text-sm text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300">{successMessage}</div>}
          {datasetsHint && <div className="rounded-xl bg-amber-50 p-3 text-sm text-amber-700 dark:bg-amber-900/30 dark:text-amber-300">{datasetsHint}</div>}
        </div>
      )}

      <div className="mb-6 grid gap-4 md:grid-cols-3">
        <MetricCard title="最近命中率" value={`${hitRate}%`} tone="green" icon={<BarChart3 className="h-5 w-5" />} />
        <MetricCard title="平均延迟" value={`${latestRun?.avgLatencyMs ?? 0}ms`} tone="blue" />
        <MetricCard title="已比较运行" value={runs.length} tone="amber" />
      </div>

      <div className="grid gap-6 xl:grid-cols-[420px_minmax(0,1fr)]">
        <aside className="space-y-5">
          <section className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
            <h2 className="mb-4 font-bold text-slate-900 dark:text-white">评测集</h2>
            <div className="space-y-3">
              <input value={datasetId} onChange={e => setDatasetId(e.target.value)} placeholder="当前评测集 ID"
                     className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
              <input value={datasetName} onChange={e => setDatasetName(e.target.value)} placeholder="新评测集名称"
                     className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
              <textarea value={datasetDescription} onChange={e => setDatasetDescription(e.target.value)} placeholder="描述（可选）"
                        className="h-20 w-full rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
              <button onClick={handleCreateDataset} disabled={loading || !datasetName.trim()}
                      className="inline-flex w-full items-center justify-center gap-2 rounded-xl bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700 disabled:opacity-50">
                <Plus className="h-4 w-4" />
                创建评测集
              </button>
              {datasets.length > 0 && (
                <div className="space-y-2 pt-2">
                  {datasets.slice(0, 5).map(dataset => (
                    <button key={dataset.id} onClick={() => setDatasetId(String(dataset.id))}
                            className="w-full rounded-xl bg-slate-50 p-3 text-left text-sm hover:bg-slate-100 dark:bg-slate-900/60 dark:hover:bg-slate-700">
                      <span className="font-semibold text-slate-800 dark:text-slate-100">#{dataset.id} {dataset.name}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          </section>

          <section className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
            <h2 className="mb-4 font-bold text-slate-900 dark:text-white">添加 Case</h2>
            <div className="space-y-3">
              <textarea value={question} onChange={e => setQuestion(e.target.value)} placeholder="评测问题"
                        className="h-24 w-full rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
              <input value={kbIds} onChange={e => setKbIds(e.target.value)} placeholder="知识库 ID，用逗号分隔"
                     className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
              {selectedKbNames && <p className="text-xs text-slate-500">已选：{selectedKbNames}</p>}
              <input value={keywords} onChange={e => setKeywords(e.target.value)} placeholder="期望关键词，用逗号分隔"
                     className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
              <button onClick={handleAddCase} disabled={loading || !currentDatasetId || !question.trim() || parseNumberList(kbIds).length === 0}
                      className="w-full rounded-xl bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-50 dark:bg-white dark:text-slate-900">
                添加用例
              </button>
            </div>
          </section>
        </aside>

        <main className="space-y-5">
          <section className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
            <h2 className="mb-4 font-bold text-slate-900 dark:text-white">运行策略</h2>
            <div className="grid gap-3 md:grid-cols-[1fr_1fr_auto]">
              <input value={topK} onChange={e => setTopK(e.target.value)} type="number" min={1}
                     className="rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
              <input value={minScore} onChange={e => setMinScore(e.target.value)} type="number" min={0} step={0.01}
                     className="rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
              <button onClick={handleRunEval} disabled={loading || !currentDatasetId}
                      className="inline-flex items-center justify-center gap-2 rounded-xl bg-emerald-600 px-5 py-2 text-sm font-semibold text-white hover:bg-emerald-700 disabled:opacity-50">
                {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
                运行评测
              </button>
            </div>
          </section>

          <section>
            <EvalRunResultTable runs={runs} onSelectRun={setSelectedRun} />
          </section>

          <section className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
            <div className="mb-4 flex items-center justify-between gap-3">
              <h2 className="font-bold text-slate-900 dark:text-white">运行详情</h2>
              <RunLookup onLoad={loadRunById} disabled={loading} />
            </div>
            {selectedRun ? (
              <div className="space-y-3">
                <div className="flex items-center gap-3">
                  <span className="font-bold text-slate-900 dark:text-white">Run #{selectedRun.id}</span>
                  <StatusBadge status={selectedRun.status} />
                </div>
                {selectedRun.caseResults?.map(result => (
                  <div key={result.id} className="rounded-xl border border-slate-100 p-4 dark:border-slate-700">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <p className="font-semibold text-slate-800 dark:text-slate-100">{result.question}</p>
                      <StatusBadge status={result.expectedKeywordHit} label={result.expectedKeywordHit ? '命中' : '未命中'} />
                    </div>
                    <p className="mt-2 text-sm text-slate-500">
                      keywords: {result.matchedKeywordCount} / hits: {result.hitCount} / latency: {result.latencyMs}ms
                    </p>
                    {result.errorMessage && <p className="mt-2 text-sm text-rose-600">{result.errorMessage}</p>}
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-slate-500">运行一次评测或输入 Run ID 后查看详情。</p>
            )}
          </section>
        </main>
      </div>
    </div>
  );
}

function RunLookup({ onLoad, disabled }: { onLoad: (runId: string) => void; disabled: boolean }) {
  const [runId, setRunId] = useState('');
  return (
    <div className="flex gap-2">
      <input value={runId} onChange={e => setRunId(e.target.value)} placeholder="Run ID"
             className="w-28 rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900" />
      <button onClick={() => onLoad(runId)} disabled={disabled || !runId}
              className="rounded-xl bg-slate-100 px-3 text-sm font-semibold text-slate-700 hover:bg-slate-200 disabled:opacity-50 dark:bg-slate-700 dark:text-slate-100">
        载入
      </button>
    </div>
  );
}

function parseNumberList(value: string): number[] {
  return value
    .split(',')
    .map(item => Number(item.trim()))
    .filter(item => Number.isFinite(item) && item > 0);
}

function parseTextList(value: string): string[] {
  return value
    .split(',')
    .map(item => item.trim())
    .filter(Boolean);
}
