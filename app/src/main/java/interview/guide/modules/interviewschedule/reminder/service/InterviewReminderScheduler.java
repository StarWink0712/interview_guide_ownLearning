package interview.guide.modules.interviewschedule.reminder.service;

import interview.guide.modules.interviewschedule.reminder.listener.InterviewReminderStreamProducer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时认领到期提醒，并把轻量任务 ID 投递到 Redis Stream。 */
@Slf4j
@Component
@RequiredArgsConstructor
//给条件装配配置一个总开关，用于控制是否开启定时任务
@ConditionalOnProperty(
    name = "app.interview-reminder.dispatch-enabled",
    havingValue = "true")
public class InterviewReminderScheduler {
  // 依赖注入提醒服务和生产者
  private final InterviewReminderService reminderService;
  // 依赖注入 Redis Stream 生产者
  private final InterviewReminderStreamProducer producer;

  // 定时任务，每分钟执行一次
  @Scheduled(
      cron = "${app.interview-reminder.scan-cron:0 * * * * *}",
      zone = "${app.interview-reminder.zone:Asia/Shanghai}")
  public void dispatchDueReminders() {
    // 调用提醒服务认领到期提醒
    List<Long> reminderIds = reminderService.claimDueReminderIds();
    // 调用生产者发送提醒到 Redis Stream
    reminderIds.forEach(producer::sendReminder);
    if (!reminderIds.isEmpty()) {
      log.info("到期面试提醒已入队: count={}", reminderIds.size());
    }
  }
}
