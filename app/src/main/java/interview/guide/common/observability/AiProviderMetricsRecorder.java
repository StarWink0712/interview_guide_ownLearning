package interview.guide.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Provider 级 AI Micrometer 指标记录器。
 *
 * <p>功能概述：
 * 记录真实 LLM Provider 调用的聚合指标，用于 Prometheus/Grafana 监控大盘。
 * 它和数据库日志的区别是：
 * <ul>
 *   <li>Micrometer 指标：适合看趋势、成功率、延迟分布和告警</li>
 *   <li>数据库日志：适合查某一次调用为什么失败、每次 attempt 的状态码是什么</li>
 * </ul>
 *
 * <p>指标分层：
 * <ul>
 *   <li>HTTP attempt 指标：每一次真实 HTTP 请求都会记录，包括 RetryTemplate 重试后的请求</li>
 *   <li>Retry 指标：每一次 Spring AI RetryTemplate 准备重试时记录</li>
 *   <li>Invocation 指标：一次 ChatClient 逻辑调用结束后记录最终结果</li>
 * </ul>
 *
 * <p>设计边界：
 * 本类不记录 StructuredOutputInvoker 的 JSON 解析失败。JSON 解析失败属于模型输出格式质量，
 * 不等同于 Provider 网络/API 调用失败。
 */
@Component
public class AiProviderMetricsRecorder {

  private static final String METRIC_HTTP_ATTEMPTS = "app.ai.provider.http.attempts";
  private static final String METRIC_HTTP_LATENCY = "app.ai.provider.http.latency";
  private static final String METRIC_RETRY_ATTEMPTS = "app.ai.provider.retry.attempts";
  private static final String METRIC_RETRY_FAILURES = "app.ai.provider.retry.failures";
  private static final String METRIC_INVOCATIONS = "app.ai.provider.invocations";
  private static final String METRIC_INVOCATION_LATENCY = "app.ai.provider.invocation.latency";

  private final MeterRegistry meterRegistry;

  /**
   * MeterRegistry 使用可选注入。
   *
   * <p>如果测试环境或轻量部署环境没有启用 Micrometer，本 Recorder 会自动 no-op，
   * 不影响 LLM 主调用链路。
   */
  public AiProviderMetricsRecorder(@Autowired(required = false) MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /**
   * 记录一次真实 HTTP attempt。
   *
   * <p>调用来源：
   * <ul>
   *   <li>AiProviderHttpObservationInterceptor：普通非流式 call()</li>
   *   <li>AiProviderWebClientObservationFilter：流式 stream()</li>
   * </ul>
   *
   * <p>AI_PROVIDER_OBSERVABILITY: 这里记录的是 Provider HTTP 请求，不是 JSON 解析尝试。
   */
  public void recordHttpAttempt(
      String providerId,
      String model,
      String clientVariant,
      String operation,
      String status,
      String httpStatus,
      boolean retryable,
      long latencyNanos
  ) {
    if (meterRegistry == null) {
      return;
    }
    Tags tags = baseTags(providerId, model, clientVariant, operation)
        .and("status", status)
        .and("http_status", httpStatus)
        .and("retryable", Boolean.toString(retryable));
    meterRegistry.counter(METRIC_HTTP_ATTEMPTS, tags).increment();
    meterRegistry.timer(METRIC_HTTP_LATENCY, tags).record(latencyNanos, TimeUnit.NANOSECONDS);
  }

  /**
   * 记录一次 RetryTemplate 准备发起的 retry。
   *
   * <p>注意：这里不包含第一次初始请求，只统计失败后的重试行为。
   */
  public void recordRetryAttempt(
      String providerId,
      String model,
      String clientVariant,
      String operation
  ) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry.counter(
        METRIC_RETRY_ATTEMPTS,
        baseTags(providerId, model, clientVariant, operation)
    ).increment();
  }

  /**
   * 记录一次 retry attempt 仍然失败的异常类型。
   *
   * <p>这个指标用于回答：“重试之后失败主要是哪类异常导致的？”
   */
  public void recordRetryFailure(
      String providerId,
      String model,
      String clientVariant,
      String operation,
      String exceptionType
  ) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry.counter(
        METRIC_RETRY_FAILURES,
        baseTags(providerId, model, clientVariant, operation)
            .and("exception", safe(exceptionType))
    ).increment();
  }

  /**
   * 记录一次完整 ChatClient 调用的最终结果。
   *
   * <p>与 recordHttpAttempt 的区别：
   * <ul>
   *   <li>recordHttpAttempt：底层 HTTP 请求粒度，可能一条 invocation 对应多条 attempt</li>
   *   <li>recordInvocation：业务逻辑调用粒度，一次 ChatClient 调用最终只记录一次</li>
   * </ul>
   */
  public void recordInvocation(
      String providerId,
      String model,
      String clientVariant,
      String operation,
      boolean streaming,
      boolean success,
      int attemptCount,
      int retryCount,
      long latencyNanos
  ) {
    if (meterRegistry == null) {
      return;
    }
    Tags tags = baseTags(providerId, model, clientVariant, operation)
        .and("streaming", Boolean.toString(streaming))
        .and("status", success ? "success" : "failure")
        .and("attempt_count", Integer.toString(attemptCount))
        .and("retry_count", Integer.toString(retryCount));
    meterRegistry.counter(METRIC_INVOCATIONS, tags).increment();
    meterRegistry.timer(METRIC_INVOCATION_LATENCY, tags)
        .record(latencyNanos, TimeUnit.NANOSECONDS);
  }

  private Tags baseTags(String providerId, String model, String clientVariant, String operation) {
    return Tags.of(
        "provider", safe(providerId),
        "model", safe(model),
        "client_variant", safe(clientVariant),
        "operation", safe(operation)
    );
  }

  private String safe(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }
}
