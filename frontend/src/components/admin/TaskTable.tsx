import type { AsyncTask } from '../../api/asyncTask';
import StatusBadge from './StatusBadge';

type TaskTableProps = {
  tasks: AsyncTask[];
  selectedId?: number | null;
  onSelect: (task: AsyncTask) => void;
};

export default function TaskTable({ tasks, selectedId, onSelect }: TaskTableProps) {
  if (!tasks.length) {
    return (
      <div className="rounded-xl border border-dashed border-slate-200 p-8 text-center text-sm text-slate-500 dark:border-slate-700 dark:text-slate-400">
        暂无异步任务
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded-2xl border border-slate-100 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <table className="min-w-full divide-y divide-slate-100 text-sm dark:divide-slate-700">
        <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500 dark:bg-slate-900/60 dark:text-slate-400">
          <tr>
            <th className="px-4 py-3 text-left">任务</th>
            <th className="px-4 py-3 text-left">业务 ID</th>
            <th className="px-4 py-3 text-left">状态</th>
            <th className="px-4 py-3 text-left">重试</th>
            <th className="px-4 py-3 text-left">更新时间</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 dark:divide-slate-700">
          {tasks.map(task => (
            <tr
              key={task.id}
              onClick={() => onSelect(task)}
              className={`cursor-pointer transition-colors hover:bg-slate-50 dark:hover:bg-slate-700/60 ${
                selectedId === task.id ? 'bg-primary-50/70 dark:bg-primary-900/20' : ''
              }`}
            >
              <td className="px-4 py-3 font-semibold text-slate-800 dark:text-slate-100">
                #{task.id} {task.taskType}
              </td>
              <td className="px-4 py-3 font-mono text-xs text-slate-500">{task.businessId}</td>
              <td className="px-4 py-3"><StatusBadge status={task.status} /></td>
              <td className="px-4 py-3 text-slate-600 dark:text-slate-300">
                {task.retryCount}/{task.maxRetryCount}
              </td>
              <td className="px-4 py-3 text-slate-500">
                {task.updatedAt ? new Date(task.updatedAt).toLocaleString() : '-'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
