1. 服务端 → LLM    发送请求，附带 tools/skills 定义
2. LLM    → 服务端   HTTP 响应："我要调用 ×× 工具，参数是..."
3. ToolCallAdvisor 拦截响应，通过 ToolCallingManager 执行对应 Skill
4. Skill 执行完毕，结果 → 再次发送给 LLM
5. LLM    → 服务端  最终自然语言回答

两者职责不同，一个管\*\*"有什么工具可调"**，一个管**"怎么执行工具调用"

***

### `interviewSkillsToolCallback`（ToolCallback）— 工具定义

由 `AgentUtilsConfiguration` 创建并注册为 Spring Bean，告诉 LLM **"你可以调用哪些技能"**。用法：

```java
// 第 442/503 行：注册到 ChatClient
builder.defaultToolCallbacks(interviewSkillsToolCallback);
```

它携带了所有技能的名称和描述列表（如 java-backend、algorithm 等），随请求一起发给 LLM，供 LLM 决策。这个就是将所以skill都携带发送给llm的操作

***

### `toolCallingManager`（ToolCallingManager）— 执行引擎

Spring AI 提供的内置组件，负责**拦截 LLM 返回的 tool call 指令并实际执行**。用法：

```java
// 第 571 行：传给 OpenAiChatModel
new OpenAiChatModel(openAiApi, options, toolCallingManager, ...);

// 第 732 行：构建 ToolCallAdvisor 拦截器
ToolCallAdvisor.builder()
    .toolCallingManager(toolCallingManager)  // 告诉拦截器用哪个引擎执行
    .build();
```

***

### 一句话区分

| <br /> | `interviewSkillsToolCallback`       | `toolCallingManager`                                |
| ------ | ----------------------------------- | --------------------------------------------------- |
| 角色     | 菜单（有什么菜）                            | 厨师（怎么做菜）                                            |
| 作用     | 定义工具列表，告诉 LLM 可调什么                  | 拦截调用请求，执行实际的工具函数                                    |
| 创建者    | 你的代码（AgentUtilsConfiguration）       | Spring AI 框架自动提供                                    |
| 使用方式   | `builder.defaultToolCallbacks(...)` | `ToolCallAdvisor.builder().toolCallingManager(...)` |

**协作关系**：`interviewSkillsToolCallback` 描述了工具，`toolCallingManager` 执行工具。LLM 根据前者决定调哪个，后者负责实际跑通调用链路。
