package interview.guide.modules.aiobservability.model;

import java.time.LocalDateTime;

public record RagEvalCaseDTO(
    Long id,
    Long datasetId,
    String question,
    String knowledgeBaseIdsJson,
    String expectedKeywordsJson,
    LocalDateTime createdAt
) {
}
