## Spring AI ChatClient 详解

`ChatClient` 是 Spring AI 中对 LLM 调用的最高层抽象，相当于 JDBC 中的 `JdbcTemplate`——它封装了底层 API 调用的所有细节。

---

### 一、层次定位

```
ChatClient          ← 你现在这一层（对话管理、工具、Advisor）
    │
    ▼
ChatModel           ← 模型层（OpenAiChatModel：拼请求、调 API、返回 ChatResponse）
    │
    ▼
OpenAiApi           ← HTTP 层（baseUrl + apiKey + 超时 + 路径适配）
```

---

### 二、核心设计：不可变 + Builder 模式 + 函数式

```java
// ChatClient 本身是不可变的，创建后无法修改
ChatClient client = ChatClient.builder(chatModel)
    .defaultSystem("你是一个面试官")
    .defaultAdvisors(new SimpleLoggerAdvisor())
    .defaultToolCallbacks(skillsTool)
    .build();

// 每次调用 prompt() 都会基于 client 创建一个新的 Prompt 构建器
// 这样不影响原始的 client
ChatClient.CallResponseSpec response = client.prompt()
    .user("出一道题")
    .call();
```

每一个 `.prompt()` 调用都产生一个**独立的请求上下文**，可以覆盖默认值而不影响原始 client。这就是函数式不变性的好处：线程安全、无副作用。

---

### 三、静态工厂方法

```java
// 1. 从 ChatModel 创建（最常用）
ChatClient client = ChatClient.create(chatModel);

// 2. 从 Builder 创建（带默认配置）
ChatClient client = ChatClient.builder(chatModel)
    .defaultSystem("默认系统提示词")
    .defaultUser(u -> u.text("默认用户提示词"))
    .defaultAdvisors(...)
    .defaultOptions(...)
    .build();
```

---

### 四、Builder 的可配置项（构造时的默认值）

| 方法 | 参数 | 作用 | 项目中使用 |
|------|------|------|-----------|
| `.defaultSystem(String)` | String | 每次请求默认拼上的 System 提示词 | 不常用（提示词由业务构建） |
| `.defaultUser(...)` | User/Function | 默认 User 消息 | 不常用 |
| `.defaultAdvisors(Advisor...)` | Advisor[] | 每次请求默认经过的拦截器链 | **高频**：ToolCallAdvisor、MemoryAdvisor、SafeGuard 等 |
| `.defaultToolCallbacks(ToolCallback...)` | ToolCallback[] | 默认注册的工具列表 | **高频**：`interviewSkillsToolCallback` |
| `.defaultOptions(ChatOptions)` | ChatOptions | 默认的温度、模型名等 | 通过 `OpenAiChatOptions` 已设置 |
| `.defaultFunctions(String...)` | String[] | 注册为 @Bean 的函数名 | 不常用（用 ToolCallback 替代） |
| `.observationRegistry(ObservationRegistry)` | ObservationRegistry | 可观测性 | 已在 ChatModel 层设置 |

**项目代码中的实际用法**：

```java
ChatClient.builder(chatModel)
    .defaultToolCallbacks(interviewSkillsToolCallback)  // 注入技能列表
    .defaultAdvisors(advisors.toArray(new Advisor[0]))  // 注入拦截器链
    .build();
```

---

### 五、`.prompt()` 的请求构建链

```java
ChatClient.ChatClientRequestSpec spec = client.prompt()
    .system("你是 Java 面试官")                // 覆盖本次的 System 提示词
    .system(s -> s.param("topic", "并发"))     // 带参数模板
    .user("出一道题")                          // 用户消息
    .user(u -> u.param("level", "中级"))       // 带参数模板
    .messages(messages)                        // 直接传 Message 列表
    .advisors(extraAdvisor)                    // 追加本次专用的 Advisor
    .toolCallbacks(customTool)                 // 追加本次专用的工具
    .options(ChatOptions)                      // 覆盖本次的温度/模型
    .function("functionName", "description", Function);
```

关键方法说明：

