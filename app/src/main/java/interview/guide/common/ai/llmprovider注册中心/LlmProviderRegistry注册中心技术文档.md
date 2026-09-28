# LlmProviderRegistry 注册中心技术文档

## 文档概览

本文档面向开发人员，全面阐述 `LlmProviderRegistry` 注册中心的设计原理、接口规范、代码执行路径与模块交互机制。适用于理解 LLM Provider 管理、ChatClient 获取、EmbeddingModel 调用以及 Advisor 拦截器链的技术实现。

**源文件**：[LlmProviderRegistry.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java)

---

## 一、模块对外接口

### 1.1 ChatClient 获取接口

#### 1.1.1 `getChatClient(String providerId)`

| 属性 | 说明 |
|------|------|
| **功能** | 获取指定 Provider 的 Default ChatClient（含 SkillsTool + 4 种 Advisor） |
| **参数** | `providerId: String` — Provider 唯一标识，如 `"dashscope"`、`"openai"` |
| **返回值** | `ChatClient` — 配置完成的 ChatClient 实例 |
| **缓存策略** | `ConcurrentHashMap.computeIfAbsent`，首次调用创建并缓存，后续命中缓存 |
| **使用场景** | 普通面试对话、AI 聊天 |

```java
ChatClient client = registry.getChatClient("dashscope");
String reply = client.prompt().user("什么是 Spring Boot？").call().content();
```

#### 1.1.2 `getDefaultChatClient()`

| 属性 | 说明 |
|------|------|
| **功能** | 获取默认 Provider 的 Default ChatClient |
| **参数** | 无 |
| **返回值** | `ChatClient` |
| **默认来源** | 数据库 `llm_global_setting.default_chat_provider_id` > YAML `app.ai.default-provider` |

#### 1.1.3 `getChatClientOrDefault(String providerId)`

| 属性 | 说明 |
|------|------|
| **功能** | 按 providerId 获取，若为 null/空白则回退到默认 |
| **参数** | `providerId: String` — 可为 null |
| **返回值** | `ChatClient` |
| **使用场景** | 业务代码灵活指定 Provider 或使用默认值的最常用入口 |

```java
// 指定 Provider
ChatClient client = registry.getChatClientOrDefault("openai");
// 使用默认
ChatClient client = registry.getChatClientOrDefault(null);
```

#### 1.1.4 `getPlainChatClient(String providerId)`

| 属性 | 说明 |
|------|------|
| **功能** | 获取不带 SkillsTool 的 Plain ChatClient |
| **参数** | `providerId: String` — 可为 null |
| **返回值** | `ChatClient` — 仅带 SafeGuardAdvisor |
| **缓存 Key** | `"{providerId}:plain"` |
| **使用场景** | 结构化输出（出题 JSON、简历评分 JSON 等） |

```java
ChatClient plain = registry.getPlainChatClient(null);
QuestionDTO q = plain.prompt()
    .user("生成一道 Java 面试题，返回 JSON")
    .call().entity(QuestionDTO.class);
```

#### 1.1.5 `getVoiceChatClient(String providerId)`

| 属性 | 说明 |
|------|------|
| **功能** | 获取语音面试专用 ChatClient |
| **参数** | `providerId: String` — 可为 null |
| **返回值** | `ChatClient` — SkillsTool + 流式 ToolCallAdvisor + SafeGuard |
| **缓存 Key** | `"{providerId}:voice"` |
| **使用场景** | 语音面试 WebSocket 实时交互 |

### 1.2 EmbeddingModel 获取接口

#### 1.2.1 `getEmbeddingModel(String providerId)`

| 属性 | 说明 |
|------|------|
| **功能** | 获取指定 Provider 的 EmbeddingModel |
| **参数** | `providerId: String` |
| **返回值** | `EmbeddingModel` |
| **异常** | `BusinessException` — Provider 不支持 Embedding 或 embeddingModel 配错时抛出 |
| **使用场景** | 知识库文档向量化、语义搜索 |

#### 1.2.2 `getDefaultEmbeddingModel()`

| 属性 | 说明 |
|------|------|
| **功能** | 获取默认 Provider 的 EmbeddingModel |
| **默认来源** | 数据库 `llm_global_setting.default_embedding_provider_id` > YAML `app.ai.default-embedding-provider` > 默认 Chat Provider |

### 1.3 缓存管理接口

