/**
 * 语音面试评估 Redis Stream 生产者
 *
 * 将语音面试评估任务发送到 Redis Stream，实现评估的异步化处理。
 * 会话结束时由 VoiceInterviewService 调用 sendEvaluateTask 入队，
 * 随后由 VoiceEvaluateStreamConsumer 消费执行。发送失败时更新会话评估状态为 FAILED。
 * 继承 AbstractStreamProducer 模板。
 *
 * 联动关系：
 * - 被 VoiceInterviewService.endSession / triggerEvaluation 调用（入队评估任务）
 * - 被 VoiceInterviewController 间接依赖（通过 Service 触发评估）
 * - 与 VoiceEvaluateStreamConsumer 配合使用（Producer 入队，Consumer 消费）
 * - 依赖 VoiceInterviewService（发送失败时更新评估状态）
 * - 依赖 RedisService（Redis Stream 操作，由父类提供）
 */
package interview.guide.modules.voiceinterview.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.observability.AsyncTaskMetricsRecorder;
import interview.guide.common.transaction.TransactionAfterCommitExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.model.AsyncTaskEnqueueResult;
import interview.guide.modules.asynctask.model.AsyncTaskType;
import interview.guide.modules.asynctask.service.AsyncTaskService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class VoiceEvaluateStreamProducer
        extends AbstractStreamProducer<VoiceEvaluateStreamProducer.VoiceEvaluateTaskPayload> {

    /**
     * 语音面试会话服务
     * 用于发送失败时更新会话评估状态为 FAILED
     */
    private final VoiceInterviewService voiceInterviewService;
    private final AsyncTaskService asyncTaskService;
    //事务提交后执行器，用于在事务提交后发送任务
    private final TransactionAfterCommitExecutor afterCommitExecutor;

    /**
     * 生产者内部任务载荷。
     *
     * @param taskId 统一异步任务表主键
     * @param sessionId 语音面试会话 ID
     * @param retryCount 当前重试次数
     */
    record VoiceEvaluateTaskPayload(Long taskId, String sessionId, int retryCount) {}

    /**
     * 创建评估任务生产者。
     *
     * 由 Spring 构造；会话服务使用 {@link Lazy} 延迟注入以打破 Service 与 Producer 的循环依赖，
     * Redis 和指标能力交给父类模板。
     *
     * @param redisService Redis 服务（由父类使用）
     * @param asyncTaskService 统一异步任务状态服务
     * @param afterCommitExecutor 事务提交后执行器
     * @param metricsRecorder 异步任务指标记录器
     * @param voiceInterviewService 语音面试会话服务
     */
    public VoiceEvaluateStreamProducer(RedisService redisService,
                                       AsyncTaskService asyncTaskService,
                                       TransactionAfterCommitExecutor afterCommitExecutor,
                                       AsyncTaskMetricsRecorder metricsRecorder,
                                       @Lazy VoiceInterviewService voiceInterviewService) {
        super(redisService, metricsRecorder);
        this.voiceInterviewService = voiceInterviewService;
        this.asyncTaskService = asyncTaskService;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    /**
     * 创建或复用统一异步任务，并在事务提交后发送评估消息。
     *
     * 由会话正常结束和手动评估入口调用。先通过 {@link AsyncTaskService} 保证同一业务任务幂等，
     * 新任务才交给提交后执行器调用父类 {@code sendTask}；发送成功后回写 Redis 消息 ID。
     *
     * @param sessionId 语音面试会话 ID
     */
    public void sendEvaluateTask(String sessionId) {
        //先检查是否存在已存在的任务，避免则重复入队
        //创建或复用已存在的任务，确保任务唯一性
        //任务id是数据库主键生成自增的，确保唯一性
        AsyncTaskEnqueueResult result = asyncTaskService.createOrReusePendingTask(
            AsyncTaskType.VOICE_INTERVIEW_EVALUATE,
            sessionId,
            streamKey(),
            "{\"sessionId\":\"" + sessionId + "\"}"
        );
        if (!result.created()) {
            log.info("语音面试评估任务已存在，跳过重复入队: sessionId={}, taskId={}",
                sessionId, result.task().id());
            return;
        }

        //创建任务载荷，包含任务ID、会话ID、重试次数（0）和会话状态
        VoiceEvaluateTaskPayload payload = new VoiceEvaluateTaskPayload(result.task().id(), sessionId, 0);
        //在事务提交后发送任务，确保会话状态更新
        //启动线程异步发送任务，避免阻塞事务处理
        afterCommitExecutor.execute(() -> {
            String messageId = sendTask(payload);
            if (messageId != null) {
                //标记任务为已入队，记录消息 ID
                asyncTaskService.markEnqueued(result.task().id(), messageId);
            }
        });
    }

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
     * 将内部载荷映射为消费者协议字段。
     *
     * 由 {@code AbstractStreamProducer.sendTask} 调用，写入任务 ID、业务 ID、语音会话 ID 和重试次数。
     *
     * @param payload 待发送的任务载荷
     * @return 消息键值对
     */
    @Override
    protected Map<String, String> buildMessage(VoiceEvaluateTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_TASK_ID, payload.taskId().toString(),
            AsyncTaskStreamConstants.FIELD_BUSINESS_ID, payload.sessionId(),
            AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, payload.sessionId(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(payload.retryCount())
        );
    }

    /**
     * 返回父类日志和指标使用的载荷标识。
     *
     * @param payload 任务载荷
     * @return 标识字符串
     */
    @Override
    protected String payloadIdentifier(VoiceEvaluateTaskPayload payload) {
        return "voiceSessionId=" + payload.sessionId();
    }

    /**
     * 处理 Redis Stream 发送失败。
     *
     * 由父类模板回调，同时把会话评估状态和统一任务状态标记为失败，错误文本在落库前截断。
     *
     * @param payload 发送失败的任务载荷
     * @param error 错误信息
     */
    @Override
    protected void onSendFailed(VoiceEvaluateTaskPayload payload, String error) {
        voiceInterviewService.updateEvaluateStatus(
                Long.parseLong(payload.sessionId()), AsyncTaskStatus.FAILED, truncateError(error));
        asyncTaskService.markFailed(payload.taskId(), truncateError(error));
    }
}
