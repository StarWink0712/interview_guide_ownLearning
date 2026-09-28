package interview.guide.modules.interviewschedule.reminder.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interviewschedule.reminder.service.InterviewReminderService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 面试提醒消息生产者
 *
 * <p>将到期的提醒 ID 封装为轻量载荷投递到 Redis Stream，
 * 由消费者异步拉取并完成实际的提醒投递（邮件发送、站内通知）。
 * 继承 {@link AbstractStreamProducer}，复用消息序列化、发送和重试等基础设施。</p>
 *
 * <p>仅在 {@code app.interview-reminder.dispatch-enabled=true} 时启用。</p>
 */
@Component
@ConditionalOnProperty(
    name = "app.interview-reminder.dispatch-enabled",
    havingValue = "true")
public class InterviewReminderStreamProducer
    extends AbstractStreamProducer<InterviewReminderStreamProducer.ReminderPayload> {

  /** 提醒服务，用于投递失败时标记提醒为 FAILED */
  private final InterviewReminderService reminderService;

  /**
   * 构造生产者实例
   *
   * @param redisService    Redis 操作服务
   * @param reminderService 提醒服务
   */
  public InterviewReminderStreamProducer(
      RedisService redisService,
      InterviewReminderService reminderService) {
    super(redisService);
    this.reminderService = reminderService;
  }

  /**
   * 将提醒 ID 投递到 Redis Stream
   *
   * @param reminderId 提醒 ID
   */
  public void sendReminder(Long reminderId) {
    sendTask(new ReminderPayload(reminderId, 0));
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

  /**
   * 构建 Redis Stream 消息体
   *
   * @param payload 提醒载荷
   * @return 包含 reminderId、businessId、retryCount 的消息 Map
   */
  @Override
  protected Map<String, String> buildMessage(ReminderPayload payload) {
    return Map.of(
        AsyncTaskStreamConstants.FIELD_REMINDER_ID,
        payload.reminderId().toString(),
        AsyncTaskStreamConstants.FIELD_BUSINESS_ID,
        payload.reminderId().toString(),
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT,
        String.valueOf(payload.retryCount()));
  }

  /** 载荷标识，用于日志追踪 */
  @Override
  protected String payloadIdentifier(ReminderPayload payload) {
    return "reminderId=" + payload.reminderId();
  }

  /**
   * 消息发送失败回调
   *
   * <p>当 Redis Stream 投递本身失败时，直接将提醒标记为 FAILED。</p>
   *
   * @param payload 提醒载荷
   * @param error   错误信息
   */
  @Override
  protected void onSendFailed(ReminderPayload payload, String error) {
    reminderService.markFailed(payload.reminderId(), error);
  }

  /**
   * 提醒投递载荷
   *
   * @param reminderId 提醒 ID
   * @param retryCount 当前重试次数
   */
  record ReminderPayload(Long reminderId, int retryCount) {
  }
}
