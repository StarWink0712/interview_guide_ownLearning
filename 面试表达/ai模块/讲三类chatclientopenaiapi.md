在创建chatmodel实例时，会传入retrytemplate，这个包括重试配置，2秒，指数，10次，最大时间3分钟。

<br />

在创建 `OpenAiChatModel` 时，我除了传入 `OpenAiApi` 和模型参数之外，还额外传了两个基础设施对象：`RetryTemplate` 和 `ObservationRegistry`。`RetryTemplate` 负责底层模型 API 调用失败时的自动重试，比如网络超时、连接异常、Provider 返回 5xx 这类临时故障。它不是处理 JSON 解析失败的，而是处理真正访问模型服务失败的场景。当前我复刻了 Spring AI 默认重试策略，最多是 1 次初始请求加 10 次重试，并且在 RetryTemplate 上挂了 `RetryListener`，用于记录真实 retry 次数和失败异常类型。并且后续还可以做更高级的熔断处理流程，比如切换到备用provider，同时上下文是业务层自己管理的，不需要担心切换provider时的上下文问题。

`ObservationRegistry` 是 Micrometer 的观测入口，用来把 Spring AI 的模型调用纳入链路追踪和指标体系。如果 Spring 容器里有 `ObservationRegistry`，模型调用过程就可以被观测；如果没有，就使用 `ObservationRegistry.NOOP`，保证监控缺失时也不会影响业务调用。也就是说，这两个参数一个解决调用可靠性，一个解决调用可观测性：`RetryTemplate` 让临时失败有机会自动恢复，`ObservationRegistry` 让模型调用的耗时、成功失败和链路信息能被监控系统采集。

<br />

retrylistener监听器在调用失败时和准备重试时都会触发各自的回调方法，记录下重试次数和失败次数到内存中，同时包括标签信息，provider，model，clientvariant（是llm还是embedding）这样前端就可以实时监控，当前内存中还存在的调用的一个完整的llm的重试和失败次数情况，但是重试次数和失败重试基本上重合了，保留一个就够了一般。

<br />

`RetryListener` 主要用来记录 RetryTemplate 层面的重试行为。它不是记录一次完整 LLM 调用结果的，而是记录底层访问模型服务失败后，RetryTemplate 触发了多少次重试。`beforeRetry` 会在每次准备重试前触发，我在这里把当前调用上下文里的 `retryCount` 加 1，并写入 Micrometer 的 `app.ai.provider.retry.attempts` 指标，标签包括 provider、model、clientVariant 和 operation。`onRetryFailure` 会在某一次 retry 请求执行后仍然失败时触发，我在这里记录 `app.ai.provider.retry.failures` 指标，并额外带上 exception 类型。

这两个指标都在 Micrometer 的 MeterRegistry 中，是聚合型实时指标，不是单次调用日志。单次调用最终的 `retryCount` 会随着 `AiProviderObservationAdvisor` 写入 `ai_provider_invocation_logs`；每一次真实 HTTP 请求的状态码和成功失败，则由 `AiProviderHttpObservationInterceptor` 写入 `ai_provider_attempt_logs`。所以排查单次问题时看数据库日志，观察整体趋势和告警时看 Micrometer / Prometheus 指标。
