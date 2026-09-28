# 大模型网关、结构化输出与 Skills

## 1. 面试官：项目如何支持多个大模型 Provider，业务代码为什么不直接 new ChatClient？

我的回答：

我的项目通过 `LlmProviderRegistry` 统一创建和缓存 ChatClient、OpenAI API 与 EmbeddingModel。业务只传 providerId，Registry 先解析显式 Provider 或默认 Provider，再从数据库配置快照读取 baseUrl、apiKey、model 等信息，必要时回退到 `application.yml`。Provider 配置变更后会清理相关缓存，使后续请求使用新客户端。这样把 URL 兼容、重试、Advisor、工具和观测逻辑集中在网关，避免每个业务模块重复组装。当前切换发生在请求前，不是单次调用失败后的跨 Provider 自动故障转移；要做高可用还需增加健康度、熔断与路由策略。

代码依据：

- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java`
- `app/src/main/java/interview/guide/common/ai/ApiPathResolver.java`
- `app/src/main/java/interview/guide/modules/llmprovider/service/LlmProviderConfigService.java`
- `app/src/main/resources/application.yml`

## 2. 面试官：为什么 Registry 要提供 default、plain、voice 三种 ChatClient？

我的回答：

我这里按调用语义拆成三种客户端。默认客户端可以挂载工具调用、日志和可选记忆 Advisor，适合普通对话；plain 客户端刻意不注册 SkillsTool 和会话记忆，供 BeanOutputConverter 等结构化 JSON 场景使用，避免工具消息或历史上下文破坏输出格式；voice 客户端采用语音专用语义，历史由业务代码从数据库组装，防止 Advisor 记忆和手工历史重复。三者共享底层 Provider 配置和观测能力，但缓存键相互隔离。这个设计比到处临时关闭 Advisor 更稳定，代价是 Registry 复杂度较高，需要测试不同变体的配置一致性。

代码依据：

- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java`
- `app/src/main/java/interview/guide/modules/interview/adaptive/TurnEvaluationService.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/DashscopeLlmService.java`

## 3. 面试官：大模型返回的 JSON 不合法时，项目如何保证结构化输出可用？

我的回答：

我的项目不是直接 `ObjectMapper.readValue` 后就失败，而是用 `StructuredOutputInvoker` 包装 Spring AI 的 `BeanOutputConverter`。第一次调用失败后，它会记录解析错误，在配置允许时把截断后的错误原因和严格 JSON 指令加入修复提示词，再次请求模型；对字符串中未转义引号等特定问题还会尝试本地修复。所有尝试都记录耗时、成功状态和调用日志，最终仍失败才抛业务异常。这样把简历评分、问题生成、自适应评估的重试策略统一起来。边界是重试会增加成本和延迟，且本地修复不能替代 JSON Schema 约束，后续可优先使用 Provider 的原生 structured output。

代码依据：

- `app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java`
- `app/src/main/java/interview/guide/common/ai/StructuredOutputProperties.java`
- `app/src/test/java/interview/guide/common/ai/StructuredOutputInvokerTest.java`
- `app/src/test/java/interview/guide/common/ai/StructuredOutputInvokerTest.java`

## 4. 面试官：项目中的 Skills 是怎样参与面试出题的，是否已经实现完整 Agent？

我的回答：

我的项目已经实现技能资源加载，但没有完整的多 Agent 规划执行系统。一条路径是 `AgentUtilsConfiguration` 从 `classpath:skills` 注册 `SkillsTool`，让默认 ChatClient 具备工具能力；另一条更确定的路径是 `InterviewSkillService` 扫描各技能目录的 `SKILL.md`、`skill.meta.yml` 和 references，解析分类、权重与参考资料，`InterviewQuestionService` 再把选定内容直接注入出题提示词。后者保证题目与技能资料强绑定，不依赖模型是否主动调用工具。当前没有 Agent 状态机、任务规划器、工具审批或多 Agent 协作，所以我只会称它为 Skills/工具接入。

代码依据：

