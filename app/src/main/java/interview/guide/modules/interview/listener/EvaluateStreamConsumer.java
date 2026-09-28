/**
 * 面试评估 Redis Stream 消费者
 *
 * <p>从 Redis Stream 消费面试评估任务，加载会话的题目和答案，
 * 调用 AnswerEvaluationService 执行 AI 评估，并将报告持久化到数据库。
 * 继承 AbstractStreamConsumer 模板，支持失败重试（最多 3 次）和
 * 完整的生命周期状态追踪（PROCESSING → COMPLETED / FAILED / DEAD_LETTER）。
 *
 * <p>消费者运行在主线程轮询循环中，通过 Redis 消费者组实现多实例负载均衡。
 * 每条消息独立处理，Ack 确认后自动进入下一条。
 *
 * <p><strong>联动关系：</strong>
 * <ul>
 *   <li>消费 EvaluateStreamProducer 发送的评估任务消息（Redis Stream 消息队列）</li>
 *   <li>依赖 AnswerEvaluationService 执行 LLM 驱动的面试答题评估逻辑</li>
 *   <li>依赖 InterviewPersistenceService 保存评估报告到 InterviewSessionEntity
 *       和 InterviewAnswerEntity</li>
 *   <li>依赖 InterviewSessionRepository 直接操作会话的评估状态
 *       （evaluateStatus、evaluateError 字段）</li>
 *   <li>依赖 LlmProviderRegistry 获取对应 Provider 的 ChatClient（带对话记忆），
 *       注意此处用 getChatClientOrDefault 而非 getPlainChatClient，
 *       因为评估属于对话式调用而非结构化输出</li>
 *   <li>依赖 AsyncTaskService 管理异步任务的数据库状态流转
 *       （PROCESSING → COMPLETED / DEAD_LETTER / RETRYING）</li>
 *   <li>依赖 ObjectMapper 对会话的 questionsJson 做 JSON 反序列化</li>
 *   <li>继承 AbstractStreamConsumer 模板，通过父类获得 Redis Stream 消费、
 *       消息 Ack、失败重试等通用能力</li>
 *   <li>抛出 AsyncTaskCancelledException 当面试会话已被删除时
 *       （由父类 AbstractStreamConsumer 捕获并标记为 CANCELLED 后 Ack 丢弃）</li>
 *   <li>使用 EvaluatePayload 作为消息反序列化载体（taskId + sessionId）</li>
 * </ul>
 */
package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.async.AsyncTaskCancelledException;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.observability.AsyncTaskMetricsRecorder;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.service.AsyncTaskService;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 面试评估 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行评估
 */
@Slf4j
@Component
public class EvaluateStreamConsumer extends AbstractStreamConsumer<EvaluateStreamConsumer.EvaluatePayload> {

    /**
     * 面试会话 Repository
     *
     * <p>直接操作 InterviewSessionEntity 的评估状态字段
     * （evaluateStatus、evaluateError），不经过 Service 层。
     */
    private final InterviewSessionRepository sessionRepository;

    /**
     * 答题评估服务
     *
     * <p>执行 LLM 驱动的面试答题评估，产出入 InterviewReportDTO。
     * 评估内容包含各题得分、分类得分、整体反馈和优缺点。
     */
    private final AnswerEvaluationService evaluationService;

    /**
     * 面试持久化服务
     *
     * <p>用于查询面试答案列表（findAnswersBySessionId）
     * 和保存评估报告到数据库（saveReport）。
     */
    private final InterviewPersistenceService persistenceService;

    /**
     * Jackson ObjectMapper 实例
     *
     * <p>用于将会话的 questionsJson 字段反序列化为 List&lt;InterviewQuestionDTO&gt;。
     */
    private final ObjectMapper objectMapper;

    /**
     * LLM 提供商注册表
     *
     * <p>通过 {@link LlmProviderRegistry#getChatClientOrDefault(String)}
     * 获取带对话记忆的 ChatClient，注意此处与自适应模块不同：
     * 评估属于对话式调用，需要 SkillsTool 和对话记忆 Advisor。
     */
    private final LlmProviderRegistry llmProviderRegistry;

    /**
     * 异步任务服务
     *
     * <p>管理异步任务的数据库状态流转
     * （markProcessing / markCompleted / markDeadLetter / markRetrying）。
     */
    private final AsyncTaskService asyncTaskService;

    /**
     * 构造器，注入所有依赖并调用父类构造器
     *
     * <p>由 Spring 创建 Consumer Bean 时调用；消费线程由父类生命周期方法启动。
     *
     * @param redisService Redis 操作服务（传给父类 AbstractStreamConsumer）
     * @param sessionRepository 面试会话仓库
     * @param evaluationService 答题评估服务
     * @param persistenceService 面试持久化服务
     * @param objectMapper JSON 序列化/反序列化工具
     * @param llmProviderRegistry LLM 提供商注册表
     * @param asyncTaskService 异步任务服务
     * @param metricsRecorder 异步任务指标记录器（传给父类）
     */
    public EvaluateStreamConsumer(
        RedisService redisService,
        InterviewSessionRepository sessionRepository,
        AnswerEvaluationService evaluationService,
        InterviewPersistenceService persistenceService,
        ObjectMapper objectMapper,
        LlmProviderRegistry llmProviderRegistry,
        AsyncTaskService asyncTaskService,
        AsyncTaskMetricsRecorder metricsRecorder
    ) {
        super(redisService, metricsRecorder);
        this.sessionRepository = sessionRepository;
        this.evaluationService = evaluationService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.llmProviderRegistry = llmProviderRegistry;
        this.asyncTaskService = asyncTaskService;
    }

