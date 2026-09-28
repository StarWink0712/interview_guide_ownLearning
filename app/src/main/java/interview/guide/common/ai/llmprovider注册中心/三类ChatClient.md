
          
## 三类 ChatClient 对比

从代码可见，三种 ChatClient 都基于同一个 `OpenAiChatModel`，区别在于**上层配置**不同：

```
                        OpenAiChatModel（底层，三个共享）
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
         Default          Plain            Voice
    ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
    │ SkillsTool ✓ │  │ SkillsTool ✗ │  │ SkillsTool ✓ │
    │ ToolCallAdv  │  │ 仅 SafeGuard │  │ 流式ToolCall  │
    │ MemoryAdv    │  │              │  │ SafeGuard    │
    │ SafeGuard    │  │              │  │ 无 Memory    │
    └─────────────┘  └─────────────┘  └─────────────┘
```

---

### 为什么需要三类？

| 场景 | 用哪种 | 原因 |
|------|--------|------|
| **普通面试对话** | **Default** | 需要 SkillsTool 让 LLM 能调技能出题，需要 Advisors 管理对话记忆和工具调用 |
| **结构化输出**（出题 JSON、评分 JSON） | **Plain** | 如果带 SkillsTool，LLM 可能先调技能再返回，导致 JSON 解析失败。Plain 强制 LLM **直接输出** |
| **语音面试** | **Voice** | 实时场景需要流式响应（边生成边播放），且语音 WebSocket 手动管理对话历史，不需要 Memory Advisor |

核心矛盾：SkillsTool 是好东西，但在"要求 LLM 直接返回结构化 JSON"的场景下反而是干扰。所以用 Plain 变体**不给工具**，确保输出纯净。

---

## ChatClient 与 OpenAiApi 的关系

```
OpenAiApi（HTTP 客户端）          ← 负责网络通信
      │                               baseUrl, apiKey, 超时
      ▼
OpenAiChatModel（聊天模型）        ← 负责模型调用
      │                               模型名、温度、toolCallingManager
      ▼
ChatClient（对话客户端）           ← 负责业务逻辑
      │                               SkillsTool、Advisors、对话历史
      ▼
业务代码调用
```

**层次关系**：

- **`OpenAiApi`** = 会说话的电话（知道拨哪个号码 `baseUrl`、用什么密码 `apiKey`），纯粹做 HTTP 请求
- **`OpenAiChatModel`** = 电话 + 拨号本（知道打给哪个 `model`、用什么 `temperature`），把 Java 参数转成 JSON 请求
- **`ChatClient`** = 电话 + 拨号本 + 接线员（SkillsTool 告诉 LLM 有哪些工具、Advisors 拦截和增强请求/响应）

**代码体现**：

```java
// 第1层：ApiPathResolver 构建底层 HTTP 客户端
OpenAiApi openAiApi = ApiPathResolver.buildOpenAiApi(config.baseUrl(), config.apiKey());

// 第2层：包装为 ChatModel（加模型参数）
OpenAiChatModel chatModel = new OpenAiChatModel(openAiApi, options, toolCallingManager, ...);

// 第3层：包装为 ChatClient（加工具和 Advisor）
ChatClient.builder(chatModel)
    .defaultToolCallbacks(interviewSkillsToolCallback)   // 注册技能
    .defaultAdvisors(toolCallAdvisor, memoryAdvisor...)   // 注册拦截器
    .build();
```

**一句话总结**：`OpenAiApi` 管怎么连，`ChatClient` 管怎么聊。三者是逐层封装的关系——下层提供能力，上层增强行为。

          
## 三类 ChatClient 完整对比

---

### 底层共性

三种 ChatClient 共享**完全相同的底层结构**：

```
OpenAiApi (调用方传入的 baseUrl + apiKey)
     ↓
OpenAiChatModel (同一厂商、同一聊天模型、同一 temperature)
     ↓
ChatClient (在此之上附加不同的上层配置)
```

