这里确实是对 `OpenAiApi` 的创建过程做了改造，但要说准确一点：**不是** **`OpenAiApi`** **自己记录日志和指标，而是在创建** **`OpenAiApi`** **时，把两个观测组件塞进它内部使用的 HTTP Client 里**。

这段代码里涉及两个 HTTP 客户端：

```java
RestClient.Builder restClientBuilder = RestClient.builder()
    .requestFactory(requestFactory);

WebClient.Builder webClientBuilder = WebClient.builder();
```

它们分别负责不同调用方式：

```text
普通非流式 call()
  -> OpenAiApi 内部使用 RestClient
  -> 走 AiProviderHttpObservationInterceptor

流式 stream()
  -> OpenAiApi 内部使用 WebClient
  -> 走 AiProviderWebClientObservationFilter
```

**一、普通 call() 谁记录日志和指标**

普通调用链路是：

```text
chatClient.prompt().call()
  -> OpenAiChatModel.call()
  -> RetryTemplate.execute(...)
  -> OpenAiApi.chatCompletionEntity(...)
  -> RestClient.post()
  -> AiProviderHttpObservationInterceptor.intercept()
  -> LLM Provider
```

这段代码：

```java
if (requestInterceptor != null) {
  restClientBuilder.requestInterceptor(requestInterceptor);
}
```

把 `AiProviderHttpObservationInterceptor` 注册到了 `RestClient` 上。

这个 interceptor 是在 `LlmProviderRegistry.buildChatModel(...)` 里创建的：

```java
requestInterceptor = new AiProviderHttpObservationInterceptor(
    aiProviderObservationLogService,
    aiProviderMetricsRecorder,
    providerId,
    config.model(),
    clientVariant,
    OPERATION_CHAT
);
```

所以普通 call() 的每一次真实 HTTP 请求，都是它记录的。

它记录两类东西。

第一类，写 Micrometer 指标：

```java
metricsRecorder.recordHttpAttempt(...)
```

对应指标：

```text
app.ai.provider.http.attempts
app.ai.provider.http.latency
```

标签包括：

```text
provider
model
client_variant
operation
status
http_status
retryable
```

第二类，写数据库 attempt 表：

```java
logService.recordAttempt(new AiProviderAttemptLogCreateRequest(...))
```

写入：

```text
ai_provider_attempt_logs
```

字段包括：

```text
invocationId
providerId
model
clientVariant
operation
attemptNo
httpMethod
uri
httpStatus
success
retryable
latencyMs
errorCategory
exceptionType
errorMessage
```

比如一次调用先 500 再成功，会写：

```text
attempt_no=1, http_status=500, success=false
attempt_no=2, http_status=200, success=true
```

所以普通 call() 的底层 HTTP 日志和指标是：

```text
AiProviderHttpObservationInterceptor 记录的
```

**二、流式 stream() 谁记录日志和指标**

流式调用链路是：

```text
chatClient.prompt().stream()
  -> OpenAiChatModel.stream()
  -> OpenAiApi.chatCompletionStream(...)
  -> WebClient.post()
  -> AiProviderWebClientObservationFilter.filter()
  -> LLM Provider stream API
```

这段代码：

```java
if (exchangeFilter != null) {
  webClientBuilder.filter(exchangeFilter);
}
```

把 `AiProviderWebClientObservationFilter` 注册到了 `WebClient` 上。

这个 filter 也是在 `LlmProviderRegistry.buildChatModel(...)` 里创建的：

```java
exchangeFilter = new AiProviderWebClientObservationFilter(
    aiProviderObservationLogService,
    aiProviderMetricsRecorder,
    providerId,
    config.model(),
    clientVariant,
    OPERATION_CHAT
);
```

它记录流式 HTTP 请求的 attempt。

它也记录两类东西。

第一类，Micrometer 指标：

```java
metricsRecorder.recordHttpAttempt(...)
```

指标同样是：

```text
app.ai.provider.http.attempts
app.ai.provider.http.latency
```

