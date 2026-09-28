import { request } from './request';

export interface BusinessMetricsSummary {
  since: string;
  recentTaskCount: number;
  failedTaskCount: number;
  aiInvocationCount: number;
  aiSuccessRate: number;
  ragQueryCount: number;
  ragAverageHitCount: number;
  voiceAverageTurnLatencyMs: number;
}

export const monitoringApi = {
  getBusinessSummary(): Promise<BusinessMetricsSummary> {
    return request.get('/api/monitoring/business-summary');
  },
};
