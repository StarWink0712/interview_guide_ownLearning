/**
 * LLM Provider 注册中心与 ChatClient 缓存
 *
 * <p>功能概述：
 * 管理多个 LLM Provider 的 ChatClient 和 EmbeddingModel 实例，支持按 providerId 动态获取。
 * 实例创建后缓存在 ConcurrentHashMap 中，支持 reload 清空重建。
 *
 * <p>三种 ChatClient 变体：
 * <ul>
 *   <li><b>Default</b>：含 SkillsTool + Advisors，用于普通对话场景</li>
 *   <li><b>Plain</b>：无工具，用于结构化输出场景（出题、简历评分等）</li>
 *   <li><b>Voice</b>：含 SkillsTool + 流式 ToolCallAdvisor，用于语音面试</li>
 * </ul>
 *
 * <p>配置来源优先级：
 * <ol>
 *   <li>数据库（LlmProviderEntity）- 优先</li>
 *   <li>配置文件（LlmProviderProperties）- 回退</li>
 * </ol>
 *
 * <p>联动关系：
 * <ul>
 *   <li>依赖 LlmProviderProperties 读取 app.ai 配置</li>
 *   <li>依赖 ApiPathResolver 构建 OpenAiApi 实例</li>
 *   <li>依赖 AgentUtilsConfiguration 提供的 interviewSkillsToolCallback</li>
 *   <li>依赖 LlmProviderRepository / LlmGlobalSettingRepository 读取数据库 Provider 配置</li>
 *   <li>依赖 ApiKeyEncryptionService 解密 API Key</li>
 *   核心：被各个业务模块调用。
 *   <li>被各业务模块 Service 注入调用：ResumeGradingService、InterviewQuestionService、
 *       InterviewSessionService、KnowledgeBaseQueryService、VoiceInterviewService 等</li>
 * </ul>
 *
 * <p>缓存策略：
 * <pre>
 * clientCache:
 *   "dashscope"           → ChatClient (default)
 *   "dashscope:plain"     → ChatClient (plain, 无工具)
 *   "dashscope:voice"     → ChatClient (voice, 流式)
 * embeddingModelCache:
 *   "dashscope"           → EmbeddingModel
 * </pre>
 */
