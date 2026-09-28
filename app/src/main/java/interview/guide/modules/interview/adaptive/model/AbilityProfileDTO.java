package interview.guide.modules.interview.adaptive.model;

import java.util.List;

public record AbilityProfileDTO(
    List<AbilityDimensionDTO> dimensions,
    int averageScore,
    String summary
) {
}