- `app/src/main/java/interview/guide/common/ai/AgentUtilsConfiguration.java`
- `app/src/main/java/interview/guide/modules/interview/skill/InterviewSkillService.java`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java`
- `app/src/main/resources/skills/`

## 5. 面试官：Provider 的动态配置和 API Key 是如何管理的？

我的回答：

我的项目提供 Provider CRUD、默认聊天/向量 Provider 切换和连通性测试。配置主路径落在数据库，启动时由 Bootstrap 服务初始化，Registry 读取后构建客户端；API Key 在返回 DTO 时只展示掩码，落库前由 `ApiKeyEncryptionService` 使用 AES-GCM 加密，密钥来自环境配置，并可通过 `require-encryption-key` 强制要求。兼容配置还可写到用户目录下的 YAML/env，而不是修改 jar 内 classpath 文件。当前开发默认允许加密密钥为空，这意味着若未显式开启强制模式，安全性不足；生产应注入稳定密钥、轮换凭据，并把配置接口置于管理员鉴权之后。

代码依据：

- `app/src/main/java/interview/guide/modules/llmprovider/service/ApiKeyEncryptionService.java`
- `app/src/main/java/interview/guide/modules/llmprovider/service/LlmProviderConfigService.java`
- `app/src/main/java/interview/guide/modules/llmprovider/service/LlmProviderBootstrapService.java`
- `app/src/main/resources/application.yml`

## 6. 面试官：项目如何处理 Prompt Injection，当前防护有什么边界？

我的回答：

我的项目对简历、JD、RAG 问题等用户输入先经过 `PromptSanitizer`，过滤常见角色标记、忽略指令语句和伪造边界标签，再用明确分隔符包裹；RAG 与日程解析提示词还追加固定安全约束，要求模型把检索内容视为数据而不是指令。这能降低直接提示词注入概率，并且观测日志尽量记录长度、哈希或预览，不应把完整敏感材料随意打日志。但这是规则型防护，存在绕过和误伤，不能当作授权机制。后续我会叠加输入输出分类、工具白名单、最小权限、敏感信息脱敏和红队测试。

代码依据：

- `app/src/main/java/interview/guide/common/ai/PromptSanitizer.java`
- `app/src/main/java/interview/guide/common/ai/PromptSecurityConstants.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java`
- `app/src/main/java/interview/guide/modules/interviewschedule/service/InterviewParseService.java`

## 7. 面试官：大模型调用的重试、观测与业务降级是怎样配合的？

我的回答：

我把三层行为分开：HTTP/Provider 层由 Registry 创建的 RetryTemplate 处理可重试错误，并通过拦截器记录每一次真实请求；Advisor 记录一次逻辑调用，避免把多次重试误算成多次业务请求；业务层的 `StructuredOutputInvoker` 只针对“模型返回了但结构解析失败”做修复重试。最终业务是否降级由场景决定，例如出题可以回退到本地通用题，RAG 查询改写失败就用原问题，语音 LLM 当前会返回可读错误文本。优点是故障域清晰，限制是缺少跨 Provider fallback，且语音把错误文本当正常回复继续持久化的语义不够严谨。

代码依据：

- `app/src/main/java/interview/guide/common/ai/AiProviderRetryTemplateFactory.java`
- `app/src/main/java/interview/guide/common/ai/AiProviderObservationAdvisor.java`
- `app/src/main/java/interview/guide/common/ai/AiProviderHttpObservationInterceptor.java`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/DashscopeLlmService.java`

# 本章总结

大模型能力不是散落在业务中的若干 API 调用，而是由 Registry 统一完成 Provider 解析、客户端变体、重试、工具和观测，再由结构化输出组件解决 JSON 可靠性。技能资料既可作为工具，也可被确定性地注入提示词。

项目已经支持多 Provider 配置与热刷新，但尚未实现跨 Provider 自动容灾和完整 Agent 编排；Prompt 防护也只是纵深防御的一层。

# 核心主线

以统一 AI 网关隔离模型差异，再按对话、结构化和语音场景选择受控的调用链。
