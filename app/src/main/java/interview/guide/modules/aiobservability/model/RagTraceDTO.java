package interview.guide.modules.aiobservability.model;

import java.time.LocalDateTime;
import java.util.List;

public record RagTraceDTO(
    Long id,
    String traceId,
    Long sessionId,
    String question,
    String rewrittenQuestion,
    String candidateQueriesJson,
    String knowledgeBaseIdsJson,
    Integer topK,
    Double minScore,
    Integer hitCount,
    Long latencyMs,
    LocalDateTime createdAt,
    List<RagRetrievedChunkDTO> chunks
) {
}