#### 1.3.1 `reload()`

| 属性 | 说明 |
|------|------|
| **功能** | 清空 `clientCache` 和 `embeddingModelCache`，下次访问时用最新配置重建 |
| **调用时机** | Provider 配置变更后（API 修改/新增/删除）、默认 Provider 切换后 |
| **副作用** | 不影响正在使用的旧实例，仅影响后续访问 |

---

## 二、模块使用指南

### 2.1 YAML 配置参数

```yaml
app:
  ai:
    default-provider: dashscope           # 默认聊天 Provider
    default-embedding-provider: dashscope  # 默认向量 Provider
    embedding-dimensions: 1024             # 全局向量维度

    providers:                             # 各厂商配置
      dashscope:
        base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
        api-key: sk-xxxxxxxx
        model: qwen-plus                   # 聊天模型
        embedding-model: text-embedding-v3 # 向量模型
        temperature: 0.2
        supports-embedding: true

    advisors:                              # Advisor 开关
      enabled: true                        # 总开关
      tool-call-enabled: true              # ToolCallAdvisor
      tool-call-conversation-history-enabled: false
      stream-tool-call-responses: false
      message-chat-memory-enabled: false   # Memory Advisor（默认关闭）
      message-chat-memory-max-messages: 120
      simple-logger-enabled: false         # 日志 Advisor
```

### 2.2 初始化方式

Registry 通过 `@Component` 自动注册，构造函数决定了配置来源模式：

```
数据库模式（生产）：
  @Autowired
  public LlmProviderRegistry(
      LlmProviderProperties properties,
      LlmProviderRepository providerRepository,         // 非 null → 数据库模式
      LlmGlobalSettingRepository globalSettingRepository,
      ApiKeyEncryptionService encryptionService,
      ...
  )

配置文件模式（开发/测试）：
  public LlmProviderRegistry(
      LlmProviderProperties properties,
      ToolCallingManager toolCallingManager,
      ...
  ) → providerRepository, globalSettingRepository 均为 null
```

### 2.3 业务 Service 调用示例

```java
@Service
public class InterviewQuestionService {

    private final LlmProviderRegistry registry;

    // === 场景1：普通对话出题（使用 Default） ===
    public String generateQuestion(String topic) {
        ChatClient client = registry.getChatClientOrDefault(null);
        return client.prompt()
            .user("请出一道关于 {topic} 的面试题").call().content();
    }

    // === 场景2：结构化输出评分（使用 Plain） ===
    public ScoreDTO evaluate(String answer) {
        ChatClient plain = registry.getPlainChatClient(null);
        return plain.prompt()
            .user("评估以下答案并返回评分JSON：" + answer)
            .call().entity(ScoreDTO.class);
    }

    // === 场景3：知识库向量化（使用 Embedding） ===
    public void vectorize(String content) {
        EmbeddingModel model = registry.getDefaultEmbeddingModel();
        float[] vector = model.embed(new Document(content));
        // 存入 pgvector...
    }
}
```

---

## 三、代码执行路径分析

### 3.1 流程一：首次获取 Default ChatClient

