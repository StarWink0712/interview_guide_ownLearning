import { request } from './request';

export type AsyncTaskType =
  | 'RESUME_ANALYZE'
  | 'KB_VECTORIZE'
  | 'INTERVIEW_EVALUATE'
  | 'VOICE_INTERVIEW_EVALUATE';

export type AsyncTaskStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'DEAD_LETTER';

export interface AsyncTask {
  id: number;
  taskType: AsyncTaskType;
  businessId: string;
  streamKey: string;
  messageId?: string;
  status: AsyncTaskStatus;
  retryCount: number;
  maxRetryCount: number;
  nextRetryAt?: string;
  lastError?: string;
  payloadJson?: string;
  parentTaskId?: number;
  activeFlag: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AsyncTaskFilters {
  taskType?: AsyncTaskType | '';
  status?: AsyncTaskStatus | '';
}

export const asyncTaskApi = {
  list(filters: AsyncTaskFilters = {}): Promise<AsyncTask[]> {
    return request.get('/api/async-tasks', {
      params: {
        taskType: filters.taskType || undefined,
        status: filters.status || undefined,
      },
    });
  },

  get(id: number): Promise<AsyncTask> {
    return request.get(`/api/async-tasks/${id}`);
  },

  retry(id: number): Promise<AsyncTask> {
    return request.post(`/api/async-tasks/${id}/retry`);
  },

  cancel(id: number): Promise<AsyncTask> {
    return request.post(`/api/async-tasks/${id}/cancel`);
  },
};
