package interview.guide.modules.interviewschedule.reminder.model;

/** 面试提醒从计划到投递结束的状态。 */
public enum ReminderStatus {
  PENDING,
  QUEUED,
  PROCESSING,
  SENT,
  FAILED,
  CANCELLED
}
