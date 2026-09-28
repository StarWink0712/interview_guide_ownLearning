# LlmProviderRegistry 多模型注册中心怎么讲

## 1. 面试中的定位

`LlmProviderRegistry` 是 `common.ai` 里最核心的类。面试时可以这样定义它：

> `LlmProviderRegistry` 是项目的 LLM Provider 注册中心。它把不同大模型厂商的 baseUrl、apiKey、模型名、Embedding 模型、温度参数等配置统一管理起来，然后按业务场景创建不同类型的 `ChatClient` 和 `EmbeddingModel`。业务模块不直接接触具体厂商 SDK，而是通过 Registry 获取已经配置好的客户端。

一句话总结：

```text
它解决的是“多模型接入、多场景调用、配置热切换、客户端复用”的问题。
```

## 2. 它为什么有必要存在

如果没有这个注册中心，项目里每个 AI 业务 Service 可能都会写类似代码：

```java
OpenAiApi api = OpenAiApi.builder()
    .baseUrl(...)
    .apiKey(...)
    .build();

OpenAiChatModel model = new OpenAiChatModel(api, options, ...);
ChatClient chatClient = ChatClient.builder(model).build();
```

这样会带来几个问题：

1. 模型配置散落在业务代码中，切换厂商要改很多地方。
2. API Key 管理不安全，容易被硬编码。
3. ChatClient 重复创建，浪费资源，也不方便统一挂 Advisor。
4. 不同场景需要不同 Client，例如结构化输出不应该带工具，语音面试需要流式工具调用。
5. 线上排查时不知道每次调用走的是哪个 Provider、哪个模型。

所以 Registry 把这些共性能力集中起来。

## 3. 配置来源：数据库优先，配置文件兜底

源码里有两个模式：

```text
数据库模式
  LlmGlobalSettingEntity      -> 记录默认 Chat Provider 和默认 Embedding Provider
  LlmProviderEntity           -> 每个厂商一条配置
  ApiKeyEncryptionService     -> 解密数据库里加密存储的 API Key

配置文件模式
  LlmProviderProperties       -> 读取 application.yml 的 app.ai.providers.*
```

面试表达：

> 我这里支持两种配置来源。开发或测试时可以直接读 `application.yml`，例如 `app.ai.providers.dashscope`、`kimi`、`deepseek`。生产或管理后台场景下，可以从数据库读取 Provider 配置，并且 API Key 不是明文使用，而是通过 `ApiKeyEncryptionService` 解密。默认 Provider 也不是写死的，而是由全局设置表或配置文件决定。

默认 Provider 解析逻辑：

```text
resolveDefaultChatProviderId()
  数据库存在全局设置 -> 读 defaultChatProviderId
  否则 -> app.ai.default-provider

resolveDefaultEmbeddingProviderId()
  数据库存在全局设置 -> 读 defaultEmbeddingProviderId
  否则 -> app.ai.default-embedding-provider
  再否则 -> default-provider
```

## 4. 缓存策略怎么讲

`LlmProviderRegistry` 内部有两个 `ConcurrentHashMap`：

```java
private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
```

缓存 key 设计：

```text
dashscope           -> 默认 ChatClient
dashscope:plain     -> Plain ChatClient
dashscope:voice     -> Voice ChatClient
dashscope           -> EmbeddingModel cache key
```

它用 `computeIfAbsent` 懒加载：

```text
第一次调用 getChatClient("dashscope")
  -> 缓存不存在
  -> createChatClient("dashscope")
  -> 放入 ConcurrentHashMap

后续再调用
  -> 直接返回同一个 ChatClient 实例
```

面试可以这样回答：

> ChatClient 和 EmbeddingModel 的创建涉及 HTTP 客户端、模型参数、Advisor 和工具注册，不需要每次请求都重新创建。我使用 `ConcurrentHashMap.computeIfAbsent` 做线程安全懒加载，第一次访问某个 Provider 时创建，后续复用。配置变更后通过 `reload()` 清空缓存，让下一次访问重新从数据库或配置文件加载最新配置。

## 5. 三种 ChatClient 是重点

这是最容易被问到的地方。

### 5.1 Default ChatClient

方法：

```java
getChatClient(providerId)
getDefaultChatClient()
getChatClientOrDefault(providerId)
```

创建流程：

```text
createChatClient(providerId)
  -> buildChatModel(providerId)
  -> ChatClient.builder(chatModel)
  -> defaultToolCallbacks(interviewSkillsToolCallback)
  -> defaultAdvisors(buildDefaultAdvisors(providerId))
  -> build()
```

它可能包含：

- SkillsTool；
- ToolCallAdvisor；
- MessageChatMemoryAdvisor；
- SimpleLoggerAdvisor；
- SafeGuardAdvisor。

