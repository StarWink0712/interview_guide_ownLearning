import type { RagTrace } from '../../api/aiObservability';

type TraceTimelineProps = {
  trace?: RagTrace | null;
};

export default function TraceTimeline({ trace }: TraceTimelineProps) {
  if (!trace) {
    return (
      <div className="rounded-xl border border-dashed border-slate-200 p-6 text-sm text-slate-500 dark:border-slate-700 dark:text-slate-400">
        选择一条 RAG trace 后，这里会展示检索链路。
      </div>
    );
  }

  const steps = [
    { title: '原始问题', value: trace.question },
    { title: '改写问题', value: trace.rewrittenQuestion || '未改写' },
    { title: '检索策略', value: `TopK ${trace.topK} / minScore ${trace.minScore}` },
    { title: '检索结果', value: `命中 ${trace.hitCount} 个片段，耗时 ${trace.latencyMs}ms` },
  ];

  return (
    <div className="space-y-4">
      {steps.map((step, index) => (
        <div key={step.title} className="relative pl-8">
          <div className="absolute left-0 top-1 flex h-5 w-5 items-center justify-center rounded-full bg-primary-600 text-xs font-bold text-white">
            {index + 1}
          </div>
          {index < steps.length - 1 && (
            <div className="absolute left-2.5 top-6 h-full w-px bg-slate-200 dark:bg-slate-700" />
          )}
          <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">
            {step.title}
          </p>
          <p className="mt-1 whitespace-pre-wrap text-sm leading-6 text-slate-700 dark:text-slate-200">
            {step.value}
          </p>
        </div>
      ))}
    </div>
  );
}
