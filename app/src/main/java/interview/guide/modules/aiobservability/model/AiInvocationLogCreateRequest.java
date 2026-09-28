package interview.guide.modules.aiobservability.model;

public record AiInvocationLogCreateRequest(
    String traceId,
    String scenario,
    String providerId,
    String model,
    String promptVersion,
    boolean streaming,
    boolean structuredOutput,
    int attemptCount,
    boolean success,
    long latencyMs,
    String errorCode,
    String errorMessage,
    String systemPrompt,
    String userPrompt
) {
}