适合普通对话、需要工具能力的面试场景。

### 5.2 Plain ChatClient

方法：

```java
getPlainChatClient(providerId)
```

创建流程：

```text
createPlainChatClient(providerId)
  -> buildChatModel(providerId)
  -> ChatClient.builder(chatModel)
  -> 只添加 SafeGuardAdvisor
  -> 不注册 SkillsTool
  -> 不添加 Memory Advisor
```

为什么重要：

> 结构化输出场景最怕模型返回的内容被工具调用、对话记忆或额外解释污染。比如我希望模型返回一个纯 JSON，如果带了 SkillsTool，模型可能触发 tool call，响应就不再是一个干净 JSON。所以出题、自适应决策、单轮回答评估这类场景优先用 Plain Client。

项目里的真实使用：

- `InterviewQuestionService` 使用 `getPlainChatClient(llmProvider)` 生成面试题；
- `TurnEvaluationService` 使用 `getPlainChatClient(llmProvider)` 做即时回答评估；
- `AdaptiveDecisionService` 使用 `getPlainChatClient(llmProvider)` 做下一轮追问/结束决策。

### 5.3 Voice ChatClient

方法：

```java
getVoiceChatClient(providerId)
```

特点：

- 注册 SkillsTool；
- 启用 ToolCallAdvisor；
- 支持流式 tool call responses；
- 不添加 MessageChatMemoryAdvisor；
- 保留 SafeGuardAdvisor。

为什么语音场景特殊：

> 语音面试是实时交互，用户说完一句，系统要尽快返回并触发 TTS。如果使用普通同步响应，等待时间会很长。所以 Voice Client 支持流式响应。同时语音会话自己的 WebSocket/session 层已经维护了对话历史，如果再加 Memory Advisor，可能导致上下文重复或串扰，所以这里不加 Memory Advisor。

项目里的真实使用：

- `DashscopeLlmService.chat(...)`
- `DashscopeLlmService.chatStreamSentences(...)`

## 6. buildChatModel 的底层链路

可以按这条线讲：

```text
buildChatModel(providerId)
  -> loadProviderOrThrow(providerId)
  -> ApiPathResolver.buildOpenAiApi(baseUrl, apiKey)
  -> OpenAiChatOptions.builder()
       .model(model)
       .temperature(config.temperature or 0.2)
  -> new OpenAiChatModel(
       openAiApi,
       options,
       toolCallingManager,
       RetryUtils.DEFAULT_RETRY_TEMPLATE,
       observationRegistry or NOOP
     )
```

这里有几个面试亮点。

### 6.1 为什么用 OpenAiChatModel 也能接 DashScope / Kimi / DeepSeek

答：

> 这些厂商提供 OpenAI-compatible API，也就是接口格式接近 OpenAI 的 `/chat/completions`。Spring AI 的 `OpenAiChatModel` 不只代表 OpenAI 官方，也可以通过自定义 baseUrl 和 apiKey 接入兼容协议的厂商。

### 6.2 为什么要传 RetryUtils.DEFAULT_RETRY_TEMPLATE

答：

> 这是 Spring AI 的底层重试模板，主要处理网络抖动、429、5xx 等瞬时错误。它和 `StructuredOutputInvoker` 的业务重试不是一层：底层重试解决“请求没成功”，结构化输出重试解决“请求成功了但返回格式解析失败”。

### 6.3 ObservationRegistry 有什么用

答：

> 它接入 Micrometer Observation，用于大模型调用的耗时、成功率和链路追踪。如果容器里没有 `ObservationRegistry`，代码用 `ObservationRegistry.NOOP` 兜底，不影响主流程。

## 7. EmbeddingModel 的设计

方法：

```java
getEmbeddingModel(providerId)
getDefaultEmbeddingModel()
```

创建流程：

```text
createEmbeddingModel(providerId)
  -> loadProviderOrThrow(providerId)
  -> 校验 supportsEmbedding
  -> 校验 embeddingModel 非空
  -> 校验 embeddingModel 不像聊天模型
  -> ApiPathResolver.buildOpenAiApi(...)
  -> OpenAiEmbeddingOptions.builder()
       .model(embeddingModel)
       .dimensions(resolveEmbeddingDimensions(...))
  -> new OpenAiEmbeddingModel(...)
```

为什么要校验 embeddingModel：

> 聊天模型和向量模型不是一回事。用户可能把 `qwen-plus`、`glm-4` 这样的聊天模型误填到 embeddingModel，调用时就会失败。所以代码里用 `looksLikeChatModel()` 做提前校验，并给出推荐模型，比如 DashScope 推荐 `text-embedding-v3`，GLM 推荐 `embedding-3`。

和 RAG 的关系：

