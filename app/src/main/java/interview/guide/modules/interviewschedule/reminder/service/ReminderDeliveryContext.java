package interview.guide.modules.interviewschedule.reminder.service;

import interview.guide.modules.interviewschedule.reminder.model.ReminderChannel;
import java.time.LocalDateTime;

/** 消费提醒任务时使用的不可变投递快照。 */
public record ReminderDeliveryContext(
    Long reminderId,
    Long scheduleId,
    ReminderChannel channel,
    String recipient,
    String title,
    String content,
    String companyName,
    String position,
    LocalDateTime interviewTime,
    String interviewType,
    String meetingLink,
    String interviewer,
    String notes) {
}
