# 三类 ChatClient 与 Advisor 链

## 1. 为什么设计 Default、Plain、Voice 三种 ChatClient？

**回答：** 三种 ChatClient 是按照业务场景拆分的，避免所有能力都堆到同一个客户端上。Default 适合自然语言回答和工具型对话；Plain 面向严格结构化 JSON 输出，不注册工具和对话记忆；Voice 面向语音面试的实时多轮对话，保留 SkillsTool 和流式 ToolCallAdvisor，但历史上下文由业务层管理。

## 2. 三种 ChatClient 分别适用于哪些业务？

**回答：** Default 适合知识库 RAG 问答、简单问答、普通聊天和工具调用；Plain 用于出题、即时评分、下一题决策、简历分析、JD 解析、面试评估和日程解析等结构化输出场景；Voice 用于语音面试的实时对话和流式回复。

## 3. 文字面试出题为什么使用 Plain Client？

**回答：** 文字面试出题虽然使用 Skill，但不是让模型通过 SkillsTool 动态选择，而是由业务层读取 `SKILL.md`、`skill.meta.yml` 和 Reference，把 Persona、分类和参考资料确定性地拼入 Prompt。随后使用 Plain Client 要求模型直接返回结构化 JSON，避免工具调用或历史上下文污染 DTO 输出。

## 4. 语音面试为什么使用 Voice Client？

**回答：** 语音面试不是一次性生成 JSON，而是多轮自然对话，模型可以根据面试方向动态加载对应 Skill 的角色和追问规则。Voice Client 支持 SkillsTool 和流式 ToolCallAdvisor，LLM 可以边生成边向前端推送，并按句子触发 TTS，降低对话停顿感；它不使用 MessageChatMemoryAdvisor，因为历史上下文由语音业务自己管理。

## 5. Default ChatClient 注册了哪些 Advisor？

**回答：** Default ChatClient 会注册 SkillsTool，并挂载 `AiProviderObservationAdvisor`、`ToolCallAdvisor`、`MessageChatMemoryAdvisor`、`SimpleLoggerAdvisor` 和 `SafeGuardAdvisor`。这些 Advisor 分别负责完整调用观测、工具调用、对话记忆、请求响应日志和基础安全过滤，构成围绕 ChatClient 调用的责任链。

## 6. `AiProviderObservationAdvisor` 记录什么？

**回答：** 它在一次 ChatClient 逻辑调用开始时创建 invocationId 和调用上下文，在调用完成后记录 provider、model、clientVariant、最终成功状态、总耗时、attemptCount 和 retryCount 等信息。它记录的是一次完整逻辑调用的最终结果，不负责记录内部每一次 HTTP 请求。

## 7. `ToolCallAdvisor` 如何执行 SkillsTool？

**回答：** 当模型判断需要 `java-backend` 等 Skill 时，会返回结构化 Tool Call。ToolCallAdvisor 检测到调用后，通过 `ToolCallingManager` 执行注册在 ChatClient 上的 SkillsTool，读取对应 `SKILL.md` 内容，把 Tool Result 返回给模型，再让模型基于面试官角色和追问规则继续生成回答。只有模型真正返回 Tool Call 时，具体工具才会执行。

<br />

llm需要用到模型时，返回给服务端tool call，然后服务端再把skill的信息返回给llm，llm再基于这个信息生成答案

<br />

## 8. `MessageChatMemoryAdvisor` 如何维护上下文？

**回答：** 请求发送给模型前，它从 Memory 中读取历史消息并补入上下文；模型返回后，再把本轮用户输入和模型回复写入 Memory。这样下一轮普通对话或工具型对话能够知道之前聊过什么，消息窗口可以配置最大消息数，例如 `maxMessages=120`。

## 9. `SimpleLoggerAdvisor` 和 `SafeGuardAdvisor` 分别做什么？

**回答：** SimpleLoggerAdvisor 记录 LLM 请求和响应，主要用于开发阶段排查 Prompt、工具调用和模型输出，生产环境一般关闭。SafeGuardAdvisor 根据敏感词检查用户消息和模型回复，命中后拦截并返回固定拒绝文案；更细的 Prompt 注入防护由 PromptSanitizer 和 PromptSecurityConstants 完成。

## 10. Advisor 与 HTTP Interceptor 的职责有什么区别？

**回答：** Advisor 工作在 ChatClient 层，负责一次逻辑调用前后的增强，例如创建观测上下文、补充记忆、执行安全检查、处理 Tool Call 和记录最终结果。RestClient Interceptor 和 WebClient Filter 工作在 OpenAiApi 的 HTTP 层，记录每一次真实请求的 provider、model、attempt 序号、HTTP 状态码和单次耗时；一次逻辑调用内部重试三次，Advisor 记录一条最终结果，HTTP 层记录三条 Attempt。

## 11. ChatModel、ChatClient 和 OpenAiApi 的层级关系是什么？

**回答：** OpenAiApi 是最底层的 Provider HTTP 通信封装，包含 URL、API Key、连接超时和读取超时；ChatModel 位于它的上层，负责模型参数、temperature、RetryTemplate 和 ObservationRegistry；ChatClient 再基于 ChatModel 创建，并注册 Advisor 列表和 SkillsTool 工具回调，最终提供给业务层使用。
