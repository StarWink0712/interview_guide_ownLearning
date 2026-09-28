# LLM 调用日志与监控

## 1. LLM 调用日志为什么要分层设计？

**回答：** 一次业务层 LLM 调用内部可能因为 RetryTemplate 产生多次真实 HTTP 请求，只记录最终结果看不到每一次失败细节，只记录 HTTP 请求又看不到一次业务调用最终是否成功。因此项目分成 HTTP Attempt、Retry 和 ChatClient Invocation 三层：分别记录每次请求、重试行为和一次完整逻辑调用。

## 2. RestClient Interceptor 和 WebClient Filter 分别记录什么？

**回答：** 普通非流式 `call()` 走 RestClient，由 `AiProviderHttpObservationInterceptor` 记录；流式 `stream()` 走 WebClient，由 `AiProviderWebClientObservationFilter` 记录。它们会捕获每一次真实 HTTP 请求的 providerId、model、clientVariant、operation、attemptNo、状态码、单次耗时、成功状态、是否可重试、异常类型和错误分类，并写入 `ai_provider_attempt_logs` 以及 Micrometer HTTP 指标。

## 3. RetryListener 在监控链路中的位置是什么？

**回答：** RetryListener 挂在 ChatModel 使用的 RetryTemplate 上，位于 HTTP Attempt 和完整 ChatClient Invocation 之间。`beforeRetry` 记录准备重试的次数并累加当前 invocation 的 retryCount；`onRetryFailure` 记录某次 Retry 执行后仍失败的异常类型。它主要写入 Micrometer 重试指标，最终 retryCount 会随完整调用写入主表。

## 4. `AiProviderObservationAdvisor` 记录什么？

**回答：** 它注册在 ChatClient 上，负责一次完整 LLM 逻辑调用的最终结果，包括 traceId、invocationId、providerId、model、clientVariant、operation、是否流式、最终成功状态、总耗时、attemptCount、retryCount 和最终错误信息。这些信息写入 `ai_provider_invocation_logs` 主表，同时也可以写入 invocation 指标。

## 5. invocation 主表和 attempt 明细表如何关联？

**回答：** `ai_provider_invocation_logs` 表示一次完整业务调用，`ai_provider_attempt_logs` 表示这次调用下的每一次真实 HTTP 请求，两张表通过 invocationId 关联。一次调用即使重试十次也只有一个 invocationId，每次 HTTP 请求再通过 attemptNo 区分顺序。

## 6. 如何通过 invocationId 排查一次完整调用？

**回答：** 先在 invocation 主表查看本次调用的 Provider、模型、总耗时、最终状态、attemptCount、retryCount 和最终异常；如果需要下钻，再根据相同 invocationId 查询 Attempt 明细，查看每一次请求的状态码、耗时、异常类型以及是否可重试。这样既能看最终结果，也能还原中间每次失败过程。

## 7. WebSocket 和 WebClient 在语音面试中的职责有什么区别？

**回答：** WebSocket 是浏览器和项目后端之间的实时双向通道，用于上传用户音频、推送字幕以及下发 AI 文本和音频；WebClient 是后端调用模型 Provider 的流式 HTTP 客户端，用于持续接收 LLM 返回的 Token。两者不冲突，观测 Filter 挂在 WebClient 上是为了记录后端访问模型厂商的真实流式请求。

## 8. Micrometer 指标如何按 Provider、状态码和异常类型聚合？

**回答：** `recordHttpAttempt` 会记录 `app.ai.provider.http.attempts` Counter 和 `app.ai.provider.http.latency` Timer，标签包括 provider、model、client_variant、operation、status、http_status 和 retryable。Prometheus 或 Grafana 可以按 provider 和 http_status 聚合某个厂商的 5xx 比例，也可以按 retryable 筛选真正触发重试的请求；单次调用排查则以数据库日志为主。
