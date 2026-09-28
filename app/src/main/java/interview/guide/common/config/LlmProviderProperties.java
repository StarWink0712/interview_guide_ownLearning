/**
 * LLM Provider 配置属性
 *
 * 映射 app.ai 前缀的全部配置项，包括默认 Provider、默认 Embedding Provider、
 * 向量维度、各 Provider 连接配置（baseUrl/apiKey/model）、Advisor 开关与参数、
 * 安全配置（API Key 加密密钥）、配置文件路径等。
 * 内嵌 ProviderConfig、SecurityConfig、AdvisorConfig 三个子配置类。
 *
 * 联动关系：
 * - 被 LlmProviderRegistry 注入，作为 Provider 配置的回退数据源
 * - 被 PromptSanitizer 注入，读取 promptSanitizerEnabled 开关
 * - 被 LlmProviderConfigService 注入，用于 Provider 配置管理
 * - 被 ApiKeyEncryptionService 注入，读取加密密钥
 * - 被 LlmProviderBootstrapService 注入，用于启动时初始化配置
 */
package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class LlmProviderProperties {
    /** 默认 Provider ID */
    private String defaultProvider = "dashscope";
    /** 默认 Embedding Provider ID */
    private String defaultEmbeddingProvider;
    /** 向量维度 */
    private Integer embeddingDimensions = 1024;
    /** 各 Provider(即各个llm模型) 配置映射（Key 为 Provider ID） */
    private Map<String, ProviderConfig> providers;
    /** Advisor 配置（工具调用、记忆、安全等开关） */
    private AdvisorConfig advisors = new AdvisorConfig();
    /** 动态配置 YAML 文件路径 */
    private String configYamlPath;
    /** 环境变量配置文件路径 */
    private String configEnvPath;
    /** 安全配置 */
    private SecurityConfig security = new SecurityConfig();

    /**
     * 单个 Provider 的连接配置
     */
    @Data
    public static class ProviderConfig {
        /** API 基础 URL */
        private String baseUrl;
        /** API 密钥 */
        private String apiKey;
        /** 模型名称 */
        private String model;
        /** Embedding 模型名称 */
        private String embeddingModel;
        /** 向量维度（覆盖全局配置） */
        private Integer embeddingDimensions;
        /** 是否支持 Embedding */
        private Boolean supportsEmbedding;
        /** 温度参数 */
        private Double temperature;
    }

    /**
     * 安全配置
     */
    @Data
    public static class SecurityConfig {
        /** API Key AES 加密密钥 */
        private String apiKeyEncryptionKey;
        /** 是否要求加密密钥 */
        private boolean requireEncryptionKey = false;
    }

    /**
     * Advisor 配置（控制 Spring AI Advisor 链的开关和参数）
     */
    @Data
    public static class AdvisorConfig {
        /** Advisor 总开关 */
        private boolean enabled = true;

        /** ToolCallAdvisor 开关 */
        private boolean toolCallEnabled = true;
        /** ToolCall 是否携带对话历史 */
        private boolean toolCallConversationHistoryEnabled = false;
        /** ToolCall 是否流式返回 */
        private boolean streamToolCallResponses = false;

        /** MessageChatMemoryAdvisor 开关（默认关闭，避免会话串扰） */
        private boolean messageChatMemoryEnabled = false;
        /** 最大记忆消息数 */
        private int messageChatMemoryMaxMessages = 120;

        /** SimpleLoggerAdvisor 开关 */
        private boolean simpleLoggerEnabled = false;

        /** SafeGuardAdvisor 开关 */
        private boolean safeguardEnabled = true;
        /** 安全拦截词列表 */
        private List<String> safeguardWords = List.of(
            "I'll now act as",
            "Sure, I'll ignore",
            "我已经忽略",
            "新的角色是",
            "忽略之前的指令",
            "forget all previous instructions"
        );

        /** Prompt 净化器开关 */
        private boolean promptSanitizerEnabled = true;
    }
}
