import { useCallback, useEffect, useState } from 'react';
import { Bell, CheckCheck, Loader2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { interviewReminderApi } from '../../api/interviewReminder';
import type { InterviewReminder } from '../../types/interviewReminder';

export function ReminderBell() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [unreadCount, setUnreadCount] = useState(0);
  const [reminders, setReminders] = useState<InterviewReminder[]>([]);

  const loadUnreadCount = useCallback(async () => {
    try {
      const result = await interviewReminderApi.unreadCount();
      setUnreadCount(result.count);
    } catch (error) {
      console.error('Failed to load reminder count:', error);
    }
  }, []);

  const loadReminders = useCallback(async () => {
    setLoading(true);
    try {
      setReminders(await interviewReminderApi.listInApp(false));
    } catch (error) {
      console.error('Failed to load reminders:', error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadUnreadCount();
    const timer = window.setInterval(loadUnreadCount, 60_000);
    return () => window.clearInterval(timer);
  }, [loadUnreadCount]);

  const toggle = async () => {
    const nextOpen = !open;
    setOpen(nextOpen);
    if (nextOpen) {
      await loadReminders();
    }
  };

  const openReminder = async (reminder: InterviewReminder) => {
    if (!reminder.readAt) {
      await interviewReminderApi.markRead(reminder.id);
      setUnreadCount(current => Math.max(0, current - 1));
    }
    setOpen(false);
    navigate('/interview-schedule');
  };

  const markAllRead = async () => {
    await interviewReminderApi.markAllRead();
    setUnreadCount(0);
    setReminders(current => current.map(reminder => ({
      ...reminder,
      readAt: reminder.readAt ?? new Date().toISOString(),
    })));
  };

  return (
    <div className="relative">
      <button
        type="button"
        onClick={toggle}
        className="relative rounded-lg p-2 text-slate-500 transition-colors hover:bg-slate-100 hover:text-primary-600 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-primary-400"
        title="站内提醒"
      >
        <Bell className="h-5 w-5" />
        {unreadCount > 0 && (
          <span className="absolute -right-1 -top-1 min-w-4 rounded-full bg-red-500 px-1 text-center text-[10px] font-bold leading-4 text-white">
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute left-0 top-11 z-[70] w-80 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-2xl dark:border-slate-700 dark:bg-slate-900">
          <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3 dark:border-slate-700">
            <span className="font-semibold text-slate-800 dark:text-white">面试提醒</span>
            {unreadCount > 0 && (
              <button
                type="button"
                onClick={markAllRead}
                className="flex items-center gap-1 text-xs text-primary-600 hover:text-primary-700 dark:text-primary-400"
              >
                <CheckCheck className="h-3.5 w-3.5" />
                全部已读
              </button>
            )}
          </div>

          <div className="max-h-96 overflow-y-auto">
            {loading ? (
              <div className="flex justify-center py-8">
                <Loader2 className="h-5 w-5 animate-spin text-primary-500" />
              </div>
            ) : reminders.length === 0 ? (
              <p className="py-8 text-center text-sm text-slate-500">暂无提醒</p>
            ) : reminders.map(reminder => (
              <button
                key={reminder.id}
                type="button"
                onClick={() => openReminder(reminder)}
                className={`block w-full border-b border-slate-100 px-4 py-3 text-left transition-colors last:border-0 dark:border-slate-800 ${
                  reminder.readAt
                    ? 'bg-white dark:bg-slate-900'
                    : 'bg-primary-50/70 dark:bg-primary-950/30'
                }`}
              >
                <div className="flex items-start gap-2">
                  {!reminder.readAt && (
                    <span className="mt-1.5 h-2 w-2 flex-none rounded-full bg-primary-500" />
                  )}
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-slate-800 dark:text-slate-100">
                      {reminder.title}
                    </p>
                    <p className="mt-1 line-clamp-2 text-xs text-slate-500 dark:text-slate-400">
                      {reminder.content}
                    </p>
                    <p className="mt-1 text-[11px] text-slate-400">
                      {new Date(reminder.remindAt).toLocaleString()}
                    </p>
                  </div>
                </div>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
