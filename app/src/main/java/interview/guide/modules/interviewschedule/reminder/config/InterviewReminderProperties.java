package interview.guide.modules.interviewschedule.reminder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 面试提醒的扫描、时点和邮件模板配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.interview-reminder")
public class InterviewReminderProperties {

  private boolean planningEnabled = true;
  private boolean dispatchEnabled;
  private String scanCron = "0 * * * * *";
  private String zone = "Asia/Shanghai";
  private int batchSize = 50;
  private int dayBeforeHours = 24;
  private int hourBeforeMinutes = 60;
  private Email email = new Email();

  @Data
  public static class Email {
    private String fromAddress = "noreply@example.com";
    private String fromName = "AI Interview";
    private String subjectPrefix = "[面试提醒]";
  }
}