唯一不同在于 `ChatClient` 层面挂载的 **SkillsTool** 和 **Advisors**。

---

## 一、Default ChatClient（默认变体）

**创建方法**：`createChatClient(providerId)`

```
ChatClient
├── SkillsTool        ✅ 注册（interviewSkillsToolCallback）
├── 底层模型           OpenAiChatModel (同一 buildChatModel)
└── Advisors 链（4个，按顺序）
    ├── ① ToolCallAdvisor           ← 拦截 tool call 并执行
    │      conversationHistory: false (配置)
    │      streamToolCallResponses: false (配置)
    ├── ② MessageChatMemoryAdvisor   ← 自动管理多轮对话历史
    │      maxMessages: 120 (配置)
    ├── ③ SimpleLoggerAdvisor        ← 请求/响应日志（调试用）
    └── ④ SafeGuardAdvisor           ← 安全过滤
```

### Advisor 链执行流程

```
用户输入
  → ① ToolCallAdvisor  请求阶段不拦截
  → ② MemoryAdvisor    把之前的对话历史拼进去
  → ③ LoggerAdvisor    记录请求日志
  → ④ SafeGuard        检查敏感词
  → LLM 生成回复
  → ④ SafeGuard        过滤输出
  → ③ LoggerAdvisor    记录响应日志
  → ② MemoryAdvisor    把本轮对话存入记忆
  → ① ToolCallAdvisor  如果 LLM 返回了 tool call，执行工具 → 把结果喂回 LLM → 再次经过 ①②③④
  → 返回用户
```

**适用场景**：普通面试对话聊天。LLM 在对话中可能需要调技能出题、回答问题，而且需要记住前面的对话上下文。

**缓存 Key**：`"dashscope"`（仅 providerId）

---

## 二、Plain ChatClient（无工具变体）

**创建方法**：`createPlainChatClient(providerId)`

```
ChatClient
├── SkillsTool        ❌ 不注册
├── 底层模型           OpenAiChatModel (同一 buildChatModel)
└── Advisors 链（1个）
    └── ④ SafeGuardAdvisor ← 仅安全防护
```

### 为什么没有 SkillsTool 和其他 Advisor？

| 移除项 | 原因 |
|--------|------|
| SkillsTool | 会导致 LLM 先触发工具调用，返回中间结果而非直接 JSON，结构化输出解析失败 |
| ToolCallAdvisor | 没有 SkillsTool 就不需要拦截 tool call |
| MemoryAdvisor | 结构化输出场景通常是一次性请求，不需要历史 |
| LoggerAdvisor | 减少输出噪声 |

### 典型问题：如果带 SkillsTool 会发生什么？

```
请求："生成一道 Java 面试题，返回 JSON 格式"
 
带 SkillsTool 时：
  LLM 想："有个 java-backend 技能，我应该先调用它"
  → 返回 tool call: command="java-backend"
  → ToolCallAdvisor 拦截，执行技能，把 SKILL.md 全文返回
  → LLM 收到技能内容，生成面试题 JSON
  → ❌ 业务代码期望 .entity(Question.class) 直接拿到 JSON，
       但第一次收到的是 tool call 指令，解析失败

不带 SkillsTool（Plain）：
  LLM 直接按提示词生成：
  → {"question": "请解释 Java 多线程...", "difficulty": "medium", ...}
  → ✅ 业务代码直接解析为 Java 对象
```

**适用场景**：结构化输出（出题 JSON、简历评分 JSON、知识库查询 JSON 等需要 `.entity()` 解析的场景）。

**缓存 Key**：`"dashscope:plain"`

---

## 三、Voice ChatClient（语音面试变体）

**创建方法**：`createVoiceChatClient(providerId)`

```
ChatClient
├── SkillsTool        ✅ 注册（interviewSkillsToolCallback）
├── 底层模型           OpenAiChatModel (同一 buildChatModel)
└── Advisors 链（2个）
    ├── ① ToolCallAdvisor (流式模式)
    │      conversationHistory: true
    │      streamToolCallResponses: true   ← 关键差异！
    └── ④ SafeGuardAdvisor
```

