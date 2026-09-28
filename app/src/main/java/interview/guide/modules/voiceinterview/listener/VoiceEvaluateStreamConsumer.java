/**
 * 语音面试评估 Redis Stream 消费者
 *
 * 从 Redis Stream 消费语音面试评估任务，调用 VoiceInterviewEvaluationService 执行评估生成，
 * 并更新会话的评估状态（PROCESSING → COMPLETED/FAILED）。失败时支持重新入队重试（最多 3 次）。
 * 继承 AbstractStreamConsumer 模板，实现消息解析、状态标记和业务处理。
 *
 * 联动关系：
 * - 依赖 VoiceInterviewService（更新评估状态）
 * - 依赖 VoiceInterviewEvaluationService（执行评估生成）
 * - 依赖 RedisService（Redis Stream 操作，由父类提供）
 * - 与 VoiceEvaluateStreamProducer 配合使用（Producer 入队，Consumer 消费）
 * - 使用 AsyncTaskStreamConstants 中的 Stream Key 和 Group Name 常量
 */
package interview.guide.modules.voiceinterview.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.async.AsyncTaskCancelledException;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.observability.AsyncTaskMetricsRecorder;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.service.AsyncTaskService;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class VoiceEvaluateStreamConsumer extends AbstractStreamConsumer<VoiceEvaluateStreamConsumer.VoiceEvaluatePayload> {

    /**
     * 语音面试会话服务
     * 用于更新会话的评估状态（PROCESSING/COMPLETED/FAILED）
     */
    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceInterviewEvaluationRepository evaluationRepository;
    private final AsyncTaskService asyncTaskService;

    /**
     * 语音面试评估服务
     * 用于执行实际的评估生成逻辑
     */
    private final VoiceInterviewEvaluationService evaluationService;

    /**
     * 创建评估 Stream 消费者并把通用 Redis、指标能力交给父类。
     *
     * 由 Spring 构造；Repository 用于消费前判定会话是否仍存在以及结果是否已经生成，避免重复 LLM
     * 调用，AsyncTaskService 与会话服务分别维护通用任务状态和业务评估状态。
     *
     * @param redisService Redis 服务（由父类使用）
     * @param voiceInterviewService 语音面试会话服务
     * @param evaluationService 语音面试评估服务
     * @param sessionRepository 会话 Repository
     * @param evaluationRepository 评估结果 Repository
     * @param asyncTaskService 统一异步任务状态服务
     * @param metricsRecorder 异步任务指标记录器
     */
    public VoiceEvaluateStreamConsumer(RedisService redisService,
                                       VoiceInterviewService voiceInterviewService,
                                       VoiceInterviewEvaluationService evaluationService,
                                       VoiceInterviewSessionRepository sessionRepository,
                                       VoiceInterviewEvaluationRepository evaluationRepository,
                                       AsyncTaskService asyncTaskService,
                                       AsyncTaskMetricsRecorder metricsRecorder) {
        super(redisService, metricsRecorder);
        this.voiceInterviewService = voiceInterviewService;
        this.evaluationService = evaluationService;
        this.sessionRepository = sessionRepository;
        this.evaluationRepository = evaluationRepository;
        this.asyncTaskService = asyncTaskService;
    }

    /**
     * 消费者内部任务载荷。
     *
     * @param taskId 统一异步任务表主键
     * @param sessionId 语音面试会话 ID
     */
    record VoiceEvaluatePayload(Long taskId, String sessionId) {}

    /**
     * 任务显示名称，用于日志输出
     *
     * @return 任务中文名称
     */
    @Override
    protected String taskDisplayName() {
        return "语音面试评估";
    }

    /**
     * Redis Stream 的 Key，从常量类获取
     *
     * @return Stream Key
     */
    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;
    }

    /**
     * Redis Stream 消费者组名称，从常量类获取
     *
     * @return Group Name
     */
    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_GROUP_NAME;
    }

    /**
     * 消费者名称前缀，从常量类获取
     *
     * @return Consumer Prefix
     */
    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_CONSUMER_PREFIX;
    }

    /**
     * 消费者线程名称
     *
     * @return 线程名称
     */
    @Override
    protected String threadName() {
        return "voice-evaluate-consumer";
    }

    /**
     * 将 Redis Stream 字段解析为评估任务载荷。
     *
     * 由父类消费模板在收到消息后调用；任务 ID 或会话 ID 缺失时返回 {@code null}，模板会跳过
     * 无法处理的消息。
     *
     * @param messageId Redis Stream 消息 ID
     * @param data 消息键值对数据
     * @return 解析后的载荷，格式错误返回 null
     */
    @Override
    protected VoiceEvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String taskIdStr = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID);
        if (taskIdStr == null || sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new VoiceEvaluatePayload(Long.parseLong(taskIdStr), sessionId);
    }

    /**
     * 获取载荷的唯一标识，用于日志输出
     *
     * @param payload 评估载荷
     * @return 标识字符串
     */
    @Override
    protected String payloadIdentifier(VoiceEvaluatePayload payload) {
        return "taskId=" + payload.taskId() + ", voiceSessionId=" + payload.sessionId();
    }

    /**
     * 在实际调用 LLM 前同步更新通用任务和会话评估状态。
     *
     * 由父类消费模板调用，状态从队列等待进入 PROCESSING。
     *
     * @param payload 评估载荷
     */
    @Override
    protected void markProcessing(VoiceEvaluatePayload payload) {
        asyncTaskService.markProcessing(payload.taskId());
        voiceInterviewService.updateEvaluateStatus(
                Long.parseLong(payload.sessionId()), AsyncTaskStatus.PROCESSING, null);
    }

    /**
     * 校验业务对象后执行一次语音评估生成。
     *
     * 由父类模板在标记处理中后调用。会话已删除时抛出取消异常并直接 ACK；结果已存在时按幂等
     * 成功处理；其他情况调用评估服务执行 LLM 和持久化。
     *
     * @param payload 评估载荷
     */
    @Override
    protected void processBusiness(VoiceEvaluatePayload payload) {
        Long sessionId = Long.parseLong(payload.sessionId());
        if (!sessionRepository.existsById(sessionId)) {
            throw new AsyncTaskCancelledException("语音面试会话已被删除: sessionId=" + sessionId);
        }
        if (evaluationRepository.findBySessionId(sessionId).isPresent()) {
            log.info("语音面试评估结果已存在，跳过重复处理: sessionId={}", sessionId);
            return;
        }
        evaluationService.generateEvaluation(sessionId);
        log.info("语音面试评估完成: sessionId={}", payload.sessionId());
    }

    /**
     * 标记评估状态为已完成（COMPLETED）
     *
     * @param payload 评估载荷
     */
    @Override
    protected void markCompleted(VoiceEvaluatePayload payload) {
        asyncTaskService.markCompleted(payload.taskId());
        voiceInterviewService.updateEvaluateStatus(
                Long.parseLong(payload.sessionId()), AsyncTaskStatus.COMPLETED, null);
    }

    /**
     * 标记因会话删除而取消的统一任务。
     *
     * 由父类捕获 {@link AsyncTaskCancelledException} 后调用，不再重试，也不更新已删除会话。
     *
     * @param payload 被取消的任务载荷
     * @param reason 取消原因
     */
    @Override
    protected void markCancelled(VoiceEvaluatePayload payload, String reason) {
        asyncTaskService.markCancelled(payload.taskId(), reason);
    }

    /**
     * 标记评估状态为失败（FAILED）
     *
     * @param payload 评估载荷
     * @param error 错误信息
     */
    @Override
    protected void markFailed(VoiceEvaluatePayload payload, String error) {
        asyncTaskService.markDeadLetter(payload.taskId(), error);
        voiceInterviewService.updateEvaluateStatus(
                Long.parseLong(payload.sessionId()), AsyncTaskStatus.FAILED, error);
    }

    /**
     * 将可重试失败重新写入 Stream，并同步统一任务重试状态。
     *
     * 由父类失败策略调用。新消息沿用原任务 ID 和会话 ID、写入递增后的重试次数；再次入队失败时
     * 同时把业务评估和统一任务标记为最终失败。
     *
     * @param payload 评估载荷
     * @param retryCount 当前重试次数
     */
    @Override
    protected void retryMessage(VoiceEvaluatePayload payload, int retryCount) {
        String sessionId = payload.sessionId();
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_TASK_ID, payload.taskId().toString(),
                AsyncTaskStreamConstants.FIELD_BUSINESS_ID, sessionId,
                AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, sessionId,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            String messageId = redisService().streamAdd(
                AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            // 重新入队后，标记任务为重试中
            asyncTaskService.markRetrying(payload.taskId(), retryCount, messageId);
            log.info("语音面试评估任务已重新入队: sessionId={}, retryCount={}", sessionId, retryCount);
        } catch (Exception e) {
            log.error("重试入队失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            voiceInterviewService.updateEvaluateStatus(
                    Long.parseLong(sessionId), AsyncTaskStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
            asyncTaskService.markDeadLetter(
                payload.taskId(),
                truncateError("重试入队失败: " + e.getMessage())
            );
        }
    }
}
