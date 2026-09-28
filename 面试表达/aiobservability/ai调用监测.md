我做了一个ai调用监控模块，当用格式化输出器调用ai时，记录每次调用的日志，一个主表记录一次完整的llm调用，一个次表记录这个完成的llm调用了几次中间失败的信息，这样可以获得每次调用了哪个模型，耗时多久，是否成功，重试了多少次等信息。

<br />

核心其实就四类日志记录，对应/Users/ogawaaya/Desktop/学习/interview\_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/aiobservability/service下面的四个service代码

<br />

重点还有RAG的知识库问答日志记录，主要有六个表，记录完整的RAG检索记录。

我在项目里做了一个 AI 可观测性模块，分两层记录：业务调用日志和 Provider 级观测日志，外加 RAG 检索链路追踪和离线评测。

业务调用日志由 `StructuredOutputInvoker` 在每次结构化调用 LLM 后写入，核心入口是 `AiInvocationLogService.recordInvocation(AiInvocationLogCreateRequest)`，入参 record 包含 traceId、scenario（如"简历分析"、"批次评估"）、providerId、model、promptVersion、streaming、structuredOutput、attemptCount（含首次+重试的总尝试次数）、success、latencyMs、errorCode、errorMessage 以及 systemPrompt 和 userPrompt 原文。方法内部先把 systemPrompt 和 userPrompt 拼接后计算 SHA-256 哈希存到 `promptHash` 字段（VARCHAR(64)），只存 hash 不存原文节省空间同时能做去重统计，再计算 `promptLength` 总字符数，错误消息超过 1000 字符自动截断防止撑爆 TEXT 字段，最后通过 `logRepository.save()` 写入 `ai_invocation_logs` 表。这个表有 BIGINT 自增主键 `id`，字段包括 `trace_id`(VARCHAR(64))、`scenario`(VARCHAR(100))、`provider_id`(VARCHAR(80))、`model`(VARCHAR(120))、`prompt_version`(VARCHAR(80))、`prompt_hash`(VARCHAR(64))、`prompt_length`(INT)、`streaming`(BOOLEAN)、`structured_output`(BOOLEAN)、`attempt_count`(INT)、`success`(BOOLEAN)、`latency_ms`(BIGINT)、`error_code`(VARCHAR(80))、`error_message`(TEXT)、`created_at`(DATETIME)，并在 `trace_id`、`scenario`、`created_at` 上建了三个索引。日志写入失败只打 warn 返回 Optional.empty()，保证日志功能不阻塞主业务。

这个其实是记录llm返回的信息，能不能很好的转换成DTO，其实没必要留，很多东西都重复了，保留一个provider级别的日志即可。

<br />

这个是核心，即llm调用时的日志记录，总结可以看面试表达/ai模块/llm重试与日志监控记录.md，核心就是记录一下每次调用llm时的信息，记录下traceid，失败次数，结果状态等等，记录底层是通过restclient的拦截器，拦截http请求进行记录，这是记录的每一次中间调用的结果，即次表，而主表，完整的chatclient调用结果 ，是由chatclient注册的advisor拦截器进行记录的。三者可以通过threadlocal共享traceid，重试次数等信息。

Provider 级观测日志更细粒度，拆成两张表由 `AiProviderObservationLogService` 管理：`ai_provider_invocation_logs` 记录一次 ChatClient 逻辑调用的最终结果（和业务日志类似但更偏底层，字段包括 traceId、invocationId、providerId、model、clientVariant 四种类型 DEFAULT/PLAIN/VOICE/EMBEDDING、operation 三种类型 CHAT/STREAM\_CHAT/EMBEDDING、streaming、startedAt、completedAt、latencyMs、success、attemptCount 底层 HTTP 请求次数、retryCount RetryTemplate 重试次数、errorCategory 如 provider\_5xx/client\_4xx/timeout/network、exceptionType 异常全限定类名、errorMessage），`ai_provider_attempt_logs` 记录底层的每一次真实 HTTP 请求（字段包括 invocationId 关联 invocation 表、providerId、model、clientVariant、operation、attemptNo 第几次请求从 1 开始、httpMethod、uri 截断到 500 字符、httpStatus、success、retryable 是否可重试、latencyMs、errorCategory、exceptionType、errorMessage）。一条 invocation 对应多条 attempt，通过 `invocation_id` 关联。写入时用 `TransactionTemplate` 设 `PROPAGATION_REQUIRES_NEW`，确保日志事务和业务事务完全隔离，日志写失败不回滚业务。数据来源是三个拦截器/Advisor：`AiProviderObservationAdvisor`（ChatClient Advisor 层，打开观测 Scope 并记录 invocation，最后一次完整的llm调用）、`AiProviderHttpObservationInterceptor`（RestClient 层，记录非流式 HTTP attempt，记录中间的http调用）、`AiProviderWebClientObservationFilter`（WebClient 层，记录流式 HTTP attempt），三者通过 `AiProviderObservationContext.Scope`（ThreadLocal）共享 traceId、invocationId、attemptCounter、retryCounter。

RAG 检索链路追踪也有两张表：`rag_retrieval_logs` 记录一次检索的完整参数和结果（traceId、sessionId、question 原始问题、rewrittenQuestion 查询改写结果、candidateQueriesJson 候选查询列表 JSON、knowledgeBaseIdsJson 查询的知识库 ID 列表、topK、minScore、hitCount 命中数、latencyMs），`rag_retrieved_chunk_logs` 记录每个命中文档块的详情（retrievalLogId 关联主表、knowledgeBaseId、chunkTextPreview 文本预览、score 相似度分数、rank 排名、metadataJson 块元信息），前端通过 `AiObservabilityController` 的 `/api/ai-observability/rag-traces/{traceId}` 和 `/api/ai-observability/rag-traces/recent` 接口查询。

实时指标方面，`AiProviderMetricsRecorder` 通过 Micrometer 向 `MeterRegistry` 注册 6 个指标：`app.ai.provider.http.attempts`（HTTP 请求计数器）、`app.ai.provider.http.latency`（HTTP 请求延迟）、`app.ai.provider.retry.attempts`（重试次数）、`app.ai.provider.retry.failures`（重试失败次数）、`app.ai.provider.invocations`（调用计数器）、`app.ai.provider.invocation.latency`（调用延迟），每个指标都带 provider/model/clientVariant/operation/success 等 tag，通过 `/actuator/prometheus` 暴露给 Prometheus，在 Grafana 里可以看调用量、成功率、P99 延迟、重试率等趋势。
