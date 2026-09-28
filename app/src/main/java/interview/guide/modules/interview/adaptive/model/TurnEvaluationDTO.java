package interview.guide.modules.interview.adaptive.model;

import java.util.List;

public record TurnEvaluationDTO(
    int questionIndex,
    int score,
    int depthLevel,
    List<String> missingKeyPoints,
    String followUpSuggestion,
    String category
) {
}
