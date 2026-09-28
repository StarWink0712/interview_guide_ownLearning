<br />

三种 `ChatClient` 是按场景拆的。`Default` 的特点是会注册 `SkillsTool` 和一些 Advisor，适合普通自然语言回答或工具型对话场景，比如 RAG 问答、简单问答、普通面试自由追问，这些场景不要求返回 DTO JSON。`Plain` 是为结构化输出准备的，不注册 `SkillsTool`，也不加对话记忆，避免模型触发工具调用或带入历史上下文后污染 JSON。`Voice` 是语音面试实时对话专用的，保留 `SkillsTool` 和流式 `ToolCallAdvisor`，但不使用 `MessageChatMemoryAdvisor`，因为语音面试的历史上下文由业务层自己管理。

出题虽然也用 Skill，但不是通过 `SkillsTool` 动态调用，而是业务层先读取 `SKILL.md`、`skill.meta.yml` 和 reference，把面试官 persona、分类和参考材料拼进 prompt，再用 `Plain Client` 要求模型直接返回结构化 JSON。

语音面试实时对话更适合用 `SkillsTool`。因为它不是一次性生成 JSON，而是多轮自然对话，模型可以按面试方向动态加载对应 `SKILL.md` 的角色和追问规则。`Voice Client` 还支持流式输出，LLM 生成回复时可以边生成边推送前端，并按句子触发 TTS，降低对话停顿感。

当前已经明确走 `Plain` 的有出题、即时评分、下一题决策。语音实时对话走 `Voice`。知识库问答、简单问答这类普通文本回答可以走 `Default`。简历分析、JD 解析、面试评估、日程解析这些本质上也是结构化输出场景，也走plain，避免 `Default` 里的工具和记忆能力干扰 JSON 输出。

<br />

新加了一个记录日志的拦截器

Default ChatClient 主要用于普通对话、RAG 问答、工具调用类场景。它和 Plain 最大的区别是：Default 会注册 `SkillsTool`，并且会挂一组 Advisor。`SkillsTool` 是通过 `interviewSkillsToolCallback` 注册到 `ChatClient.builder(...).defaultToolCallbacks(...)` 上的；Advisor 则通过 `builder.defaultAdvisors(...)` 注册到 ChatClient 调用链里。

这组 Advisor 里，第一类是我新增的 `AiProviderObservationAdvisor`。它是 Provider 级观测 Advisor，独立于工具、记忆等开关存在，负责在一次 ChatClient 调用开始时创建 `invocationId` 和上下文，调用结束后记录这次 LLM 逻辑调用的最终结果，比如 provider、model、clientVariant、是否成功、总耗时、attemptCount、retryCount 等。它记录的是一次完整调用的最终结果，不负责记录每次 HTTP 请求；每次 HTTP attempt 是由 RestClient 的 `AiProviderHttpObservationInterceptor` 或 WebClient 的 `AiProviderWebClientObservationFilter` 记录的。

第二类是 `ToolCallAdvisor`。它负责处理模型发起的工具调用。比如模型判断当前需要加载 `java-backend` 技能时，会返回一个结构化 tool call，请求调用 Skill 工具并传入技能名或参数。`ToolCallAdvisor` 会通过 `ToolCallingManager` 执行前面注册的 `SkillsTool`，拿到对应 `SKILL.md` 的内容，再把工具结果作为上下文返回给模型，让模型继续基于这个面试官角色、追问规则或技能要求生成回答。只有模型真的返回 tool call 时，工具才会被执行。

第三类是 `MessageChatMemoryAdvisor`，它负责对话记忆。请求发给模型前，它会把历史消息补进上下文；模型返回后，它再把本轮用户输入和模型回复写入 memory。这样下一轮普通对话或工具型对话时，模型能知道前面聊过什么。

第四类是 `SimpleLoggerAdvisor`，用于记录 LLM 请求和响应，方便开发阶段排查 prompt、工具调用和模型输出问题。

第五类是 `SafeGuardAdvisor`，用于基础安全过滤，比如拦截不符合面试场景的输入。更细的 Prompt 注入防护，则由 `PromptSanitizer` 和 `PromptSecurityConstants` 在业务 prompt 或结构化调用里处理。

所以 Advisor 可以理解成围绕 ChatClient 调用的一条责任链：调用前可以做观测上下文创建、补充记忆、安全检查；调用过程中可以处理 tool call；调用结束后可以记录日志、保存记忆、写入 Provider 调用结果。Default ChatClient 适合不强制要求结构化 JSON 的普通对话和工具调用场景；如果要求模型严格返回 DTO JSON，就应该用 Plain ChatClient，避免 SkillsTool 或工具调用结果污染 JSON。

