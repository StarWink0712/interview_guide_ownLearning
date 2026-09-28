/**
 * 简历分析 Redis Stream 消费者
 *
 * 从 Redis Stream 消费简历分析任务消息，调用 AI 评分服务完成简历分析，
 * 并将分析结果持久化到数据库。处理前会校验简历实体是否存在，不存在则直接 ACK 丢弃。
 * 支持失败重试（最多 3 次），超过后标记任务为 FAILED。
 *
 * 联动关系：
 * - 继承 AbstractStreamConsumer，复用 Redis Stream 消费模板
 * - 依赖 ResumeGradingService 执行 AI 简历评分
 * - 依赖 ResumePersistenceService 保存分析结果
 * - 依赖 ResumeRepository 更新分析状态和校验简历存在性
 * - 与 AnalyzeStreamProducer 配合使用（Producer 发送、Consumer 消费）
 * - 使用 AsyncTaskStreamConstants 中定义的 Stream Key 和 Group Name
 */
package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.async.AsyncTaskCancelledException;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.observability.AsyncTaskMetricsRecorder;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.service.AsyncTaskService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumeParseService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 简历分析 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行 AI 分析
 */
@Slf4j
@Component
public class AnalyzeStreamConsumer extends AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload> {


    // 依赖注入的 AI 简历评分服务
    // 用于执行简历分析任务
    private final ResumeGradingService gradingService;
    // 依赖注入的持久化服务
    // 用于保存分析结果
    private final ResumePersistenceService persistenceService;
    // 依赖注入的简历仓库
    // 用于校验简历实体是否存在
    private final ResumeRepository resumeRepository;
    // 依赖注入的简历解析服务
    // 用于解析简历文件
    private final ResumeParseService parseService;
    // 依赖注入的异步任务服务
    // 用于执行异步任务，如 AI 分析
    private final AsyncTaskService asyncTaskService;

    public AnalyzeStreamConsumer(
        RedisService redisService,
        ResumeGradingService gradingService,
        ResumePersistenceService persistenceService,
        ResumeRepository resumeRepository,
        ResumeParseService parseService,
        AsyncTaskService asyncTaskService,
        AsyncTaskMetricsRecorder metricsRecorder
    ) {
        super(redisService, metricsRecorder);
        this.gradingService = gradingService;
        this.persistenceService = persistenceService;
        this.resumeRepository = resumeRepository;
        this.parseService = parseService;
        this.asyncTaskService = asyncTaskService;
    }

    /**
     * 分析任务消息体，只包含任务ID和简历ID。
     */
    record AnalyzePayload(Long taskId, Long resumeId) {}

    @Override
    protected String taskDisplayName() {
        return "简历分析";
    }

    @Override
    protected String streamKey() {
        // 简历分析 Stream Key
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        // 简历分析 Group Name
        return AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        // 简历分析 Consumer Prefix前缀
        return AsyncTaskStreamConstants.RESUME_ANALYZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "analyze-consumer";
    }

    /**
     * 从 Redis Stream 消息中解析分析任务消息体
     * @param messageId 消息ID
     * @param data      消息数据
     * @return 分析任务消息体
     */
    @Override
    protected AnalyzePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        // 从 Redis Stream 消息中提取任务ID和简历ID
        String taskIdStr = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
        String resumeIdStr = data.get(AsyncTaskStreamConstants.FIELD_RESUME_ID);
        if (taskIdStr == null || resumeIdStr == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        //封装成分析任务消息体对象
        return new AnalyzePayload(Long.parseLong(taskIdStr), Long.parseLong(resumeIdStr));
    }

    /**
     * 用于唯一标识分析任务，根据简历ID
     * @param payload 分析任务消息体
     * @return 唯一标识符字符串
     */
    @Override
    protected String payloadIdentifier(AnalyzePayload payload) {
        return "taskId=" + payload.taskId() + ", resumeId=" + payload.resumeId();
    }

