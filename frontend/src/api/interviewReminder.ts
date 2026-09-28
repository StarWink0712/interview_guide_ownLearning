import { request } from './request';
import type {
  InterviewReminder,
  UnreadReminderCount,
} from '../types/interviewReminder';

export const interviewReminderApi = {
  listInApp: (unreadOnly = false): Promise<InterviewReminder[]> =>
    request.get('/api/interview-schedule/reminders/in-app', {
      params: { unreadOnly },
    }),

  unreadCount: (): Promise<UnreadReminderCount> =>
    request.get('/api/interview-schedule/reminders/unread-count'),

  markRead: (id: number): Promise<void> =>
    request.patch(`/api/interview-schedule/reminders/${id}/read`),

  markAllRead: (): Promise<void> =>
    request.patch('/api/interview-schedule/reminders/read-all'),
};
