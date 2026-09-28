/**
 * 知识库向量化 Redis Stream 消费者
 *
 * 从 Redis Stream 消费向量化任务消息，解析出知识库 ID 后，
 * 委托 KnowledgeBaseVectorService 执行文本分块、Embedding 向量化与 pgvector 存储。
 *
 * 完整的消费流程与状态流转：
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ Redis Stream 消息到达                                                │
 * │   → parsePayload() 解析 taskId + kbId                               │
 * │   → markProcessing() 标记 PROCESSING                                │
 * │   → processBusiness() 执行业务逻辑                                   │
 * │       ├── 1. 校验知识库实体是否存在（已删除则取消任务）                  │
 * │       ├── 2. 幂等检查：已完成且 chunkCount>0 则跳过不重复处理            │
 * │       ├── 3. 从 RustFS 下载原始文件并重新解析文本                      │
 * │       └── 4. 委托 KnowledgeBaseVectorService.vectorizeAndStore()     │
 * │   → markCompleted() 标记 COMPLETED                                  │
 * │                                                                     │
 * │ 异常路径：                                                           │
 * │   AsyncTaskCancelledException → markCancelled()（知识库被删等不可恢复） │
 * │   其他 Exception                → retryMessage() 重试（最多 3 次）     │
 * │   超过最大重试次数               → markFailed() 标记 FAILED           │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * 联动关系：
 * - 继承 AbstractStreamConsumer<VectorizePayload>，复用 Redis Stream 消费模板：
 *     消息 ACK/重试/线程管理/并发控制由父类统一处理
 * - 委托 KnowledgeBaseVectorService.vectorizeAndStore() 执行核心向量化逻辑
 * - 依赖 KnowledgeBaseRepository 校验实体存在性 + 更新向量化状态
 * - 依赖 KnowledgeBaseParseService.downloadAndParseContent() 从 RustFS 下载并解析文本
 * - 依赖 AsyncTaskService 更新异步任务表的状态（PROCESSING/COMPLETED/FAILED/CANCELLED）
 * - 与 VectorizeStreamProducer 配对使用（生产者发送消息到 Stream，消费者取出处理）
 * - 通过 AsyncTaskStreamConstants 获取 Stream Key / Group / Consumer前缀等配置
 *
 * @see VectorizeStreamProducer 消息生产者
 * @see KnowledgeBaseVectorService 向量化核心逻辑
 * @see AbstractStreamConsumer 父类消费模板
 */
package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.async.AsyncTaskCancelledException;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.observability.AsyncTaskMetricsRecorder;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.service.AsyncTaskService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseParseService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库向量化 Stream 消费者
 * <p>
 * 作为 Redis Stream 的消费端，从消息队列中拉取向量化任务，
 * 逐条处理：将知识库文档文本分块 → 调用 Embedding API 向量化 → 存入 pgvector。
 * <p>
 * 设计要点：
 * - 消费者在独立线程中运行，与 HTTP 请求线程完全解耦
 * - 消息处理前校验知识库实体是否存在（处理期间可能被前端删除）
 * - 向量化幂等：已 COMPLETED 且 chunkCount>0 的知识库直接跳过
 * - 重试机制：失败自动重试最多 3 次，超过后标记 FAILED
 * - 文本内容不存消息体（太大），改为消费者从 RustFS 下载后重新解析
 */