    /**
     * 消息负载内部 record
     *
     * @param taskId 异步任务数据库 ID
     * @param sessionId 面试会话 UUID
     */
    record EvaluatePayload(Long taskId, String sessionId) {}

    /**
     * 返回任务显示名称，用于日志和指标标签
     *
     * <p>由 {@code AbstractStreamConsumer} 在处理日志和指标时回调。
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
     * <p>由父类创建消费者组、拉取、ACK 和恢复 pending 消息时回调。
     *
     * @return 面试评估专用 Stream Key（常量值）
     */
    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    /**
     * 返回消费者组名称
     *
     * <p>由父类初始化消费者组和执行 ACK 时回调。
     *
     * @return 面试评估消费者组名（常量值）
     */
    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME;
    }

    /**
     * 返回消费者实例名前缀
     *
     * <p>由父类初始化唯一 consumerName 时回调。
     *
     * <p>每个消费者实例会生成唯一的消费者名（前缀 + UUID），
     * 用于 Redis 消费者组内的实例标识和负载均衡。
     *
     * @return 消费者前缀（常量值）
     */
    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_CONSUMER_PREFIX;
    }

    /**
     * 返回轮询线程名称
     *
     * <p>由父类创建单线程消费执行器时回调。
     *
     * @return 线程名 "evaluate-consumer"
     */
    @Override
    protected String threadName() {
        return "evaluate-consumer";
    }

    /**
     * 解析 Redis Stream 消息为 EvaluatePayload
     *
     * <p>由父类收到每条新消息或认领 pending 消息后首先回调。
     *
     * <p>从消息字段中提取 taskId 和 sessionId，缺失任一必要字段时
     * 返回 null（父类会跳过并 Ack 该消息）。
     *
     * @param messageId Redis Stream 消息 ID
     * @param data 消息的键值对数据
     * @return 解析后的负载对象，必要字段缺失时返回 null
     */
    @Override
    protected EvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String taskIdStr = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        if (taskIdStr == null || sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new EvaluatePayload(Long.parseLong(taskIdStr), sessionId);
    }

    /**
     * 返回消息负载的标识字符串，用于日志输出
     *
     * <p>由父类处理成功、失败和重试日志时回调。
     *
     * @param payload 消息负载
     * @return 形如 "taskId=123, sessionId=abc-def" 的标识字符串
     */
    @Override
    protected String payloadIdentifier(EvaluatePayload payload) {
        return "taskId=" + payload.taskId() + ", sessionId=" + payload.sessionId();
    }

    /**
     * 标记任务进入处理中状态
     *
     * <p>由父类在 {@link #processBusiness(EvaluatePayload)} 之前回调。
     *
     * <p>同时更新两处状态：
     * <ul>
     *   <li>异步任务表：标记为 PROCESSING</li>
     *   <li>面试会话表：更新 evaluateStatus 为 PROCESSING</li>
     * </ul>
     *
     * @param payload 消息负载
     */
    @Override
    protected void markProcessing(EvaluatePayload payload) {
        asyncTaskService.markProcessing(payload.taskId());
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.PROCESSING, null);
    }

    /**
     * 执行核心业务逻辑：AI 面试评估
     *
     * <p>处理流程：
     * <ol>
     *   <li>从数据库加载面试会话（含关联的简历）</li>
     *   <li>会话不存在时抛出 AsyncTaskCancelledException，父类标记为 CANCELLED 后 Ack 丢弃</li>
     *   <li>幂等性校验：已评估（EVALUATED 状态且 overallScore 不为 null）则跳过重复处理</li>
     *   <li>将会话的 questionsJson 反序列化为题目列表</li>
     *   <li>将面试者的答案列表填充到对应题目中（通过 withAnswer 方法）</li>
     *   <li>通过 LlmProviderRegistry 获取 ChatClient（带对话记忆）</li>
     *   <li>调用 AnswerEvaluationService 执行 AI 评估</li>
     *   <li>将评估报告通过 InterviewPersistenceService 持久化到数据库</li>
     * </ol>
     *
     * @param payload 消息负载，包含 taskId 和 sessionId
     * @throws AsyncTaskCancelledException 面试会话已被删除时抛出，由父类标记为 CANCELLED
     */
    @Override
    protected void processBusiness(EvaluatePayload payload) {
        String sessionId = payload.sessionId();
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionIdWithResume(sessionId);
        if (sessionOpt.isEmpty()) {
            // 会话已删除，抛出取消异常由父类标记为 CANCELLED 并 Ack 丢弃
            throw new AsyncTaskCancelledException("面试会话已被删除: sessionId=" + sessionId);
        }

        InterviewSessionEntity session = sessionOpt.get();
        // 幂等性校验：已评估则跳过重复处理
        if (session.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED
                && session.getOverallScore() != null) {
            log.info("面试评估结果已存在，跳过重复处理: sessionId={}", sessionId);
            return;
        }
        // 将会话的题目 JSON 反序列化为 DTO 列表
        List<InterviewQuestionDTO> questions = objectMapper.readValue(
            session.getQuestionsJson(),
            new TypeReference<>() {}
        );

        // 将面试者的答案填充到对应题号上
        List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(sessionId);
        for (InterviewAnswerEntity answer : answers) {
            int index = answer.getQuestionIndex();
            if (index >= 0 && index < questions.size()) {
                InterviewQuestionDTO question = questions.get(index);
                questions.set(index, question.withAnswer(answer.getUserAnswer()));
            }
        }

        // 获取带对话记忆的 ChatClient（非 plain 版本，评估属于对话式调用）
        String provider = session.getLlmProvider();
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

        // 提取简历文本作为评估上下文的一部分
        String resumeText = session.getResume() != null ? session.getResume().getResumeText() : "";
        InterviewReportDTO report = evaluationService.evaluateInterview(chatClient, sessionId, resumeText, questions);
        persistenceService.saveReport(sessionId, report);
    }

    /**
     * 标记任务完成
     *
     * <p>由父类在 {@link #processBusiness(EvaluatePayload)} 正常返回后回调。
     *
     * <p>同时更新两处状态：
     * <ul>
     *   <li>异步任务表：标记为 COMPLETED</li>
     *   <li>面试会话表：更新 evaluateStatus 为 COMPLETED</li>
     * </ul>
     *
     * @param payload 消息负载
     */
    @Override
    protected void markCompleted(EvaluatePayload payload) {
        asyncTaskService.markCompleted(payload.taskId());
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.COMPLETED, null);
    }

    /**
     * 标记任务取消（会话已删除等场景）
     *
     * <p>由父类捕获 {@code AsyncTaskCancelledException} 后回调。
     *
     * <p>仅更新异步任务表为 CANCELLED，不修改会话状态
     * （因为会话本身可能已被删除）。
     *
     * @param payload 消息负载
     * @param reason 取消原因
     */
    @Override
    protected void markCancelled(EvaluatePayload payload, String reason) {
        asyncTaskService.markCancelled(payload.taskId(), reason);
    }

    /**
     * 标记任务失败（超过最大重试次数后进入死信）
     *
     * <p>由父类在自动重试次数耗尽后回调。
     *
     * <p>注意此处直接调用 markDeadLetter 而非 markFailed，
     * 表示任务已超过最大重试次数进入终态。
     *
     * @param payload 消息负载
     * @param error 错误信息
     */
    @Override
    protected void markFailed(EvaluatePayload payload, String error) {
        asyncTaskService.markDeadLetter(payload.taskId(), error);
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.FAILED, error);
    }

    /**
     * 重试消息：将任务重新入队到 Redis Stream
     *
     * <p>构建包含 taskId、sessionId 和当前重试次数的新消息，
     * 通过 RedisService 直接 XADD 到评估 Stream，并把异步任务表
     * 更新回 PENDING 状态。
     *
     * <p>入队失败时标记任务为 DEAD_LETTER，会话评估状态为 FAILED。
     *
     * @param payload 消息负载
     * @param retryCount 当前已重试次数（1-based）
     */
    @Override
    protected void retryMessage(EvaluatePayload payload, int retryCount) {
        String sessionId = payload.sessionId();
        try {
            // 构建重试消息，携带当前重试次数
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_TASK_ID, payload.taskId().toString(),
                AsyncTaskStreamConstants.FIELD_BUSINESS_ID, sessionId,
                AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            String messageId = redisService().streamAdd(
                AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            asyncTaskService.markRetrying(payload.taskId(), retryCount, messageId);
            log.info("评估任务已重新入队: sessionId={}, retryCount={}", sessionId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
            asyncTaskService.markDeadLetter(
                payload.taskId(),
                truncateError("重试入队失败: " + e.getMessage())
            );
        }
    }

    /**
     * 更新面试会话的评估状态
     *
     * <p>由处理开始、完成、最终失败和重试入队失败路径调用。
     *
     * <p>直接操作 InterviewSessionEntity 的 evaluateStatus 和 evaluateError 字段。
     * 会话不存在时静默跳过（可能是已被删除）。
     *
     * <p>更新过程中的异常会被捕获并记录日志，不向上层抛出，
     * 确保状态更新失败不影响主流程。
     *
     * @param sessionId 面试会话 UUID
     * @param status 新的评估状态（PROCESSING / COMPLETED / FAILED）
     * @param error 错误信息，非失败状态时为 null
     */
    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        try {
            sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
                session.setEvaluateStatus(status);
                session.setEvaluateError(error);
                sessionRepository.save(session);
                log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
            });
        } catch (Exception e) {
            log.error("更新评估状态失败: sessionId={}, status={}, error={}", sessionId, status, e.getMessage(), e);
        }
    }

}
