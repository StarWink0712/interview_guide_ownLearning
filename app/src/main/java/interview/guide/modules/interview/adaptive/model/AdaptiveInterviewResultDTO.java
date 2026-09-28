package interview.guide.modules.interview.adaptive.model;

public record AdaptiveInterviewResultDTO(
    TurnEvaluationDTO turnEvaluation,
    AbilityProfileDTO abilityProfile,
    AdaptiveDecisionDTO adaptiveDecision
) {
}
