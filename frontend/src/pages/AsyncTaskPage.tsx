import { useEffect, useState } from 'react';
import { Loader2, RefreshCw } from 'lucide-react';
import {
  asyncTaskApi,
  type AsyncTask,
  type AsyncTaskStatus,
  type AsyncTaskType,
} from '../api/asyncTask';
import { getErrorMessage } from '../api/request';
import AdminNav from '../components/admin/AdminNav';
import ConfirmActionButton from '../components/admin/ConfirmActionButton';
import StatusBadge from '../components/admin/StatusBadge';
import TaskTable from '../components/admin/TaskTable';

const taskTypes: AsyncTaskType[] = [
  'RESUME_ANALYZE',
  'KB_VECTORIZE',
  'INTERVIEW_EVALUATE',
  'VOICE_INTERVIEW_EVALUATE',
];

const statuses: AsyncTaskStatus[] = [
  'PENDING',
  'PROCESSING',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'DEAD_LETTER',
];

export default function AsyncTaskPage() {
  const [tasks, setTasks] = useState<AsyncTask[]>([]);
  const [selectedTask, setSelectedTask] = useState<AsyncTask | null>(null);
  const [taskType, setTaskType] = useState<AsyncTaskType | ''>('');
  const [status, setStatus] = useState<AsyncTaskStatus | ''>('');
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);
  const [error, setError] = useState('');

  const loadTasks = async () => {
    setLoading(true);
    setError('');
    try {
      const list = await asyncTaskApi.list({ taskType, status });
      setTasks(list);
      setSelectedTask(current => current
        ? list.find(item => item.id === current.id) ?? list[0] ?? null
        : list[0] ?? null);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadTasks();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [taskType, status]);

  const handleRetry = async () => {
    if (!selectedTask) return;
    setActionLoading(true);
    try {
      const updated = await asyncTaskApi.retry(selectedTask.id);
      setSelectedTask(updated);
      await loadTasks();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setActionLoading(false);
    }
  };

  const handleCancel = async () => {
    if (!selectedTask) return;
    setActionLoading(true);
    try {
      const updated = await asyncTaskApi.cancel(selectedTask.id);
      setSelectedTask(updated);
      await loadTasks();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setActionLoading(false);
    }
  };

  const canRetry = selectedTask && ['FAILED', 'CANCELLED', 'DEAD_LETTER'].includes(selectedTask.status);
  const canCancel = selectedTask && ['PENDING', 'PROCESSING'].includes(selectedTask.status);

  return (
    <div className="mx-auto max-w-7xl">
      <AdminNav />
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">异步任务管理</h1>
          <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
            查看 Redis Stream 异步任务状态，定位失败原因，并对失败任务执行重试。
          </p>
        </div>
        <button
          onClick={loadTasks}
          className="inline-flex items-center gap-2 rounded-xl bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700"
        >
          <RefreshCw className="h-4 w-4" />
          刷新
        </button>
      </div>

      <div className="mb-6 grid gap-3 rounded-2xl border border-slate-100 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800 md:grid-cols-3">
        <select value={taskType} onChange={e => setTaskType(e.target.value as AsyncTaskType | '')}
                className="rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900">
          <option value="">全部任务类型</option>
          {taskTypes.map(item => <option key={item} value={item}>{item}</option>)}
        </select>
        <select value={status} onChange={e => setStatus(e.target.value as AsyncTaskStatus | '')}
                className="rounded-xl border border-slate-200 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900">
          <option value="">全部状态</option>
          {statuses.map(item => <option key={item} value={item}>{item}</option>)}
        </select>
        <div className="flex items-center text-sm text-slate-500 dark:text-slate-400">
          当前结果：{tasks.length} 条
        </div>
      </div>

      {error && <div className="mb-4 rounded-xl bg-rose-50 p-3 text-sm text-rose-700 dark:bg-rose-900/30 dark:text-rose-300">{error}</div>}

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_420px]">
        <section>
          {loading ? (
            <div className="flex h-64 items-center justify-center rounded-2xl bg-white dark:bg-slate-800">
              <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
            </div>
          ) : (
            <TaskTable tasks={tasks} selectedId={selectedTask?.id} onSelect={setSelectedTask} />
          )}
        </section>

        <aside className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
          <h2 className="mb-4 font-bold text-slate-900 dark:text-white">任务详情</h2>
          {selectedTask ? (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-lg font-bold text-slate-900 dark:text-white">#{selectedTask.id}</p>
                  <p className="text-sm text-slate-500">{selectedTask.taskType}</p>
                </div>
                <StatusBadge status={selectedTask.status} />
              </div>
              <div className="space-y-2 rounded-xl bg-slate-50 p-4 text-sm dark:bg-slate-900/60">
                <p><span className="text-slate-400">businessId:</span> {selectedTask.businessId}</p>
                <p><span className="text-slate-400">streamKey:</span> {selectedTask.streamKey}</p>
                <p><span className="text-slate-400">messageId:</span> {selectedTask.messageId || '-'}</p>
                <p><span className="text-slate-400">retry:</span> {selectedTask.retryCount}/{selectedTask.maxRetryCount}</p>
                <p><span className="text-slate-400">updated:</span> {new Date(selectedTask.updatedAt).toLocaleString()}</p>
              </div>
              {selectedTask.lastError && (
                <div className="rounded-xl bg-rose-50 p-4 text-sm text-rose-700 dark:bg-rose-900/30 dark:text-rose-300">
                  {selectedTask.lastError}
                </div>
              )}
              {selectedTask.payloadJson && (
                <pre className="max-h-64 overflow-auto rounded-xl bg-slate-950 p-4 text-xs text-slate-100">
                  {selectedTask.payloadJson}
                </pre>
              )}
              <div className="flex flex-wrap gap-2">
                <ConfirmActionButton
                  confirmText="确认重新投递这个任务？"
                  loading={actionLoading}
                  disabled={!canRetry}
                  tone="primary"
                  onConfirm={handleRetry}
                >
                  重试任务
                </ConfirmActionButton>
                <ConfirmActionButton
                  confirmText="确认取消这个任务？"
                  loading={actionLoading}
                  disabled={!canCancel}
                  tone="danger"
                  onConfirm={handleCancel}
                >
                  取消任务
                </ConfirmActionButton>
              </div>
            </div>
          ) : (
            <p className="text-sm text-slate-500">选择一条任务查看详情。</p>
          )}
        </aside>
      </div>
    </div>
  );
}