package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.AdvisorConfig;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.observability.AiProviderMetricsRecorder;
import interview.guide.modules.aiobservability.service.AiProviderObservationLogService;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class LlmProviderRegistry {

    private static final String CLIENT_VARIANT_DEFAULT = "DEFAULT";
    private static final String CLIENT_VARIANT_PLAIN = "PLAIN";
    private static final String CLIENT_VARIANT_VOICE = "VOICE";
    private static final String CLIENT_VARIANT_EMBEDDING = "EMBEDDING";
    private static final String OPERATION_CHAT = "CHAT";
    private static final String OPERATION_EMBEDDING = "EMBEDDING";

    /**
     * LLM Provider 配置属性，从 application.yml 读取。
     * 包含 providers 映射、默认 provider、advisor 配置等。
     */
    private final LlmProviderProperties properties;

    /**
     * ChatClient 缓存，按 providerId 或 providerId:variant 作为 key。
     * 使用 ConcurrentHashMap 保证线程安全。
     *
     * <p>
     * 缓存 key 格式：
     * <ul>
     * <li>"dashscope" - 默认 ChatClient</li>
     * <li>"dashscope:plain" - 无工具的 ChatClient</li>
     * <li>"dashscope:voice" - 语音面试专用 ChatClient</li>
     * </ul>
     */
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    /**
     * EmbeddingModel 缓存，按 providerId 作为 key。
     * 用于知识库向量化和相似度搜索。
     */
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
 
    /**
     * 一个管系统默认用哪个llm，一个管具体的llm的配置
     * LlmGlobalSettingEntity（全局设置，1行）
     * ├── defaultChatProviderId = "dashscope" ← 告诉系统"默认用千问聊天"
     * └── defaultEmbeddingProviderId = "dashscope" ← 告诉系统"默认用千问做向量"
     * 
     * LlmProviderEntity（厂商配置，N行，一行一个厂商）
     * ├── id="dashscope" → baseUrl=xxx, apiKey=xxx, model=qwen-plus...
     * ├── id="openai" → baseUrl=xxx, apiKey=xxx, model=gpt-4...
     * └── id="deepseek" → baseUrl=xxx, apiKey=xxx, model=deepseek-chat...
     */
    /**
     * Provider 数据访问层，用于从数据库读取 Provider 配置。
     * 可为 null（非数据库模式时使用配置文件）。
     * 优先走数据库存储的配置，而不是走配置文件的配置
     * 因为数据库是可以动态更新的，而配置文件修改较麻烦，项目不需要重启
     * apikey可以在数据库中加密存储，比明文写在yml中要安全。
     */
    private final LlmProviderRepository providerRepository;

    /**
     * 全局设置数据访问层，用于读取默认 Provider 配置。
     * 可为 null（非数据库模式时使用配置文件）。
     */
    private final LlmGlobalSettingRepository globalSettingRepository;

    /**
     * API Key 解密服务，用于解密数据库中加密存储的 API Key。
     * 可为 null（非数据库模式时 API Key 明文存储在配置文件）。
     */
    private final ApiKeyEncryptionService encryptionService;

    /**
     * 工具调用管理器，用于处理 LLM 的 function calling。
     * 可为 null（未启用工具调用时）。
     */
    private final ToolCallingManager toolCallingManager;

    /**
     * 观察注册表，用于 Micrometer 监控和链路追踪。
     * 可为 null（未启用监控时）。
     */
    private final ObservationRegistry observationRegistry;

    /**
     * AI_PROVIDER_OBSERVABILITY: Provider 级调用日志服务，记录真实 LLM HTTP attempt
     * 和一次 ChatClient 逻辑调用的最终结果。
     */
    private final AiProviderObservationLogService aiProviderObservationLogService;

    /** AI_PROVIDER_OBSERVABILITY: Provider 级 Micrometer 指标记录器。 */
    private final AiProviderMetricsRecorder aiProviderMetricsRecorder;

    /** AI_PROVIDER_OBSERVABILITY: 给 Spring AI RetryTemplate 挂 RetryListener。 */
    private final AiProviderRetryTemplateFactory aiProviderRetryTemplateFactory;

    /**
     * 面试技能工具回调，由 AgentUtilsConfiguration 创建。
     * 允许 LLM 调用预定义的面试技能（如出题、评分等）。
     * 可为 null（未配置技能工具时）。
     * 这个就是AgentUtilsConfiguration.java中定义的方法
     * ToolCallback Bean 会被 LlmProviderRegistry 注入到 ChatClient 中，
     * 使得 LLM 能够在对话过程中调用预定义的面试技能。
     * 而在这个就是AgentUtilsConfiguration中，设置了
     * toolcallback可以根据skill的目录，获取路径下的所有skill
     */
    private final ToolCallback interviewSkillsToolCallback;

    /**
     * 推荐的 Embedding 模型映射表。
     *
     * <p>
     * 用于在用户误将聊天模型配置为 Embedding 模型时提供正确的推荐。
     * Key: Provider ID（小写），Value: 推荐的 Embedding 模型名称
     */
    private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
            "dashscope", "text-embedding-v3",
            "glm", "embedding-3",
            "zhipu", "embedding-3",
            "baidu", "Embedding-V1",
            "minimax", "embo-01");

    /**
     * 完整构造函数（数据库模式）。
     *
     * <p>
     * 注入所有依赖，支持从数据库读取 Provider 配置。
     *
     * @param properties                  LLM Provider 配置属性
     * @param providerRepository          Provider 数据访问层（可为 null）
     * @param globalSettingRepository     全局设置数据访问层（可为 null）
     * @param encryptionService           API Key 解密服务（可为 null）
     * @param toolCallingManager          工具调用管理器（可为 null）
     * @param observationRegistry         观察注册表（可为 null）
     * @param interviewSkillsToolCallback 面试技能工具回调（可为 null）
     */
    @Autowired
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            @Autowired(required = false) ToolCallingManager toolCallingManager,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback,
            @Autowired(required = false) AiProviderObservationLogService aiProviderObservationLogService,
            @Autowired(required = false) AiProviderMetricsRecorder aiProviderMetricsRecorder,
            @Autowired(required = false) AiProviderRetryTemplateFactory aiProviderRetryTemplateFactory) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.encryptionService = encryptionService;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.interviewSkillsToolCallback = interviewSkillsToolCallback;
        this.aiProviderObservationLogService = aiProviderObservationLogService;
        this.aiProviderMetricsRecorder = aiProviderMetricsRecorder;
        this.aiProviderRetryTemplateFactory = aiProviderRetryTemplateFactory;
    }

    /**
     * 简化构造函数（配置文件模式）。
     *
     * <p>
     * 用于测试或不需要数据库存储的场景。
     * Provider 配置直接从 LlmProviderProperties 读取。
     *
     * @param properties                  LLM Provider 配置属性
     * @param toolCallingManager          工具调用管理器
     * @param observationRegistry         观察注册表
     * @param interviewSkillsToolCallback 面试技能工具回调
     */
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ToolCallback interviewSkillsToolCallback) {
        this(
                properties,
                null,
                null,
                null,
                toolCallingManager,
                observationRegistry,
                interviewSkillsToolCallback,
                null,
                null,
                null
        );
    }

    /**
     * 获取指定 Provider 的 ChatClient（默认变体）。
     *
     * <p>
     * 如果客户端不在缓存中，则根据 Provider 配置创建新实例并缓存。
     * 默认变体包含 SkillsTool 和配置的 Advisors。
     *
     * <p>
     * 缓存策略：使用 ConcurrentHashMap.computeIfAbsent 保证线程安全的懒加载。
     *
     * @param providerId Provider 唯一标识，如 "dashscope"、"openai"、"lmstudio"
     * @return 对应 Provider 的 ChatClient 实例
     * @throws IllegalArgumentException 当 providerId 不存在或 Provider 未启用时抛出
     */
    public ChatClient getChatClient(String providerId) {
        return clientCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new client for provider: {}", id);
            return createChatClient(id);
        });
    }

    /**
     * 获取默认 Provider 的 ChatClient。
     *
     * <p>
     * 默认 Provider 由以下方式确定：
     * <ol>
     * <li>数据库模式：从 llm_global_setting 表的 default_chat_provider_id 字段读取</li>
     * <li>配置文件模式：从 app.ai.default-provider 配置读取</li>
     * </ol>
     *
     * @return 默认 Provider 的 ChatClient 实例
     */
    public ChatClient getDefaultChatClient() {
        // 数据库模式或配置文件模式，读取这个字段就知道用哪个默认的chatclient了
        return getChatClient(resolveDefaultChatProviderId());
    }

    /**
     * 获取指定 Provider 的 ChatClient，若 providerId 为空则返回默认 ChatClient。
     *
     * <p>
     * 这是最常用的获取 ChatClient 的方法，支持业务代码灵活指定 Provider 或使用默认值。
     *
     * <p>
     * 使用示例：
     * 
     * <pre>{@code
     * // 使用指定 Provider
     * ChatClient client = registry.getChatClientOrDefault("openai");
     *
     * // 使用默认 Provider（传入 null）
     * ChatClient client = registry.getChatClientOrDefault(null);
     * }</pre>
     *
     * @param providerId Provider 唯一标识，可为 null 或空字符串
     * @return 对应 Provider 的 ChatClient 实例，或默认 ChatClient
     */
    public ChatClient getChatClientOrDefault(String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            return getChatClient(providerId);
        }
        return getDefaultChatClient();
    }

    /**
     * 获取不带 SkillsTool 的 ChatClient（Plain 变体）。
     *
     * <p>
     * 适用场景：
     * <ul>
     * <li>结构化输出：要求模型一次性返回可解析的 JSON</li>
     * <li>面试出题：生成结构化的面试题目</li>
     * <li>简历评分：返回结构化的评分结果</li>
     * </ul>
     *
     * <p>
     * 为什么需要 Plain 变体：
     * 
     * <pre>
     * 带 SkillsTool 的 ChatClient 可能触发工具调用，
     * 导致响应中混入工具调用消息，破坏结构化输出的格式。
     * Plain 变体确保模型直接返回预期的 JSON 格式。
     * </pre>
     *
     * <p>
     * 缓存 key 格式："{providerId}:plain"
     *
     * @param providerId Provider 唯一标识，可为 null（使用默认 Provider）
     * @return 不带工具的 ChatClient 实例
     */
    public ChatClient getPlainChatClient(String providerId) {
        // 解析provider id，为空就填默认
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":plain", key -> createPlainChatClient(id));
    }

    /**
     * 获取语音面试专用 ChatClient（Voice 变体）。
     *
     * <p>
     * 特点：
     * <ul>
     * <li>包含 SkillsTool：支持面试技能调用</li>
     * <li>启用流式 ToolCallAdvisor：支持流式工具调用响应</li>
     * <li>不包含 Memory Advisor：语音面试手动管理对话历史</li>
     * </ul>
     *
     * <p>
     * 为什么语音面试需要特殊处理：
     * 
     * <pre>
     * 语音面试是实时交互场景，需要：
     * 1. 流式响应：边生成边播放，减少等待时间
     * 2. 手动管理历史：语音对话历史由 WebSocket 会话管理
     * 3. 工具调用支持：AI 可以调用技能工具（如查询题库）
     * </pre>
     *
     * <p>
     * 缓存 key 格式："{providerId}:voice"
     *
     * @param providerId Provider 唯一标识，可为 null（使用默认 Provider）
     * @return 语音面试专用 ChatClient 实例
     */
    public ChatClient getVoiceChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":voice", key -> createVoiceChatClient(id));
    }

    /**
     * 清空所有缓存，强制下次访问时重新创建客户端。
     *
     * <p>
     * 调用时机：
     * <ul>
     * <li>Provider 配置变更后（通过 API 修改了 Provider）</li>
     * <li>默认 Provider 切换后</li>
     * <li>手动触发配置刷新时</li>
     * </ul>
     *
     * <p>
     * 注意：此方法只清空缓存，不影响正在使用的客户端实例。
     * 新的请求将使用更新后的配置创建新客户端。
     */
    public void reload() {
        int size = clientCache.size() + embeddingModelCache.size();
        clientCache.clear();
        embeddingModelCache.clear();
        log.info("[LlmProviderRegistry] Cache cleared ({} entries). Next access will re-create clients.", size);
    }

    /**
     * 获取指定 Provider 的 EmbeddingModel。
     *
     * <p>
     * EmbeddingModel 用于将文本转换为向量，主要用途：
     * <ul>
     * <li>知识库文档向量化</li>
     * <li>语义相似度搜索</li>
     * <li>RAG（检索增强生成）</li>
     * </ul>
     *
     * <p>
     * 缓存策略：使用 ConcurrentHashMap.computeIfAbsent 保证线程安全的懒加载。
     *
     * @param providerId Provider 唯一标识
     * @return 对应 Provider 的 EmbeddingModel 实例
     * @throws BusinessException 当 Provider 不支持 Embedding 或 Embedding 模型配置错误时抛出
     */
    public EmbeddingModel getEmbeddingModel(String providerId) {
        return embeddingModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new embedding model for provider: {}", id);
            return createEmbeddingModel(id);
        });
    }

    /**
     * 获取默认 Provider 的 EmbeddingModel。
     *
     * <p>
     * 默认 Embedding Provider 由以下方式确定：
     * <ol>
     * <li>数据库模式：从 llm_global_setting 表的 default_embedding_provider_id 字段读取</li>
     * <li>配置文件模式：从 app.ai.default-embedding-provider 配置读取</li>
     * <li>回退：使用默认 Chat Provider</li>
     * </ol>
     *
     * @return 默认 Provider 的 EmbeddingModel 实例
     */
    public EmbeddingModel getDefaultEmbeddingModel() {
        return getEmbeddingModel(resolveDefaultEmbeddingProviderId());
    }

    /**
     * 创建默认变体的 ChatClient。
     *
     * <p>
     * 创建步骤：
     * <ol>
     * <li>构建 OpenAiChatModel（底层聊天模型）</li>
     * <li>注册 SkillsTool（面试技能工具）</li>
     * <li>配置 Advisors（工具调用、记忆、日志、安全防护）</li>
     * </ol>
     * 入参：
     * <ul>
     * <li>providerId：Provider 唯一标识，如 dashscope</li>
     * <li>clientVariant：ChatClient 类型，如 DEFAULT / PLAIN / VOICE / EMBEDDING</li>
     * <li>operation：操作类型，如 CHAT / EMBEDDING</li>
     * </ul>
     *
     * @param providerId Provider 唯一标识
     * @param model         模型名，如 qwen-plus
     * @param providerId Provider 唯一标识
     * @return 配置完整的 ChatClient 实例
     */
    private ChatClient createChatClient(String providerId) {
        OpenAiChatModel chatModel = buildChatModel(providerId, CLIENT_VARIANT_DEFAULT);
        String model = resolveChatModelName(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);

        // 注册面试技能工具回调
        if (interviewSkillsToolCallback != null) {
            builder.defaultToolCallbacks(interviewSkillsToolCallback);
        }

        // 配置 Advisors
        List<Advisor> advisors = buildDefaultAdvisors(providerId, model, CLIENT_VARIANT_DEFAULT);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors.toArray(new Advisor[0]));
            log.info("[LlmProviderRegistry] Applied {} advisors for provider {}", advisors.size(), providerId);
        }

        return builder.build();
    }

    /**
     * 创建 Plain 变体的 ChatClient（无工具）。
     *
     * <p>
     * 特点：
     * <ul>
     * <li>不注册 SkillsTool</li>
     * <li>只配置 SafeGuardAdvisor（安全防护）</li>
     * <li>确保模型直接返回结构化输出</li>
     * </ul>
     *
     * @param providerId Provider 唯一标识
     * @return 不带工具的 ChatClient 实例
     */
    private ChatClient createPlainChatClient(String providerId) {
        OpenAiChatModel chatModel = buildChatModel(providerId, CLIENT_VARIANT_PLAIN);
        String model = resolveChatModelName(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);

        List<Advisor> advisors = new ArrayList<>();
        // AI_PROVIDER_OBSERVABILITY: Plain 不加工具，但仍记录真实 Provider 调用。
        buildProviderObservationAdvisor(providerId, model, CLIENT_VARIANT_PLAIN).ifPresent(advisors::add);
        // 只添加安全防护 Advisor，不添加工具和记忆
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors.toArray(new Advisor[0]));
        }

        log.info("[LlmProviderRegistry] Created plain ChatClient (no tools) for {}", providerId);
        return builder.build();
    }

    /**
     * 创建 Voice 变体的 ChatClient（语音面试专用）。
     *
     * <p>
     * 特点：
     * <ul>
     * <li>注册 SkillsTool：支持面试技能调用</li>
     * <li>启用流式 ToolCallAdvisor：支持流式工具调用</li>
     * <li>不添加 Memory Advisor：语音面试手动管理对话历史</li>
     * <li>添加 SafeGuardAdvisor：安全防护</li>
     * </ul>
     *
     * @param providerId Provider 唯一标识
     * @return 语音面试专用 ChatClient 实例
     */
    private ChatClient createVoiceChatClient(String providerId) {
        // 构建 OpenAiChatModel（底层聊天模型）,调用下面的buildChatModel方法
        OpenAiChatModel chatModel = buildChatModel(providerId, CLIENT_VARIANT_VOICE);
        String model = resolveChatModelName(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);

        // 注册面试技能工具回调
        if (interviewSkillsToolCallback != null) {
            builder.defaultToolCallbacks(interviewSkillsToolCallback);
        }

        // 配置 Advisors
        List<Advisor> advisors = new ArrayList<>();
        // AI_PROVIDER_OBSERVABILITY: Voice 的逻辑流式调用由 Advisor 汇总，底层流式 HTTP 由 WebClient filter 记录。
        buildProviderObservationAdvisor(providerId, model, CLIENT_VARIANT_VOICE).ifPresent(advisors::add);

        // 添加流式 ToolCallAdvisor（启用对话历史和流式响应）
        if (toolCallingManager != null) {
            advisors.add(buildToolCallAdvisor(true, true));
        }

        // 添加安全防护
        buildSafeGuardAdvisor().ifPresent(advisors::add);

        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors.toArray(new Advisor[0]));
        }

        log.info("[LlmProviderRegistry] Created voice ChatClient (SkillsTool + streaming ToolCall) for {}", providerId);
        return builder.build();
    }

    /**
     * 构建 OpenAiChatModel（底层聊天模型）。
     *
     * <p>
     * 创建步骤：
     * <ol>
     * <li>加载 Provider 配置（从数据库或配置文件）</li>
     * <li>构建 OpenAiApi 实例（处理 API 路径）</li>
     * <li>配置 ChatOptions（模型名称、温度参数）</li>
     * <li>创建 OpenAiChatModel 实例</li>
     * </ol>
     *
     * <p>
     * 注意：虽然使用 OpenAiChatModel，但兼容所有 OpenAI API 格式的服务商
     * （如 DashScope、智谱、DeepSeek 等）。
     *
     * @param providerId Provider 唯一标识
     * @return 配置完整的 OpenAiChatModel 实例
     */
    private OpenAiChatModel buildChatModel(String providerId, String clientVariant) {

        // 加载 Provider 配置
        // 从数据库或配置文件加载 Provider 配置
        // 如果 Provider 不存在，抛出异常
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        log.info("[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}",
                providerId, config.baseUrl(), config.model());

        // 构建 OpenAiApi 实例（处理不同服务商的 API 路径差异）
        // 这里baseurl配置模型网址比如:https://dashscope.aliyuncs.com/compatible-mode/v1
        // apikey是模型的api密钥，用于认证和授权
        // 这里就是调用apipathresolver创建openaiapi
        /**
         * baseUrl: https://dashscope.aliyuncs.com/compatible-mode/v1 ← 阿里云的大门
         * ├── model: qwen-plus ← 均衡版（速度快、便宜）
         * ├── model: qwen-max ← 旗舰版（能力最强、贵）
         * ├── model: qwen-turbo ← 极速版（最快、最便宜）
         * └── model: text-embedding-v3 ← 向量模型（不聊天，只做向量化）
         */
        AiProviderHttpObservationInterceptor requestInterceptor = null;
        AiProviderWebClientObservationFilter exchangeFilter = null;
        if (aiProviderObservationLogService != null && aiProviderMetricsRecorder != null) {
            requestInterceptor = new AiProviderHttpObservationInterceptor(
                    aiProviderObservationLogService,
                    aiProviderMetricsRecorder,
                    providerId,
                    config.model(),
                    clientVariant,
                    OPERATION_CHAT);
            exchangeFilter = new AiProviderWebClientObservationFilter(
                    aiProviderObservationLogService,
                    aiProviderMetricsRecorder,
                    providerId,
                    config.model(),
                    clientVariant,
                    OPERATION_CHAT);
        }
        OpenAiApi openAiApi = ApiPathResolver.buildOpenAiApi(
                config.baseUrl(),
                config.apiKey(),
                requestInterceptor,
                exchangeFilter);

        // 配置聊天选项
        /**
         * 指定用哪个模型，如 qwen-plus 、 gpt-4 、 deepseek-chat
         * 控制输出的随机性，0~1 之间，越低越确定、越高越有创意
         */
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.model())// 如千问plus
                .temperature(config.temperature() != null ? config.temperature() : 0.2)
                .build();

        RetryTemplate retryTemplate = buildRetryTemplate(
                providerId,
                config.model(),
                clientVariant,
                OPERATION_CHAT);

        // 创建 ChatModel 实例
        return new OpenAiChatModel(
                // 从 OpenAiApi 实例获取 API 路径,用于调用模型的api接口还有网络请求
                openAiApi,
                // 从 OpenAiChatOptions 实例获取模型名称、温度参数等
                options,
                toolCallingManager,
                /**
                 * 两个参数的含义：
                 * 
                 * 1. retryTemplate（Spring AI 的重试模板）
                 * 
                 * - 当调用 LLM API 失败时（如网络抖动、服务暂时不可用），自动按策略重试
                 * - AI_PROVIDER_OBSERVABILITY: 当前工厂复刻 Spring AI 默认策略，
                 *   maxRetries=10，即最多 1 次初始请求 + 10 次重试
                 * - 当前默认错误处理下主要重试 ResourceAccessException 和 5xx 对应的
                 *   TransientAiException；4xx 会作为 NonTransientAiException，不进入重试
                 * 2. observationRegistry （Micrometer 可观测性注册表）
                 * 
                 * - 用于链路追踪和监控指标收集
                 * - 如果 Spring 容器中有 ObservationRegistry Bean → 启用监控
                 * - 如果没有 → 用 NOOP（空操作，不收集任何数据）作为兜底
                 * - 可以追踪每次 LLM 调用的耗时、成功率等指标这两个参数分别是 重试策略 和 可观测性
                 */
                retryTemplate,
                observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP);
    }

    /**
     * 创建 EmbeddingModel 实例。
     *
     * <p>
     * 创建步骤：
     * <ol>
     * <li>加载 Provider 配置</li>
     * <li>校验 Provider 支持 Embedding</li>
     * <li>校验 Embedding 模型配置正确（非聊天模型）</li>
     * <li>构建 OpenAiEmbeddingModel 实例</li>
     * </ol>
     *
     * <p>
     * 校验逻辑：
     * <ul>
     * <li>supportsEmbedding 必须为 true</li>
     * <li>embeddingModel 必须配置且非空</li>
     * <li>embeddingModel 不能是聊天模型名称（如 qwen-plus、glm-4）</li>
     * </ul>
     *
     * @param providerId Provider 唯一标识
     * @return 配置完整的 EmbeddingModel 实例
     * @throws BusinessException 当 Provider 不支持 Embedding 或配置错误时抛出
     */
    private EmbeddingModel createEmbeddingModel(String providerId) {
        // 也是从数据库或配置文件加载 Provider 配置
        // 如果 Provider 不存在，抛出异常
        ProviderSnapshot config = loadProviderOrThrow(providerId);

        // 校验：Provider 必须支持 Embedding
        if (!config.supportsEmbedding() || isBlank(config.embeddingModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                    "Provider '" + providerId + "' 未配置可用的 Embedding 模型，无法执行知识库向量化");
        }

        // 校验：Embedding 模型不能是聊天模型，这个模型是转文本到向量的模型，而不是聊天的模型
        if (looksLikeChatModel(config.embeddingModel())) {
            String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
            String suffix = recommendation != null
                    ? "，推荐填写 " + recommendation
                    : "，请填写该厂商真实的 Embedding 模型名";
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                    "Provider '" + providerId + "' 的 Embedding Model 配成了聊天模型 '"
                            + config.embeddingModel() + "'" + suffix);
        }

        log.info("[LlmProviderRegistry] Building EmbeddingModel - Provider: {}, BaseUrl: {}, Model: {}",
                providerId, config.baseUrl(), config.embeddingModel());

        // 构建 OpenAiApi 实例（处理不同服务商的 API 路径差异）
        // 这里baseurl配置模型网址比如:https://dashscope.aliyuncs.com/compatible-mode/v1
        // apikey是模型的api密钥，用于认证和授权
        AiProviderHttpObservationInterceptor requestInterceptor = null;
        if (aiProviderObservationLogService != null && aiProviderMetricsRecorder != null) {
            requestInterceptor = new AiProviderHttpObservationInterceptor(
                    aiProviderObservationLogService,
                    aiProviderMetricsRecorder,
                    providerId,
                    config.embeddingModel(),
                    CLIENT_VARIANT_EMBEDDING,
                    OPERATION_EMBEDDING);
        }
        OpenAiApi openAiApi = ApiPathResolver.buildOpenAiApi(
                config.baseUrl(),
                config.apiKey(),
                requestInterceptor,
                null);

        // 配置 Embedding 选项
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(config.embeddingModel())
                .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
                .build();

        // 创建 EmbeddingModel 实例
        return new OpenAiEmbeddingModel(
                openAiApi,
                MetadataMode.EMBED,
                options,
                buildRetryTemplate(
                        providerId,
                        config.embeddingModel(),
                        CLIENT_VARIANT_EMBEDDING,
                        OPERATION_EMBEDDING),
                observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP);
    }

    /**
     * 构建默认 Advisors 列表。
     *
     * <p>
     * 根据配置添加以下 Advisors（按顺序）：
     * <ol>
     * <li><b>ToolCallAdvisor</b>：处理工具调用，支持多轮工具调用</li>
     * <li><b>MessageChatMemoryAdvisor</b>：对话记忆，保持上下文连贯</li>
     * <li><b>SimpleLoggerAdvisor</b>：日志记录，便于调试</li>
     * <li><b>SafeGuardAdvisor</b>：安全防护，过滤敏感内容</li>
     * </ol>
     *
     * @param providerId Provider 唯一标识（用于日志）
     * @return Advisors 列表，可能为空
     */
    private List<Advisor> buildDefaultAdvisors(
            String providerId,
            String model,
            String clientVariant) {
        // 从配置文件加载 Advisors 配置
        // 如果配置不存在，返回空列表
        AdvisorConfig config = properties.getAdvisors();
        List<Advisor> advisors = new ArrayList<>();
        // AI_PROVIDER_OBSERVABILITY: 观测 Advisor 独立于工具/记忆开关，保证所有 ChatClient 可观测。
        buildProviderObservationAdvisor(providerId, model, clientVariant).ifPresent(advisors::add);
        if (config == null || !config.isEnabled()) {
            return advisors;
        }

        // 1. ToolCallAdvisor：处理工具调用

        if (config.isToolCallEnabled()) {
            if (toolCallingManager != null) {
                // 构建 ToolCallAdvisor 实例
                // 从配置文件中获取对话历史记录启用状态和流式响应状态
                advisors.add(buildToolCallAdvisor(
                        // 从配置文件中获取对话历史记录启用状态
                        config.isToolCallConversationHistoryEnabled(),
                        // 从配置文件中获取流式响应状态
                        config.isStreamToolCallResponses()));
            } else {
                log.warn("[LlmProviderRegistry] ToolCallAdvisor skipped: ToolCallingManager unavailable, provider={}",
                        providerId);
            }
        }

        // 2. MessageChatMemoryAdvisor：对话记忆
        // 从配置文件中获取最大消息数
        if (config.isMessageChatMemoryEnabled()) {
            int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
            // 构建 MessageChatMemoryAdvisor 实例
            // 从配置文件中获取最大消息数
            MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
                    MessageWindowChatMemory.builder()
                            .maxMessages(maxMessages)
                            .build())
                    .build();
            advisors.add(memoryAdvisor);
        }

        // 3. SimpleLoggerAdvisor：日志记录
        if (config.isSimpleLoggerEnabled()) {
            advisors.add(new SimpleLoggerAdvisor());
        }

        // 4. SafeGuardAdvisor：安全防护
        // 从配置文件中获取敏感词列表
        buildSafeGuardAdvisor().ifPresent(advisors::add);

        return advisors;
    }

    // 构建观测 Advisor拦截器
    private Optional<AiProviderObservationAdvisor> buildProviderObservationAdvisor(
            String providerId,
            String model,
            String clientVariant) {
        if (aiProviderObservationLogService == null || aiProviderMetricsRecorder == null) {
            return Optional.empty();
        }
        return Optional.of(new AiProviderObservationAdvisor(
                aiProviderObservationLogService,
                aiProviderMetricsRecorder,
                providerId,
                model,
                clientVariant));
    }

    // 构建 RetryTemplate 实例
    private RetryTemplate buildRetryTemplate(
            String providerId,
            String model,
            String clientVariant,
            String operation) {
        if (aiProviderRetryTemplateFactory == null) {
            return RetryUtils.DEFAULT_RETRY_TEMPLATE;
        }
        return aiProviderRetryTemplateFactory.create(providerId, model, clientVariant, operation);
    }

    private String resolveChatModelName(String providerId) {
        try {
            return loadProviderOrThrow(providerId).model();
        } catch (Exception e) {
            log.warn("[LlmProviderRegistry] Resolve chat model failed: provider={}", providerId, e);
            return "unknown";
        }
    }

    /**
     * 构建 ToolCallAdvisor。
     *
     * <p>
     * ToolCallAdvisor 负责：
     * <ul>
     * <li>解析 LLM 的工具调用请求</li>
     * <li>执行对应的工具方法</li>
     * <li>将工具结果返回给 LLM</li>
     * <li>支持多轮工具调用</li>
     * </ul>
     *
     * @param conversationHistoryEnabled 是否启用对话历史记录
     * @param streamToolCallResponses    是否流式返回工具调用响应
     * @return 配置好的 ToolCallAdvisor 实例
     */
    private ToolCallAdvisor buildToolCallAdvisor(boolean conversationHistoryEnabled,
            boolean streamToolCallResponses) {
        return ToolCallAdvisor.builder()
                //这里传入工具调用管理器，执行工具调用方法
                .toolCallingManager(toolCallingManager)
                .conversationHistoryEnabled(conversationHistoryEnabled)
                .streamToolCallResponses(streamToolCallResponses)
                .build();
    }

    /**
     * 构建 SafeGuardAdvisor（安全防护 Advisor）。
     *
     * <p>
     * SafeGuardAdvisor 负责：
     * <ul>
     * <li>检测用户输入中的敏感词</li>
     * <li>拦截不合适的请求</li>
     * <li>返回预设的拒绝响应</li>
     * </ul>
     *
     * <p>
     * 配置来源：app.ai.advisors.safeguard-words
     *
     * @return SafeGuardAdvisor 实例（若未启用则返回 Optional.empty()）
     */
    private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isSafeguardEnabled()) {
            return Optional.empty();
        }
        SafeGuardAdvisor advisor = SafeGuardAdvisor.builder()
                .sensitiveWords(config.getSafeguardWords())
                .failureResponse("抱歉，我只能协助面试相关的任务。")
                .order(100) // 设置较高的执行顺序，确保在其他 Advisor 之后执行
                .build();
        return Optional.of(advisor);
    }

    /**
     * 解析 Provider ID，若为空则返回默认 Provider ID。
     *
     * @param providerId 传入的 Provider ID，可为 null
     * @return 有效的 Provider ID
     */
    private String resolveProviderId(String providerId) {
        return (providerId != null && !providerId.isBlank())
                ? providerId
                : resolveDefaultChatProviderId();
    }

    /**
     * 解析默认 Chat Provider ID。
     *
     * <p>
     * 解析优先级：
     * <ol>
     * <li>数据库：llm_global_setting 表的 default_chat_provider_id</li>
     * <li>配置文件：app.ai.default-provider</li>
     * </ol>
     *
     * @return 默认 Chat Provider ID
     */
    private String resolveDefaultChatProviderId() {
        // 数据库模式
        if (globalSettingRepository == null) {
            return properties.getDefaultProvider();
        }
        // 从数据库读取，若为空则回退到配置文件
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
                .map(LlmGlobalSettingEntity::getDefaultChatProviderId)
                .filter(id -> !isBlank(id))
                .orElse(properties.getDefaultProvider());
    }

    /**
     * 解析默认 Embedding Provider ID。
     *
     * <p>
     * 解析优先级：
     * <ol>
     * <li>数据库：llm_global_setting 表的 default_embedding_provider_id</li>
     * <li>配置文件：app.ai.default-embedding-provider</li>
     * <li>回退：使用默认 Chat Provider</li>
     * </ol>
     *
     * @return 默认 Embedding Provider ID
     */
    private String resolveDefaultEmbeddingProviderId() {
        // 数据库模式
        if (globalSettingRepository == null) {
            return !isBlank(properties.getDefaultEmbeddingProvider())
                    ? properties.getDefaultEmbeddingProvider()
                    : properties.getDefaultProvider();
        }
        // 从数据库读取，若为空则回退到配置文件，最后回退到默认 Chat Provider
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
                .map(LlmGlobalSettingEntity::getDefaultEmbeddingProviderId)
                .filter(id -> !isBlank(id))
                .orElseGet(() -> !isBlank(properties.getDefaultEmbeddingProvider())
                        ? properties.getDefaultEmbeddingProvider()
                        : properties.getDefaultProvider());
    }

    /**
     * 加载 Provider 配置（优先数据库，回退配置文件）。
     *
     * <p>
     * 加载逻辑：
     * <ol>
     * <li>若 providerRepository 为 null，从配置文件加载</li>
     * <li>否则从数据库加载，解密 API Key</li>
     * <li>若 Provider 不存在或未启用，抛出异常</li>
     * </ol>
     *
     * @param providerId Provider 唯一标识
     * @return Provider 配置快照
     * @throws IllegalArgumentException 当 Provider 不存在或未启用时抛出
     */
    private ProviderSnapshot loadProviderOrThrow(String providerId) {
        // 配置文件模式
        if (providerRepository == null) {
            return loadProviderFromPropertiesOrThrow(providerId);
        }

        // 数据库模式：查询并过滤已启用的 Provider 这个是provider配置在数据库中的实体
        LlmProviderEntity entity = providerRepository.findById(providerId)
                .filter(LlmProviderEntity::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LLM provider: " + providerId));

        // 解密 API Key 并构建快照
        return new ProviderSnapshot(
                entity.getId(),
                entity.getBaseUrl(),
                encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()),
                entity.getModel(),
                entity.getEmbeddingModel(),
                entity.getEmbeddingDimensions(),
                entity.isSupportsEmbedding(),
                entity.getTemperature());
    }

    /**
     * 从配置文件加载 Provider 配置。
     *
     * <p>
     * 用于非数据库模式，直接从 application.yml 读取配置。
     *
     * @param providerId Provider 唯一标识
     * @return Provider 配置快照
     * @throws IllegalArgumentException 当 Provider 配置不存在时抛出
     */
    private ProviderSnapshot loadProviderFromPropertiesOrThrow(String providerId) {
        ProviderConfig config = properties.getProviders().get(providerId);
        if (config == null) {
            log.error("[LlmProviderRegistry] Provider config not found: {}", providerId);
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
        }

        // 判断是否支持 Embedding
        boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
                || !isBlank(config.getEmbeddingModel());

        return new ProviderSnapshot(
                providerId,
                config.getBaseUrl(),
                config.getApiKey(),
                config.getModel(),
                config.getEmbeddingModel(),
                config.getEmbeddingDimensions(),
                supportsEmbedding,
                config.getTemperature());
    }

    /**
     * 判断字符串是否为空白。
     *
     * @param value 待判断的字符串
     * @return true 表示为 null 或仅包含空白字符
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 解析 Embedding 维度。
     *
     * <p>
     * 优先使用 Provider 配置的维度，若无效则使用全局默认维度。
     *
     * @param configuredDimensions Provider 配置的维度
     * @return 有效的 Embedding 维度
     */
    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

    /**
     * 判断模型名称是否像聊天模型。
     *
     * <p>
     * 用于校验 Embedding 模型配置，防止用户误将聊天模型配置为 Embedding 模型。
     *
     * <p>
     * 判断规则：模型名称以常见聊天模型前缀开头：
     * <ul>
     * <li>glm-（智谱）</li>
     * <li>deepseek（深度求索）</li>
     * <li>kimi / moonshot（月之暗面）</li>
     * <li>qwen（通义千问）</li>
     * <li>ernie（文心一言）</li>
     * </ul>
     *
     * @param model 模型名称
     * @return true 表示可能是聊天模型
     */
    private boolean looksLikeChatModel(String model) {
        String lower = model.toLowerCase();
        return lower.startsWith("glm-")
                || lower.startsWith("deepseek")
                || lower.startsWith("kimi")
                || lower.startsWith("moonshot")
                || lower.startsWith("qwen")
                || lower.startsWith("ernie");
    }

    /**
     * Provider 配置快照。
     *
     * <p>
     * 使用 record 封装 Provider 的完整配置信息，便于在方法间传递。
     * 作为不可变数据载体，保证配置在读取后不被修改。
     *
     * @param id                  Provider 唯一标识
     * @param baseUrl             API 基础 URL
     * @param apiKey              API Key（已解密）
     * @param model               聊天模型名称
     * @param embeddingModel      Embedding 模型名称
     * @param embeddingDimensions Embedding 向量维度
     * @param supportsEmbedding   是否支持 Embedding
     * @param temperature         温度参数
     */
    private record ProviderSnapshot(
            String id,
            String baseUrl,
            String apiKey,
            String model,
            String embeddingModel,
            Integer embeddingDimensions,
            boolean supportsEmbedding,
            Double temperature) {
    }
}
