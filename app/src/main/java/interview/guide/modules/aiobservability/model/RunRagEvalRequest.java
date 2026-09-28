package interview.guide.modules.aiobservability.model;

public record RunRagEvalRequest(
    Integer topK,
    Double minScore
) {
}
