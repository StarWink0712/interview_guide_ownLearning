package interview.guide.common.ai;

import interview.guide.common.observability.AiProviderMetricsRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;

/**
 * Provider 级 RetryTemplate 工厂。
 *
 * <p>功能概述：
 * 为 OpenAiChatModel / OpenAiEmbeddingModel 创建带观测能力的 Spring RetryTemplate。
 * 它复刻 Spring AI 2.0.0-M4 的默认重试参数，同时额外挂载 RetryListener，
 * 用于记录真实网络/API 重试次数。
 *
 * <p>为什么不直接使用 RetryUtils.DEFAULT_RETRY_TEMPLATE：
 * Spring AI 默认模板虽然会重试，但它的 listener 只打日志，无法把 provider、model、
 * clientVariant、operation 等维度写入 Micrometer。这里创建“每个模型实例专属”的
 * RetryTemplate，才能把重试指标归属到具体 Provider。
 *
 * <p>默认策略：
 * <ul>
 *   <li>maxRetries=10，即最多 1 次初始请求 + 10 次重试</li>
 *   <li>initialInterval=2000ms</li>
 *   <li>multiplier=5，指数退避</li>
 *   <li>maxInterval=180000ms，单次等待最长 3 分钟</li>
 *   <li>重试 TransientAiException 和 ResourceAccessException</li>
 * </ul>
 *
 * <p>注意：
 * StructuredOutputInvoker 的重试是 JSON 解析失败后的业务重试；
 * 本类的重试是底层网络/API 失败后的 Provider 重试，二者语义不同。
 */
@Component
@RequiredArgsConstructor
public class AiProviderRetryTemplateFactory {

  private static final int DEFAULT_MAX_RETRIES = 10;
  private static final long DEFAULT_INITIAL_INTERVAL_MS = 2000;
  private static final int DEFAULT_MULTIPLIER = 5;
  private static final long DEFAULT_MAX_INTERVAL_MS = 180000;

  private final AiProviderMetricsRecorder metricsRecorder;

  /**
   * 创建带 RetryListener 的 RetryTemplate。
   *
   * @param providerId    Provider ID，如 dashscope
   * @param model         模型名，如 qwen-plus
   * @param clientVariant ChatClient 类型：DEFAULT / PLAIN / VOICE / EMBEDDING
   * @param operation     操作类型：CHAT / EMBEDDING
   * @return 供 OpenAiChatModel 或 OpenAiEmbeddingModel 使用的 RetryTemplate
   */
  public RetryTemplate create(
      String providerId,
      String model,
      String clientVariant,
      String operation
  ) {
    RetryPolicy retryPolicy = RetryPolicy.builder()
        .maxRetries(DEFAULT_MAX_RETRIES)
        .includes(TransientAiException.class)
        .includes(ResourceAccessException.class)
        .delay(Duration.ofMillis(DEFAULT_INITIAL_INTERVAL_MS))
        .multiplier(DEFAULT_MULTIPLIER)
        .maxDelay(Duration.ofMillis(DEFAULT_MAX_INTERVAL_MS))
        .build();

    RetryTemplate retryTemplate = new RetryTemplate(retryPolicy);
    retryTemplate.setRetryListener(new RetryListener() {
      /**
       * 每次真正开始 retry 前触发，不包含第一次初始请求。
       *
       * <p>这里同时做两件事：
       * 1. 当前 Scope 内 retryCounter +1，最终写入 invocation.retryCount；
       * 2. 写入 Micrometer retry attempt 指标，用于实时监控。
       */
      @Override
      public void beforeRetry(RetryPolicy policy, Retryable<?> retryable) {
        AiProviderObservationContext.Scope scope = AiProviderObservationContext.current();
        if (scope != null) {
          scope.recordRetryAttempt();
        }
        metricsRecorder.recordRetryAttempt(providerId, model, clientVariant, operation);
      }

      /** 某次 retry 执行后仍然失败时触发，用于统计重试失败异常类型。 */
      @Override
      public void onRetryFailure(
          RetryPolicy policy,
          Retryable<?> retryable,
          Throwable throwable
      ) {
        metricsRecorder.recordRetryFailure(
            providerId,
            model,
            clientVariant,
            operation,
            AiProviderObservationSupport.exceptionType(throwable)
        );
      }
    });
    return retryTemplate;
  }
}
