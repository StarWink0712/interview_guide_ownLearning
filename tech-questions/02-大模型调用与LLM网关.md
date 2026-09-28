# 大模型调用与 LLM 网关

## 1. 面试官：为什么项目需要统一 LLM 网关，而不是各 Service 直接调用模型？

我的回答：

LLM 网关的价值是把 Provider 差异、配置、重试、观测和客户端生命周期从业务代码中抽离。我的项目通过 `LlmProviderRegistry` 暴露默认、指定 Provider、plain 和 voice 等 ChatClient 获取方法，业务只表达使用哪个 providerId，不重复拼 baseUrl、apiKey、model、Advisor 与 HTTP 客户端。Registry 还缓存 ChatClient 和 EmbeddingModel，配置更新时清理缓存。这样简历、出题、RAG 和语音可以共享治理策略。当前网关是应用内组件，不是独立网络服务，也没有自动跨 Provider 故障转移；生产上可在其上增加健康评分、熔断、成本路由和配额隔离。

代码依据：

- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java` — `getChatClientOrDefault()`
- `app/src/main/java/interview/guide/modules/resume/service/ResumeGradingService.java` — `analyzeResume()`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java` — LLM 调用入口

## 2. 面试官：多 Provider 配置如何加载、切换和热更新？

我的回答：

多 Provider 的核心是运行时解析，而不是把模型写死在 Service。我的项目以数据库中的 `LlmProviderEntity` 和全局默认设置为主，启动时由 Bootstrap 初始化预设；Registry 读取配置快照，缺失时兼容 `application.yml`，并分别解析默认聊天与默认 embedding Provider。管理接口可新增、修改、测试和切换默认项，保存后触发 Registry reload，清理对应客户端缓存，使后续请求使用新配置。当前切换只影响新请求，正在进行的流不会迁移，ASR 已建立连接也不会自动重启。生产上应采用配置版本、灰度比例和回滚记录。

代码依据：

- `app/src/main/java/interview/guide/modules/llmprovider/service/LlmProviderConfigService.java` — Provider CRUD
- `app/src/main/java/interview/guide/modules/llmprovider/service/LlmProviderBootstrapService.java` — 初始化
- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java` — `reloadProvider()`
- `app/src/main/resources/application.yml` — `app.ai.providers`

## 3. 面试官：ChatClient 在项目中起什么作用，为什么分成 default、plain 和 voice？

我的回答：

`ChatClient` 是 Spring AI 对 Prompt、模型调用、流式响应和 Advisor 链的高层封装。我的项目的 default 客户端可挂载 SkillsTool、日志、工具调用和可选记忆，适合普通对话；plain 客户端刻意不带工具和会话记忆，用于结构化 JSON，避免工具消息破坏格式；voice 客户端面向语音语义，历史由业务从数据库手工组装，防止 Advisor 记忆重复。三种变体共享同一 Provider 底层配置，却使用不同缓存键。这样调用意图明确，代价是 Registry 复杂度增加，需要持续验证三种客户端的超时、观测和模型参数一致。

代码依据：

- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java` — `getDefaultChatClient()`
- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java` — `getPlainChatClient()`
- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java` — `getVoiceChatClient()`
- `app/src/main/java/interview/guide/modules/interview/adaptive/TurnEvaluationService.java` — plain 客户端使用

## 4. 面试官：OpenAI 兼容 Provider 的 base URL 为什么仍然容易出错？

我的回答：

所谓 OpenAI 兼容通常只保证接口形状接近，不保证 base URL 是否已经包含 `/v1`、`/openai` 或具体资源路径。如果客户端再自动追加，就会出现重复版本段或 404。我的项目使用 `ApiPathResolver` 规范化 URL，Registry 创建 ChatClient、EmbeddingModel 和测试客户端时共同使用，并通过本地 HTTP 集成测试验证不同路径组合。这样支持 DashScope、DeepSeek、GLM、Kimi 与 LM Studio 等配置。当前解决的是路径拼接，不代表各家流式格式、错误码、tool calling 和 token 统计完全一致；新增 Provider 仍需契约测试。

代码依据：

- `app/src/main/java/interview/guide/common/ai/ApiPathResolver.java` — URL 解析
- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java` — 客户端创建
- `app/src/test/java/interview/guide/common/ai/ApiPathResolverTest.java` — 路径单测
- `app/src/test/java/interview/guide/common/ai/LlmProviderRegistryPathIntegrationTest.java` — HTTP 集成测试

## 5. 面试官：Provider 网络重试和结构化输出重试有什么区别？

我的回答：

网络重试处理的是请求未可靠完成，例如连接错误、限流或暂时性服务异常；结构化重试处理的是模型已经返回文本，但 JSON 不符合目标类型。我的项目由 `AiProviderRetryTemplateFactory` 为底层 Provider 调用设置退避并记录 retry，HTTP interceptor 记录每次 attempt；`StructuredOutputInvoker` 则根据解析错误构造修复 Prompt，再发起新的逻辑尝试。两者分开后，监控能区分传输故障和内容质量问题，也能避免所有错误盲目重试。当前最大重试策略仍需结合费用与幂等性调整，鉴权失败和明显参数错误不应重试，流式响应中途失败也不能简单从头拼接。

代码依据：

- `app/src/main/java/interview/guide/common/ai/AiProviderRetryTemplateFactory.java` — `create()`
- `app/src/main/java/interview/guide/common/ai/AiProviderHttpObservationInterceptor.java` — attempt 记录
- `app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java` — `invoke()`

## 6. 面试官：模型超时、限流或不可用时，项目如何降级？

我的回答：

降级应保持核心业务可继续，同时不能把故障伪装成成功。我的项目在不同场景采用不同策略：问题生成失败时使用另一并行分支或本地通用题；RAG query rewrite 失败就用原问题；自适应决策失败走分数规则；异步报告失败由 Stream 重试并暴露任务状态。全局异常处理还能区分超时、401 和 429。当前语音 LLM 会把错误转换为可读回复，简历评分会生成零分结果，这会弱化失败语义。生产上我会增加 Provider 熔断、备用模型、超时预算和明确的 degraded/failed 状态，而不是一律返回文本。

代码依据：

- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java` — 出题降级
- `app/src/main/java/interview/guide/modules/interview/adaptive/AdaptiveDecisionService.java` — 规则兜底
- `app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java` — AI 异常映射
- `app/src/main/java/interview/guide/modules/voiceinterview/service/DashscopeLlmService.java` — 当前错误文本

