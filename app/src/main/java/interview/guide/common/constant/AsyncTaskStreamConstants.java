/**
 * 异步任务 Redis Stream 通用常量
 *
 * 定义知识库向量化、简历分析、面试评估、语音面试评估四个异步管道的
 * Stream Key、Consumer Group、Consumer 前缀、消息字段名等常量，
 * 以及通用的消费者配置（最大重试次数、批次大小、轮询间隔、Stream 最大长度）。
 *
 * 联动关系：
 * - 被 AbstractStreamConsumer / AbstractStreamProducer 引用通用常量
 * - 被各具体 Producer/Consumer 引用各自管道的 Stream Key 和 Group Name：
 *   VectorizeStreamProducer/Consumer、AnalyzeStreamProducer/Consumer、
 *   EvaluateStreamProducer/Consumer、VoiceEvaluateStreamProducer/Consumer
 */
package interview.guide.common.constant;

public final class AsyncTaskStreamConstants {

    private AsyncTaskStreamConstants() {
    }

    // ========== 通用消息字段 ==========

    /**
     * 重试次数字段
     */
    public static final String FIELD_RETRY_COUNT = "retryCount";

    /**
     * 统一异步任务ID字段
     */
    public static final String FIELD_TASK_ID = "taskId";

    /**
     * 业务对象ID字段
     */
    public static final String FIELD_BUSINESS_ID = "businessId";

    /**
     * 链路追踪ID字段
     */
    public static final String FIELD_TRACE_ID = "traceId";

    /**
     * 文档内容字段
     *
     * @deprecated Stream 消息已轻量化，新消息不再写入大段 content。
     */
    @Deprecated
    public static final String FIELD_CONTENT = "content";

    // ========== 通用消费者配置 ==========

    /**
     * 最大重试次数
     */
    public static final int MAX_RETRY_COUNT = 3;

    /**
     * 每次拉取的消息批次大小
     */
    public static final int BATCH_SIZE = 10;

    /**
     * 消费者轮询间隔（毫秒）
     */
    public static final long POLL_INTERVAL_MS = 1000;

    /**
     * Stream 最大长度（自动裁剪旧消息，防止无限增长）
     */
    public static final int STREAM_MAX_LEN = 1000;

    /**
     * Pending 消息最小空闲时间，超过后可被恢复服务 claim
     */
    public static final long PENDING_IDLE_TIMEOUT_MS = 60_000;

    /**
     * Pending 恢复每条 Stream 单次最大 claim 数
     */
    public static final int PENDING_RECOVERY_BATCH_SIZE = 10;

    // ========== 知识库向量化 Stream 配置 ==========

    /**
     * 知识库向量化 Stream Key
     */
    public static final String KB_VECTORIZE_STREAM_KEY = "knowledgebase:vectorize:stream";

    /**
     * 知识库向量化 Consumer Group 名称
     */
    public static final String KB_VECTORIZE_GROUP_NAME = "vectorize-group";

    /**
     * 知识库向量化 Consumer 名称前缀
     */
    public static final String KB_VECTORIZE_CONSUMER_PREFIX = "vectorize-consumer-";

    /**
     * 知识库ID字段
     */
    public static final String FIELD_KB_ID = "kbId";

    // ========== 简历分析 Stream 配置 ==========

    /**
     * 简历分析 Stream Key
     */
    public static final String RESUME_ANALYZE_STREAM_KEY = "resume:analyze:stream";

    /**
     * 简历分析 Consumer Group 名称
     */
    public static final String RESUME_ANALYZE_GROUP_NAME = "analyze-group";

    /**
     * 简历分析 Consumer 名称前缀
     */
    public static final String RESUME_ANALYZE_CONSUMER_PREFIX = "analyze-consumer-";

    /**
     * 简历ID字段
     */
    public static final String FIELD_RESUME_ID = "resumeId";

    // ========== 面试评估 Stream 配置 ==========

    /**
     * 面试评估 Stream Key
     */
    public static final String INTERVIEW_EVALUATE_STREAM_KEY = "interview:evaluate:stream";

    /**
     * 面试评估 Consumer Group 名称
     */
    public static final String INTERVIEW_EVALUATE_GROUP_NAME = "evaluate-group";

    /**
     * 面试评估 Consumer 名称前缀
     */
    public static final String INTERVIEW_EVALUATE_CONSUMER_PREFIX = "evaluate-consumer-";

    /**
     * 面试会话ID字段
     */
    public static final String FIELD_SESSION_ID = "sessionId";

    // ========== 语音面试评估 Stream 配置 ==========

    /**
     * 语音面试评估 Stream Key
     */
    public static final String VOICE_EVALUATE_STREAM_KEY = "voice:evaluate:stream";

    /**
     * 语音面试评估 Consumer Group 名称
     */
    public static final String VOICE_EVALUATE_GROUP_NAME = "voice-evaluate-group";

    /**
     * 语音面试评估 Consumer 名称前缀
     */
    public static final String VOICE_EVALUATE_CONSUMER_PREFIX = "voice-evaluate-consumer-";

    /**
     * 语音面试会话ID字段
     */
    public static final String FIELD_VOICE_SESSION_ID = "voiceSessionId";

    // ========== 面试日程提醒 Stream 配置 ==========

    public static final String INTERVIEW_REMINDER_STREAM_KEY =
            "interview-schedule:reminder:stream";

    public static final String INTERVIEW_REMINDER_GROUP_NAME =
            "interview-reminder-group";

    public static final String INTERVIEW_REMINDER_CONSUMER_PREFIX =
            "interview-reminder-consumer-";

    public static final String FIELD_REMINDER_ID = "reminderId";
}
