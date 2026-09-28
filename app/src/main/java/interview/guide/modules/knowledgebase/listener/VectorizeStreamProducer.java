/**
 * 知识库向量化 Redis Stream 生产者
 *
 * 负责将向量化任务消息发送到 Redis Stream，由 VectorizeStreamConsumer 异步消费处理。
 * 采用事务后提交（Transaction-After-Commit）模式：先保存知识库到数据库，事务提交后
 * 才将消息 XADD 到 Stream，确保"消息入队"与"数据库写入"的一致性。
 *
 * 完整的消息生产流程：
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │ KnowledgeBaseUploadService 保存知识库到数据库（事务中）                  │
 * │   → 事务提交成功后                                                     │
 * │   → VectorizeStreamProducer.sendVectorizeTask(kbId, content)          │
 * │       ├── 1. AsyncTaskService.createOrReusePendingTask()              │
 * │       │      在 async_tasks 表创建/复用一条 PENDING 任务记录            │
 * │       │      幂等：相同 kbId 的 PENDING 任务不重复创建                  │
 * │       ├── 2. afterCommitExecutor.execute()                            │
 * │       │      等待当前事务提交后异步执行                                 │
 * │       │      构建 VectorizeTaskPayload(taskId, kbId, retryCount=0)    │
 * │       ├── 3. 父类 sendTask(payload)                                   │
 * │       │      XADD 消息到 Redis Stream                                 │
 * │       └── 4. asyncTaskService.markEnqueued(taskId, messageId)         │
 * │              更新任务表状态为 ENQUEUED，记录 Stream messageId           │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * 发送失败处理：
 *   → onSendFailed() 回调
 *       ├── knowledgeBaseRepository → 更新 vectorStatus=FAILED
 *       └── asyncTaskService.markFailed() → 更新任务表状态
 *
 * 联动关系：
 * - 继承 AbstractStreamProducer<VectorizeTaskPayload>，复用 Redis Stream 生产者模板：
 *     消息序列化、XADD 发送、失败回调、指标记录由父类统一处理
 * - 被 KnowledgeBaseUploadService.uploadKnowledgeBase() 调用（上传后首次向量化）
 * - 被 KnowledgeBaseUploadService.revectorize() 调用（手动重新向量化）
 * - 被 VectorizeStreamConsumer.retryMessage() 间接关联（消费者重试时也通过相似逻辑 XADD）
 * - 依赖 AsyncTaskService 创建和管理异步任务表记录
 * - 依赖 TransactionAfterCommitExecutor 保证事务后发送（数据一致性）
 * - 依赖 KnowledgeBaseRepository 在发送失败时更新向量化状态
 * - 通过 AsyncTaskStreamConstants 获取 Stream Key / 字段名常量
 *
 * @see VectorizeStreamConsumer 消息消费者
 * @see KnowledgeBaseUploadService 调用方
 * @see AbstractStreamProducer 父类生产模板
 */
package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.observability.AsyncTaskMetricsRecorder;
import interview.guide.common.transaction.TransactionAfterCommitExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.model.AsyncTaskEnqueueResult;
import interview.guide.modules.asynctask.model.AsyncTaskType;
import interview.guide.modules.asynctask.service.AsyncTaskService;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 向量化任务生产者
 * <p>
 * 作为 Redis Stream 的生产端，将向量化任务编组成消息发送到 Stream，
 * 消费者（VectorizeStreamConsumer）从 Stream 拉取消息后异步处理。
 * <p>
 * 设计要点：
 * - 事务后提交：先写数据库（事务中），事务提交后才 XADD 到 Stream，避免消息被消费时数据未落库
 * - 幂等入队：同一 kbId 的 PENDING 任务不重复创建，防止重复向量化
 * - 失败兜底：XADD 失败时自动更新知识库状态为 FAILED，并记录到异步任务表
 * - 消息体轻量：不传文本内容（数万字符），只传 taskId/kbId/retryCount 三个整型字段
 *    消费者从 RustFS 下载原始文件解析，避免 Redis 内存和网络带宽膨胀
 */
@Slf4j
@Component
public class VectorizeStreamProducer extends AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload> {

    /**
     * 知识库 Repository —— 数据库访问层
     * 用于：XADD 发送失败时，回调更新知识库实体的向量化状态为 FAILED
     */
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 异步任务服务 —— 通用任务表管理
     * 用于：创建异步任务记录（PENDING 状态），XADD 成功后标记 ENQUEUED，
     *      XADD 失败时标记 FAILED。前端可以通过任务 ID 查询向量化进度
     */
    private final AsyncTaskService asyncTaskService;

