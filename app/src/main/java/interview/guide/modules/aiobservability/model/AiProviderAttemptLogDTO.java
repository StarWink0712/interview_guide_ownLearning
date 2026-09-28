package interview.guide.modules.aiobservability.model;

import java.time.LocalDateTime;

/**
 * Provider 级 AI HTTP attempt 日志响应 DTO。
 *
 * <p>前端通过 invocationId 下钻时使用，用来展示一次 LLM 调用下每次真实 HTTP
 * 请求的状态码、耗时、错误分类和异常信息。
 */
public record AiProviderAttemptLogDTO(
    Long id,
    String invocationId,
    String providerId,
    String model,
    String clientVariant,
    String operation,
    Integer attemptNo,
    String httpMethod,
    String uri,
    Integer httpStatus,
    Boolean success,
    Boolean retryable,
    Long latencyMs,
    String errorCategory,
    String exceptionType,
    String errorMessage,
    LocalDateTime createdAt
) {
}
