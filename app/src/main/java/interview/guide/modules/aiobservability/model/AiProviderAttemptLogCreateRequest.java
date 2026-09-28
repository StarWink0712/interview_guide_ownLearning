package interview.guide.modules.aiobservability.model;

/**
 * 创建 Provider HTTP attempt 日志的内部请求对象。
 *
 * <p>每次 RestClient interceptor 或 WebClient filter 捕获到底层 HTTP 请求后，
 * 都会构造该对象交给 AiProviderObservationLogService 持久化。
 */
public record AiProviderAttemptLogCreateRequest(
    String invocationId,
    String providerId,
    String model,
    String clientVariant,
    String operation,
    int attemptNo,
    String httpMethod,
    String uri,
    Integer httpStatus,
    boolean success,
    boolean retryable,
    long latencyMs,
    String errorCategory,
    String exceptionType,
    String errorMessage
) {
}
