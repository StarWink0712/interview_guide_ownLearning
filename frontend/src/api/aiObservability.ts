import { request } from './request';

export interface AiInvocationLog {
  id: number;
  traceId: string;
  scenario: string;
  providerId: string;
  model: string;
  promptVersion?: string;
  promptHash?: string;
  promptLength?: number;
  streaming: boolean;
  structuredOutput: boolean;
  attemptCount: number;
  success: boolean;
  latencyMs: number;
  errorCode?: string;
  errorMessage?: string;
  createdAt: string;
}

export interface RagRetrievedChunk {
  id: number;
  knowledgeBaseId?: number;
  chunkTextPreview: string;
  score?: number;
  rank?: number;
  metadataJson?: string;
}

export interface RagTrace {
  id: number;
  traceId: string;
  sessionId?: number;
  question: string;
  rewrittenQuestion?: string;
  candidateQueriesJson?: string;
  knowledgeBaseIdsJson?: string;
  topK: number;
  minScore: number;
  hitCount: number;
  latencyMs: number;
  createdAt: string;
  chunks: RagRetrievedChunk[];
}

export interface RagEvalDataset {
  id: number;
  name: string;
  description?: string;
  createdAt: string;
  updatedAt: string;
}

export interface RagEvalCase {
  id: number;
  datasetId: number;
  question: string;
  knowledgeBaseIdsJson: string;
  expectedKeywordsJson: string;
  createdAt: string;
}

export type RagEvalRunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface RagEvalRunCaseResult {
  id: number;
  runId: number;
  caseId: number;
  question: string;
  expectedKeywordsJson: string;
  matchedKeywordCount: number;
  expectedKeywordHit: boolean;
  hitCount: number;
  latencyMs: number;
  retrievedPreviewJson?: string;
  errorMessage?: string;
}

export interface RagEvalRun {
  id: number;
  datasetId: number;
  status: RagEvalRunStatus;
  strategyJson: string;
  totalCases: number;
  matchedCases: number;
  avgLatencyMs: number;
  createdAt: string;
  completedAt?: string;
  caseResults: RagEvalRunCaseResult[];
}

export interface CreateRagEvalDatasetRequest {
  name: string;
  description?: string;
}

export interface AddRagEvalCaseRequest {
  question: string;
  knowledgeBaseIds: number[];
  expectedKeywords?: string[];
}

export interface RunRagEvalRequest {
  topK?: number;
  minScore?: number;
}

export const aiObservabilityApi = {
  listInvocations(traceId?: string): Promise<AiInvocationLog[]> {
    return request.get('/api/ai-observability/invocations', {
      params: traceId ? { traceId } : undefined,
    });
  },

  getInvocation(id: number): Promise<AiInvocationLog> {
    return request.get(`/api/ai-observability/invocations/${id}`);
  },

  getRagTrace(traceId: string): Promise<RagTrace> {
    return request.get(`/api/ai-observability/rag-traces/${traceId}`);
  },

  listRecentRagTraces(): Promise<RagTrace[]> {
    return request.get('/api/ai-observability/rag-traces/recent');
  },
};

export const ragEvalApi = {
  listDatasets(): Promise<RagEvalDataset[]> {
    return request.get('/api/ai-observability/rag-eval/datasets');
  },

  getDataset(id: number): Promise<RagEvalDataset> {
    return request.get(`/api/ai-observability/rag-eval/datasets/${id}`);
  },

  createDataset(payload: CreateRagEvalDatasetRequest): Promise<RagEvalDataset> {
    return request.post('/api/ai-observability/rag-eval/datasets', payload);
  },

  addCase(datasetId: number, payload: AddRagEvalCaseRequest): Promise<RagEvalCase> {
    return request.post(`/api/ai-observability/rag-eval/datasets/${datasetId}/cases`, payload);
  },

  runDataset(datasetId: number, payload?: RunRagEvalRequest): Promise<RagEvalRun> {
    return request.post(`/api/ai-observability/rag-eval/datasets/${datasetId}/runs`, payload);
  },

  getRun(runId: number): Promise<RagEvalRun> {
    return request.get(`/api/ai-observability/rag-eval/runs/${runId}`);
  },
};