```
触发条件：业务代码首次调用 getChatClient("dashscope")
前置状态：clientCache 中无 "dashscope" 对应的实例

┌─ 步骤 1 ───────────────────────────────────────────────────────────────┐
│ getChatClient("dashscope")                                              │
│   → clientCache.computeIfAbsent("dashscope", id -> createChatClient(id))│
│   → 缓存未命中，进入创建逻辑                                             │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─ 步骤 2：构建 OpenAiChatModel ──────────────────────────────────────────┐
│ buildChatModel("dashscope")                                              │
│   ├── loadProviderOrThrow("dashscope")                                   │
│   │     → providerRepository == null ?                                  │
│   │          properties.getProviders().get("dashscope")   // 配置文件    │
│   │        : providerRepository.findById("dashscope")    // 数据库      │
│   │        .filter(isEnabled) → ProviderSnapshot                        │
│   │                                                                      │
│   ├── ApiPathResolver.buildOpenAiApi(config.baseUrl(), config.apiKey())  │
│   │     → SimpleClientHttpRequestFactory(10s连接, 5min读取)              │
│   │     → RestClient.Builder → OpenAiApi.Builder                        │
│   │     → baseUrlContainsVersion()? 短路径 : 长路径                      │
│   │     → OpenAiApi 实例                                                │
│   │                                                                      │
│   └── new OpenAiChatModel(openAiApi, options, toolCallingManager,       │
│           RetryUtils.DEFAULT_RETRY_TEMPLATE, observationRegistry)        │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─ 步骤 3：注册 SkillsTool + Advisors ────────────────────────────────────┐
│ ChatClient.builder(chatModel)                                            │
│   ├── .defaultToolCallbacks(interviewSkillsToolCallback)  // 技能工具    │
│   │                                                                      │
│   └── buildDefaultAdvisors("dashscope")                                  │
│         ├── properties.getAdvisors().isEnabled()?                        │
│         │     → ① ToolCallAdvisor (tool-call-enabled)                    │
│         │         .toolCallingManager(toolCallingManager)                │
│         │         .conversationHistoryEnabled(false)                     │
│         │         .streamToolCallResponses(false)                        │
│         │     → ② MessageChatMemoryAdvisor (message-chat-memory-enabled) │
│         │         .maxMessages(120)  // 滑动窗口记忆                     │
│         │     → ③ SimpleLoggerAdvisor (simple-logger-enabled)            │
│         │     → ④ SafeGuardAdvisor ← 始终添加                           │
│         │     : 全部关闭 → 返回空列表                                    │
│                                                                          │
│   → builder.build() → ChatClient 实例                                    │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─ 步骤 4：缓存并返回 ────────────────────────────────────────────────────┐
│ clientCache.put("dashscope", chatClient)                                 │
│ 返回 ChatClient 实例                                                     │
└─────────────────────────────────────────────────────────────────────────┘

后置状态：clientCache["dashscope"] = ChatClient 实例
异常处理：
  - loadProviderOrThrow 抛 IllegalArgumentException → 向上传播，不缓存
  - createEmbeddingModel 抛 BusinessException → 向上传播，不缓存
  - ToolCallingManager 为 null → Warn 日志，跳过 ToolCallAdvisor
```

#### 流程总结

Registry 首次为某 Provider 创建 ChatClient 时，首先从数据库或 YAML 配置文件加载 Provider 配置（baseUrl、apiKey、model、temperature），用配置通过 ApiPathResolver 构建底层 HTTP 客户端 OpenAiApi，再包装为 OpenAiChatModel。在 ChatClient 层面，注入 interviewSkillsToolCallback（技能工具列表）使 LLM 可调用面试技能，然后根据 AdvisorConfig 配置按序装配 ToolCallAdvisor（拦截工具调用）、MessageChatMemoryAdvisor（滑动窗口对话记忆，默认关闭避免会话串扰）、SimpleLoggerAdvisor（调试日志）和 SafeGuardAdvisor（安全防护）。最终实例存入 ConcurrentHashMap 缓存，后续调用直接命中缓存而无需重建。整个流程采用懒加载模式，只在首次访问时触发，兼具线程安全和高性能。

---

### 3.2 流程二：LLM 工具调用完整链路

```
触发条件：用户发送消息后，LLM 决定调用 Skill 工具
前置状态：ChatClient 已构建，interviewSkillsToolCallback 和 ToolCallAdvisor 已注册

第 1 轮请求：
  用户消息 → Advisor 链 → LLM
  请求阶段 Advisor 链行为：
    ① ToolCallAdvisor → 不拦截请求
    ② MemoryAdvisor   → 拼接之前的对话历史
    ③ LoggerAdvisor   → 打请求日志
    ④ SafeGuard       → 检查输入敏感词

  LLM 响应（不是最终回答）：
    { "tool_calls": [{ "function": { "name": "Skill", "arguments": {"command": "java-backend"} } }] }
         │
         ▼
  响应阶段 Advisor 链行为：
    ④ SafeGuard       → 过滤输出
    ③ LoggerAdvisor   → 打响应日志
    ② MemoryAdvisor   → 存入本轮对话
    ① ToolCallAdvisor → 拦截到 tool call 指令！
         │
         ├── 从 toolCallingManager 找到 SkillsFunction
         ├── SkillsFunction.apply(new SkillsInput("java-backend"))
         │     → skillsMap.get("java-backend") → Skill 对象
         │     → 返回 BasePath + SKILL.md 全文（面试官人设 + 出题规则）
         │
         ├── conversationHistoryEnabled ?
         │     false (Default): 仅拼 (原问题 + 工具结果)
         │     true  (Voice):   拼 (完整对话历史 + 原问题 + 工具结果)
         │
         └── 构造新消息 → 再次发送给 LLM

第 2 轮请求：
  LLM 收到 SKILL.md 规则，按面试官人设生成面试题
  LLM 响应：自然语言面试题
    ④ SafeGuard → 过滤
    ③ Logger    → 日志
    ② Memory    → 存入记忆
    ① ToolCall → 无 tool call，跳过
  → 返回用户

异常处理：
  - 工具执行失败 → ToolCallAdvisor 返回错误信息给 LLM，由 LLM 决定后续
  - toolCallingManager 为 null → ToolCallAdvisor 未注册，LLM 无法调用工具
  - 技能名不存在 → SkillsFunction 返回 "Skill not found: xxx"

后置状态：对话历史已更新（Memory 模式下）
```

