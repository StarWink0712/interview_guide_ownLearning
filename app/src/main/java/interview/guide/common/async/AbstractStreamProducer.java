/**
 * Redis Stream 生产者模板基类
 *
 * 统一消息发送骨架与失败处理逻辑。子类只需实现抽象方法即可完成特定业务生产者的开发。
 * 发送失败时回调 onSendFailed 通知子类处理。
 *
 * 联动关系：
 * - 依赖 RedisService 执行 Redis Stream Add 操作
 * - 依赖 AsyncTaskStreamConstants 获取 Stream 最大长度常量
 * - 被具体生产者继承：VectorizeStreamProducer（知识库向量化）、
 *   AnalyzeStreamProducer（简历分析）、EvaluateStreamProducer（面试评估）、
 *   VoiceEvaluateStreamProducer（语音面试评估）
 */
package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.observability.AsyncTaskMetricsRecorder;
import interview.guide.common.observability.TraceIdFilter;
import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public abstract class AbstractStreamProducer<T> {

    /** Redis 通用服务，用于 Stream Add 操作 */
    private final RedisService redisService;
    private final AsyncTaskMetricsRecorder metricsRecorder;

    /**
     * 构造函数：注入 RedisService 依赖
     *
     * @param redisService Redis 通用服务依赖（由子类 super() 传入）
     */
    protected AbstractStreamProducer(RedisService redisService) {
        this(redisService, null);
    }

    protected AbstractStreamProducer(
        RedisService redisService,
        AsyncTaskMetricsRecorder metricsRecorder
    ) {
        this.redisService = redisService;
        this.metricsRecorder = metricsRecorder;
    }

    /**
     * 发送任务到 Redis Stream。
     * 构建消息体 → Stream Add → 成功日志；异常时回调 onSendFailed。
     * 内部把payload转换为map格式，key是字段名，value是字段值，方便后续的解析
     * @param payload 业务载荷
     */
    protected String sendTask(T payload) {
        try {
            //
            String messageId = redisService.streamAdd(
                //streamKey 是 Redis Stream 的键名，与业务类型相关的常量
                streamKey(),
                //message 是要发送到 Redis Stream 的消息体，包含任务负载和跟踪ID
                //这里吧payload转换为map格式，key是字段名，value是字段值，方便后续的解析
                buildMessageWithTrace(payload),
                //maxLen 是 Redis Stream 最大长度，超过后会删除旧消息
                // 这里设置为默认值，根据业务需求调整
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            // 记录任务创建指标
            recordCreated();
            log.info("{}任务已发送到Stream: {}, messageId={}",
                taskDisplayName(), payloadIdentifier(payload), messageId);
            return messageId;
        } catch (Exception e) {
            log.error("发送{}任务失败: {}, error={}",
                taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
            recordFailed();
            //发送失败时回调子类处理
            onSendFailed(payload, "任务入队失败: " + e.getMessage());
            return null;
        }
    }

    // 构建包含跟踪ID的消息体，payload包含任务id和简历id还有重试次数
    // 这里是为了在 Redis Stream 中记录任务的执行轨迹，方便后续的监控和调试
    private Map<String, String> buildMessageWithTrace(T payload) {
        Map<String, String> message = new LinkedHashMap<>(buildMessage(payload));
        // 从当前线程上下文获取跟踪ID，用于关联任务执行轨迹
        TraceIdFilter.currentTraceId()
            .ifPresent(traceId -> message.put(AsyncTaskStreamConstants.FIELD_TRACE_ID, traceId));
        return message;
    }

    private void recordCreated() {
        if (metricsRecorder != null) {
            metricsRecorder.recordCreated(taskDisplayName(), streamKey());
        }
    }

    private void recordFailed() {
        if (metricsRecorder != null) {
            metricsRecorder.recordFailed(taskDisplayName(), streamKey());
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
     * @return 任务显示名称（用于日志），如 "知识库向量化"
     */
    protected abstract String taskDisplayName();

    /**
     * @return Redis Stream Key，如 "knowledgebase:vectorize:stream"
     */
    protected abstract String streamKey();

    /**
     * 构建发送到 Redis Stream 的消息体
     *
     * @param payload 业务载荷
     * @return 消息键值对 Map
     */
    protected abstract Map<String, String> buildMessage(T payload);

    /**
     * 获取载荷的唯一标识，用于日志输出
     *
     * @param payload 业务载荷
     * @return 标识字符串，如 "resumeId=123"
     */
    protected abstract String payloadIdentifier(T payload);

    /**
     * 发送失败回调（更新数据库状态为 FAILED 等）
     *
     * @param payload 业务载荷
     * @param error 错误描述
     */
    protected abstract void onSendFailed(T payload, String error);
}