## 7. 面试官：项目如何管理上下文窗口，避免历史和材料无限增长？

我的回答：

上下文窗口有限，过长会增加费用、延迟并稀释关键指令。我的项目没有把所有历史无界发送：RAG Chat 只读取最近已完成消息并排除当前问题；面试历史题最多取 60 道用于去重；统一评估会截断简历和参考资料，并按默认 8 题分批；RAG 只拼接 topK 命中片段；语音历史从数据库按会话装配。这样在可控 token 内保留近期与高相关信息。当前还没有统一 token 预算器，部分限制依赖字符数而非模型 tokenizer。生产上我会按 system、history、retrieval、output 分配预算，并对旧历史做摘要。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/RagChatSessionService.java` — 最近历史加载
- `app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java` — `MAX_HISTORICAL_QUESTIONS`
- `app/src/main/java/interview/guide/common/evaluation/UnifiedEvaluationService.java` — 分批与截断
- `app/src/main/java/interview/guide/modules/voiceinterview/service/DashscopeLlmService.java` — 历史装配

## 8. 面试官：Token、延迟和成本在项目中如何统计？

我的回答：

模型治理至少需要调用量、延迟、重试、token 与成本。我的项目已经通过 Advisor 和 HTTP 拦截器记录逻辑 invocation 与真实 attempt，Micrometer Recorder 统计 Provider、模型、场景、状态和耗时，数据库明细可按 trace 查询。部分响应对象能够取得 usage 时可记录 token，但当前没有看到统一、可靠的输入输出 token 归集，也没有按 Provider 价格表计算成本；管理摘要主要展示次数、成功率和延迟。因此我会把“成本统计”描述为未完整实现。生产上应保存模型版本、prompt/completion token、缓存命中和单价快照，再按业务场景汇总预算。

代码依据：

- `app/src/main/java/interview/guide/common/ai/AiProviderObservationAdvisor.java` — 逻辑调用观测
- `app/src/main/java/interview/guide/common/observability/AiProviderMetricsRecorder.java` — Provider 指标
- `app/src/main/java/interview/guide/modules/aiobservability/model/AiProviderInvocationLogEntity.java` — 调用明细
- `app/src/main/java/interview/guide/modules/monitoring/service/BusinessMetricsQueryService.java` — 管理摘要

## 9. 面试官：如何降低模型幻觉，项目已经做了哪些处理？

我的回答：

幻觉是模型在缺少可靠证据时仍生成看似合理内容，不能仅靠降低 temperature 解决。我的项目在 RAG 中用知识库 filter、最低相似度和无命中提示约束回答，并把检索内容包在安全边界中；面试评分注入技能参考资料与固定 rubric；结构化场景使用类型转换和字段归一化；生成题失败还有本地兜底。当前没有统一的事实引用、答案级 faithfulness 检测或双模型复核，RAG 流式无答案判断还依赖前 120 字文本规则。生产上我会要求答案携带 chunk 引用，增加 answerable 门控、事实核验和离线忠实度评测。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java` — RAG 生成与无结果探测
- `app/src/main/java/interview/guide/common/ai/PromptSecurityConstants.java` — 上下文安全约束
- `app/src/main/java/interview/guide/common/evaluation/UnifiedEvaluationService.java` — 参考资料评分
- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java` — 本地兜底

## 10. 面试官：如果需要模型路由和故障转移，当前网关还缺什么？

我的回答：

真正的模型路由要根据健康、延迟、成本、能力和租户策略动态选路，并在安全条件下故障转移。我的项目已经能按请求选择 providerId、设置默认聊天与 embedding Provider、测试连通性并热刷新客户端，但一次调用失败后不会自动换 Provider；配置中也没有熔断状态、实时健康分、权重或预算。结构化输出切换模型还可能产生口径变化，Embedding 更不能随意切换维度。生产上我会为每种能力建立候选池，用连续失败熔断、半开探测和超时预算路由，同时固定 embedding 索引版本，记录 fallback 链，避免无声改变业务结果。

代码依据：

- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java` — Provider 解析
- `app/src/main/java/interview/guide/modules/llmprovider/service/LlmProviderConfigService.java` — `testProvider()`
- `app/src/main/java/interview/guide/modules/llmprovider/model/LlmGlobalSettingEntity.java` — 默认 Provider
- `app/src/main/resources/application.yml` — Provider 能力配置

# 本章总结

LLM 网关统一了 Provider 配置、ChatClient 变体、URL 兼容、重试和观测，使业务场景不直接耦合模型厂商。上下文、降级和幻觉控制则由各场景按语义补充。

当前已经支持多 Provider 与动态切换，但自动路由、跨 Provider 容灾、统一 token 成本和答案级事实评测仍属于生产化能力。

# 核心主线

把模型差异和治理集中在统一 Registry 中，让业务只表达调用意图，并对失败、成本和质量保持可观测。
