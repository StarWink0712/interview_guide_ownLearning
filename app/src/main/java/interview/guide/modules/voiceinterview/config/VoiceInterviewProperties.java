/**
 * 语音面试配置属性集合
 *
 * 集中管理语音面试模块的所有配置项，包括面试阶段时长、阿里云 STT/TTS、Qwen ASR/TTS、
 * 限流策略、音频参数、开场问题模板以及 LLM 流式推送参数。
 * 配置前缀为 app.voice-interview，通过 @ConfigurationProperties 绑定。
 *
 * 联动关系：
 * - 被 QwenAsrService 依赖（读取 qwen.asr 配置）
 * - 被 QwenTtsService 依赖（读取 qwen.tts 配置）
 * - 被 DashscopeLlmService 依赖（读取 aiQuestionMaxChars、llmStreamingEnabled 等参数）
 * - 被 VoiceInterviewWebSocketHandler 依赖（读取 phase、audio、opening、流式推送等配置）
 * - 被 VoiceInterviewService 依赖（读取 phase 时长配置用于阶段切换判断）
 */
package interview.guide.modules.voiceinterview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音面试配置属性类
 * 通过 @ConfigurationProperties(prefix = "app.voice-interview") 绑定配置文件中的参数
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.voice-interview")
public class VoiceInterviewProperties {

    /**
     * 面试阶段配置（开场、技术面、项目面、HR面）
     * 每个阶段包含：建议时长、最小/最大时长、最小/最大题目数量
     * phaseConfig是内部类，用于存储每个阶段的配置项
     */
    private PhaseConfig phase = new PhaseConfig();

    /**
     * 阿里云语音服务配置（STT/TTS）
     * 包含阿里云语音服务的认证信息和功能参数
     */
    private AliyunConfig aliyun = new AliyunConfig();

    /**
     * 限流配置
     * 控制每个会话、每个IP、并发连接的数量限制
     */
    private RateLimitConfig rateLimit = new RateLimitConfig();

    /**
     * 音频参数配置
     * 包含编解码格式、采样率、比特率等参数
     */
    private AudioConfig audio = new AudioConfig();

    /**
     * 通义千问（Qwen）语音服务配置
     * 使用阿里 DashScope API 的 ASR/TTS 服务
     */
    private QwenConfig qwen = new QwenConfig();

    /**
     * 开场问题配置
     * 针对不同技能方向预设的开场问题和算法题模板
     */
    private OpeningConfig opening = new OpeningConfig();

    /**
     * 语音面试单轮面试官回复最大字符数（超出会截断到句子边界）
     * 即ai回复的最大字符数
     * 防止回复过长影响用户体验
     */
    private int aiQuestionMaxChars = 120;

    /**
     * 是否启用 LLM 流式文本下发（先文本后音频）
     * 启用后：先实时推送文本，降低首字等待时延，音频随后推送
     * 禁用后：等 LLM 完全生成后统一推送文本和音频
     */
    private boolean llmStreamingEnabled = true;

    /**
     * 流式文本最小推送间隔（毫秒）
     * 避免 WebSocket 过于频繁刷屏，控制推送频率
     */
    private int aiStreamPushIntervalMs = 180;

    /**
     * 流式文本推送最小增量字符数
     * 只有当文本增长超过此数值时才推送，减少网络开销
     */
    private int aiStreamMinCharsDelta = 12;

    /**
     * 每会话允许的并发 TTS 合成调用上限
     * 防止 DashScope 连接限制，避免资源耗尽
     */
    private int maxConcurrentTtsPerSession = 3;

    /**
     * 是否启用分块音频推送模式
     * 启用：每句 TTS 完成后立即推送，不等全部完成
     * 禁用：收集所有 PCM 后合并为一个完整音频推送
     */
    private boolean chunkedAudioEnabled = false;