#### 流程总结

当 LLM 决定调用技能工具时，ToolCallAdvisor 在响应阶段拦截 tool call 指令，通过 ToolCallingManager 查找对应的 SkillsFunction 并执行，从预加载的 skillsMap 中取出对应 SKILL.md 全文。若启用 conversationHistoryEnabled，则把用户原始问题、LLM 的 tool call 指令和工具返回结果拼接为一条完整的新消息；否则仅拼接本轮内容。该消息被再次发送给 LLM 进入第二轮对话，LLM 基于 SKILL.md 中定义的面试官人设和出题规则生成最终回答。若开启流式模式，工具执行的中间结果会实时推送给前端（语音面试场景）。这种"拦截-执行-拼接-再请求"的设计保证了 LLM 始终能拿到正确的上下文来完成任务。

---

### 3.3 流程三：EmbeddingModel 创建

```
触发条件：业务代码首次调用 getEmbeddingModel("dashscope")
前置状态：embeddingModelCache 中无 "dashscope" 对应的实例

┌─ 步骤 1 ───────────────────────────────────────────────────────────────┐
│ getEmbeddingModel("dashscope")                                           │
│   → embeddingModelCache.computeIfAbsent("dashscope",                     │
│         id -> createEmbeddingModel(id))                                  │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─ 步骤 2：加载配置 ──────────────────────────────────────────────────────┐
│ loadProviderOrThrow("dashscope")                                         │
│   → 从数据库或 YAML 加载 Provider 配置                                    │
│   → 返回 ProviderSnapshot(baseUrl, apiKey, embeddingModel, ...)         │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─ 步骤 3：校验 ──────────────────────────────────────────────────────────┐
│ if (!config.supportsEmbedding() || isBlank(config.embeddingModel()))     │
│   → throw BusinessException("未配置 Embedding 模型")                     │
│                                                                          │
│ if (looksLikeChatModel(config.embeddingModel()))                         │
│   → 检测 embeddingModel 是否为聊天模型名(qwen-plus/glm-4/deepseek...)    │
│   → throw BusinessException("配成聊天模型了，推荐 xxx")                  │
│   uses RECOMMENDED_EMBEDDING_MODELS:                                    │
│     dashscope → "text-embedding-v3", glm → "embedding-3",                │
│     baidu → "Embedding-V1", minimax → "embo-01"                        │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─ 步骤 4：构建 ──────────────────────────────────────────────────────────┐
│ OpenAiApi openAiApi = ApiPathResolver.buildOpenAiApi(                    │
│     config.baseUrl(), config.apiKey());                                  │
│                                                                          │
│ OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()         │
│     .model(config.embeddingModel())                                      │
│     .dimensions(resolveEmbeddingDimensions(...))   // 默认 1024          │
│     .build();                                                            │
│                                                                          │
│ return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED,           │
│     options, RetryUtils.DEFAULT_RETRY_TEMPLATE, observationRegistry);    │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─ 步骤 5：缓存 ──────────────────────────────────────────────────────────┐
│ embeddingModelCache.put("dashscope", embeddingModel)                     │
│ 返回 EmbeddingModel 实例                                                 │
└─────────────────────────────────────────────────────────────────────────┘

后置状态：embeddingModelCache["dashscope"] = OpenAiEmbeddingModel
异常处理：
  - 不支持 Embedding → BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED)
  - embeddingModel 配成聊天模型名 → BusinessException + 推荐模型名
```

#### 流程总结

