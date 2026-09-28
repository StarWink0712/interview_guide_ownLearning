/**
 * Redis Stream 消费者模板基类
 *
 * 提供异步任务消费的完整骨架：初始化消费者组、循环拉取消息、解析载荷、
 * 执行业务逻辑、ACK 确认、失败重试（最大 3 次）、状态标记。
 * 子类只需实现抽象方法即可完成特定业务消费者的开发。
 * 使用单线程 ThreadPoolExecutor + 守护线程运行消费循环。
 *
 * 联动关系：
 * - 依赖 RedisService 执行 Redis Stream 操作
 * - 依赖 AsyncTaskStreamConstants 获取批次大小、轮询间隔、最大重试次数等常量
 * - 被具体消费者继承：VectorizeStreamConsumer（知识库向量化）、
 *   AnalyzeStreamConsumer（简历分析）、EvaluateStreamConsumer（面试评估）、
 *   VoiceEvaluateStreamConsumer（语音面试评估）
 */
package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.observability.AsyncTaskMetricsRecorder;
import interview.guide.common.observability.TraceIdFilter;
import interview.guide.infrastructure.redis.RedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class AbstractStreamConsumer<T> {

    /** Redis 通用服务，用于执行 Stream 创建、消息拉取、ACK 确认等操作 */
    private final RedisService redisService;
    /** 消费者运行状态标志，false 时停止消费循环 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 单线程消费者线程池，保证消息顺序处理 */
    private ExecutorService executorService;
    /** 消费者名称（consumerPrefix + 8 位随机 UUID），用于 Redis Stream 消费者标识 */
    private String consumerName;
    //异步任务指标记录器
    private final AsyncTaskMetricsRecorder metricsRecorder;

    /**
     * 构造函数：注入 RedisService 依赖
     *
     * @param redisService Redis 通用服务依赖（由子类 super() 传入）
     */
    protected AbstractStreamConsumer(RedisService redisService) {
        this(redisService, null);
    }

    protected AbstractStreamConsumer(
        RedisService redisService,
        AsyncTaskMetricsRecorder metricsRecorder
    ) {
        this.redisService = redisService;
        this.metricsRecorder = metricsRecorder;
    }

    /**
     * 初始化并启动消费者。
     * 生成唯一 consumerName、创建单线程守护线程池、启动消费循环。
     * Spring 容器完成依赖注入后自动调用。
     * 即spring容器完成依赖注入又，会自动执行这个方法
     */
    @PostConstruct
    public void init() {
        // 生成唯一 consumerName，格式：consumerPrefix_8位随机UUID，固定前缀加八位uuid
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        // 创建单线程守护线程池，确保消息顺序处理
        // 线程名格式：taskDisplayName_consumerName
        this.executorService = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            //无界工作队列，容量无限
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, threadName());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );

        running.set(true);
        // 启动消费循环，拉取消息并处理任务,核心是startConsumer方法
        executorService.submit(this::startConsumer);
        log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName);
    }

    /**
     * 关闭消费者。
     * 设置运行标志为 false 停止消费循环，然后关闭线程池。
     * Spring 容器销毁 Bean 前自动调用。
     * spring容器销毁前通过pre注解启动关闭消费者线程池
     */
    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    /**
     * 启动消费流程：创建 Redis Stream 消费者组，然后进入消费循环。
     * 创建组可能因为组已存在而失败（日志警告后继续运行）。
     */
    private void startConsumer() {
        try {
            //createStreamGroup 创建 Redis Stream 消费者组，确保消息顺序处理
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("Redis Stream group is ready: {}", groupName());
        } catch (Exception e) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
        }

        // 进入消费循环，拉取消息并处理
        consumeLoop();
    }

    /**
     * 主消费循环：不断从 Redis Stream 拉取消息并处理。
     * 通过 running 标志控制循环退出，线程中断时优雅停止。
     */
    private void consumeLoop() {
        while (running.get()) {
            try {
                // 从 Redis Stream 拉取消息，处理消息
                redisService.streamConsumeMessages(
                    //streamKey Redis Stream 键，用于指定要消费的 Stream
                    //groupName Redis Stream 消费者组名称，用于指定要消费的 Stream 消息
                    //consumerName Redis Stream 消费者名称，用于指定要消费的 Stream 消息
                    //count 每次拉取的消息数量
                    //blockTimeoutMs 消息拉取超时时间，单位毫秒
                    streamKey(),
                    groupName(),
                    consumerName,
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    //调用processMessage方法处理消息，这里是传入这个处理器，用于后续的解析和处理
                    //点进到redisservice的streamConsumeMessages方法内， processor.process(entry.getKey(), entry.getValue());
                    //会调用这个代码处理，这里就是把这个处理器传入进去，而处理逻辑是在processMessage方法中
                    this::processMessage
                );
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Consumer thread interrupted");
                    break;
                }
                log.error("Failed to consume message", e);
            }
        }
    }

    /**
     * 处理单条 Redis Stream 消息（模板方法骨架）。
     * 流程：解析载荷 → 标记处理中 → 执行业务 → 标记完成 → ACK。
     * 异常时：未超最大重试次数则重试，否则标记失败。
     * data包含任务ID和简历ID，以及重试次数还有跟踪ID
     * @param messageId Redis Stream 消息 ID
     * @param data 消息键值对数据
     */
    /**
     * 处理单条 Redis Stream 消息的完整流程。
     * 每当从 Redis Stream 拉取到一条消息，就会调用这个方法。
     *
     * @param messageId Redis Stream 消息唯一 ID（如 "1639123456789-0"）
     * @param data      消息体 Map，包含任务ID、简历ID、重试次数等键值对
     */
    private void processMessage(StreamMessageId messageId, Map<String, String> data) {
        // === 第1步：备份当前 MDC 中的 TraceId ===
        // MDC 是 SLF4J 提供的线程本地 Map，日志输出时会自动拼接其中的键值对
        // 先取出当前线程 MDC 中已有的 TraceId，处理完消息后需要恢复它
        /**
         * 因为消费者线程是 线程池复用的 ，
         * 这条消息处理完，线程不销毁，继续处理下一条消息。
         * 下一条消息的 traceId 可能是 xyz-456-ghi 。
         * 如果不先清掉旧值，下一条的日志会串掉（要么带上上条的 traceId，要么残留污染）。
         * 所以 finally 里恢复成处理本条消息之前的原始状态。
         */
        String previousTraceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        // 从消息中取出生产者写入的 traceId（用于跨服务链路追踪）
        String traceId = data.get(AsyncTaskStreamConstants.FIELD_TRACE_ID);
        try {
            // 如果消息中带了 traceId，写入当前线程的 MDC
            // 这样后续所有 log.info/error 的日志都会自动带上这个 traceId
            if (traceId != null && !traceId.isBlank()) {
                MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, traceId);
            }

            // === 第2步：解析消息中的业务数据 ===
            // 根据消息体把 Map<String, String> 转成业务对象（比如 KnowledgeBaseTask）
            // 子类自己实现 parsePayload 来定制解析逻辑
            T payload = parsePayload(messageId, data);
            if (payload == null) {
                // 解析失败（数据格式有问题），ACK 确认后丢弃，不再重试
                ackMessage(messageId);
                return;
            }

            // === 第3步：获取这条消息的重试次数 ===
            // 生产者发送消息时会在消息体中写入 retryCount 字段
            int retryCount = parseRetryCount(data);
            // 打印日志：任务类型、业务标识、消息ID、第几次重试
            log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
                taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

            // === 第4步：执行业务处理流程 ===
            try {
                //这些都是要子类重写的
                // 4a 标记为"处理中"：更新数据库任务状态为 PROCESSING
                markProcessing(payload);
                // 4b 记录指标：处理中任务数 +1
                recordProcessing();
                // 4c 执行业务：调用 LLM / 向量化 / 文件解析等真正耗时操作
                processBusiness(payload);
                // 4d 标记为"已完成"：更新数据库状态为 COMPLETED
                markCompleted(payload);
                // 4e 记录指标：完成数 +1
                recordCompleted();
                // 4f 向 Redis Stream 确认消息已消费，Redis 从 pending 中移除
                ackMessage(messageId);
                // 4g 打印成功完成日志
                log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
            } catch (AsyncTaskCancelledException e) {
                // 任务被取消：比如关联的简历已被用户删除，没有处理的必要了
                log.warn("{} task cancelled: payload={}, reason={}",
                    taskDisplayName(), payloadIdentifier(payload), e.getMessage());
                // 标记为"已取消"：数据库状态更新为 CANCELLED
                markCancelled(payload, truncateError(e.getMessage()));
                // ACK 这条消息，不再重试
                ackMessage(messageId);
            } catch (Exception e) {
                // 业务执行出错：LLM 调用超时、网络异常、数据库写入失败等
                log.error("{} task failed: {}", taskDisplayName(), payloadIdentifier(payload), e);
                if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                    // 还没超过最大重试次数（默认 3 次），进行重试
                    recordRetry();
                    // 把任务重新发回 Redis Stream，retryCount + 1
                    retryMessage(payload, retryCount + 1);
                } else {
                    // 已经重试到上限了，放弃，标记为最终失败
                    recordFailed();
                    // 数据库状态更新为 FAILED，记录错误信息和重试次数
                    markFailed(payload, truncateError(
                        taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage()
                    ));
                }
                // 无论重试还是放弃，当前消息都要 ACK
                // 如果走重试路径，生产者会重新生产一条新消息去后续消费
                ackMessage(messageId);
            }
        } finally {
            // === 第5步：恢复 MDC 中原来的 TraceId ===
            // finally 保证不管成功还是失败，都会恢复线程 MDC 的原始状态
            // 这是为了不污染线程池中复用线程的 MDC 上下文
            if (previousTraceId == null) {
                // 之前没有 traceId，直接删掉我们临时写入的
                MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
            } else {
                // 之前有 traceId（比如消费者线程同时也在处理某类请求），恢复原值
                MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, previousTraceId);
            }
        }
    }

    /**
     * 从消息数据中解析重试次数，格式错误时返回 0
     *
     * @param data 消息键值对数据
     * @return 重试次数
     */
    protected int parseRetryCount(Map<String, String> data) {
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 截断过长的错误消息，防止存储超标
     *
     * @param error 原始错误消息
     * @return 截断后（最多 500 字符）的错误消息
     */
    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    /**
     * 对 Stream 消息执行 ACK 确认
     *
     * @param messageId 待确认的消息 ID
     */
    private void ackMessage(StreamMessageId messageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    /**
     * 恢复空闲超时的 pending 消息。
     *
     * @param idleTimeoutMs pending 空闲阈值
     * @param count 单次恢复数量
     * @return 本次恢复并处理的消息数
     */
    public int recoverPendingMessages(long idleTimeoutMs, int count) {
        Map<StreamMessageId, Map<String, String>> messages = redisService.streamAutoClaim(
            streamKey(),
            groupName(),
            consumerName,
            idleTimeoutMs,
            count
        );
        if (messages.isEmpty()) {
            return 0;
        }
        messages.forEach(this::processMessage);
        log.info("{} pending messages recovered: count={}, consumerName={}",
            taskDisplayName(), messages.size(), consumerName);
        return messages.size();
    }

    /**
     * 获取 RedisService 实例（供子类使用）
     *
     * @return RedisService 实例
     */
    protected RedisService redisService() {
        return redisService;
    }

    private void recordProcessing() {
        if (metricsRecorder != null) {
            metricsRecorder.recordProcessing(taskDisplayName(), streamKey());
        }
    }

    private void recordCompleted() {
        if (metricsRecorder != null) {
            metricsRecorder.recordCompleted(taskDisplayName(), streamKey());
        }
    }

    private void recordFailed() {
        if (metricsRecorder != null) {
            metricsRecorder.recordFailed(taskDisplayName(), streamKey());
        }
    }

    private void recordRetry() {
        if (metricsRecorder != null) {
            metricsRecorder.recordRetry(taskDisplayName(), streamKey());
        }
    }

    // ========== 子类必须实现的抽象方法 ==========

    /**
     * @return 任务显示名称（用于日志），如 "知识库向量化"
     */
    protected abstract String taskDisplayName();

    /**
     * @return Redis Stream Key，如 "knowledgebase:vectorize:stream"
     */
    protected abstract String streamKey();

    /**
     * @return Redis Stream 消费者组名称，如 "vectorize-group"
     */
    protected abstract String groupName();

    /**
     * @return 消费者名称前缀，如 "vectorize-consumer-"
     */
    protected abstract String consumerPrefix();

    /**
     * @return 消费者线程名称
     */
    protected abstract String threadName();

    /**
     * 将 Redis Stream 消息原始数据解析为业务载荷对象
     * 四个子类继承这个方法，分别解析不同的业务载荷对象
     * @param messageId 消息 ID
     * @param data 消息键值对
     * @return 解析后的载荷对象，格式错误返回 null（自动 ACK 跳过）
     */
    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    /**
     * 获取载荷的唯一标识，用于日志输出
     *
     * @param payload 业务载荷
     * @return 标识字符串，如 "resumeId=123"
     */
    protected abstract String payloadIdentifier(T payload);

    /**
     * 标记任务状态为"处理中"（更新数据库状态为 PROCESSING）
     *
     * @param payload 业务载荷
     */
    protected abstract void markProcessing(T payload);

    /**
     * 执行业务处理逻辑（调用 LLM、解析、存储等）
     *
     * @param payload 业务载荷
     */
    protected abstract void processBusiness(T payload);

    /**
     * 标记任务状态为"已完成"（更新数据库状态为 COMPLETED）
     *
     * @param payload 业务载荷
     */
    protected abstract void markCompleted(T payload);

    /**
     * 标记任务状态为"已取消"（例如业务实体已删除）
     *
     * @param payload 业务载荷
     * @param reason 取消原因
     */
    protected abstract void markCancelled(T payload, String reason);

    /**
     * 标记任务状态为"失败"（更新数据库状态为 FAILED 并记录错误信息）
     *
     * @param payload 业务载荷
     * @param error 错误描述
     */
    protected abstract void markFailed(T payload, String error);

    /**
     * 重试消息：将任务重新入队到 Redis Stream，重试次数加 1
     *
     * @param payload 业务载荷
     * @param retryCount 新的重试次数
     */
    protected abstract void retryMessage(T payload, int retryCount);
}
