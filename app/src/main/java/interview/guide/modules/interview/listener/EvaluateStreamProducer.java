/**
 * 面试评估 Redis Stream 生产者
 *
 * <p>将面试评估任务发送到 Redis Stream 消息队列，由 EvaluateStreamConsumer
 * 异步消费执行。继承 AbstractStreamProducer 模板，提供发送失败兜底处理。
 *
 * <p>核心设计要点：
 * <ul>
 *   <li>通过 AsyncTaskService 的 createOrReusePendingTask 确保同一会话
 *       只有一个评估任务在队列中，防止重复入队</li>
 *   <li>消息发送在事务提交后执行（TransactionAfterCommitExecutor），
 *       保证数据库事务先提交、消息才入队，避免事务回滚导致消息已发送的
 *       不一致问题</li>
 *   <li>发送失败时自动将会话评估状态和异步任务标记为 FAILED</li>
 * </ul>
 *
 * <p><strong>联动关系：</strong>
 * <ul>
 *   <li>被 InterviewSessionService 调用（提交最后一题或提前交卷时触发，
 *       通过 sendEvaluateTask 入队评估任务）</li>
 *   <li>发送的消息由 EvaluateStreamConsumer 消费（Redis Stream 消费者组）</li>
 *   <li>依赖 AsyncTaskService 进行任务创建/复用和状态标记
 *       （createOrReusePendingTask / markEnqueued / markFailed）</li>
 *   <li>依赖 InterviewSessionRepository 在发送失败时直接更新会话的评估状态
 *       （evaluateStatus、evaluateError 字段）</li>
 *   <li>依赖 TransactionAfterCommitExecutor 确保消息在事务提交后入队，
 *       避免分布式事务不一致</li>
 *   <li>继承 AbstractStreamProducer 模板，通过父类获得 Redis Stream
 *       消息构建、发送、截断和指标记录等通用能力</li>
 *   <li>使用 EvaluateTaskPayload 作为消息构建载体（taskId + sessionId + retryCount）</li>
 * </ul>
 */
package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.observability.AsyncTaskMetricsRecorder;
import interview.guide.common.transaction.TransactionAfterCommitExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.model.AsyncTaskEnqueueResult;
import interview.guide.modules.asynctask.model.AsyncTaskType;
import interview.guide.modules.asynctask.service.AsyncTaskService;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 面试评估任务生产者
 * 负责发送评估任务到 Redis Stream
 */
@Slf4j
@Component
public class EvaluateStreamProducer extends AbstractStreamProducer<EvaluateStreamProducer.EvaluateTaskPayload> {

    /**
     * 面试会话 Repository
     *
     * <p>在消息发送失败时直接更新会话的 evaluateStatus 和 evaluateError 字段，
     * 不经过 Service 层。
     */
    private final InterviewSessionRepository sessionRepository;

    /**
     * 异步任务服务
     *
     * <p>管理异步任务的数据库状态流转：
     * <ul>
     *   <li>createOrReusePendingTask — 创建或复用待处理任务（防重复入队）</li>
     *   <li>markEnqueued — 标记任务已入队（记录消息 ID）</li>
     *   <li>markFailed — 发送失败时标记任务为 FAILED</li>
     * </ul>
     */
    private final AsyncTaskService asyncTaskService;

    /**
     * 事务提交后执行器
     *
     * <p>确保消息在数据库事务成功提交后才入队，避免事务回滚后
     * 消息已被消费的分布式不一致问题。
     */
    private final TransactionAfterCommitExecutor afterCommitExecutor;

    /**
     * 任务负载内部 record
     *
     * @param taskId 异步任务数据库 ID
     * @param sessionId 面试会话 UUID
     * @param retryCount 当前重试次数（生产者为 0）
     */
    record EvaluateTaskPayload(Long taskId, String sessionId, int retryCount) {}