EmbeddingModel 的创建流程与 ChatClient 类似但更简洁。加载配置后首先校验 Provider 是否声明支持 Embedding 且配置了 embeddingModel，然后通过 `looksLikeChatModel()` 方法检测 embeddingModel 是否为聊天模型名称（如 qwen-plus、glm-4、deepseek 等），若匹配则抛出 BusinessException 并附带推荐的正确模型名（内置厂商映射表 RECOMMENDED_EMBEDDING_MODELS）。校验通过后调用 ApiPathResolver 构建 OpenAiApi 实例（走 `/embeddings` 端点），封装为 OpenAiEmbeddingModel 并配置向量维度（默认 1024）、重试策略和可观测性组件。最终实例存入 embeddingModelCache 缓存，后续直接命中。

---

### 3.4 流程四：配置热加载（reload）

```
触发条件：
  - 用户通过管理 API 修改/新增/删除 Provider 配置
  - 切换了默认 Chat/Embedding Provider
  - 手动触发刷新

前置状态：clientCache 和 embeddingModelCache 中存有旧配置的实例

┌─ 步骤 1：接收 Reload 触发 ──────────────────────────────────────────────┐
│ 调用方调用 registry.reload()                                             │
│   触发场景：LlmProviderConfigService 中 Provider CRUD 成功后调用          │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─ 步骤 2：清空缓存 ──────────────────────────────────────────────────────┐
│ int size = clientCache.size() + embeddingModelCache.size();              │
│ clientCache.clear();           // 清空所有 ChatClient 缓存               │
│ embeddingModelCache.clear();   // 清空所有 EmbeddingModel 缓存            │
│ log.info("Cache cleared ({} entries)", size);                           │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─ 步骤 3：下次访问自动重建（懒加载）───────────────────────────────────────┐
│ 业务代码再次调用 getChatClient("dashscope")                              │
│   → clientCache.computeIfAbsent → 缓存未命中                             │
│   → createChatClient("dashscope") ← 用最新配置重建                       │
│        ├── loadProviderOrThrow() → 读取最新的数据库/YAML 配置             │
│        ├── buildChatModel() → 新 baseUrl/apiKey/model                    │
│        └── 注册 SkillsTool + Advisors                                    │
│   → 新实例存入缓存                                                        │
└─────────────────────────────────────────────────────────────────────────┘

后置状态：缓存已清空，旧实例仍可使用但不再被新请求引用
注意事项：reload() 只清空缓存，不影响正在使用旧实例的进行中请求
```

#### 流程总结

reload() 是 Registry 的配置热更新入口。用户通过管理 API 修改 Provider 配置后，LlmProviderConfigService 调用 reload() 清空 clientCache 和 embeddingModelCache 所有条目。已缓存的旧 ChatClient 和 EmbeddingModel 实例不会立即销毁（正在处理的请求仍可使用），但后续新的 getChatClient/getEmbeddingModel 调用会因缓存未命中而触发懒加载重建，此时 loadProviderOrThrow() 读取的已是最新的数据库配置，从而在无需重启应用的前提下实现 Provider 的动态切换。

---

## 四、模块间交互机制

### 4.1 依赖关系全景图

```
                        LlmProviderRegistry
                       （注册中心，@Component）
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
 AgentUtilsConfig         ApiPathResolver        LlmProviderProperties
（产出 skillsTool）       （构建 OpenAiApi）       （YAML 配置绑定）
        │                     │                     │
        ▼                     ▼                     ▼
  SkillsTool              OpenAiApi             application.yml
  (.build() →          (.baseUrl + apiKey         app.ai.*
   ToolCallback)          + 超时 + 路径适配)
        │                     │
        ▼                     ▼
  ChatClient ──────────▶ OpenAiChatModel
  .defaultToolCallbacks    .toolCallingManager
  .defaultAdvisors         .retryTemplate
        │                  .observationRegistry
        │                     │
        ▼                     ▼
  + ToolCallAdvisor       LLM 厂商 API
  + MemoryAdvisor
  + LoggerAdvisor
  + SafeGuardAdvisor

        ┌─────────────────────┴─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
LlmProviderRepository   LlmGlobalSettingRepo   ApiKeyEncryptionService
（厂商配置 CRUD）         （默认 Provider 设置）    （AES-GCM 解密）
        │                     │                     │
        ▼                     ▼                     ▼
  llm_provider_config    llm_global_setting      加密 API Key
  (数据库表)              (单例表 ID=1)            ← 数据库
```

