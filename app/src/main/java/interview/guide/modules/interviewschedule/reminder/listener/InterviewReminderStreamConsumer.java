package interview.guide.modules.interviewschedule.reminder.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interviewschedule.reminder.model.ReminderChannel;
import interview.guide.modules.interviewschedule.reminder.service.InterviewReminderService;
import interview.guide.modules.interviewschedule.reminder.service.ReminderDeliveryContext;
import interview.guide.modules.interviewschedule.reminder.service.ReminderEmailSender;
import interview.guide.modules.interviewschedule.reminder.service.SmtpReminderEmailSender;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 消费提醒任务：邮件调用 SMTP，站内提醒只完成状态流转。 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "app.interview-reminder.dispatch-enabled",
    havingValue = "true")
public class InterviewReminderStreamConsumer
    extends AbstractStreamConsumer<InterviewReminderStreamConsumer.ReminderPayload> {

  /**
   * 提醒状态管理服务，负责标记 PROCESSING、SENT、FAILED、CANCELLED 等状态，
   * 以及获取投递所需的完整上下文（提醒信息 + 日程详情）
   */
  private final InterviewReminderService reminderService;

  /**
   * 邮件投递端口（当前实现为 {@link SmtpReminderEmailSender}），
   * 通过接口抽象便于将来替换 SMTP、SES 等邮件发送实现
   */
  private final ReminderEmailSender emailSender;

  /**
   * 构造消费者实例
   *
   * @param redisService    Redis 操作服务，传给父类用于 Stream 拉取和 ACK
   * @param reminderService 提醒状态管理服务
   * @param emailSender     邮件投递器
   */
  public InterviewReminderStreamConsumer(
      RedisService redisService,
      InterviewReminderService reminderService,
      ReminderEmailSender emailSender) {
    super(redisService);
    this.reminderService = reminderService;
    this.emailSender = emailSender;
  }

  /** 任务展示名称，用于日志输出 */
  @Override
  protected String taskDisplayName() {
    return "面试提醒";
  }

  /** Redis Stream Key */
  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.INTERVIEW_REMINDER_STREAM_KEY;
  }

  /** Redis Stream 消费者组名称 */
  @Override
  protected String groupName() {
    return AsyncTaskStreamConstants.INTERVIEW_REMINDER_GROUP_NAME;
  }

  /** 消费者名称前缀，实际名称会拼接 8 位随机 UUID */
  @Override
  protected String consumerPrefix() {
    return AsyncTaskStreamConstants.INTERVIEW_REMINDER_CONSUMER_PREFIX;
  }

  /** 消费者守护线程名称 */
  @Override
  protected String threadName() {
    return "interview-reminder-consumer";
  }

  /**
   * 从 Redis Stream 消息体中解析提醒 ID
   *
   * <p>消息格式为生产者写入的键值对 Map，从中提取 reminderId 字段。</p>
   *
   * @param messageId Redis Stream 消息唯一 ID
   * @param data      消息体键值对
   * @return 解析成功返回 ReminderPayload，解析失败返回 null（父类自动 ACK 跳过）
   */
  @Override
  protected ReminderPayload parsePayload(
      StreamMessageId messageId,
      Map<String, String> data) {
        // 从消息体中提取 reminderId 字段
    String rawReminderId = data.get(AsyncTaskStreamConstants.FIELD_REMINDER_ID);
    if (rawReminderId == null) {
      log.warn("提醒消息缺少 reminderId，跳过: messageId={}", messageId);
      return null;
    }
    try {
      return new ReminderPayload(Long.parseLong(rawReminderId));
    } catch (NumberFormatException e) {
      log.warn("提醒消息 reminderId 非法，跳过: messageId={}, reminderId={}",
          messageId, rawReminderId);
      return null;
    }
  }

  /** 载荷标识，用于日志追踪 */
  @Override
  protected String payloadIdentifier(ReminderPayload payload) {
    return "reminderId=" + payload.reminderId();
  }

  /**
   * 将提醒状态标记为处理中（PROCESSING）
   *
   * <p>由父类在 processBusiness 之前调用。如果提醒已被取消，
   * 底层会抛出 AsyncTaskCancelledException，父类自动 ACK 跳过。</p>
   */
  @Override
  protected void markProcessing(ReminderPayload payload) {
    reminderService.markProcessing(payload.reminderId());
  }

  /**
   * 核心业务处理：按通道分流完成提醒投递
   *
   * <ol>
   *   <li>通过 reminderService 获取投递上下文（提醒 + 日程完整信息）</li>
   *   <li>EMAIL 通道：调用 emailSender.send() 发送 HTML 格式面试提醒邮件</li>
   *   <li>IN_APP 通道：仅记录日志，状态流转到 SENT 后前端通过 API 查询展示</li>
   * </ol>
   *
   * @param payload 提醒载荷
   */
  @Override
  protected void processBusiness(ReminderPayload payload) {
    // 从数据库获取投递上下文
    ReminderDeliveryContext context =
        reminderService.getDeliveryContext(payload.reminderId());
        // 按通道分流投递
        // EMAIL 通道：调用 emailSender.send() 发送 HTML 格式面试提醒邮件
        // IN_APP 通道：仅记录日志，状态流转到 SENT 后前端通过 API 查询展示
    if (context.channel() == ReminderChannel.EMAIL) {
      emailSender.send(context);
      return;
    }
    log.info("站内面试提醒已到达展示时间: reminderId={}, scheduleId={}",
        context.reminderId(), context.scheduleId());
  }

  /**
   * 标记提醒投递完成（SENT）
   *
   * <p>由父类在 processBusiness 成功执行后调用。</p>
   */
  @Override
  protected void markCompleted(ReminderPayload payload) {
    reminderService.markSent(payload.reminderId());
  }

  /**
   * 标记提醒已取消（CANCELLED）
   *
   * <p>由父类在捕获 AsyncTaskCancelledException 时调用。</p>
   */
  @Override
  protected void markCancelled(ReminderPayload payload, String reason) {
    reminderService.markCancelled(payload.reminderId(), reason);
  }

  /**
   * 标记提醒最终失败（FAILED）
   *
   * <p>由父类在重试次数耗尽后调用。</p>
   */
  @Override
  protected void markFailed(ReminderPayload payload, String error) {
    reminderService.markFailed(payload.reminderId(), error);
  }

  /**
   * 重试消息：将提醒重新投递到 Redis Stream
   *
   * <p>由父类在业务处理异常且未超过最大重试次数时调用。
   * 重新构建消息体（retryCount+1），写入 Redis Stream，
   * 同时将数据库中的提醒状态重置为 QUEUED。</p>
   *
   * @param payload    提醒载荷
   * @param retryCount 新的重试次数（当前 retryCount + 1）
   */
  @Override
  protected void retryMessage(ReminderPayload payload, int retryCount) {
    Map<String, String> message = Map.of(
        AsyncTaskStreamConstants.FIELD_REMINDER_ID,
        payload.reminderId().toString(),
        AsyncTaskStreamConstants.FIELD_BUSINESS_ID,
        payload.reminderId().toString(),
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT,
        String.valueOf(retryCount));
    redisService().streamAdd(
        streamKey(),
        message,
        AsyncTaskStreamConstants.STREAM_MAX_LEN);
    reminderService.markRetrying(payload.reminderId(), retryCount);
  }

  /**
   * 提醒任务载荷
   *
   * @param reminderId 提醒 ID
   */
  record ReminderPayload(Long reminderId) {
  }
}