    /**
     * 标记分析任务为处理中
     * @param payload 分析任务消息体
     */
    @Override
    protected void markProcessing(AnalyzePayload payload) {
        asyncTaskService.markProcessing(payload.taskId());
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.PROCESSING, null);
    }

    /**
     * 处理分析任务业务逻辑
     * @param payload 分析任务消息体
     */
    @Override
    protected void processBusiness(AnalyzePayload payload) {
        //获取简历id
        Long resumeId = payload.resumeId();
        //根据简历id查询简历实体
        //如果简历不存在，抛出异常
        ResumeEntity resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new AsyncTaskCancelledException("简历已被删除: resumeId=" + resumeId));
        if (resume.getAnalyzeStatus() == AsyncTaskStatus.COMPLETED
                && persistenceService.getLatestAnalysis(resumeId).isPresent()) {
            log.info("简历分析结果已存在，跳过重复处理: resumeId={}", resumeId);
            return;
        }
        //解析简历文本
        //如果解析失败，抛出异常
        //如果解析成功，调用AI分析服务
        //如果分析成功，保存分析结果
        //如果分析失败，抛出异常
        String resumeText = resolveResumeText(resume);
        //调用AI分析服务
        ResumeAnalysisResponse analysis = gradingService.analyzeResume(resumeText);
        //通过简历id查询简历实体，更新分析状态为已完成
        resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new AsyncTaskCancelledException("简历在分析期间被删除: resumeId=" + resumeId));
            //保存分析结果,调用持久化服务保存分析结果
        persistenceService.saveAnalysis(resume, analysis);
    }
    /**
     * 标记分析任务为已完成
     * @param payload 分析任务消息体
     */
    @Override
    protected void markCompleted(AnalyzePayload payload) {
        //标记分析任务为已完成,调用异步任务服务标记任务为已完成
        asyncTaskService.markCompleted(payload.taskId());
        //更新简历分析状态为已完成
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markCancelled(AnalyzePayload payload, String reason) {
        asyncTaskService.markCancelled(payload.taskId(), reason);
    }

    @Override
    protected void markFailed(AnalyzePayload payload, String error) {
        asyncTaskService.markDeadLetter(payload.taskId(), error);
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, error);
    }
    /**
     * 处理发送失败的情况
     * 重试入队分析任务，增加重试次数
     * @param payload 分析任务消息体
     * @param retryCount 重试次数
     */
    @Override
    protected void retryMessage(AnalyzePayload payload, int retryCount) {
        Long resumeId = payload.resumeId();
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_TASK_ID, payload.taskId().toString(),
                AsyncTaskStreamConstants.FIELD_BUSINESS_ID, resumeId.toString(),
                AsyncTaskStreamConstants.FIELD_RESUME_ID, resumeId.toString(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            String messageId = redisService().streamAdd(
                AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            asyncTaskService.markRetrying(payload.taskId(), retryCount, messageId);
            log.info("简历分析任务已重新入队: resumeId={}, retryCount={}", resumeId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: resumeId={}, error={}", resumeId, e.getMessage(), e);
            updateAnalyzeStatus(resumeId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
            asyncTaskService.markDeadLetter(
                payload.taskId(),
                truncateError("重试入队失败: " + e.getMessage())
            );
        }
    }

    /**
     * 更新分析状态
     */
    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        try {
            resumeRepository.findById(resumeId).ifPresent(resume -> {
                resume.setAnalyzeStatus(status);
                resume.setAnalyzeError(error);
                resumeRepository.save(resume);
                log.debug("分析状态已更新: resumeId={}, status={}", resumeId, status);
            });
        } catch (Exception e) {
            log.error("更新分析状态失败: resumeId={}, status={}, error={}", resumeId, status, e.getMessage(), e);
        }
    }

    private String resolveResumeText(ResumeEntity resume) {
        String resumeText = resume.getResumeText();
        if (resumeText != null && !resumeText.isBlank()) {
            return resumeText;
        }
        resumeText = parseService.downloadAndParseContent(
            resume.getStorageKey(),
            resume.getOriginalFilename()
        );
        if (resumeText == null || resumeText.isBlank()) {
            throw new AsyncTaskCancelledException("无法获取简历文本内容: resumeId=" + resume.getId());
        }
        resume.setResumeText(resumeText);
        resumeRepository.save(resume);
        return resumeText;
    }

}
