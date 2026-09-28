package interview.guide.modules.aiobservability.model;

import java.time.LocalDateTime;

/**
 * Provider 级 AI 逻辑调用日志响应 DTO。
 *
 * <p>用于 Controller 返回给前端观测页面。DTO 与 Entity 字段基本一致，
 * 但不会把 JPA Entity 直接暴露给前端，符合项目“禁止直接返回 Entity”的规范。
 */
public record AiProviderInvocationLogDTO(
    Long id,
    String traceId,
    String invocationId,
    String providerId,
    String model,
    String clientVariant,
    String operation,
    Boolean streaming,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    Long latencyMs,
    Boolean success,
    Integer attemptCount,
    Integer retryCount,
    String errorCategory,
    String exceptionType,
    String errorMessage,
    LocalDateTime createdAt
) {
}
