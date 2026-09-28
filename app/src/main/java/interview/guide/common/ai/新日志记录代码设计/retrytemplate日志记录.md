这段代码记录的不是“一次完整 LLM 调用成功/失败”，而是 **RetryTemplate 的重试行为指标**。

也就是说，它不写数据库表，只写 **Micrometer 内存指标**。

对应的是：

```java
metricsRecorder.recordRetryAttempt(...)
metricsRecorder.recordRetryFailure(...)
```

它们在 [AiProviderMetricsRecorder.java](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/observability/AiProviderMetricsRecorder.java:91) 里，记录到 Micrometer：

```text
app.ai.provider.retry.attempts
app.ai.provider.retry.failures
```

**beforeRetry 记录什么**

```java
public void beforeRetry(...) {
    AiProviderObservationContext.Scope scope = AiProviderObservationContext.current();
    if (scope != null) {
        scope.recordRetryAttempt();
    }
    metricsRecorder.recordRetryAttempt(providerId, model, clientVariant, operation);
}
```

它在“准备进行一次 retry 之前”触发。

比如一次 LLM 调用：

```text
第 1 次请求失败
准备第 2 次请求 -> beforeRetry 触发一次
第 2 次请求失败
准备第 3 次请求 -> beforeRetry 再触发一次
第 3 次请求成功
```

那么：

```text
retryCount = 2
app.ai.provider.retry.attempts +2
```

这里的 `scope.recordRetryAttempt()` 是把当前这次 invocation 的 retry 数加 1，最后会写到：

```text
ai_provider_invocation_logs.retry_count
```

但是 `metricsRecorder.recordRetryAttempt(...)` 本身写的是 Micrometer 指标，不写数据库。

**onRetryFailure 记录什么**

```java
public void onRetryFailure(..., Throwable throwable) {
    metricsRecorder.recordRetryFailure(..., exceptionType);
}
```

它在“某一次 retry 执行了，但 retry 这次仍然失败”后触发。

比如：

```text
第 1 次请求失败
准备 retry #1
第 2 次请求失败 -> onRetryFailure 触发一次

准备 retry #2
第 3 次请求成功 -> onRetryFailure 不触发
```

所以这个例子里：

```text
retry.attempts +2
retry.failures +1
```

因为第二次 retry 最终成功了，所以不会记 retry failure。

**那每一次 HTTP 失败记录在哪里？**

每一次真实 HTTP 请求，包括失败和成功，不是这段 RetryListener 记录的，而是：

```text
AiProviderHttpObservationInterceptor
```

它会写数据库：

```text
ai_provider_attempt_logs
```

比如一次 LLM 调用：

```text
第 1 次 HTTP 请求 -> 500
第 2 次 HTTP 请求 -> 500
第 3 次 HTTP 请求 -> 200
```

那么会写 3 条 attempt 日志：

```text
attempt_no=1, http_status=500, success=false
attempt_no=2, http_status=500, success=false
attempt_no=3, http_status=200, success=true
```

这些才是“每一次包括失败的 HTTP 调用记录”。

**那一次完整 LLM 调用记录在哪里？**

一次完整 LLM 调用最终成功或失败，是：

```text
AiProviderObservationAdvisor
```

记录到数据库：

```text
ai_provider_invocation_logs
```

比如上面这个例子，最终第三次成功，那么 invocation 表只有一条：

```text
success=true
attempt_count=3
retry_count=2
latency_ms=总耗时
```

所以三层关系是：

```text
RetryListener
  记录 retry 行为指标
  -> Micrometer
  -> app.ai.provider.retry.attempts
  -> app.ai.provider.retry.failures

HttpObservationInterceptor
  记录每一次真实 HTTP 请求
  -> 数据库 ai_provider_attempt_logs
  -> Micrometer app.ai.provider.http.attempts / latency

ObservationAdvisor
  记录一次完整 LLM 逻辑调用最终结果
  -> 数据库 ai_provider_invocation_logs
  -> Micrometer app.ai.provider.invocations / latency
```

所以回答你的问题：

> 是不是记录一次完整的成功 LLM 调用？

不是。`beforeRetry` / `onRetryFailure` 只记录“重试过程中的行为指标”。

> 如果一次 LLM 调用了多次，因为前面每次都失败了，会不会记录每一次失败？

会，但不是这段代码完整记录。完整记录分两处：

```text
每一次 HTTP 请求成功/失败
  -> AiProviderHttpObservationInterceptor
  -> ai_provider_attempt_logs

这次 LLM 调用最终成功/失败
  -> AiProviderObservationAdvisor
  -> ai_provider_invocation_logs

每次准备 retry、retry 后仍失败的次数
  -> RetryListener
  -> Micrometer retry 指标
```

你面试里可以这样讲：

> `RetryListener` 不是记录完整 LLM 调用日志的，它只记录 RetryTemplate 的重试行为。`beforeRetry` 在每次准备重试前触发，用来累加当前 invocation 的 retryCount，并写入 Micrometer 的 retry attempt 指标；`onRetryFailure` 在某次 retry 执行后仍然失败时触发，用来记录 retry 失败的异常类型。每一次真实 HTTP 请求的成功或失败由 RestClient 的 `AiProviderHttpObservationInterceptor` 写入 attempt 表；一次完整 LLM 调用的最终成功或失败由 `AiProviderObservationAdvisor` 写入 invocation 表。这样就能同时看到“重试了几次”“每次 HTTP 请求状态是什么”“最终这次 LLM 调用有没有成功”。