### 与 Default 的关键差异

| 配置项 | Default | Voice | 原因 |
|--------|---------|-------|------|
| ToolCallAdvisor | 标准模式 | **流式模式** | 语音需要边生成边播放，减少等待 |
| conversationHistory | false | **true** | 流式工具调用需要传递历史 |
| MessageChatMemoryAdvisor | 有 | **无** | 语音通过 WebSocket 手动管理对话历史 |
| SimpleLoggerAdvisor | 有 | **无** | 减少流式场景的日志干扰 |

### 为什么语音需要流式 ToolCall？

```
Default 模式：
  LLM 生成 → 全部完成 → 一次性返回

Voice 流式模式：
  LLM 生成 → 一个字一个字地推 → 同时语音合成播放
  （如果中间触发 tool call，也以流式方式处理，不阻塞）
```

**适用场景**：语音面试的 WebSocket 实时交互。

**缓存 Key**：`"dashscope:voice"`

---

## 四、完整对比总表

| | Default | Plain | Voice |
|---|---|---|---|
| **SkillsTool** | ✅ 有 | ❌ 无 | ✅ 有 |
| **ToolCallAdvisor** | ✅ 标准模式 | ❌ 无 | ✅ 流式模式 |
| **MessageChatMemoryAdvisor** | ✅ 120条 | ❌ 无 | ❌ 无（手动管理） |
| **SimpleLoggerAdvisor** | ✅ 可配置 | ❌ 无 | ❌ 无 |
| **SafeGuardAdvisor** | ✅ 有 | ✅ 有 | ✅ 有 |
| **适用场景** | 普通面试对话 | 结构化输出（JSON） | 语音实时交互 |
| **缓存 Key** | `providerId` | `providerId:plain` | `providerId:voice` |
| **底层 ChatModel** | 相同 | 相同 | 相同 |

---

## 五、ChatClient 与 EmbeddingModel 的关系

ChatClient 和 EmbeddingModel 是**完全独立的两条线**，在 `LlmProviderRegistry` 中使用不同的缓存 Map 管理：

```
clientCache (Map<String, ChatClient>)        embeddingModelCache (Map<String, EmbeddingModel>)
├── "dashscope"        → ChatClient          └── "dashscope" → EmbeddingModel
├── "dashscope:plain"  → ChatClient
└── "dashscope:voice"  → ChatClient
```

### 构建路径完全不同

```
ChatClient 路径：                          EmbeddingModel 路径：
───────────────                           ───────────────────
baseUrl + apiKey                          baseUrl + apiKey（同一厂商）
    ↓                                         ↓
OpenAiApi (路径: /chat/completions)        OpenAiApi (路径: /embeddings)
    ↓                                         ↓
OpenAiChatModel (model: qwen-plus)         OpenAiEmbeddingModel (model: text-embedding-v3)
    ↓                                         ↓
ChatClient + SkillsTool + Advisors         直接作为 Bean 使用
```

### 关键差异

| | ChatClient | EmbeddingModel |
|---|---|---|
| **API 端点** | `/chat/completions` | `/embeddings` |
| **使用模型** | 聊天模型（如 `qwen-plus`） | 向量模型（如 `text-embedding-v3`） |
| **输入** | 自然语言对话 | 文本文档 |
| **输出** | 自然语言回复 | 向量数组（1024 维） |
| **用途** | 面试对话、出题、评分 | 知识库向量化、RAG 语义搜索 |
| **SkillsTool** | 可挂载 | 不涉及 |
| **Advisors** | 可配置 | 不涉及 |

**一句话总结**：ChatClient 管"聊天"，EmbeddingModel 管"检索"，两者共享同一个厂商的 `baseUrl` 和 `apiKey`，但走不同的 API 端点、用不同的模型，是两个完全独立的管道。