<br />

Default ChatClient 会注册 `SkillsTool` 和一组 Advisor。`SkillsTool` 是通过 `interviewSkillsToolCallback` 挂到 ChatClient 上的；Advisor 里比较重要的是 `ToolCallAdvisor`，它负责处理模型发起的工具调用。比如模型判断当前需要加载 `java-backend` 技能时，会返回一个结构化 tool call，请求调用 Skill 工具并传入对应 command。`ToolCallAdvisor` 会拦截这个 tool call，通过 `ToolCallingManager` 执行 `SkillsTool`，拿到对应的 `SKILL.md` 内容，再把工具结果返回给模型，让模型基于这个面试官角色和出题规则继续生成回答。

第二类是 `MessageChatMemoryAdvisor`，它负责对话记忆。请求发给模型前，它可以把历史消息补进上下文；模型返回后，它再把本轮用户输入和模型回复写入 memory，下一轮对话就能知道前面聊过什么。

第三类是 `SimpleLoggerAdvisor`，用于记录 LLM 请求和响应，方便调试模型调用链路。

第四类是 `SafeGuardAdvisor`，用于基础安全过滤。更细的 Prompt 注入防护则由 `PromptSanitizer` 和 `PromptSecurityConstants` 处理。

Advisor 可以理解成围绕 LLM 调用的一条链路：请求发出前可以增强 prompt、加入记忆或做安全检查；模型返回后可以处理 tool call、记录日志、保存上下文。不是所有 Advisor 每次都会触发，比如只有模型真的返回 tool call 时，`ToolCallAdvisor` 才会执行具体工具。

<br />

chatmodel是chatclient的下一层对象，需要传入providerid，openaiapi（是最底层，需要传入url，apikey，超时时间，读取超时时间），以及temperature。chatclient的创建，需要传入的有chatmodel，advisor列表，skillstool工具回调。

