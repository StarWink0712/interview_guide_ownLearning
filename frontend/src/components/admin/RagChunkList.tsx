import type { RagRetrievedChunk } from '../../api/aiObservability';

type RagChunkListProps = {
  chunks: RagRetrievedChunk[];
};

export default function RagChunkList({ chunks }: RagChunkListProps) {
  if (!chunks.length) {
    return (
      <div className="rounded-xl border border-dashed border-slate-200 p-6 text-center text-sm text-slate-500 dark:border-slate-700 dark:text-slate-400">
        暂无命中文档片段
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {chunks.map((chunk, index) => (
        <div
          key={chunk.id ?? index}
          className="rounded-xl border border-slate-100 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800"
        >
          <div className="mb-2 flex flex-wrap items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
            <span className="rounded-full bg-slate-100 px-2 py-0.5 font-semibold dark:bg-slate-700">
              Rank #{chunk.rank ?? index + 1}
            </span>
            {chunk.knowledgeBaseId && <span>KB: {chunk.knowledgeBaseId}</span>}
            {chunk.score !== undefined && <span>Score: {chunk.score.toFixed(4)}</span>}
          </div>
          <p className="whitespace-pre-wrap text-sm leading-6 text-slate-700 dark:text-slate-200">
            {chunk.chunkTextPreview || '无预览文本'}
          </p>
        </div>
      ))}
    </div>
  );
}
