# ChatModel 重试与 ObservationRegistry

## 1. 创建 `OpenAiChatModel` 时传入了哪些基础设施对象？

**回答：** 除了 `OpenAiApi` 和模型参数以外，还会传入 `RetryTemplate` 和 `ObservationRegistry`。RetryTemplate 负责底层模型 API 调用的可靠性，ObservationRegistry 负责把 Spring AI 模型调用纳入 Micrometer 的指标和链路观测体系。

这里写的是错的，retrytemplate是在创建chatmodel时的设置，目的是配置重试策略，openaiapi底层只是挂载拦截器记录http状态到数据库中，。

## 2. `RetryTemplate` 处理什么类型的失败？

**回答：** RetryTemplate 处理真正访问模型服务时发生的临时故障，例如网络超时、连接异常以及 Provider 返回的 5xx。它不负责处理模型返回 JSON 后的解析失败，JSON 解析失败属于 `StructuredOutputInvoker` 的业务级重试场景。

## 3. 当前重试次数、退避时间和最大间隔是多少？

**回答：** 当前策略最多是 1 次初始请求加 10 次重试，第一次重试等待 2 秒，之后使用指数退避，倍数为 5，最大间隔为 3 分钟。它主要针对网络访问和服务端临时异常，不会对配置类 4xx 错误反复重试。

## 4. `ObservationRegistry` 有什么作用？

**回答：** `ObservationRegistry` 是 Micrometer 的观测入口，用于采集模型调用的耗时、成功失败状态和链路信息，让 Spring AI 的调用能够接入统一的监控与追踪体系。它解决的是调用可观测性问题，而 RetryTemplate 解决的是调用可靠性问题。

## 5. 没有监控组件时为什么使用 `ObservationRegistry.NOOP`？

**回答：** 如果 Spring 容器中存在 ObservationRegistry，就启用实际观测；如果没有，就传入 `ObservationRegistry.NOOP`。这样监控能力是可选的，监控组件缺失时不会阻断正常的 LLM 业务调用。

## 6. `RetryListener` 的两个回调分别记录什么？

**回答：** `beforeRetry` 在每次准备重试前触发，把当前调用上下文中的 `retryCount` 加一，并记录 `app.ai.provider.retry.attempts`；`onRetryFailure` 在某次 Retry 请求执行后仍然失败时触发，记录 `app.ai.provider.retry.failures`，同时带上异常类型。指标标签包括 provider、model、clientVariant 和 operation。

## 7. Micrometer 指标与数据库调用日志有什么区别？

**回答：** Micrometer 的 retry 指标保存在 MeterRegistry 中，是聚合型实时指标，适合通过 Prometheus 和 Grafana 查看趋势和告警；它不是某一次调用的单条日志。一次完整调用的 `retryCount` 会由 `AiProviderObservationAdvisor` 写入 `ai_provider_invocation_logs`，每一次真实 HTTP 请求则写入 `ai_provider_attempt_logs`，所以排查单次问题看数据库，观察整体趋势看 Micrometer 指标。