| 方法 | 说明 |
|------|------|
| `.system(String)` | 设置 System role 的系统提示词 |
| `.system(Consumer<SystemParam>)` | 用函数式方式构建 System 提示词，内部分隔符 `{}` 替换 |
| `.user(String)` | 设置 User role 的用户消息 |
| `.user(Consumer<UserParam>)` | 同上，函数式方式 |
| `.messages(Message...)` / `.messages(List<Message>)` | 一次性设置全部消息（自带 role） |
| `.advisors(Advisor...)` | 追加本次专用的 Advisor，与默认的合并 |
| `.toolCallbacks(ToolCallback...)` | 追加工具回调 |
| `.options(ChatOptions)` | 覆盖选项（温度等） |
| `.function(name, desc, func)` | 注册单个 Function Calling |

---

### 六、`.call()` vs `.stream()` — 执行方式

```java
// === 同步调用（阻塞等待完整响应） ===
ChatResponse fullResponse = client.prompt().user("...").call().chatResponse();
String text = client.prompt().user("...").call().content();              // 最常用
MyDTO obj = client.prompt().user("...").call().entity(MyDTO.class);       // JSON → Java

// === 流式调用（逐 token 推送） ===
Flux<ChatResponse> flux = client.prompt().user("...").stream().chatResponse();
Flux<String> textFlux = client.prompt().user("...").stream().content();
```

`call()` 返回 `CallResponseSpec`：

| 方法 | 返回值 | 作用 |
|------|--------|------|
| `.content()` | `String` | 取纯文本（本项目结构化输出用这个） |
| `.chatResponse()` | `ChatResponse` | 取完整响应对象（含 token 用量、finishReason 等） |
| `.entity(Class<T>)` | `T` | JSON 自动反序列化为 Java 对象（Plain ChatClient 用） |
| `.entity(ParameterizedTypeReference<T>)` | `T` | 泛型反序列化 |
| `.entity(StructuredOutputConverter<T>)` | `T` | 自定义转换器 |

`stream()` 返回 `StreamResponseSpec`：

| 方法 | 返回值 | 作用 |
|------|--------|------|
| `.content()` | `Flux<String>` | 逐 token 推送文本（语音面试用） |
| `.chatResponse()` | `Flux<ChatResponse>` | 逐 token 推送完整响应 |

---

### 七、Advisor 拦截器机制

ChatClient 的核心扩展点是 `Advisor` 接口，分为两个阶段：

```
请求阶段：                          响应阶段：
                                    
  Before Advisor                    After Advisor
  (修改/增强请求)                     (修改/增强响应)
      │                                   ▲
      ▼                                   │
  ┌──────────┐                    ┌──────────────┐
  │ 修改 Prompt│                   │ 修改 Response │
  │ 注入上下文│ ────── LLM ──────▶ │ 记录日志      │
  │ 安全检查  │                   │ 执行 ToolCall │
  └──────────┘                    └──────────────┘
```

```java
public interface Advisor {
    // 请求前的处理
    AdvisedRequest adviseRequest(AdvisedRequest request, Map<String, Object> context);
    
    // 响应后的处理
    ChatResponse adviseResponse(ChatResponse response, Map<String, Object> context);
    
    // 唯一标识
    String getName();
}
```

项目中的四种 Advisor 如何利用这个双阶段机制：

| Advisor | 请求阶段 | 响应阶段 |
|---------|---------|---------|
| **ToolCallAdvisor** | 不做操作 | **拦截 tool call 指令 → 执行工具 → 再次请求 LLM** |
| **MessageChatMemoryAdvisor** | **取历史消息拼入请求** | **把本次对话存入记忆** |
| **SimpleLoggerAdvisor** | 记录请求 Prompt | 记录响应内容 |
| **SafeGuardAdvisor** | **过滤敏感词** | 过滤输出 |

---

### 八、ToolCallback（Function Calling）

```java
// 单个工具回调
@FunctionalInterface
public interface ToolCallback {
    // 工具定义（名称、描述、参数 Schema）
    ToolDefinition getToolDefinition();
    
    // 实际执行方法
    String call(String toolInput);
}

// 注册到 ChatClient
ChatClient.builder(chatModel)
    .defaultToolCallbacks(
        new FunctionToolCallback("Skill", skillsFunction, SkillsInput.class)
    )
    .build();
```

