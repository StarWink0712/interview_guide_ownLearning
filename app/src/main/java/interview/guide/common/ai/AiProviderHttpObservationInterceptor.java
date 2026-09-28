package interview.guide.common.ai;

import interview.guide.common.observability.AiProviderMetricsRecorder;
import interview.guide.modules.aiobservability.model.AiProviderAttemptLogCreateRequest;
import interview.guide.modules.aiobservability.model.AiProviderInvocationLogCreateRequest;
import interview.guide.modules.aiobservability.service.AiProviderObservationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * RestClient 层 Provider HTTP 调用观测拦截器。
 *
 * <p>功能概述：
 * Spring AI 的普通非流式聊天调用最终会进入 OpenAiApi.chatCompletionEntity，
 * 该方法使用 RestClient 发起 HTTP 请求。本拦截器挂在 RestClient.Builder 上，
 * 因此可以记录每一次真实发送到 LLM Provider 的 HTTP 请求。
 *
 * <p>为什么放在 RestClient 层：
 * <ul>
 *   <li>ChatClient 层只能知道一次逻辑调用成功或失败</li>
 *   <li>RetryTemplate 重试后会产生多次 HTTP 请求</li>
 *   <li>只有 RestClient interceptor 才能看到每一次底层 HTTP attempt 的状态码和耗时</li>
 * </ul>
 *
 * <p>记录内容：
 * <ul>
 *   <li>Micrometer：app.ai.provider.http.attempts / latency</li>
 *   <li>数据库：ai_provider_attempt_logs</li>
 * </ul>
 *
 * <p>特殊场景：
 * 正常 ChatClient 调用会先由 AiProviderObservationAdvisor 创建 Scope；
 * 如果未来有代码绕过 ChatClient 直接调用 OpenAiApi，本拦截器会创建 standalone Scope，
 * 保证底层 HTTP 请求仍然可观测。
 */
@RequiredArgsConstructor
public class AiProviderHttpObservationInterceptor implements ClientHttpRequestInterceptor {

  private final AiProviderObservationLogService logService;
  private final AiProviderMetricsRecorder metricsRecorder;
  private final String providerId;
  private final String model;
  private final String clientVariant;
  private final String operation;

  /**
   * 拦截并记录一次 RestClient HTTP 请求。
   *
   * <p>AI_PROVIDER_OBSERVABILITY: 每进入一次 intercept，就代表一次真实 HTTP attempt。
   * 如果 RetryTemplate 触发了 3 次底层请求，本方法也会执行 3 次。
   */
  @Override
  @NonNull
  public ClientHttpResponse intercept(
      @NonNull HttpRequest request,
      @NonNull byte[] body,
      @NonNull ClientHttpRequestExecution execution
  ) throws IOException {
    AiProviderObservationContext.Scope scope = AiProviderObservationContext.current();
    boolean standalone = false;
    if (scope == null) {
      scope = AiProviderObservationContext.open(null, providerId, model, clientVariant, operation, false);
      standalone = true;
    }

    int attemptNo = scope.nextAttemptNo();
    long startNanos = System.nanoTime();
    Throwable error = null;
    Integer httpStatus = null;
    boolean success = false;

    try {
      ClientHttpResponse response = execution.execute(request, body);
      httpStatus = response.getStatusCode().value();
      success = AiProviderObservationSupport.isHttpSuccess(httpStatus);
      return response;
    } catch (IOException | RuntimeException e) {
      error = e;
      throw e;
    } finally {
      recordAttempt(scope, attemptNo, request, httpStatus, success, error, startNanos);
      if (standalone) {
        recordStandaloneInvocation(scope, success, httpStatus, error);
        scope.close();
      }
    }
  }

  /**
   * 写入 attempt 级指标和数据库日志。
   *
   * <p>attempt 表用于复盘底层细节，例如：
   * 第 1 次请求 500，第 2 次请求 500，第 3 次请求 200。
   */
  private void recordAttempt(
      AiProviderObservationContext.Scope scope,
      int attemptNo,
      HttpRequest request,
      Integer httpStatus,
      boolean success,
      Throwable error,
      long startNanos
  ) {
    long latencyNanos = System.nanoTime() - startNanos;
    boolean retryable = error != null
        || (httpStatus != null && AiProviderObservationSupport.isRetryableHttpStatus(httpStatus));
    String status = success ? "success" : "failure";
    String statusBucket = AiProviderObservationSupport.httpStatusBucket(httpStatus);

    metricsRecorder.recordHttpAttempt(
        scope.providerId(),
        scope.model(),
        scope.clientVariant(),
        scope.operation(),
        status,
        statusBucket,
        retryable,
        latencyNanos
    );

    logService.recordAttempt(new AiProviderAttemptLogCreateRequest(
        scope.invocationId(),
        scope.providerId(),
        scope.model(),
        scope.clientVariant(),
        scope.operation(),
        attemptNo,
        request.getMethod().name(),
        request.getURI().toString(),
        httpStatus,
        success,
        retryable,
        latencyNanos / 1_000_000,
        AiProviderObservationSupport.errorCategory(httpStatus, error),
        AiProviderObservationSupport.exceptionType(error),
        AiProviderObservationSupport.errorMessage(error)
    ));
  }

  /**
   * 记录绕过 ChatClient Advisor 时的最终 invocation。
   *
   * <p>正常业务调用通常由 AiProviderObservationAdvisor 记录最终 invocation。
   * 这个方法只是兜底，避免直接调用 OpenAiApi 时只有 attempt 没有 invocation。
   */
  private void recordStandaloneInvocation(
      AiProviderObservationContext.Scope scope,
      boolean success,
      Integer httpStatus,
      Throwable error
  ) {
    long latencyNanos = scope.elapsedNanos();
    metricsRecorder.recordInvocation(
        scope.providerId(),
        scope.model(),
        scope.clientVariant(),
        scope.operation(),
        false,
        success,
        scope.attemptCount(),
        scope.retryCount(),
        latencyNanos
    );
    logService.recordInvocation(new AiProviderInvocationLogCreateRequest(
        scope.traceId(),
        scope.invocationId(),
        scope.providerId(),
        scope.model(),
        scope.clientVariant(),
        scope.operation(),
        false,
        scope.startedAt(),
        LocalDateTime.now(),
        scope.elapsedMs(),
        success,
        scope.attemptCount(),
        scope.retryCount(),
        AiProviderObservationSupport.errorCategory(httpStatus, error),
        AiProviderObservationSupport.exceptionType(error),
        AiProviderObservationSupport.errorMessage(error)
    ));
  }
}
