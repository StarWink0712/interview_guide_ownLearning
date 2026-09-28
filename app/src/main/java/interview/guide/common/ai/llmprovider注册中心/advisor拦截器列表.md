这段代码是 `buildDefaultAdvisors()` 方法，负责**根据配置动态构建 Default ChatClient 的 Advisor 拦截器链**。只在 Default 变体使用，Plain 和 Voice 各自有自己简化的 Advisor 构建逻辑。

***

## 整体结构

```
properties.getAdvisors()
        │
        ▼
config.isEnabled() == false?  →  返回空列表，什么都不加
        │
        ▼
按配置开关逐个添加 Advisor（都是可选的）：
  ├── ① ToolCallAdvisor        ← 处理 LLM 工具调用
  ├── ② MessageChatMemoryAdvisor ← 管理对话历史
  ├── ③ SimpleLoggerAdvisor    ← 请求/响应日志
  └── ④ SafeGuardAdvisor       ← 安全过滤（始终添加）
```

***

## 逐段解析

### 总开关

```java
AdvisorConfig config = properties.getAdvisors();
if (config == null || !config.isEnabled()) {
    return List.of();
}
```

`advisors.enabled` 是总开关，设 `false` 则全部关闭，返回空列表。这给了运维一个"一键关闭所有增强功能"的开关。

***

### ① ToolCallAdvisor — 工具调用拦截器

```java
if (config.isToolCallEnabled()) {
    if (toolCallingManager != null) {
        advisors.add(buildToolCallAdvisor(
            config.isToolCallConversationHistoryEnabled(),  // 是否带对话历史
            config.isStreamToolCallResponses()));           // 是否流式返回
    } else {
        log.warn("ToolCallAdvisor skipped: ToolCallingManager unavailable");
    }
}
```

| 配置项                                      | 默认值   | 作用            |
| ---------------------------------------- | ----- | ------------- |
| `tool-call-enabled`                      | true  | 启用工具调用        |
| `tool-call-conversation-history-enabled` | false | 工具调用时是否携带历史对话 |
| `stream-tool-call-responses`             | false | 工具调用结果是否流式返回  |

**作用**：当 LLM 返回 "我要调 java-backend 技能" 时，ToolCallAdvisor 拦截响应，通过 `ToolCallingManager` 执行技能，再把结果喂回 LLM。如果 `toolCallingManager` 为 null（没启用 function calling），跳过并打 warn 日志。

***

### ② MessageChatMemoryAdvisor — 对话记忆

```java
if (config.isMessageChatMemoryEnabled()) {
    int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
    MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
        MessageWindowChatMemory.builder()
            .maxMessages(maxMessages)
            .build())
        .build();
    advisors.add(memoryAdvisor);
}
```

| 配置项                                | 默认值         | 作用        |
| ---------------------------------- | ----------- | --------- |
| `message-chat-memory-enabled`      | false（默认关闭） | 启用对话记忆    |
| `message-chat-memory-max-messages` | 120         | 最多记忆多少条消息 |

**作用**：自动在每次请求时把最近的对话历史拼进去，让 LLM 知道"刚才聊了什么"。默认关闭是为了**避免不同用户的会话互相串扰**。`maxMessages` 用 `Math.max(20, ...)` 保底，防止配置成 0 导致功能形同虚设。

***

### ③ SimpleLoggerAdvisor — 日志记录

```java
if (config.isSimpleLoggerEnabled()) {
    advisors.add(new SimpleLoggerAdvisor());
}
```

仅一行，启用后在日志中打印每次 LLM 请求和响应的内容，方便开发调试。生产环境一般关闭。

***

### ④ SafeGuardAdvisor — 安全防护

```java
buildSafeGuardAdvisor().ifPresent(advisors::add);
```

**不受任何开关控制，始终添加**（只要 Advisor 总开关开启）。负责过滤敏感词、防止提示词注入等安全防护。`ifPresent` 说明如果安全配置没配也可能不存在。

***

## 执行顺序的重要性

四种 Advisor 按 1→4 的顺序依次执行，这个顺序是有意义的：

```
用户消息
  → ① ToolCallAdvisor  请求阶段不拦截（只在响应阶段拦截 tool call）
  → ② MemoryAdvisor    把历史对话拼进去
  → ③ LoggerAdvisor    打请求日志
  → ④ SafeGuard        检查敏感词
  → LLM
  → ④ SafeGuard        过滤输出
  → ③ LoggerAdvisor    打响应日志
  → ② MemoryAdvisor    存入本轮对话
  → ① ToolCallAdvisor  如果 LLM 返回 tool call，执行并循环
  → 返回用户
```

