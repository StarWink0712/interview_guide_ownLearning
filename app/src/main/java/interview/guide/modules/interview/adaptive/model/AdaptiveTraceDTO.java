package interview.guide.modules.interview.adaptive.model;

public record AdaptiveTraceDTO(
    int questionIndex,
    String category,
    TurnEvaluationDTO turnEvaluation,
    AdaptiveDecisionDTO adaptiveDecision
) {
}