    /**
     * 构造器，注入所有依赖并调用父类构造器
     *
     * <p>由 Spring 创建 Producer Bean 时调用；构造过程不发送消息。
     *
     * @param redisService Redis 操作服务（传给父类 AbstractStreamProducer）
     * @param sessionRepository 面试会话仓库
     * @param asyncTaskService 异步任务服务
     * @param afterCommitExecutor 事务提交后执行器
     * @param metricsRecorder 异步任务指标记录器（传给父类）
     */
    public EvaluateStreamProducer(
            RedisService redisService,
            InterviewSessionRepository sessionRepository,
            AsyncTaskService asyncTaskService,
            TransactionAfterCommitExecutor afterCommitExecutor,
            AsyncTaskMetricsRecorder metricsRecorder) {
        super(redisService, metricsRecorder);
        this.sessionRepository = sessionRepository;
        this.asyncTaskService = asyncTaskService;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    /**
     * 发送评估任务到 Redis Stream
     *
     * <p>对外唯一入口，由 InterviewSessionService 在面试结束时调用。
     *
     * <p>处理流程：
     * <ol>
     *   <li>通过 AsyncTaskService 创建或复用待处理任务
     *       （activeFlag 机制防止同一会话重复入队）</li>
     *   <li>任务已存在则跳过（幂等性保证）</li>
     *   <li>构建 EvaluateTaskPayload（taskId + sessionId + retryCount=0）</li>
     *   <li>在数据库事务提交后，通过父类 sendTask 将消息 XADD 到 Redis Stream</li>
     *   <li>发送成功则保持任务为 PENDING 并记录消息 ID</li>
     *   <li>发送失败则由父类回调 onSendFailed 标记 FAILED</li>
     * </ol>
     *
     * @param sessionId 面试会话 UUID
     */
    public void sendEvaluateTask(String sessionId) {

        // 创建或复用待处理任务，确保每个会话只有一个评估任务在队列中
        AsyncTaskEnqueueResult result = asyncTaskService.createOrReusePendingTask(
            AsyncTaskType.INTERVIEW_EVALUATE,
            sessionId,
            streamKey(),
            "{\"sessionId\":\"" + sessionId + "\"}"
        );
        if (!result.created()) {
            log.info("面试评估任务已存在，跳过重复入队: sessionId={}, taskId={}",
                sessionId, result.task().id());
            return;
        }
        // 构建任务负载，初始重试次数为 0
        EvaluateTaskPayload payload = new EvaluateTaskPayload(result.task().id(), sessionId, 0);
        // 在事务提交后入队，确保数据一致性
        afterCommitExecutor.execute(() -> {
            String messageId = sendTask(payload);
            if (messageId != null) {
                asyncTaskService.markEnqueued(result.task().id(), messageId);
            }
        });
    }

    /**
     * 返回任务显示名称，用于日志和指标标签
     *
     * <p>由 {@code AbstractStreamProducer.sendTask} 在记录日志和指标时调用。
     *
     * @return 任务名称 "评估"
     */
    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    /**
     * 返回 Redis Stream Key
     *
     * <p>由父类发送消息和 {@link #sendEvaluateTask(String)} 创建任务记录时调用。
     *
     * @return 面试评估专用 Stream Key（常量值，与消费者一致）
     */
    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    /**
     * 构建 Redis Stream 消息的键值对
     *
     * <p>由父类 {@code sendTask} 在执行 XADD 前回调。
     *
     * <p>将 taskId、businessId、sessionId 和 retryCount 组装为消息字段，
     * 消费者通过 parsePayload 解析这些字段。
     *
     * @param payload 任务负载
     * @return 包含所有消息字段的不可变 Map
     */
    @Override
    protected Map<String, String> buildMessage(EvaluateTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_TASK_ID, payload.taskId().toString(),
            AsyncTaskStreamConstants.FIELD_BUSINESS_ID, payload.sessionId(),
            AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(payload.retryCount())
        );
    }

    /**
     * 返回消息负载的标识字符串，用于日志输出
     *
     * <p>由父类在发送成功或失败日志中回调。
     *
     * @param payload 任务负载
     * @return 形如 "sessionId=abc-def" 的标识字符串
     */
    @Override
    protected String payloadIdentifier(EvaluateTaskPayload payload) {
        return "sessionId=" + payload.sessionId();
    }

    /**
     * 消息发送失败回调
     *
     * <p>同时更新两处状态为 FAILED：
     * <ul>
     *   <li>面试会话表：更新 evaluateStatus 为 FAILED，evaluateError 截断到 500 字符</li>
     *   <li>异步任务表：标记为 FAILED（错误信息截断）</li>
     * </ul>
     *
     * <p>此方法由父类 AbstractStreamProducer 在 XADD 失败时自动调用。
     *
     * @param payload 任务负载
     * @param error 错误信息（已由父类 truncateError 处理长度）
     */
    @Override
    protected void onSendFailed(EvaluateTaskPayload payload, String error) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.FAILED, truncateError(error));
        asyncTaskService.markFailed(payload.taskId(), truncateError(error));
    }

    /**
     * 更新面试会话的评估状态
     *
     * <p>仅由 {@link #onSendFailed(EvaluateTaskPayload, String)} 调用，
     * 属于发送失败后的补偿状态写入。
     *
     * <p>直接操作 InterviewSessionEntity 的 evaluateStatus 和 evaluateError 字段。
     * 错误信息超过 500 字符时自动截断，防止数据库字段溢出。
     * 会话不存在时静默跳过（findBySessionId 返回空 Optional）。
     *
     * @param sessionId 面试会话 UUID
     * @param status 评估状态（仅在 onSendFailed 中调用，值为 FAILED）
     * @param error 错误信息，可为 null
     */
    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setEvaluateStatus(status);
            if (error != null) {
                // 截断到 500 字符，防止数据库 TEXT 字段溢出
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            sessionRepository.save(session);
        });
    }
}