> 知识库向量化、语义检索都依赖 EmbeddingModel。项目里 `LlmEmbeddingConfig` 把 `registry.getDefaultEmbeddingModel()` 代理成 Spring Bean，这样 Spring AI 的 VectorStore 可以自动注入，但每次实际调用时仍然走 Registry，保证默认 Embedding Provider 切换后能生效。

## 8. Advisor 链怎么讲

`buildDefaultAdvisors` 根据配置装配：

```text
ToolCallAdvisor
  解析模型返回的工具调用请求，执行工具，再把结果交回模型

MessageChatMemoryAdvisor
  做滑动窗口对话记忆，默认关闭以避免会话串扰

SimpleLoggerAdvisor
  调试日志，默认关闭，避免生产日志过多

SafeGuardAdvisor
  敏感词安全防护，返回固定拒绝语
```

面试回答：

> Advisor 类似 Spring AI 的拦截器链。它不改变业务 Service 的调用方式，但可以在模型调用前后加横切能力，比如工具调用、对话记忆、日志和安全防护。我把这些能力放到配置项里控制，避免写死。

## 9. reload 热加载怎么讲

`reload()` 做的事情很简单：

```java
clientCache.clear();
embeddingModelCache.clear();
```

但它的作用范围很大：

```text
管理后台修改 Provider 配置
  -> LlmProviderConfigService 更新数据库或配置文件
  -> 调用 registry.reload()
  -> 清空旧 ChatClient / EmbeddingModel 缓存
  -> 下一次业务调用重新加载新配置
```

面试表达：

> 我没有直接修改正在使用的客户端实例，因为可能有请求正在进行中。reload 只是清空缓存，后续新请求会懒加载新配置。这样能做到较低风险的热切换。

如果面试官追问多实例：

> 当前这个 `reload()` 是单 JVM 内缓存清理。如果生产是多实例部署，还需要通过配置中心、消息广播或管理 API 对所有实例触发 reload，这是我下一步会补的。

## 10. 需要诚实说明的实现边界

### 10.1 Provider 不存在时目前不是所有路径都抛 BusinessException

`loadProviderOrThrow` 在 Provider 不存在时抛了 `IllegalArgumentException`。从项目编码规范看，更理想的是统一成：

```java
throw new BusinessException(
    ErrorCode.PROVIDER_CONFIG_READ_FAILED,
    "Unknown LLM provider: " + providerId
);
```

面试可以这样说：

> 目前 Registry 内部 Provider 不存在的分支还有 `IllegalArgumentException`，这是遗留点。业务层多数 AI 失败会包装成 `BusinessException`，但这个地方后续可以统一错误码，方便全局异常处理器返回标准 Result。

### 10.2 不是所有结构化业务都已经用 Plain Client

出题、自适应评估已经使用 Plain Client，但一些早期业务如简历分析、JD 解析仍有使用默认 Client 的路径。

面试可以这样说：

> 从设计上，结构化 JSON 场景都应该优先走 Plain Client，避免工具调用干扰输出。现在核心面试出题和评估已按这个方式做了，部分早期模块可以继续迁移，这是一个明确的重构方向。

## 11. 高频追问与回答

### 问：为什么用 `ConcurrentHashMap`？

答：

> Registry 是 Spring 单例 Bean，会被多个请求线程同时调用。`ConcurrentHashMap.computeIfAbsent` 可以保证缓存懒加载在并发场景下是线程安全的，避免重复创建或数据竞争。

### 问：为什么不把 ChatClient 直接注册成多个 Spring Bean？

答：

> 因为 Provider 是动态的，可能来自数据库，可能被管理后台新增、删除、切换。直接注册静态 Bean 不适合运行时变更。Registry 通过 providerId 动态创建和缓存，更适合多 Provider 和热加载场景。

### 问：Default Provider 和 Default Embedding Provider 为什么分开？

答：

> 聊天模型和向量模型的最佳选择可能不同。比如聊天用 qwen-plus，向量用 text-embedding-v3。RAG 检索对 embedding 维度和模型稳定性很敏感，所以我把 chat provider 和 embedding provider 分开配置。

### 问：如果 Provider 配错了怎么办？

答：

> Registry 在创建客户端时会 fail fast。Provider 不存在会抛异常，Embedding 不支持或模型名疑似聊天模型也会抛业务异常。这样问题会在调用入口尽早暴露，而不是等到底层 HTTP 请求失败才排查。

### 问：这个类体现了哪些设计模式？

答：

> 可以说它综合用了 Registry 模式、Factory 模式、Cache/Lazy Loading 和 Adapter 思路。Registry 负责按 providerId 查找，Factory 负责构造 ChatClient/EmbeddingModel，缓存避免重复创建，ApiPathResolver 则是对不同 OpenAI-compatible 厂商路径差异的适配。

