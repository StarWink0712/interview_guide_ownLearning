package interview.guide.modules.aiobservability.model;

import java.time.LocalDateTime;

public record AiInvocationLogDTO(
    Long id,
    String traceId,
    String scenario,
    String providerId,
    String model,
    String promptVersion,
    String promptHash,
    Integer promptLength,
    Boolean streaming,
    Boolean structuredOutput,
    Integer attemptCount,
    Boolean success,
    Long latencyMs,
    String errorCode,
    String errorMessage,
    LocalDateTime createdAt
) {
}