    /**
     * 面试阶段配置
     * 定义每个面试阶段的时长范围和题目数量范围
     */
    @Data
    public static class PhaseConfig {
        /**
         * 开场介绍阶段配置
         * 默认：建议5分钟，范围3-8分钟，2-5题
         */
        private DurationConfig intro = new DurationConfig(3, 5, 8, 2, 5);

        /**
         * 技术面试阶段配置
         * 默认：建议10分钟，范围8-15分钟，3-8题
         */
        private DurationConfig tech = new DurationConfig(8, 10, 15, 3, 8);

        /**
         * 项目经历阶段配置
         * 默认：建议10分钟，范围8-15分钟，2-5题
         */
        private DurationConfig project = new DurationConfig(8, 10, 15, 2, 5);

        /**
         * HR面阶段配置
         * 默认：建议5分钟，范围3-8分钟，2-5题
         */
        private DurationConfig hr = new DurationConfig(3, 5, 8, 2, 5);
    }

    /**
     * 阶段时长和题目数量配置
     */
    @Data
    public static class DurationConfig {
        /**
         * 最小面试时长（分钟）
         */
        private int minDuration;

        /**
         * 建议面试时长（分钟）
         */
        private int suggestedDuration;

        /**
         * 最大面试时长（分钟）
         */
        private int maxDuration;

        /**
         * 该阶段最少题目数量
         */
        private int minQuestions;

        /**
         * 该阶段最多题目数量
         */
        private int maxQuestions;

        /**
         * 创建一个阶段的时长和题量边界配置。
         *
         * 默认阶段配置在 {@link PhaseConfig} 初始化时调用；会话服务读取这些边界判断当前阶段是否
         * 应切换，或为已完成阶段构造全零兜底配置。
         *
         * @param min 最小时长（分钟）
         * @param suggested 建议时长（分钟）
         * @param max 最大时长（分钟）
         * @param minQ 最少题目数量
         * @param maxQ 最多题目数量
         */
        public DurationConfig(int min, int suggested, int max, int minQ, int maxQ) {
            this.minDuration = min;
            this.suggestedDuration = suggested;
            this.maxDuration = max;
            this.minQuestions = minQ;
            this.maxQuestions = maxQ;
        }
    }

    /**
     * 阿里云语音服务配置（已废弃，推荐使用 qwen 配置）
     */
    @Data
    public static class AliyunConfig {
        /**
         * 阿里云 STT（语音转文字）配置
         */
        private SttConfig stt = new SttConfig();

        /**
         * 阿里云 TTS（文字转语音）配置
         */
        private TtsConfig tts = new TtsConfig();
    }

    /**
     * 阿里云 STT 配置（已废弃）
     */

    public static class SttConfig {
        private String appKey;
        private String accessKey;
        private String format = "opus";
        private int sampleRate = 16000;
    }

    /**
     * 阿里云 TTS 配置（已废弃）
     */
 
    public static class TtsConfig {
        private String appKey;
        private String accessKey;
        private String voice = "xiaoyun";
        private String format = "mp3";
        private int sampleRate = 16000;
    }

    /**
     * 限流配置
     * 控制语音面试的并发访问量，防止资源耗尽
     */
    public static class RateLimitConfig {
        /**
         * 每个会话最大消息数
         */
        private int maxPerSession = 10;

        /**
         * 每个 IP 最大连接数
         */
        private int maxPerIp = 3;

        /**
         * 最大并发会话数
         */
        private int maxConcurrent = 50;
    }

    /**
     * 音频参数配置
     * 定义音频流的编码格式和参数
     */
    @Data
    public static class AudioConfig {
        /**
         * 音频编解码格式（opus/pcm）
         */
        private String codec = "opus";

        /**
         * 采样率（Hz）
         */
        private int sampleRate = 16000;

        /**
         * 比特率（bps）
         */
        private int bitRate = 24000;

        /**
         * 声道数（1=单声道）
         */
        private int channels = 1;

        /**
         * 音频分块时长（毫秒）
         */
        private int chunkDuration = 2000;
    }

