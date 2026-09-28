package interview.guide.common.ai;

import interview.guide.common.observability.AiProviderMetricsRecorder;
import interview.guide.modules.aiobservability.model.AiProviderAttemptLogCreateRequest;
import interview.guide.modules.aiobservability.service.AiProviderObservationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * WebClient 层 Provider 流式调用观测 Filter。
 *
 * <p>功能概述：
 * Spring AI 的 stream() 调用最终会进入 OpenAiApi.chatCompletionStream，
 * 该方法使用 WebClient 发起 SSE/流式 HTTP 请求。普通 RestClient interceptor
 * 覆盖不到 stream()，因此需要单独在 WebClient.Builder 上注册 ExchangeFilterFunction。
 *
 * <p>和 AiProviderHttpObservationInterceptor 的区别：
 * <ul>
 *   <li>AiProviderHttpObservationInterceptor：覆盖普通 call()，底层是 RestClient</li>
 *   <li>AiProviderWebClientObservationFilter：覆盖 stream()，底层是 WebClient</li>
 * </ul>
 *
 * <p>当前主要服务于 Voice ChatClient：
 * 语音面试需要边生成边返回，所以会走流式 LLM 调用。本 Filter 负责记录流式 HTTP
 * 连接是否建立成功、状态码、连接建立阶段耗时以及异常类型。
 *
 * <p>注意：
 * WebClient 的 filter 记录的是流式 HTTP response 建立阶段；完整流是否正常结束，
 * 由 AiProviderObservationAdvisor 在 Flux 完成或出错时记录 invocation 最终结果。
 */
@RequiredArgsConstructor
public class AiProviderWebClientObservationFilter implements ExchangeFilterFunction {

  private final AiProviderObservationLogService logService;
  private final AiProviderMetricsRecorder metricsRecorder;
  private final String providerId;
  private final String model;
  private final String clientVariant;
  private final String operation;

  /**
   * 拦截 WebClient 请求并记录 stream() 底层 HTTP attempt。
   *
   * <p>AI_PROVIDER_OBSERVABILITY: 这里覆盖的是流式调用的真实 HTTP 请求，
   * 不是一次业务 turn，也不是一次 JSON 结构化解析。
   */
  @Override
  public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
    return Mono.defer(() -> {
      AiProviderObservationContext.Scope scope = AiProviderObservationContext.current();
      boolean standalone = false;
      if (scope == null) {
        scope = AiProviderObservationContext.open(null, providerId, model, clientVariant, operation, true);
        standalone = true;
      }

      int attemptNo = scope.nextAttemptNo();
      long startNanos = System.nanoTime();
      AiProviderObservationContext.Scope currentScope = scope;
      boolean currentStandalone = standalone;

      return next.exchange(request)
          .doOnNext(response -> recordResponse(currentScope, attemptNo, request, response, startNanos))
          .doOnError(error -> recordError(currentScope, attemptNo, request, error, startNanos))
          .doFinally(signalType -> {
            if (currentStandalone) {
              currentScope.close();
            }
          });
    });
  }

  /** 记录已经拿到 HTTP response 的流式请求。 */
  private void recordResponse(
      AiProviderObservationContext.Scope scope,
      int attemptNo,
      ClientRequest request,
      ClientResponse response,
      long startNanos
  ) {
    int httpStatus = response.statusCode().value();
    boolean success = AiProviderObservationSupport.isHttpSuccess(httpStatus);
    long latencyNanos = System.nanoTime() - startNanos;
    boolean retryable = AiProviderObservationSupport.isRetryableHttpStatus(httpStatus);
    recordAttempt(scope, attemptNo, request, httpStatus, success, retryable, null, latencyNanos);
  }

  /** 记录在拿到 HTTP response 前失败的流式请求，例如连接失败或超时。 */
  private void recordError(
      AiProviderObservationContext.Scope scope,
      int attemptNo,
      ClientRequest request,
      Throwable error,
      long startNanos
  ) {
    long latencyNanos = System.nanoTime() - startNanos;
    recordAttempt(scope, attemptNo, request, null, false, true, error, latencyNanos);
  }

  /**
   * 统一写入流式 HTTP attempt 的指标和数据库日志。
   *
   * <p>流式调用一般不会被 Spring AI RetryTemplate 包住，因此 attempt 数通常为 1。
   * 但保留 attemptNo 可以让后续如果加 WebClient retry 时继续沿用同一张表。
   */
  private void recordAttempt(
      AiProviderObservationContext.Scope scope,
      int attemptNo,
      ClientRequest request,
      Integer httpStatus,
      boolean success,
      boolean retryable,
      Throwable error,
      long latencyNanos
  ) {
    metricsRecorder.recordHttpAttempt(
        scope.providerId(),
        scope.model(),
        scope.clientVariant(),
        scope.operation(),
        success ? "success" : "failure",
        AiProviderObservationSupport.httpStatusBucket(httpStatus),
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
        request.method().name(),
        request.url().toString(),
        httpStatus,
        success,
        retryable,
        latencyNanos / 1_000_000,
        AiProviderObservationSupport.errorCategory(httpStatus, error),
        AiProviderObservationSupport.exceptionType(error),
        AiProviderObservationSupport.errorMessage(error)
    ));
  }
}