    /**
     * 事务后提交执行器 —— 保证数据一致性
     * 关键：必须在数据库事务提交确认落库后，才将消息发送到 Stream。
     * 否则可能出现"消费者取出消息开始处理，但数据库里知识库记录还没保存"的竞态条件。
     * execute() 方法的 Runnable 会在当前事务成功提交后异步执行。
     */
    private final TransactionAfterCommitExecutor afterCommitExecutor;

    /**
     * 向量化任务的消息体结构（存储到 Redis Stream 中）
     * <p>
     * 注意：消息体不包含文档文本内容！文本可能数万字符，
     * 存入 Redis 会严重膨胀内存。消费者从 RustFS 下载原始文件重新解析。
     *
     * @param taskId     异步任务 ID（async_tasks 表主键，用于状态追踪和前端轮询）
     * @param kbId       知识库 ID（knowledge_bases 表主键，消费者根据此 ID 查实体+下载文件）
     * @param retryCount 重试次数（首次入队为 0，消费者重试时递增，最多 3 次）
     */
    record VectorizeTaskPayload(Long taskId, Long kbId, int retryCount) {}

    /**
     * 构造器注入所有依赖
     *
     * @param redisService            Redis 客户端（父类 AbstractStreamProducer 用于 XADD）
     * @param knowledgeBaseRepository 知识库仓库（发送失败时更新状态）
     * @param asyncTaskService        异步任务服务（创建/更新任务表记录）
     * @param afterCommitExecutor     事务后提交执行器（保证 DB 写完后才发消息）
     * @param metricsRecorder         指标记录器（父类使用，记录生产耗时等监控数据）
     */
    public VectorizeStreamProducer(
            RedisService redisService,
            KnowledgeBaseRepository knowledgeBaseRepository,
            AsyncTaskService asyncTaskService,
            TransactionAfterCommitExecutor afterCommitExecutor,
            AsyncTaskMetricsRecorder metricsRecorder) {
        super(redisService, metricsRecorder);
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.asyncTaskService = asyncTaskService;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    /**
     * 发送向量化任务到 Redis Stream
     * <p>
     * 这是外部调用入口，被 KnowledgeBaseUploadService 在上传/重向量化时调用。
     * 整体流程分为两步（事务内 + 事务后）：
     *
     * <pre>
     * 事务内（同步）：
     *   1. AsyncTaskService.createOrReusePendingTask()
     *      → 在 async_tasks 表插入一条 type=KB_VECTORIZE, status=PENDING 的记录
     *      → 幂等：如果该 kbId 已有 PENDING 任务，复用已有记录（避免重复）
     *
     * 事务后（异步，通过 TransactionAfterCommitExecutor）：
     *   2. 构建 VectorizeTaskPayload(taskId, kbId, retryCount=0)
     *   3. sendTask(payload) → 父类 XADD 到 Redis Stream
     *   4. asyncTaskService.markEnqueued() → 更新任务表为 ENQUEUED
     * </pre>
     *
     * 为什么分两步？
     * - 数据库事务可能回滚，如果此时消息已经入队，消费者取出处理时会发现关联数据不存在
     * - TransactionAfterCommitExecutor 保证"数据库落库 → 消息入队"的顺序，避免竞态
     *
     * @param kbId    知识库 ID（knowledge_bases 表主键）
     * @param content 文档文本内容（仅用于 payloadJson 日志，不存入消息体）
     */
    public void sendVectorizeTask(Long kbId, String content) {
        // 步骤 1：在数据库中创建异步任务记录（PENDING 状态）
        // createOrReusePendingTask 的幂等逻辑：
        //   查询该 kbId 是否有 status=PENDING 的 KB_VECTORIZE 类型任务
        //   有 → 复用，返回 created=false（避免重复创建）
        //   无 → 新建，返回 created=true
        AsyncTaskEnqueueResult result = asyncTaskService.createOrReusePendingTask(
            AsyncTaskType.KB_VECTORIZE,         // 任务类型：知识库向量化
            kbId.toString(),                     // 业务标识（用于幂等去重）
            streamKey(),                         // Stream Key（用于运维追溯）
            payloadJson(kbId, content)           // 负载摘要 JSON（不含实际文本，只有长度）
        );

        // 步骤 1b：幂等检查 —— PENDING 任务已存在则跳过入队
        // 场景：前端短时间内重复点击"上传"，同一个 kbId 的第一个任务还在排队
        if (!result.created()) {
            log.info("知识库向量化任务已存在，跳过重复入队: kbId={}, taskId={}",
                kbId, result.task().id());
            return;
        }

        // 步骤 2：构建消息体（轻量级，只含三个整型字段）
        VectorizeTaskPayload payload = new VectorizeTaskPayload(
            result.task().id(),   // 异步任务 ID
            kbId,                  // 知识库 ID
            0                      // 首次入队，重试次数为 0
        );

        // 步骤 3：事务提交后异步发送到 Redis Stream
        // afterCommitExecutor.execute() 会在当前事务成功提交后执行 Runnable
        // 如果事务回滚，此回调不会执行，保证不会产生"消息在但数据不在"的孤儿消息
        afterCommitExecutor.execute(() -> {
            // 步骤 3a：调用父类 sendTask() → buildMessage() → XADD
            // 如果 XADD 成功 → 步骤 3b
            // 如果 XADD 失败 → 父类回调 onSendFailed()
            String messageId = sendTask(payload);
            if (messageId != null) {
                // 步骤 3b：标记任务已入队（更新 async_tasks 表）
                // 记录 Stream 的 messageId，用于运维排查和状态追踪
                asyncTaskService.markEnqueued(result.task().id(), messageId);
            }
        });
    }

    // ======================== 模板方法实现 ========================

    /**
     * 返回任务展示名称（用于父类日志输出）
     * <p>
     * 父类的日志模板会使用这个值，例如：
     * "向量化消息发送成功: kbId=xxx" / "向量化消息发送失败: kbId=xxx"
     */
    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    /**
     * 返回 Redis Stream 的 Key
     * <p>
     * 生产者和消费者必须使用同一个 Stream Key 才能配对通信。
     * 实际值来自 AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY。
     */
    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    /**
     * 将任务对象构建为 Redis Stream 消息 Map
     * <p>
     * Redis Stream 消息体是 Map<String, String>，消费者通过 field=value 解析。
     * 这里只传递四个轻量字段（不含文本内容）：
     *
     * @param payload 任务消息体
     * @return Redis Stream 消息 Map，包含：
     *         taskId     - 异步任务 ID（用于状态追踪）
     *         businessId - 业务标识（与 taskId 用途重叠，保留兼容）
     *         kbId       - 知识库 ID（消费者据此查实体+下载文件）
     *         retryCount - 重试次数（首次为 0，消费者重试时递增）
     */
    @Override
    protected Map<String, String> buildMessage(VectorizeTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_TASK_ID, payload.taskId().toString(),
            AsyncTaskStreamConstants.FIELD_BUSINESS_ID, payload.kbId().toString(),
            AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(payload.retryCount())
        );
    }

