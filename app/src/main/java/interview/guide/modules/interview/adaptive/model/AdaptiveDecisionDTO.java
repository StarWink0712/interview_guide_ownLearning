package interview.guide.modules.interview.adaptive.model;

public record AdaptiveDecisionDTO(
    String action,
    String targetCategory,
    String nextDifficulty,
    String reason,
    String nextQuestion
) {
}
