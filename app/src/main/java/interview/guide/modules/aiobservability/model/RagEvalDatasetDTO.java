package interview.guide.modules.aiobservability.model;

import java.time.LocalDateTime;

public record RagEvalDatasetDTO(
    Long id,
    String name,
    String description,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
