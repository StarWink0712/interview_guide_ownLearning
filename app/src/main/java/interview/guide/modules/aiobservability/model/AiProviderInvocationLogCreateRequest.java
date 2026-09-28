package interview.guide.modules.aiobservability.model;

import java.time.LocalDateTime;

/**
 * 创建 Provider invocation 日志的内部请求对象。
 *
 * <p>该 record 不面向前端，只在 common.ai 观测组件和
 * AiProviderObservationLogService 之间传递数据。使用 record 可以保证数据载体不可变，
 * 避免日志字段在异步/回调链路中被意外修改。
 */
public record AiProviderInvocationLogCreateRequest(
    String traceId,
    String invocationId,
    String providerId,
    String model,
    String clientVariant,
    String operation,
    boolean streaming,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    long latencyMs,
    boolean success,
    int attemptCount,
    int retryCount,
    String errorCategory,
    String exceptionType,
    String errorMessage
) {
}