    /**
     * 返回消息的可读标识（用于日志）
     */
    @Override
    protected String payloadIdentifier(VectorizeTaskPayload payload) {
        return "kbId=" + payload.kbId();
    }

    /**
     * 消息发送失败回调
     * <p>
     * 当父类 sendTask() 执行 XADD 失败时，会自动调用此方法。
     * 做两件事：
     * 1. 更新知识库实体的向量化状态为 FAILED（前端可看到失败提示）
     * 2. 更新异步任务表状态为 FAILED（前端轮询可获取失败原因）
     *
     * 注意：error 参数已被父类截断（truncateError），不需要再次截断。
     *
     * @param payload 发送失败的任务消息体
     * @param error   父类捕获的错误信息（已截断，不超过数据库字段长度限制）
     */
    @Override
    protected void onSendFailed(VectorizeTaskPayload payload, String error) {
        // 更新知识库实体：状态 → FAILED，错误信息 → 截断到 500 字符
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, truncateError(error));
        // 更新异步任务表：状态 → FAILED
        asyncTaskService.markFailed(payload.taskId(), truncateError(error));
    }

    // ======================== 私有工具方法 ========================

    /**
     * 更新知识库实体的向量化状态和错误信息
     * <p>
     * 通过 findById + save 更新状态（而非 JPQL UPDATE），
     * 确保 JPA @PreUpdate 生命周期回调正常触发。
     * 实体不存在时静默跳过（ifPresent）。
     *
     * @param kbId   知识库 ID
     * @param status 目标向量化状态（FAILED 或 PROCESSING 等）
     * @param error  错误信息（超出 500 字符会被截断，调用了父类的 truncateError）
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
            kb.setVectorStatus(status);       // 更新状态枚举
            if (error != null) {
                // 截断错误信息到 500 字符以内，匹配数据库字段长度
                kb.setVectorError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            knowledgeBaseRepository.save(kb);  // JPA save 触发 @PreUpdate
        });
    }

    /**
     * 构建用于运维日志的负载摘要 JSON 字符串
     * <p>
     * 注意：这里只记录 kbId 和文本长度，不记录实际文本内容！
     * 原因：文本可能数万字符，写入日志会造成日志爆炸和磁盘浪费。
     *
     * @param kbId    知识库 ID
     * @param content 文档文本内容（仅取其长度）
     * @return 形如 {"kbId":123,"contentLength":50000} 的 JSON 字符串
     */
    private String payloadJson(Long kbId, String content) {
        int contentLength = content == null ? 0 : content.length();
        return "{\"kbId\":" + kbId + ",\"contentLength\":" + contentLength + "}";
    }
}