@Slf4j
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload> {

    /**
     * 知识库向量化服务 —— 核心业务 Bean
     * 负责：TokenTextSplitter 文本分块 → DashScope Embedding API 向量化 → pgvector 批量写入
     */
    private final KnowledgeBaseVectorService vectorService;

    /**
     * 知识库 Repository —— 数据库访问层
     * 用于：校验知识库实体是否存在 + 更新向量化状态（PENDING/PROCESSING/COMPLETED/FAILED）
     */
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 知识库解析服务 —— 文件文本提取
     * 用于：从 RustFS 下载原始文件（PDF/DOCX 等），通过 Apache Tika 重新提取纯文本
     * 为什么不在消息体中传文本？消息体过大（可能数万字符）会影响 Redis Stream 性能，
     * 所以消息只传 kbId，消费者从对象存储下载原始文件再解析
     */
    private final KnowledgeBaseParseService parseService;

    /**
     * 异步任务服务 —— 通用任务表状态管理
     * 用于：在 async_tasks 表中记录每个向量化任务的状态流转（PENDING→PROCESSING→COMPLETED/FAILED）
     * 前端可以通过任务 ID 查询执行进度
     */
    private final AsyncTaskService asyncTaskService;

    /**
     * 构造器注入所有依赖
     *
     * @param redisService       Redis 客户端（父类 AbstractStreamConsumer 使用，操作 Stream）
     * @param vectorService      向量化核心服务
     * @param knowledgeBaseRepository 知识库仓库
     * @param parseService       文件解析服务（Tika）
     * @param asyncTaskService   异步任务状态管理
     * @param metricsRecorder    指标记录器（父类使用，记录任务耗时/成功率等监控数据）
     */
    public VectorizeStreamConsumer(
        RedisService redisService,
        KnowledgeBaseVectorService vectorService,
        KnowledgeBaseRepository knowledgeBaseRepository,
        KnowledgeBaseParseService parseService,
        AsyncTaskService asyncTaskService,
        AsyncTaskMetricsRecorder metricsRecorder
    ) {
        super(redisService, metricsRecorder);
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.parseService = parseService;
        this.asyncTaskService = asyncTaskService;
    }

    /**
     * 向量化任务的消息体结构
     *
     * @param taskId 异步任务 ID（async_tasks 表主键，用于状态追踪）
     * @param kbId   知识库 ID（knowledge_bases 表主键，标识要对哪个知识库做向量化）
     */
    record VectorizePayload(Long taskId, Long kbId) {}

    // ======================== 模板方法实现 ========================

    /**
     * 返回任务展示名称（用于日志输出）
     * <p>
     * 父类的日志模板会使用这个值：
     * e.g. "向量化消息处理异常: taskId=xxx, kbId=xxx"
     */
    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    /**
     * 返回 Redis Stream 的 Key
     * <p>
     * 实际值来自 AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY，
     * 生产者和消费者通过同一个 Stream Key 配对通信
     */
    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    /**
     * 返回消费者组名称
     * <p>
     * 同一个 Group 内的消费者共享消息，每条消息只会被组内一个消费者处理，
     * 实现负载均衡（多实例部署时自动分摊消息）
     */
    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME;
    }

    /**
     * 返回消费者实例名称前缀
     * <p>
     * 父类会在前缀后拼接 UUID 生成唯一消费者名，
     * Redis Stream 用消费者名追踪 pending 消息（未确认的消息）
     */
    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX;
    }

    /**
     * 返回消费线程的名称
     * <p>
     * 消费者在一个独立的后台线程中运行，便于通过 jstack 等工具辨识
     */
    @Override
    protected String threadName() {
        return "vectorize-consumer";
    }

    /**
     * 解析 Redis Stream 消息为结构化对象
     * <p>
     * Redis Stream 消息体是 Map<String, String>（由 VectorizeStreamProducer.buildMessage() 构建），
     * 包含字段：taskId、businessId、kbId、retryCount。
     * 这里只提取 taskId 和 kbId 两个关键字段。
     *
     * @param messageId Redis Stream 消息 ID（时间戳-序号格式，如 1620000000000-0）
     * @param data      消息体 Map
     * @return 解析后的 VectorizePayload，格式错误时返回 null（父类会直接 ACK 丢弃）
     */
    @Override
    protected VectorizePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        // 从 Redis Stream 消息中提取两个关键字段
        String taskIdStr = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
        String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        // 字段缺失的消息视为脏数据，直接丢弃不重试
        if (taskIdStr == null || kbIdStr == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new VectorizePayload(Long.parseLong(taskIdStr), Long.parseLong(kbIdStr));
    }

    /**
     * 返回消息的可读标识（用于日志）
     */
    @Override
    protected String payloadIdentifier(VectorizePayload payload) {
        return "taskId=" + payload.taskId() + ", kbId=" + payload.kbId();
    }

    // ======================== 状态流转方法 ========================

    /**
     * 标记任务开始处理（状态：PENDING → PROCESSING）
     * <p>
     * 在执行业务逻辑之前调用，让前端可以通过轮询得知"系统正在向量化这个知识库"
     *
     * @param payload 任务消息体
     */
    @Override
    protected void markProcessing(VectorizePayload payload) {
        // 更新异步任务表状态
        asyncTaskService.markProcessing(payload.taskId());
        // 更新知识库实体的向量化状态字段
        updateVectorStatus(payload.kbId(), VectorStatus.PROCESSING, null);
    }

    /**
     * 核心业务处理方法 —— 执行向量化操作
     * <p>
     * 这是整个消费者的核心，完整流程：
     * <ol>
     *   <li>查询知识库实体，校验是否仍存在（可能已被前端删除）</li>
     *   <li>幂等检查：如果状态已是 COMPLETED 且 chunkCount>0，说明已有历史向量数据，跳过</li>
     *   <li>从 RustFS 下载原始文件，通过 Tika 重新解析为纯文本</li>
     *   <li>委托 KnowledgeBaseVectorService.vectorizeAndStore() 执行分块+向量化+存储</li>
     * </ol>
     *
     * 异常策略：
     * <ul>
     *   <li>知识库已被删除 → 抛 AsyncTaskCancelledException → 父类调 markCancelled()</li>
     *   <li>无法获取文本内容 → 抛 AsyncTaskCancelledException</li>
     *   <li>向量化过程异常 → 父类捕获后调 retryMessage() 重试</li>
     * </ul>
     *
     * @param payload 任务消息体（包含 taskId 和 kbId）
     * @throws AsyncTaskCancelledException 知识库已删除或无法获取文本时取消任务
     */
    @Override
    protected void processBusiness(VectorizePayload payload) {
        // 步骤 1：从数据库查询知识库实体
        // 为什么不在 markProcessing 中查一次然后用？因为可能已经被前端删除
        // 这里实时查询确保数据一致性
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseRepository.findById(payload.kbId())
            .orElseThrow(() -> new AsyncTaskCancelledException("知识库已被删除: kbId=" + payload.kbId()));

        // 步骤 2：幂等检查 —— 防止消息重复消费导致重复调用 Embedding API
        // 条件：状态已是 COMPLETED 且 chunkCount > 0（确认确实有向量数据写入）
        // 为什么检查 chunkCount？极端情况：进程崩溃时状态可能是 COMPLETED 但实际未完成
        if (knowledgeBase.getVectorStatus() == VectorStatus.COMPLETED
                && knowledgeBase.getChunkCount() != null
                && knowledgeBase.getChunkCount() > 0) {
            log.info("知识库向量已存在，跳过重复处理: kbId={}", payload.kbId());
            return;  // 直接返回，父类会调 markCompleted()
        }

        // 步骤 3：从 RustFS 下载原始文件并重新解析文本
        // 消息体只传 kbId 不传文本（文本可能数万字符，会膨胀 Redis 内存和网络传输）
        // 消费者从对象存储下载原始文件（PDF/DOCX），用 Apache Tika 提取纯文本
        String content = parseService.downloadAndParseContent(
            knowledgeBase.getStorageKey(),      // RustFS 的 key，如 "knowledgebase/abc-123.pdf"
            knowledgeBase.getOriginalFilename()  // 原始文件名，用于 Tika 检测文件类型
        );

        // 步骤 4：前置校验 —— 文本为空或纯空白时任务无法继续
        if (content == null || content.isBlank()) {
            throw new AsyncTaskCancelledException("无法获取知识库文本内容: kbId=" + payload.kbId());
        }

        // 步骤 5：执行向量化（实际调用链 ↓）
        // vectorService.vectorizeAndStore()
        //   1. deleteByKnowledgeBaseId(kbId) —— 先删旧向量
        //   2. TokenTextSplitter.apply(content) —— 文本分块（~800 tokens/块）
        //   3. chunks.forEach(c -> c.getMetadata().put("kb_id", kbId.toString())) —— 标记 kb_id
        //   4. 分批调用 vectorStore.add(batch) —— Embedding API 向量化 + pgvector 写入
        //      （阿里云 DashScope 限制每批最多 10 个 chunk）
        vectorService.vectorizeAndStore(payload.kbId(), content);
    }

    /**
     * 标记任务成功完成（状态：PROCESSING → COMPLETED）
     * <p>
     * processBusiness() 正常返回后由父类调用此方法
     *
     * @param payload 任务消息体
     */
    @Override
    protected void markCompleted(VectorizePayload payload) {
        // 更新异步任务表状态为 COMPLETED
        asyncTaskService.markCompleted(payload.taskId());
        // 更新知识库实体的向量化状态字段（前端轮询时可见）
        updateVectorStatus(payload.kbId(), VectorStatus.COMPLETED, null);
    }

    /**
     * 标记任务已取消（状态：PROCESSING → CANCELLED）
     * <p>
     * 当 processBusiness() 抛出 AsyncTaskCancelledException 时，父类调用此方法。
     * 取消场景：知识库已被删除、无法获取文本内容等不可恢复的错误。
     * 注意：取消的任务不会重试。
     *
     * @param payload 任务消息体
     * @param reason  取消原因（来自 AsyncTaskCancelledException 的 message）
     */
    @Override
    protected void markCancelled(VectorizePayload payload, String reason) {
        asyncTaskService.markCancelled(payload.taskId(), reason);
    }

    /**
     * 标记任务失败（超过最大重试次数后，状态 → FAILED）
     * <p>
     * 当 processBusiness() 抛出非 AsyncTaskCancelledException 的异常，
     * 且重试次数已达到上限（默认 3 次）时，父类调用此方法。
     * 失败的知识库需要用户手动点击"重新向量化"重新入队。
     *
     * @param payload 任务消息体
     * @param error   错误信息
     */
    @Override
    protected void markFailed(VectorizePayload payload, String error) {
        // 标记异步任务表中为死信（DEAD_LETTER）
        asyncTaskService.markDeadLetter(payload.taskId(), error);
        // 更新知识库实体状态为 FAILED，让前端看到失败原因
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, error);
    }

    /**
     * 重试消息 —— 失败后将任务重新入队到 Redis Stream
     * <p>
     * 当 processBusiness() 抛出可重试异常时，父类会在递增 retryCount 后调用此方法。
     * 将新的消息 XADD 到 Stream 尾部，消费者会再次取出处理。
     * 最多重试 3 次（父类 AbstractStreamConsumer 控制上限）。
     *
     * 工作流程：
     * 1. 构建新的 Stream 消息 Map（taskId、kbId、businessId、retryCount）
     * 2. XADD 到 Stream，获取新的 messageId
     * 3. 更新异步任务表状态为 RETRYING，记录新的 messageId
     * 4. 当前消息（旧 messageId）由父类 ACK 确认消费
     *
     * @param payload    原任务消息体
     * @param retryCount 当前重试次数（父类已递增，例如第 1 次重试时值为 1）
     */
    @Override
    protected void retryMessage(VectorizePayload payload, int retryCount) {
        Long kbId = payload.kbId();
        try {
            // 构建新的 Stream 消息（字段与 VectorizeStreamProducer.buildMessage() 一致）
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_TASK_ID, payload.taskId().toString(),
                AsyncTaskStreamConstants.FIELD_BUSINESS_ID, kbId.toString(),
                AsyncTaskStreamConstants.FIELD_KB_ID, kbId.toString(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            // XADD 到 Stream 尾部（使用父类 RedisService 的 streamAdd 方法）
            String messageId = redisService().streamAdd(
                AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN  // Stream 最大长度限制，防内存溢出
            );

            // 更新异步任务表的状态和 messageId（前端可以跟踪最新任务位置）
            asyncTaskService.markRetrying(payload.taskId(), retryCount, messageId);
            log.info("向量化任务已重新入队: kbId={}, retryCount={}", kbId, retryCount);

        } catch (Exception e) {
            // 极端情况：重试入队本身失败（如 Redis 连接断开）
            // 此时无法重试，只能标记失败
            log.error("重试入队失败: kbId={}, error={}", kbId, e.getMessage(), e);
            updateVectorStatus(kbId, VectorStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
            asyncTaskService.markDeadLetter(
                payload.taskId(),
                truncateError("重试入队失败: " + e.getMessage())
            );
        }
    }

    // ======================== 私有工具方法 ========================

    /**
     * 更新知识库实体的向量化状态和错误信息
     * <p>
     * 统一的状态更新入口，被 markProcessing、markCompleted、markFailed、retryMessage 等调用。
     * 使用 findById + save 而非 JPQL UPDATE，确保 @PreUpdate 等 JPA 生命周期回调正常工作。
     * <p>
     * 异常处理：更新状态本身失败时不抛异常（避免覆盖业务异常的堆栈），只记录错误日志。
     * 因为向量化状态是辅助信息，核心业务流程不依赖它。
     *
     * @param kbId   知识库 ID
     * @param status 目标向量化状态（PROCESSING/COMPLETED/FAILED）
     * @param error  错误信息（COMPLETED 时为 null，FAILED 时为具体错误描述，超出 500 字符会被截断）
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        try {
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setVectorStatus(status);       // 更新状态枚举
                kb.setVectorError(error);          // 更新错误信息（成功时为 null）
                knowledgeBaseRepository.save(kb);  // JPA save 触发 @PreUpdate
                log.debug("向量化状态已更新: kbId={}, status={}", kbId, status);
            });
        } catch (Exception e) {
            // 状态更新失败不应影响主流程，只记录日志
            log.error("更新向量化状态失败: kbId={}, status={}, error={}", kbId, status, e.getMessage(), e);
        }
    }

}
