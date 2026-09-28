export type ReminderChannel = 'EMAIL' | 'IN_APP';
export type ReminderTiming = 'DAY_BEFORE' | 'HOUR_BEFORE';
export type ReminderStatus =
  | 'PENDING'
  | 'QUEUED'
  | 'PROCESSING'
  | 'SENT'
  | 'FAILED'
  | 'CANCELLED';

export interface InterviewReminder {
  id: number;
  scheduleId: number;
  channel: ReminderChannel;
  timing: ReminderTiming;
  status: ReminderStatus;
  title: string;
  content: string;
  remindAt: string;
  sentAt: string | null;
  readAt: string | null;
  createdAt: string;
}

export interface UnreadReminderCount {
  count: number;
}