**一句话总结**：这个方法是 Default ChatClient 的"增强配置中心"，通过配置开关灵活控制四项增强功能，每个 Advisor 各管一个独立职责，按需组合。

<br />

这段代码是 `ToolCallAdvisor` 的工厂方法，构建 Spring AI 中最关键的拦截器。

```java
private ToolCallAdvisor buildToolCallAdvisor(
        boolean conversationHistoryEnabled,
        boolean streamToolCallResponses) {
    return ToolCallAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .conversationHistoryEnabled(conversationHistoryEnabled)
            .streamToolCallResponses(streamToolCallResponses)
            .build();
}
```

### 三个参数的含义

| 参数                           | 作用                                          | Default 调用    | Voice 调用 |
| :--------------------------- | :------------------------------------------ | :------------ | :------- |
| `toolCallingManager`         | **执行引擎**：拦截到 LLM 的 tool call 后，用谁来实际执行技能    | 从构造函数传入       | 同左       |
| `conversationHistoryEnabled` | 是否把**本轮对话历史**一同发给 LLM（tool call 结果喂回去时带上历史） | `false`（配置文件） | `true`   |
| `streamToolCallResponses`    | 工具执行结果是否以**流式**返回                           | `false`（配置文件） | `true`   |

### 为什么 Default 和 Voice 传的参数不同？

```java
// Default: buildDefaultAdvisors() 中调用
buildToolCallAdvisor(
    config.isToolCallConversationHistoryEnabled(),  // false
    config.isStreamToolCallResponses()              // false
);

// Voice: createVoiceChatClient() 中调用
buildToolCallAdvisor(
    true,   // 语音需要对话历史
    true    // 语音需要流式
);
```

Default 是文字聊天，不需要流式，对话历史由 MemoryAdvisor 单独管理，所以两个都关。Voice 是实时语音，需要边生成边播放，且语音没有 MemoryAdvisor，所以两个都开。

### 运行时它在做什么？

```
LLM 返回: {"tool_calls": [{"function": {"name": "Skill", "arguments": {"command": "java-backend"}}}]}
         │
         ▼
ToolCallAdvisor 拦截
         │
         ├── 从 toolCallingManager 找到 SkillsFunction
         ├── 执行 SkillsFunction.apply(new SkillsInput("java-backend"))
         │      → 返回 SKILL.md 全文
         │
         ├── conversationHistoryEnabled=true?
         │      → 把用户原始问题 + LLM tool call + 工具结果拼成一条新消息
         │
         ├── streamToolCallResponses=true?
         │      → 边返回边推送给前端（语音场景）
         │
         └── 把结果喂回 LLM → LLM 基于 SKILL.md 生成最终回答
```

**一句话总结**：`buildToolCallAdvisor` 就是把"谁执行工具"、"带不带历史"、"要不要流式"三个配置打包成一个拦截器实例，它在运行时拦截 LLM 的 tool call 响应并驱动工具调用的完整闭环。

基本正确，稍微修正一下两个参数的理解：

***

### 完整流程

```
第 1 轮：
  用户："出一道 Java 题"
  服务端 → LLM（带上 SkillsTool 列表）

  LLM 的第一次响应（不是最终回答）：
  "我需要调用 skill=java-backend"
         │
         ▼
  ToolCallAdvisor 拦截 ✅
         │
         ├── 解析出 command="java-backend"
         ├── toolCallingManager 执行 → 拿到 SKILL.md 全文
         │
         └── 把 SKILL.md 全文 + 原问题 拼成新消息，再次发给 LLM
               （这一步始终发生，不管两个参数是 true 还是 false）

第 2 轮：
  LLM 收到 SKILL.md 的规则，按规则生成面试题
  返回最终回答给用户
```

### 两个参数的实际作用

| 参数                           | 不是控制什么          | 实际控制什么                      |
| :--------------------------- | :-------------- | :-------------------------- |
| `conversationHistoryEnabled` | 不是决定"拼不拼接"      | 控制拼接时**带不带前几轮的对话历史**（不只是本轮） |
| `streamToolCallResponses`    | 不是决定"要不要发给 LLM" | 控制中间过程是否**流式推送给前端**         |

**`false`** **时**：只拼本轮的（原问题 + SKILL.md），不带之前的历史。

**`true`** **时**：拼本轮的 + 之前 N 轮的完整对话，一起发给 LLM。语音场景需要这个，因为语音没有 MemoryAdvisor 管理历史。

所以你理解的核心逻辑是对的：**拦截 → 执行 → 拼接 → 再发给 LLM**，两个参数只是控制"拼多少历史"和"要不要流式输出"。
