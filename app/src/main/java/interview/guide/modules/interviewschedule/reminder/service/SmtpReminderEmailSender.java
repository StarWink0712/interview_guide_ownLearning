package interview.guide.modules.interviewschedule.reminder.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interviewschedule.reminder.config.InterviewReminderProperties;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/**
 * 基于 Spring JavaMailSender 的 SMTP 邮件投递实现
 *
 * <p>通过 SMTP 协议发送 HTML 格式的面试提醒邮件，包含公司、岗位、
 * 面试时间、会议链接等完整日程信息。实现 {@link ReminderEmailSender} 接口，
 * 可通过切换 Spring Bean 替换为 SES、阿里云邮件推送等其他实现。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmtpReminderEmailSender implements ReminderEmailSender {

  /** 邮件中面试时间的显示格式：2026年07月09日 14:00 */
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm");

  /** Spring 邮件发送器，由 spring-boot-starter-mail 自动配置 */
  private final JavaMailSender mailSender;

  /** 提醒配置属性：发件人地址、名称、邮件主题前缀 */
  private final InterviewReminderProperties properties;

  /**
   * 发送面试提醒邮件
   *
   * <p>构建 HTML 格式邮件内容，通过 SMTP 发送。包含：
   * 公司名称、岗位、面试时间、面试形式、会议链接、面试官、备注等完整日程信息。</p>
   *
   * @param context 投递上下文（提醒 + 日程完整信息）
   * @throws BusinessException 收件地址为空或邮件发送失败时抛出
   */
  @Override
  public void send(ReminderDeliveryContext context) {
    // 前置校验：收件人地址不能为空
    if (context.recipient() == null || context.recipient().isBlank()) {
      throw new BusinessException(
          ErrorCode.INTERVIEW_REMINDER_SEND_FAILED,
          "邮件提醒缺少收件地址");
    }

    try {
      // 通过 JavaMailSender 创建 MIME 邮件对象
      MimeMessage message = mailSender.createMimeMessage();
      // MimeMessageHelper 简化邮件字段设置，false 表示不含附件，UTF-8 编码
      MimeMessageHelper helper = new MimeMessageHelper(
          message,
          false,
          StandardCharsets.UTF_8.name());

      // 从配置中获取邮件相关参数（发件人地址、名称、主题前缀）
      InterviewReminderProperties.Email email = properties.getEmail();
      // 设置发件人：地址 + 显示名称，UTF-8 编码防止中文乱码
      helper.setFrom(new InternetAddress(
          email.getFromAddress(),
          email.getFromName(),
          StandardCharsets.UTF_8.name()));
      // 设置收件人：取上下文中的提醒邮箱地址
      helper.setTo(context.recipient());
      // 设置邮件主题：配置的主题前缀 + 提醒标题（如"[面试提醒] 阿里巴巴面试将在明天开始"）
      helper.setSubject(email.getSubjectPrefix() + " " + context.title());
      // 设置邮件正文：调用 buildHtml 生成 HTML 内容，true 表示 content-type 为 text/html
      helper.setText(buildHtml(context), true);

      // 通过 SMTP 协议发送邮件
      mailSender.send(message);
      // 发送成功后记录日志，邮箱地址做脱敏处理（如 z***@company.com）
      log.info(
          "Interview reminder email sent: reminderId={}, scheduleId={}, recipient={}",
          context.reminderId(),
          context.scheduleId(),
          maskEmail(context.recipient()));
    } catch (BusinessException e) {
      // 业务异常（如缺少收件地址）直接抛出，不做包装
      throw e;
    } catch (Exception e) {
      // SMTP 连接失败、认证失败等底层异常统一包装为 BusinessException
      throw new BusinessException(
          ErrorCode.INTERVIEW_REMINDER_SEND_FAILED,
          "发送面试提醒邮件失败: " + e.getMessage(),
          e);
    }
  }

  /**
   * 构建 HTML 格式的邮件正文
   *
   * <p>根据投递上下文中各字段的有无动态显示或隐藏对应的信息区块
   * （会议信息、面试官、备注），避免空白段落。</p>
   *
   * @param context 投递上下文
   * @return 完整的 HTML 邮件内容
   */
  private String buildHtml(ReminderDeliveryContext context) {
    String meetingSection = context.meetingLink() == null || context.meetingLink().isBlank()
        ? ""
        : "<p><strong>会议信息：</strong>" + linkOrText(context.meetingLink()) + "</p>";
    String interviewerSection = context.interviewer() == null || context.interviewer().isBlank()
        ? ""
        : "<p><strong>面试官：</strong>" + escape(context.interviewer()) + "</p>";
    String notesSection = context.notes() == null || context.notes().isBlank()
        ? ""
        : "<div style=\"margin-top:16px;padding:12px;background:#f8fafc;border-radius:8px;\">"
            + "<strong>备注：</strong>" + escape(context.notes()) + "</div>";

    return """
        <!doctype html>
        <html lang="zh-CN">
          <body style="font-family:Arial,'Microsoft YaHei',sans-serif;color:#1e293b;">
            <div style="max-width:620px;margin:0 auto;padding:24px;">
              <h2 style="color:#4f46e5;">%s</h2>
              <p>%s</p>
              <div style="padding:18px;border:1px solid #e2e8f0;border-radius:12px;">
                <p><strong>公司：</strong>%s</p>
                <p><strong>岗位：</strong>%s</p>
                <p><strong>时间：</strong>%s</p>
                <p><strong>形式：</strong>%s</p>
                %s
                %s
              </div>
              %s
              <p style="margin-top:24px;color:#64748b;font-size:13px;">
                本邮件由 AI Interview 面试日程提醒服务自动发送。
              </p>
            </div>
          </body>
        </html>
        """.formatted(
        escape(context.title()),
        escape(context.content()),
        escape(context.companyName()),
        escape(context.position()),
        context.interviewTime().format(DATE_TIME_FORMATTER),
        escape(defaultText(context.interviewType(), "未填写")),
        interviewerSection,
        meetingSection,
        notesSection);
  }

  /**
   * 将会议链接或纯文本渲染为 HTML
   *
   * <p>如果是 http/https 开头的链接则渲染为可点击的 a 标签，
   * 否则（如"会议号: 123456"）以纯文本展示。</p>
   */
  private String linkOrText(String value) {
    String escaped = escape(value);
    if (value.startsWith("https://") || value.startsWith("http://")) {
      return "<a href=\"" + escaped + "\">" + escaped + "</a>";
    }
    return escaped;
  }

  /**
   * HTML 转义，防止 XSS 注入
   *
   * <p>对用户输入的文本进行 HTML 实体编码（如 &lt; → &amp;lt;），
   * 确保邮件内容中不会嵌入恶意脚本。</p>
   */
  private String escape(String value) {
    return HtmlUtils.htmlEscape(defaultText(value, ""));
  }

  /**
   * 安全取值：null 时返回默认值
   */
  private String defaultText(String value, String fallback) {
    return value == null ? fallback : value;
  }

  /**
   * 邮箱地址脱敏，用于日志安全
   *
   * <p>如 "zhangsan@company.com" → "z***@company.com"</p>
   */
  private String maskEmail(String email) {
    int at = email.indexOf('@');
    if (at <= 1) {
      return "***";
    }
    return email.substring(0, 1) + "***" + email.substring(at);
  }
}
