### 📌 什么是 ChatClient？

**ChatClient** 是 Spring AI 框架提供的核心接口，用于与大语言模型（LLM）进行交互。它封装了与 AI 模型通信的复杂细节，提供简洁的 API 让开发者专注于业务逻辑。

---

### 🏗️ 架构层次

```
┌─────────────────────────────────────────────────────────────────┐
│                      业务代码层                                  │
│              InterviewService、ResumeService...                 │
└─────────────────────────────────────────────────────────────────┘
                              ↓ 调用
┌─────────────────────────────────────────────────────────────────┐
│                      ChatClient（门面）                          │
│                                                                 │
│   提供：                                                         │
│   • prompt() - 发送提示词                                        │
│   • call() - 同步调用                                            │
│   • stream() - 流式调用                                          │
│   • 工具回调注册                                                  │
│   • Advisor 链式处理                                             │
└─────────────────────────────────────────────────────────────────┘
                              ↓ 委托
┌─────────────────────────────────────────────────────────────────┐
│                    ChatModel（底层模型）                          │
│                                                                 │
│   OpenAiChatModel / AzureChatModel / OllamaChatModel...        │
│   负责实际的 HTTP 请求和响应解析                                   │
└─────────────────────────────────────────────────────────────────┘
                              ↓ HTTP
┌─────────────────────────────────────────────────────────────────┐
│                    LLM 服务商 API                                 │
│                                                                 │
│   OpenAI / DashScope / 智谱 / DeepSeek...                       │
└─────────────────────────────────────────────────────────────────┘
```

---

### 💡 核心功能

| 功能 | 方法 | 说明 |
| --- | --- | --- |
| **发送消息** | `prompt().call()` | 同步发送提示词并获取响应 |
| **流式响应** | `prompt().stream()` | 实时获取响应，适合长文本生成 |
| **工具调用** | `defaultToolCallbacks()` | 注册工具，让 AI 能调用函数 |
| **对话记忆** | `MessageChatMemoryAdvisor` | 保持多轮对话上下文 |
| **安全防护** | `SafeGuardAdvisor` | 过滤敏感内容 |

---

### 📝 使用示例

```java
@Service
public class InterviewService {

    private final LlmProviderRegistry registry;

    // 最简单的用法：发送消息获取响应
    public String generateQuestion(String topic) {
        ChatClient chatClient = registry.getDefaultChatClient();

        return chatClient.prompt()
            .user("请生成一道关于 " + topic + " 的面试题")
            .call()
            .content();  // 获取响应文本
    }

    // 结构化输出：返回 Java 对象
    public InterviewQuestion generateStructuredQuestion(String topic) {
        ChatClient plainClient = registry.getPlainChatClient(null);

        return plainClient.prompt()
            .user("生成一道关于 " + topic + " 的面试题，返回 JSON 格式")
            .call()
            .entity(InterviewQuestion.class);  // 自动解析为对象
    }

    // 流式响应：实时输出
    public Flux<String> streamAnswer(String question) {
        ChatClient chatClient = registry.getDefaultChatClient();

        return chatClient.prompt()
            .user(question)
            .stream()
            .content();  // 返回响应流
    }
}
```

---

### 🔧 ChatClient 的构建过程

```java
// 在 LlmProviderRegistry 中构建 ChatClient
private ChatClient createChatClient(String providerId) {
    // 1. 创建底层 ChatModel
    OpenAiChatModel chatModel = buildChatModel(providerId);

    // 2. 使用 Builder 模式构建 ChatClient
    ChatClient.Builder builder = ChatClient.builder(chatModel);

    // 3. 注册工具回调（让 AI 能调用面试技能）
    if (interviewSkillsToolCallback != null) {
        builder.defaultToolCallbacks(interviewSkillsToolCallback);
    }

    // 4. 配置 Advisors（拦截器链）
    List<Advisor> advisors = buildDefaultAdvisors(providerId);
    builder.defaultAdvisors(advisors.toArray(new Advisor[0]));

    // 5. 构建最终实例
    return builder.build();
}
```

---

### 🎭 三种 ChatClient 变体的区别

| 变体 | 获取方法 | 特点 | 适用场景 |
| --- | --- | --- | --- |
| **Default** | `getDefaultChatClient()` | SkillsTool + 全部 Advisors | 普通对话、面试聊天 |
| **Plain** | `getPlainChatClient()` | 无工具，只有安全防护 | 结构化输出（出题、评分） |
| **Voice** | `getVoiceChatClient()` | SkillsTool + 流式 ToolCall | 语音面试实时交互 |

**为什么需要 Plain 变体？**

```
普通 ChatClient：
用户 → AI → 触发工具调用 → 返回工具结果 → AI 整理回答
（响应中可能包含工具调用消息，破坏 JSON 格式）

Plain ChatClient：
用户 → AI → 直接返回 JSON
（确保结构化输出不被干扰）
```

---

### 🔄 Advisor 链式处理

```
用户输入
    ↓
┌─────────────────────────────────────────────────────────────┐
│ SafeGuardAdvisor（安全防护）                                 │
│   • 检测敏感词                                               │
│   • 拦截不当请求                                             │
└─────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────┐
│ MessageChatMemoryAdvisor（对话记忆）                         │
│   • 从历史记录加载上下文                                      │
│   • 保持对话连贯性                                           │
└─────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────┐
│ ToolCallAdvisor（工具调用）                                  │
│   • 解析 AI 的工具调用请求                                    │
│   • 执行工具方法                                             │
│   • 返回结果给 AI                                            │
└─────────────────────────────────────────────────────────────┘
    ↓
ChatModel（发送到 LLM）
    ↓
AI 响应
```

---

### 📊 与传统 HTTP 调用的对比

| 方式 | 代码量 | 功能 | 错误处理 |
| --- | --- | --- | --- |
| **原生 HTTP** | 多 | 手动构建请求、解析响应 | 手动处理 |
| **ChatClient** | 少 | 自动处理工具调用、记忆、重试 | 内置重试机制 |

```java
// 原生 HTTP 调用（繁琐）
String response = httpClient.post()
    .uri("<https://api.openai.com/v1/chat/completions>")
    .header("Authorization", "Bearer " + apiKey)
    .body(Map.of(
        "model", "gpt-4",
        "messages", List.of(Map.of("role", "user", "content", "你好"))
    ))
    .retrieve()
    .body(String.class);
// 还需要手动解析 JSON...

// ChatClient 调用（简洁）
String response = chatClient.prompt()
    .user("你好")
    .call()
    .content();
```

---

### 💡 总结

| 概念 | 说明 |
| --- | --- |
| **ChatClient** | Spring AI 提供的 LLM 交互门面，简化 AI 调用 |
| **ChatModel** | 底层模型实现，负责实际 HTTP 通信 |
| **Advisor** | 拦截器，提供记忆、工具调用、安全防护等功能 |
| **ToolCallback** | 工具回调，让 AI 能调用预定义的函数 |
| **三种变体** | Default（普通）、Plain（结构化）、Voice（语音） |

简单来说，**ChatClient 就是业务代码与 AI 模型之间的桥梁**，让开发者无需关心底层通信细节，专注于业务逻辑开发。