每次请求时，`ToolCallback` 的定义会被序列化为 OpenAI 的 `tools` 参数发送给 LLM：

```json
{
  "tools": [{
    "type": "function",
    "function": {
      "name": "Skill",
      "description": "获取指定面试技能领域的内容",
      "parameters": {
        "type": "object",
        "properties": {
          "command": {
            "type": "string",
            "enum": ["java-backend", "algorithm", "system-design", "..."]
          }
        }
      }
    }
  }]
}
```

---

### 九、内存模型（线程安全性）

```
ChatClient（不可变）
├── defaultSystem: String             —— final
├── defaultUser: UserSpec             —— final  
├── defaultAdvisors: List<Advisor>    —— final, 不可修改
├── defaultToolCallbacks: List<..>    —— final
├── defaultOptions: ChatOptions       —— final
└── chatModel: ChatModel              —— final

.prompt() 调用时：
  → 创建一个新的请求上下文（独立可变）
  → 默认值作为基础，允许覆盖
  → 请求结束后销毁
```

**所以 ChatClient 是线程安全的**，可以在多个线程间共享，不需要锁或 synchronized。

---

### 十、项目中使用总结

```
LlmProviderRegistry
    │
    ├── createChatClient("dashscope")
    │     ChatClient.builder(chatModel)
    │       .defaultToolCallbacks(interviewSkillsToolCallback)  ← SkillsTool
    │       .defaultAdvisors(
    │           ToolCallAdvisor,         ← 工具调用
    │           MessageChatMemoryAdvisor, ← 对话记忆
    │           SimpleLoggerAdvisor,     ← 日志
    │           SafeGuardAdvisor)        ← 安全
    │       .build()
    │
    ├── createPlainChatClient("dashscope")
    │     ChatClient.builder(chatModel)
    │       .defaultAdvisors(SafeGuardAdvisor)  ← 只有安全
    │       .build()
    │          ← 没有 SkillsTool（避免干扰 JSON 解析）
    │
    └── createVoiceChatClient("dashscope")
          ChatClient.builder(chatModel)
            .defaultToolCallbacks(interviewSkillsToolCallback)
            .defaultAdvisors(
                ToolCallAdvisor(流式),  ← 流式工具调用
                SafeGuardAdvisor)
            .build()
               ← 没有 MemoryAdvisor（语音手动管理历史）
```

这是 Spring AI `ChatClient` 的链式调用 API，按顺序解释：

```
chatClient.prompt()           // ① 开始构建一次对话请求
    .system(attemptSystemPrompt)   // ② 设置 System prompt
    .user(userPrompt)              // ③ 设置 User prompt
    .call()                        // ④ 发起 HTTP 请求，同步等待响应
    .content();                    // ⑤ 从响应中取文本内容
```

### ④ `.call()` — 实际发请求

```
System Prompt + User Prompt → 拼成 JSON → HTTP POST → LLM 厂商
                                                         │
                                                    等待响应...
                                                         │
                                                   ← 返回 ChatResponse
```

`.call()` 是**同步阻塞**调用，会一直等到 LLM 返回完整响应才继续。与之对应的是 `.stream()`，每生成一个 token 就推回来一次（语音场景用）。

返回值是 `ChatResponse`，里面包了完整的 LLM 响应。

### ⑤ `.content()` — 取文本

LLM 返回的响应结构大致是：

```json
{
  "id": "chatcmpl-xxx",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": "{\"question\": \"请解释...\", \"score\": 85}"
    }
  }],
  "usage": { "prompt_tokens": 120, "completion_tokens": 80 }
}
```

`.content()` 就是从 `choices[0].message.content` 里把文本取出来，其他元数据（token 用量、请求 ID 等）丢掉。

### 完整对照

```java
// 这三行等价于：
String content = chatClient.prompt().system("...").user("...").call().content();

// 拆开写就是：
ChatClient.CallResponseSpec spec = chatClient.prompt()
    .system("请返回 JSON")
    .user("出一道 Java 题")
    .call();                              // ← 发请求 + 等响应
String content = spec.content();          // ← 取文本
```

**简单记**：`.call()` = 打电话等对方说完，`.content()` = 把对方说的话转成文字。