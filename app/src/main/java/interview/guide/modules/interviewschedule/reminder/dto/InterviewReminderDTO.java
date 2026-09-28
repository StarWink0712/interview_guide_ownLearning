package interview.guide.modules.interviewschedule.reminder.dto;

import interview.guide.modules.interviewschedule.reminder.model.ReminderChannel;
import interview.guide.modules.interviewschedule.reminder.model.ReminderStatus;
import interview.guide.modules.interviewschedule.reminder.model.ReminderTiming;
import java.time.LocalDateTime;

/** 前端展示的站内提醒。 */
public record InterviewReminderDTO(
    Long id,
    Long scheduleId,
    ReminderChannel channel,
    ReminderTiming timing,
    ReminderStatus status,
    String title,
    String content,
    LocalDateTime remindAt,
    LocalDateTime sentAt,
    LocalDateTime readAt,
    LocalDateTime createdAt) {
}
