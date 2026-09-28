package interview.guide.modules.interview.adaptive.model;

import java.util.List;

public record AbilityDimensionDTO(
    String category,
    int score,
    double confidence,
    int questionCount,
    String recentPerformance,
    List<String> weakKeywords
) {
}
