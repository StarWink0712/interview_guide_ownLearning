package interview.guide.modules.interviewschedule.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.interviewschedule.reminder.config.InterviewReminderProperties;
import interview.guide.modules.interviewschedule.reminder.model.ReminderChannel;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class SmtpReminderEmailSenderTest {

  @Mock
  private JavaMailSender mailSender;

  @Test
  @DisplayName("SMTP发送器构造HTML邮件并交给JavaMailSender")
  void shouldBuildAndSendMimeMessage() throws Exception {
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
    when(mailSender.createMimeMessage()).thenReturn(message);

    InterviewReminderProperties properties = new InterviewReminderProperties();
    properties.getEmail().setFromAddress("noreply@example.com");
    properties.getEmail().setFromName("AI Interview");
    SmtpReminderEmailSender sender =
        new SmtpReminderEmailSender(mailSender, properties);

    ReminderDeliveryContext context = new ReminderDeliveryContext(
        1L,
        20L,
        ReminderChannel.EMAIL,
        "candidate@example.com",
        "示例公司面试将在一小时后开始",
        "请提前做好准备",
        "示例公司",
        "Java 工程师",
        LocalDateTime.of(2026, 6, 20, 14, 30),
        "VIDEO",
        "https://meeting.example.com/room",
        "张老师",
        "准备系统设计");

    sender.send(context);

    verify(mailSender).send(message);
    assertThat(message.getSubject()).contains("面试将在一小时后开始");
    assertThat(message.getAllRecipients()[0].toString())
        .isEqualTo("candidate@example.com");
    assertThat(message.getDataHandler().getContentType()).contains("text/html");
  }
}