### 4.2 数据传递格式：ProviderSnapshot

所有内部方法之间通过 `ProviderSnapshot` record 传递配置数据，它是对外数据源的统一抽象：

```java
private record ProviderSnapshot(
    String id,              // Provider 唯一标识，如 "dashscope"
    String baseUrl,         // API 地址
    String apiKey,          // 已解密的 API 密钥
    String model,           // 聊天模型名，如 "qwen-plus"
    String embeddingModel,  // 向量模型名，如 "text-embedding-v3"
    Integer embeddingDimensions, // 向量维度
    boolean supportsEmbedding,   // 是否支持向量化
    Double temperature      // 温度参数 0~1
) {}
```

**数据来源**：`loadProviderOrThrow()` 根据模式不同，从两个数据源之一构建该 record：

| 模式 | 数据源 | API Key 状态 |
|------|--------|-------------|
| 数据库模式 | `LlmProviderEntity`（JPA 实体） | 通过 `encryptionService.decrypt()` 解密 |
| 配置文件模式 | `LlmProviderProperties.ProviderConfig` | 明文 |

### 4.3 通信协议

| 交互方向 | 协议/方式 | 数据格式 |
|---------|----------|---------|
| Registry → 数据库 | Spring Data JPA | JPA Entity / Repository |
| Registry → 配置文件 | `@ConfigurationProperties` 自动绑定 | YAML → Java 对象 |
| Registry → LLM 厂商 | HTTPS REST | JSON（OpenAI 兼容格式） |
| 业务代码 → Registry | Java 方法调用（进程内） | String providerId / ProviderSnapshot |
| ConfigService → Registry | `reload()` 方法调用 | 无参数，副作用为清空缓存 |

### 4.4 缓存隔离策略

Registry 内部使用两个独立的 `ConcurrentHashMap` 进行缓存隔离：

```
clientCache: Map<String, ChatClient>
├── "dashscope"             → Default ChatClient
├── "dashscope:plain"       → Plain ChatClient  (无 SKillsTool)
├── "dashscope:voice"       → Voice ChatClient  (流式 ToolCall)
├── "openai"                → Default ChatClient
├── "openai:plain"          → Plain ChatClient
└── "openai:voice"          → Voice ChatClient

embeddingModelCache: Map<String, EmbeddingModel>
├── "dashscope"             → OpenAiEmbeddingModel
└── "openai"                → OpenAiEmbeddingModel
```

**设计要点**：
- 三个 ChatClient 变体使用 `providerId:variant` 后缀区分，互不干扰
- EmbeddingModel 和 ChatClient 完全隔离，各自独立创建和缓存
- 所有缓存使用 `computeIfAbsent` 实现线程安全的懒加载

### 4.5 配置优先级链

```
默认 Provider 决策链：
  数据库 llm_global_setting 表
    → YAML app.ai.default-provider
      → "dashscope"（硬编码兜底）

默认 Embedding Provider 决策链：
  数据库 llm_global_setting 表
    → YAML app.ai.default-embedding-provider
      → 默认 Chat Provider（最终回退）

Provider 配置决策链：
  数据库 llm_provider_config 表（enabled=true）
    → YAML app.ai.providers.{id}

API Key 安全链：
  数据库（AES-GCM 加密存储）
    → YAML 明文（仅开发环境）
```

---

## 术语表

| 术语 | 解释 |
|------|------|
| **ChatClient** | Spring AI 的对话客户端，封装了 LLM 请求/响应、工具调用、对话记忆 |
| **OpenAiChatModel** | Spring AI 的聊天模型底层实现，封装 OpenAI 兼容 API 调用 |
| **OpenAiApi** | HTTP 客户端，管理 baseUrl + apiKey + 超时配置 |
| **ToolCallback** | Spring AI 工具回调接口，定义 LLM 可调用的工具列表 |
| **ToolCallAdvisor** | 拦截 LLM 的 tool call 响应并执行工具调用的 Advisor |
| **Advisor** | Spring AI 的拦截器/中间件，在请求前后执行增强逻辑 |
| **ProviderSnapshot** | 内部 record，统一抽象不同数据源的 Provider 配置 |
| **reload()** | 清空所有缓存的热更新入口，下次访问自动用最新配置重建 |
| **computeIfAbsent** | ConcurrentHashMap 的原子操作，保证线程安全的懒加载 |
