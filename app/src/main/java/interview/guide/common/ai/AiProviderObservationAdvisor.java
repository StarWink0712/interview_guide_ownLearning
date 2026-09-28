package interview.guide.common.ai;

import interview.guide.common.observability.AiProviderMetricsRecorder;
import interview.guide.modules.aiobservability.model.AiProviderInvocationLogCreateRequest;
import interview.guide.modules.aiobservability.service.AiProviderObservationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatClient 层 Provider 调用观测 Advisor。
 *
 * <p>功能概述：
 * 在 ChatClient 调用的最外层打开 AiProviderObservationContext.Scope，
 * 并在调用结束时记录一次完整 invocation。它负责的是“逻辑调用最终结果”，
 * 不是底层每一次 HTTP 请求。
 *
 * <p>为什么需要 Advisor：
 * <ul>
 *   <li>RestClient/WebClient 只能看到底层 HTTP attempt，看不到一次业务调用的整体结果</li>
 *   <li>RetryTemplate 可能让一次业务调用产生多条 attempt，需要一个外层统一汇总</li>
 *   <li>Advisor 能同时覆盖 ChatClient.call() 和 ChatClient.stream()</li>
 * </ul>
 *
 * <p>记录关系：
 * <pre>
 * adviseCall / adviseStream 打开 Scope
 *   -> 底层 HTTP attempt 由 Interceptor/Filter 写入 ai_provider_attempt_logs
 *   -> 调用完成后本 Advisor 写入 ai_provider_invocation_logs
 * </pre>
 *
 * <p>执行顺序：
 * getOrder() 返回较高优先级，目的是让 Scope 尽量包住后续 ToolCallAdvisor、
 * MemoryAdvisor、OpenAiChatModel 等完整链路。
 */
@RequiredArgsConstructor
public class AiProviderObservationAdvisor implements CallAdvisor, StreamAdvisor {

  private static final String CALL_OPERATION = "CHAT";
  private static final String STREAM_OPERATION = "STREAM_CHAT";

  private final AiProviderObservationLogService logService;
  private final AiProviderMetricsRecorder metricsRecorder;
  private final String providerId;
  private final String model;
  private final String clientVariant;

  /**
   * 处理普通非流式 ChatClient.call()。
   *
   * <p>AI_PROVIDER_OBSERVABILITY: 这里记录一次逻辑调用最终成功/失败。
   * 底层如果发生 RetryTemplate 重试，attemptCount 会由 HTTP interceptor 累加。
   */
  @Override
  public ChatClientResponse adviseCall(
      ChatClientRequest chatClientRequest,
      CallAdvisorChain callAdvisorChain
  ) {
    try (AiProviderObservationContext.Scope scope = AiProviderObservationContext.open(
        null,
        providerId,
        model,
        clientVariant,
        CALL_OPERATION,
        false
    )) {
      Throwable error = null;
      boolean success = false;
      try {
        ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);
        success = true;
        return response;
      } catch (RuntimeException e) {
        error = e;
        throw e;
      } finally {
        recordInvocation(scope, success, error);
      }
    }
  }

  /**
   * 处理流式 ChatClient.stream()。
   *
   * <p>流式调用返回 Flux，真正执行发生在订阅时，所以这里使用 Flux.defer，
   * 确保每次订阅都有独立 Scope。否则如果提前创建 Scope，可能出现上下文生命周期
   * 早于真实流式请求的问题。
   */
  @Override
  public Flux<ChatClientResponse> adviseStream(
      ChatClientRequest chatClientRequest,
      StreamAdvisorChain streamAdvisorChain
  ) {
    return Flux.defer(() -> {
      AiProviderObservationContext.Scope scope = AiProviderObservationContext.open(
          null,
          providerId,
          model,
          clientVariant,
          STREAM_OPERATION,
          true
      );
      AtomicBoolean recorded = new AtomicBoolean(false);
      AtomicReference<Throwable> streamError = new AtomicReference<>();

      return streamAdvisorChain.nextStream(chatClientRequest)
          .doOnError(error -> {
            streamError.set(error);
            recordInvocationOnce(scope, false, error, recorded);
          })
          .doOnComplete(() -> recordInvocationOnce(scope, true, null, recorded))
          .doFinally(signalType -> {
            Throwable error = streamError.get();
            if (error == null && !recorded.get()) {
              recordInvocationOnce(scope, false, null, recorded);
            }
            scope.close();
          });
    });
  }

  /** Advisor 名称，用于 Spring AI Advisor 链路调试。 */
  @Override
  public String getName() {
    return getClass().getSimpleName();
  }

  /** 优先级靠前，确保观测 Scope 包住后续大部分 Advisor 和 Model 调用。 */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 50;
  }

  /** 流式调用可能出现 complete/error/finally 多个信号，使用 AtomicBoolean 防止重复写入。 */
  private void recordInvocationOnce(
      AiProviderObservationContext.Scope scope,
      boolean success,
      Throwable error,
      AtomicBoolean recorded
  ) {
    if (recorded.compareAndSet(false, true)) {
      recordInvocation(scope, success, error);
    }
  }

  /**
   * 写入一次完整 invocation 的指标和数据库日志。
   *
   * <p>invocation 表回答的是：
   * “这次 ChatClient 调用最终成功了吗？总耗时多少？底层发了几次 HTTP attempt？
   * RetryTemplate 触发了几次 retry？”
   */
  private void recordInvocation(
      AiProviderObservationContext.Scope scope,
      boolean success,
      Throwable error
  ) {
    long latencyNanos = scope.elapsedNanos();
    int attemptCount = scope.attemptCount();
    int retryCount = scope.retryCount();
    metricsRecorder.recordInvocation(
        scope.providerId(),
        scope.model(),
        scope.clientVariant(),
        scope.operation(),
        scope.streaming(),
        success,
        attemptCount,
        retryCount,
        latencyNanos
    );
    logService.recordInvocation(new AiProviderInvocationLogCreateRequest(
        scope.traceId(),
        scope.invocationId(),
        scope.providerId(),
        scope.model(),
        scope.clientVariant(),
        scope.operation(),
        scope.streaming(),
        scope.startedAt(),
        LocalDateTime.now(),
        scope.elapsedMs(),
        success,
        attemptCount,
        retryCount,
        AiProviderObservationSupport.errorCategory(error),
        AiProviderObservationSupport.exceptionType(error),
        AiProviderObservationSupport.errorMessage(error)
    ));
  }
}
