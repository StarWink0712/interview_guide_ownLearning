package interview.guide.modules.interviewschedule.reminder.service;

/** 邮件投递端口，便于替换 SMTP、SES 等实现。 */
public interface ReminderEmailSender {

  void send(ReminderDeliveryContext context);
}