<br />

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         业务层调用                                        │
│                                                                          │
│  知识库问答   简历分析   面试出题   语音面试   JD解析   回答评估            │
│     │           │         │        │        │         │                │
│     │    ┌──────┘         │        │        └────┬────┘                │
│     │    │  ┌─────────────┘        │             │                      │
│     ▼    ▼  ▼                      ▼             ▼                      │
│  ┌──────────────┐          ┌──────────────┐                             │
│  │ Default      │          │ Plain        │                             │
│  │ ChatClient   │          │ ChatClient   │                             │
│  │              │          │              │                             │
│  │ 适用场景：    │          │ 适用场景：     │                             │
│  │ ·普通对话     │          │ ·结构化JSON输出│                             │
│  │ ·RAG问答     │          │ ·简历分析     │                             │
│  │ ·工具调用     │          │ ·JD解析      │                             │
│  │              │          │              │                             │
│  │ 关键区别：    │          │ 关键区别：     │                             │
│  │ ✅ SkillsTool │          │ ❌ 无工具      │                             │
│  │ ✅ Advisor链  │          │ ✅ SafeGuard  │                             │
│  └──────┬───────┘          └──────┬───────┘                             │
│         │                         │                                     │
└─────────┼─────────────────────────┼─────────────────────────────────────┘
          │                         │
          │  ┌──────────────────────────────────────────────┐
          │  │         Default Advisor 责任链                │
          │  │                                              │
          │  │  ChatClient.prompt().user(msg).call()        │
          │  │         │                                    │
          │  │         ▼                                    │
          │  │  ┌─────────────────────────────────────┐     │
          │  │  │ ① AiProviderObservationAdvisor      │     │
          │  │  │   请求前：创建 invocationId + 上下文   │     │
          │  │  │   响应后：记录 provider/model/耗时/结果│     │
          │  │  │   独立于其他开关，始终存在              │     │
          │  │  └────────────┬────────────────────────┘     │
          │  │               ▼                              │
          │  │  ┌─────────────────────────────────────┐     │
          │  │  │ ② ToolCallAdvisor                   │     │
          │  │  │   请求：放行                          │     │
          │  │  │   响应：检测 LLM 是否返回 tool_call     │     │
          │  │  │      ├─ 无 → 放行                    │     │
          │  │  │      └─ 有 → ToolCallingManager      │     │
          │  │  │              执行 SkillsTool          │     │
          │  │  │              结果喂回 LLM（循环）       │     │
          │  │  └────────────┬────────────────────────┘     │
          │  │               ▼                              │
          │  │  ┌─────────────────────────────────────┐     │
          │  │  │ ③ MessageChatMemoryAdvisor          │     │
          │  │  │   请求前：从 Memory 取出历史拼入上下文   │     │
          │  │  │   响应后：本轮对话写入 Memory          │     │
          │  │  │   可配置 maxMessages=120             │     │
          │  │  └────────────┬────────────────────────┘     │
          │  │               ▼                              │
          │  │  ┌─────────────────────────────────────┐     │
          │  │  │ ④ SimpleLoggerAdvisor               │     │
          │  │  │   请求前：打印 request log             │     │
          │  │  │   响应后：打印 response log            │     │
          │  │  │   开发调试用，生产一般关闭              │     │
          │  │  └────────────┬────────────────────────┘     │
          │  │               ▼                              │
          │  │  ┌─────────────────────────────────────┐     │
          │  │  │ ⑤ SafeGuardAdvisor (order=100)     │     │
          │  │  │   请求前：扫描用户消息含不含敏感词      │     │
          │  │  │   响应后：扫描 LLM 回复含不含敏感词     │     │
          │  │  │   命中 → 拦截，返回固定拒绝文案         │     │
          │  │  └────────────┬────────────────────────┘     │
          │  │               ▼                              │
          │  │         LLM API 调用                          │
          │  │         (HTTP 请求 → 模型返回)                 │
          │  │               │                              │
          │  │               ▼                              │
          │  │     Advisor 链反向回溯（⑤→④→③→②→①）        │
          │  │     各自在响应后做收尾工作                     │
          │  └──────────────────────────────────────────────┘
          │
          │  ┌──────────────────────────────────────────────┐
          │  │         Plain Advisor 链（极简）              │
          │  │                                              │
          │  │  ChatClient.prompt().user(msg).call()        │
          │  │         │                                    │
          │  │         ▼                                    │
          │  │  AiProviderObservationAdvisor                │
          │  │         │                                    │
          │  │         ▼                                    │
          │  │  SafeGuardAdvisor                            │
          │  │         │                                    │
          │  │         ▼                                    │
          │  │   LLM API 调用                               │
          │  │   (无工具、无记忆，保证纯 JSON 输出)           │
          │  └──────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         LLM 实际 HTTP 调用                                │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────┐        │
│  │ AiProviderHttpObservationInterceptor (RestClient)             │        │
│  │ AiProviderWebClientObservationFilter (WebClient)               │        │
│  │                                                                │        │
│  │ 每次 HTTP 请求/响应都记录：                                     │        │
│  │   · provider + model + clientVariant                          │        │
│  │   · attempt 序号（重试时递增）                                   │        │
│  │   · HTTP status + latency                                     │        │
│  │                                                                │        │
│  │ 与 Advisor 的分工：                                             │        │
│  │   Advisor 记"一次逻辑调用的最终结果"（可能内部重试了3次）          │        │
│  │   Interceptor 记"每次 HTTP 请求的细节"（3次记录3条）              │        │
│  └─────────────────────────────────────────────────────────────┘        │
└─────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         模型服务（API）                                   │
│   DashScope / DeepSeek / 智谱 / OpenAI ...                              │
└─────────────────────────────────────────────────────────────────────────┘


═══════════════════════════════════════════════════════════════════════════
  选择指南
═══════════════════════════════════════════════════════════════════════════

  Default ChatClient                    Plain ChatClient
  ──────────────────                    ────────────────
  使用场景：                             使用场景：
    · 知识库 RAG 问答                       · 简历 AI 评分（→ ResumeAnalysisResponseDTO）
    · 面试出题 + 技能工具调用                · 面试回答评估（→ EvaluationDTO）
    · 语音面试（Voice 变体）                 · JD 解析（→ JdParseResult）
    · 普通聊天                              · 任何需要 Strict JSON 的场景
  
  特征：                                 特征：
    ✅ SkillsTool 注册                     ❌ 无工具注册（保证 JSON 纯净）
    ✅ ToolCallAdvisor                     ❌ 无 ToolCallAdvisor
    ✅ Memory 对话记忆                      ❌ 无 Memory（避免历史污染 JSON）
    ✅ 5 个 Advisor 全链                    ✅ 2 个 Advisor（观测 + 安全）
```