第二类，数据库 attempt 表：

```java
logService.recordAttempt(new AiProviderAttemptLogCreateRequest(...))
```

写入同一张表：

```text
ai_provider_attempt_logs
```

也就是说，普通 call 和流式 stream 的 HTTP attempt 最终进入同一套指标和同一张 attempt 表，只是入口不同：

```text
call()   -> RestClient Interceptor
stream() -> WebClient Filter
```

**三、那完整 LLM 调用日志是谁记录的**

这段 `buildOpenAiApi(...)` 只解决“底层 HTTP attempt”的记录。

一次完整 LLM 调用的最终结果，不是在这里记录的，而是在 `ChatClient` 层的：

```text
AiProviderObservationAdvisor
```

它是在创建 ChatClient 的时候添加到 `defaultAdvisors(...)` 里的。

它写：

```text
ai_provider_invocation_logs
app.ai.provider.invocations
app.ai.provider.invocation.latency
```

所以三者分工是：

```text
AiProviderObservationAdvisor
  记录一次完整 ChatClient 调用的最终结果
  -> ai_provider_invocation_logs
  -> app.ai.provider.invocations
  -> app.ai.provider.invocation.latency

AiProviderHttpObservationInterceptor
  记录普通 call() 的每次 HTTP 请求
  -> ai_provider_attempt_logs
  -> app.ai.provider.http.attempts
  -> app.ai.provider.http.latency

AiProviderWebClientObservationFilter
  记录流式 stream() 的每次 HTTP 请求
  -> ai_provider_attempt_logs
  -> app.ai.provider.http.attempts
  -> app.ai.provider.http.latency
```

**四、这段 OpenAiApi 改造的核心意义**

你可以这样讲：

> 我改造 `OpenAiApi` 构建过程，不是让 `OpenAiApi` 本身记录日志，而是在创建 `OpenAiApi` 时，把观测组件挂到它底层使用的两个 HTTP Client 上。Spring AI 的普通 `call()` 最终走 `RestClient`，所以我给 `RestClient.Builder` 注册了 `AiProviderHttpObservationInterceptor`，用来记录每一次真实 HTTP 请求的状态码、耗时、是否成功、是否可重试，并写入 attempt 表和 Micrometer HTTP 指标。Spring AI 的流式 `stream()` 最终走 `WebClient`，所以我给 `WebClient.Builder` 注册了 `AiProviderWebClientObservationFilter`，用来记录流式请求的 HTTP attempt。这样无论是普通文本调用还是语音面试流式调用，都能在底层 HTTP 层拿到真实的 Provider 调用日志和指标。

更短一点可以背：

> `OpenAiApi` 这里的改造主要是把观测能力下沉到 HTTP Client 层。普通 `call()` 走 `RestClient`，所以由 `AiProviderHttpObservationInterceptor` 记录每次 HTTP attempt；流式 `stream()` 走 `WebClient`，所以由 `AiProviderWebClientObservationFilter` 记录流式 HTTP attempt。它们都会写 `ai_provider_attempt_logs`，并上报 `app.ai.provider.http.attempts` 和 `app.ai.provider.http.latency`。而一次完整 LLM 调用的最终成功失败，仍然由 ChatClient 层的 `AiProviderObservationAdvisor` 写入 invocation 表。

> `OpenAiApi` 本身是 Spring AI 对 OpenAI-compatible 接口的封装，它底层真正发 HTTP 请求的是 `RestClient` 和 `WebClient`。普通非流式 `call()` 走 `RestClient`，流式 `stream()` 走 `WebClient`。所以我在构建 `OpenAiApi` 时，把自定义的 `AiProviderHttpObservationInterceptor` 挂到 `RestClient` 上，把 `AiProviderWebClientObservationFilter` 挂到 `WebClient` 上。这样每次真正访问模型 Provider 的 HTTP 请求都会被拦截到，从而记录状态码、耗时、是否成功、是否可重试、异常类型等信息，并写入 attempt 日志和 Micrometer 指标。