    /**
     * 通义千问（Qwen）语音服务配置
     * 使用阿里 DashScope API 的实时语音服务
     */
    @Data
    public static class QwenConfig {
        /**
         * Qwen ASR（自动语音识别）配置
         */
        private AsrConfig asr = new AsrConfig();

        /**
         * Qwen TTS（语音合成）配置
         */
        private QwenTtsConfig tts = new QwenTtsConfig();
    }

    /**
     * Qwen ASR（自动语音识别）配置
     * 用于实时语音识别，将用户语音转换为文本
     */
    @Data
    public static class AsrConfig {
        /**
         * ASR WebSocket 服务地址
         */
        private String url = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";

        /**
         * ASR 模型名称
         */
        private String model = "qwen3-asr-flash-realtime";

        /**
         * 阿里云 API Key（从环境变量或配置文件读取）
         */
        private String apiKey;

        /**
         * 识别语言（zh=中文）
         */
        private String language = "zh";

        /**
         * 音频输入格式（pcm）
         */
        private String format = "pcm";

        /**
         * 音频采样率（Hz）
         */
        private int sampleRate = 16000;

        /**
         * 是否启用语音活动检测（VAD）
         */
        private boolean enableTurnDetection = true;

        /**
         * VAD 类型（server_vad=服务端VAD）
         */
        private String turnDetectionType = "server_vad";

        /**
         * VAD 阈值
         */
        private float turnDetectionThreshold = 0.0f;

        /**
         * 静音检测时长（毫秒）
         * 超过此时间检测到静音，判定为一句话结束
         */
        private int turnDetectionSilenceDurationMs = 1000;
    }

    /**
     * Qwen TTS（语音合成）配置
     * 用于将 AI 生成的文本转换为语音
     */
    @Data
    public static class QwenTtsConfig {
        /**
         * TTS 模型名称
         */
        private String model = "qwen3-tts-flash-realtime";

        /**
         * 阿里云 API Key（从环境变量或配置文件读取）
         */
        private String apiKey;

        /**
         * 语音音色（Cherry=思悦，xiaoyun=云栖等）
         */
        private String voice = "Cherry";

        /**
         * 音频输出格式（pcm）
         */
        private String format = "pcm";

        /**
         * 音频采样率（Hz）
         */
        private int sampleRate = 24000;

        /**
         * TTS 模式（commit=句子模式）
         */
        private String mode = "commit";

        /**
         * 语言类型（Chinese=中文）
         */
        private String languageType = "Chinese";

        /**
         * 语速（1.0=正常速度）
         */
        private float speechRate = 1.0f;

        /**
         * 音量（0-100）
         */
        private int volume = 60;
    }

    /**
     * 开场问题配置
     * 为不同技能方向预设的开场白和问题模板
     */
    @Data
    public static class OpeningConfig {
        /**
         * 技能方向对应的开场问题模板
         * Map<技能ID, 开场问题内容>
         */
        private Map<String, String> skillQuestions = new LinkedHashMap<>();

        /**
         * 使用算法面试开场白的技能列表
         * 如：bytedance-backend、algorithm
         */
        private List<String> algorithmSkills = List.of("bytedance-backend", "algorithm");

        /**
         * 算法面试开场问题模板
         */
        private String algorithmQuestion =
            "你好，我是本场面试官。先做一道算法与数据结构热身题：请你从哈希表/堆/栈/队列/树/图里选两个，结合一道你熟悉的题，口述为什么选这个结构、核心步骤、时间复杂度、空间复杂度、边界条件与反例。本场不需要写代码，重点看你的思路和取舍。";

        /**
         * 后端面试开场问题模板
         */
        private String backendQuestion =
            "你好，我是本场面试官。第一个问题：请用 1 分钟介绍一个你深度参与的项目，按三点回答：业务目标、你负责的核心模块、核心技术栈。说完我会立刻追问一个关键技术决策。";
    }
}
