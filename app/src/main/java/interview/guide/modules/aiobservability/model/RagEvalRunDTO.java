package interview.guide.modules.aiobservability.model;

import java.time.LocalDateTime;
import java.util.List;

public record RagEvalRunDTO(
    Long id,
    Long datasetId,
    RagEvalRunStatus status,
    String strategyJson,
    Integer totalCases,
    Integer matchedCases,
    Long avgLatencyMs,
    LocalDateTime createdAt,
    LocalDateTime completedAt,
    List<RagEvalRunCaseResultDTO> caseResults
) {
}
