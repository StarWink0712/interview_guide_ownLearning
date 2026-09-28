/**
 * 简历分析 Redis Stream 生产者
 *
 * 将简历分析任务消息发送到 Redis Stream，触发异步分析流程。
 * 发送失败时会更新简历的分析状态为 FAILED。
 *
 * 联动关系：
 * - 继承 AbstractStreamProducer，复用 Redis Stream 生产模板
 * - 依赖 ResumeRepository 在发送失败时更新分析状态
 * - 被 ResumeUploadService 调用（上传后发送分析任务、重新分析时发送任务）
 * - 与 AnalyzeStreamConsumer 配合使用（Producer 发送、Consumer 消费）
 * - 使用 AsyncTaskStreamConstants 中定义的 Stream Key 和 Group Name
 */
package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.observability.AsyncTaskMetricsRecorder;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionAfterCommitExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.model.AsyncTaskEnqueueResult;
import interview.guide.modules.asynctask.model.AsyncTaskType;
import interview.guide.modules.asynctask.service.AsyncTaskService;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 简历分析任务生产者
 * 负责发送分析任务到 Redis Stream
 */
@Slf4j
@Component
public class AnalyzeStreamProducer extends AbstractStreamProducer<AnalyzeStreamProducer.AnalyzeTaskPayload> {
    // 简历持久化服务，用于更新分析状态
    // 依赖 ResumeRepository 更新分析状态
    private final ResumeRepository resumeRepository; 
    private final AsyncTaskService asyncTaskService;
    private final TransactionAfterCommitExecutor afterCommitExecutor;

    //内部类，用于封装分析任务的负载信息
    // 包含任务ID、简历ID和重试次数，用于在 Redis Stream 中记录任务的执行轨迹和状态信息
    record AnalyzeTaskPayload(Long taskId, Long resumeId, int retryCount) {}

    public AnalyzeStreamProducer(
            RedisService redisService,
            ResumeRepository resumeRepository,
            AsyncTaskService asyncTaskService,
            TransactionAfterCommitExecutor afterCommitExecutor,
            AsyncTaskMetricsRecorder metricsRecorder) {
        super(redisService, metricsRecorder);
        this.resumeRepository = resumeRepository;
        this.asyncTaskService = asyncTaskService;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    /**
     * 发送分析任务到 Redis Stream
     * 简历内容只进了数据库，即AsyncTaskEnqueueResult result
     * 并没有进入到stream中，因为新建立的payload中只包含了任务id，简历id，重试次数
     * @param resumeId 简历ID
     * @param content  简历内容，即传入的resumeText文本
     */
    public void sendAnalyzeTask(Long resumeId, String content) {
        //调用AsyncTaskService创建或复用待处理任务
        //AsyncTaskEnqueueResult是异步任务的入队结果（类实例），包含任务ID、业务ID、任务类型、任务负载等信息
        //即封装成一个任务实体，包括简历的内容，任务类型，简历id，streamkey（固定常量AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY）
        AsyncTaskEnqueueResult result = asyncTaskService.createOrReusePendingTask(
            // 任务类型：简历分析，有四类，对应四个不同的分析任务
            AsyncTaskType.RESUME_ANALYZE,
            // 业务ID：简历ID，用于唯一标识分析任务
            resumeId.toString(),
            // Redis Stream Key，用于将分析任务消息发送到指定的 Redis Stream 中
            //要把这个任务发送到对应的stream中
            streamKey(),
            // 任务负载：包含简历ID、内容和重试次数
            // 用于在 Redis Stream 中记录任务的执行轨迹和状态信息
            //payloadJson内部方法将简历id和简历内容转换为json字符串
            payloadJson(resumeId, content)
        );
        if (!result.created()) {
            log.info("简历分析任务已存在，跳过重复入队: resumeId={}, taskId={}",
                resumeId, result.task().id());
            return;
        }
        // 构建分析任务负载类实例
        // 包含任务ID、简历ID和重试次数
        AnalyzeTaskPayload payload = new AnalyzeTaskPayload(result.task().id(), resumeId, 0);
        // 确保在事务提交后执行发送操作，避免数据不一致
        afterCommitExecutor.execute(() -> {
            //调用父类的sendTask方法发送任务到Redis Stream
            //返回消息ID，用于标记任务已入队
            //如果返回null，说明发送失败
            String messageId = sendTask(payload);
            if (messageId != null) {
                asyncTaskService.markEnqueued(result.task().id(), messageId);
            }
        });
    }

    @Override
    protected String taskDisplayName() {
        return "分析";
    }

    // 定义分析任务的 Redis Stream Key
    // 用于将分析任务消息发送到指定的 Redis Stream 中
    @Override
    protected String streamKey() {
        // 定义分析任务的 Redis Stream Key
        // 用于将分析任务消息发送到指定的 Redis Stream 中
        // 把这个任务发送到对应的stream中
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    // 构建分析任务消息体
    // 包含简历ID、内容和重试次数
    @Override
    protected Map<String, String> buildMessage(AnalyzeTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_TASK_ID, payload.taskId().toString(),
            AsyncTaskStreamConstants.FIELD_BUSINESS_ID, payload.resumeId().toString(),
            AsyncTaskStreamConstants.FIELD_RESUME_ID, payload.resumeId().toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(payload.retryCount())
        );
    }
    // 用于唯一标识分析任务，根据简历ID
    @Override
    protected String payloadIdentifier(AnalyzeTaskPayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    // 处理发送失败的情况
    // 更新简历的分析状态为 FAILED
    // 并截断错误信息（最多 500 个字符）
    @Override
    protected void onSendFailed(AnalyzeTaskPayload payload, String error) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, truncateError(error));
        asyncTaskService.markFailed(payload.taskId(), truncateError(error));
    }

    /**
     * 更新分析状态
     * 截断错误信息（最多 500 个字符）
     * @param resumeId 简历ID
     * @param status   分析状态
     * @param error    错误信息
     */
    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        resumeRepository.findById(resumeId).ifPresent(resume -> {
            resume.setAnalyzeStatus(status);
            if (error != null) {
                resume.setAnalyzeError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            resumeRepository.save(resume);
        });
    }

    // 构建分析任务负载的 JSON 字符串
    // 包含简历ID、内容长度（用于统计任务执行时间）
    // 用于在 Redis Stream 中记录任务的执行轨迹和状态信息
    //即将简历id和简历内容转换为json字符串
    private String payloadJson(Long resumeId, String content) {
        int contentLength = content == null ? 0 : content.length();
        return "{\"resumeId\":" + resumeId + ",\"contentLength\":" + contentLength + "}";
    }
}
