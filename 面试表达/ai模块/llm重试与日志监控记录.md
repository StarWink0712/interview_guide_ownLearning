总结一下，我设计了详细的日志监控流程。在构建openaiapi底层时，通过将两个拦截器挂载到底层用的restclient和webclient时，当底层通过http与llm通信时（超时就会重连，时间设置的是2s），拦截器就可以记录下相关参数，包括providerid，model名称，http状态码，当前状态，重试状态等信息，然后这些信息会被存到数据库表中（即一次http请求），也会记录到内存中，让前端可以查询内存获取到当前的调用情况。

再往上一层是在chatmodel中设置的retrytemplate，配置了重试策略，包括重试次数和时间等，然后retrylistener会记录重试次数和失败次数到内存中（这里不是把http的重试次数等信息记录到数据库，而是记录到内存中）。再往上就是chatclient的设置，有一个拦截器，当一个llm完整调用完成时，会把重试的次数，最终是否成功，调用总耗时，平均耗时，成功率等信息也记录到数据库中。并且这个层级和openaiapi层级是父子层级，所以数据库表要设置一个主表存这个，明细表存每次http请求的信息，通过invocationid关联（全部的请求，失败了也都是用这个id） 。

这样完整的日志监控流程就完成了。即在openaiapi底层挂载的拦截器，是记录每次http请求信息到数据库，作为明细表；而chatclient层级的advisor拦截器，是记录最终请求结果到数据库，作为主表。而异步任务的重试在异步任务表中，json结构化解析重试是一次llm是成功的，只不过没有解析出来结构化数据，这个重试相当于一个异步任务内的重试，和llm调用http的重试还有异步任务的重试不同，这个是记录到一次异步任务内的记录，即再处理这个异步任务时，调用llm成功了但是没有解析成功再调用一次；而llm的调用信息是在llm调用的专门的表中的。

<br />

<br />

<br />

这里即provider级别的监控日志：/Users/ogawaaya/Desktop/学习/interview\_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/aiobservability/service/AiProviderObservationLogService.java

我设计了一套分层的 LLM 调用日志和监控流程。底层是在构建 `OpenAiApi` 时，把两个观测组件挂到它内部使用的 HTTP Client 上：普通非流式 `call()` 底层走 `RestClient`，所以挂 `AiProviderHttpObservationInterceptor`；流式 `stream()` 底层走 `WebClient`，所以挂 `AiProviderWebClientObservationFilter`。这样每一次真正访问 LLM Provider 的 HTTP 请求都会被捕获，记录 providerId、model、clientVariant、operation、attemptNo、HTTP 状态码、单次耗时、是否成功、是否可重试、异常类型和错误分类。这些数据一方面写入 `ai_provider_attempt_logs` 明细表，另一方面写入 Micrometer 指标，比如 `app.ai.provider.http.attempts` 和 `app.ai.provider.http.latency`。

中间一层是在创建 `OpenAiChatModel` 时传入自定义的 `RetryTemplate`。这个 `RetryTemplate` 里配置了 `RetryPolicy`，包括最多重试 10 次、第一次重试等待 2 秒、指数退避倍数 5、最大等待 180 秒，以及只对网络异常和 5xx 这类临时错误重试。同时我给它挂了 `RetryListener`，`beforeRetry` 会记录准备重试的次数，并累加当前 invocation 的 `retryCount`；`onRetryFailure` 会记录某次 retry 执行后仍然失败的异常类型。这部分主要写入 Micrometer 重试指标，最终的 `retryCount` 会随着完整调用写入主表。

最上层是在 `ChatClient` 里注册 `AiProviderObservationAdvisor`。它负责记录一次完整 LLM 逻辑调用的最终结果，比如 traceId、invocationId、providerId、model、clientVariant、operation、是否流式、最终是否成功、总耗时、attemptCount、retryCount 和最终错误信息。这些数据写入 `ai_provider_invocation_logs` 主表，同时也写入 Micrometer 的 invocation 指标。

数据库上我设计成一主一明细两张表：`ai_provider_invocation_logs` 表示一次完整 LLM 调用，`ai_provider_attempt_logs` 表示这次调用下面的每一次真实 HTTP 请求。两张表通过 `invocationId` 关联。也就是说，一次业务层 LLM 调用只有一个 `invocationId`，即使底层重试了 10 次，这些 HTTP attempt 也都使用同一个 `invocationId`，再通过 `attemptNo` 区分第几次请求。这样既可以在主表里快速看一次调用最终是否成功，也可以下钻到明细表看每次 HTTP 请求失败在哪里、状态码是什么、是否可重试。

查询时，前端可以通过 provider invocation 接口查看数据库里的单次调用和 attempt 明细；运维监控则可以通过 Micrometer/Prometheus/Grafana 查看整体趋势，比如 provider 失败率、HTTP 延迟、重试次数和异常类型分布。这样就把单次排障和整体监控两个需求都覆盖到了。

<br />

详情看    新日志记录代码设计 /Users/ogawaaya/Desktop/学习/interview\_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/aiobservability/日志监控修改/新方案.md。还有app/src/main/java/interview/guide/modules/aiobservability/analysis/模块功能.md

<br />

语音面试里确实用了 WebSocket，但 WebSocket 负责的是浏览器和我后端之间的实时双向通信，比如用户音频上传、字幕推送、AI 文本和音频下发。\
后端真正调用大模型时，走的是 Spring AI 的 ChatClient。同步调用底层一般走 RestClient，流式调用 `.stream()` 底层走 WebClient，因为它要持续接收模型返回的 token 流。\
所以 WebSocket 和 WebClient 不冲突：WebSocket 是前端到后端的实时通道，WebClient 是后端到模型厂商的流式 HTTP 客户端。我的观测 Filter 挂在 WebClient 上，是为了记录后端访问 LLM Provider 的真实 HTTP 流式请求，不需要改成 WebSocket。

<br />

<br />

<br />

写入 Micrometer 的 invocation 指标。其实这个可以选择不讲，核心讲记录到数据库日志中的llm调用日志就行。

在 [AiProviderMetricsRecorder](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/observability/AiProviderMetricsRecorder.java) 的 `recordHttpAttempt` 方法，由两个组件调用：

**非流式（RestClient）**：`AiProviderHttpObservationInterceptor` 第 117 行

```java
metricsRecorder.recordHttpAttempt(
    scope.providerId(), scope.model(), scope.clientVariant(), scope.operation(),
    status,           // "success" / "failure"
    statusBucket,     // httpStatus 分桶，如 "2xx"、"5xx"
    retryable,
    latencyNanos
);
```

**流式（WebClient）**：`AiProviderWebClientObservationFilter` 第 120 行，完全相同的调用。

`recordHttpAttempt` 内部往 MeterRegistry 注册两个指标：

```java
meterRegistry.counter(METRIC_HTTP_ATTEMPTS, tags).increment();       // app.ai.provider.http.attempts
meterRegistry.timer(METRIC_HTTP_LATENCY, tags).record(latencyNanos); // app.ai.provider.http.latency
```

每个指标带的 tag 包括：`provider`、`model`、`client_variant`、`operation`、`status`、`http_status`、`retryable`。所以 Grafana 里可以按 provider + http\_status 聚合看某个厂商的 5xx 比例，或按 retryable 过滤只看重试触发的请求。
