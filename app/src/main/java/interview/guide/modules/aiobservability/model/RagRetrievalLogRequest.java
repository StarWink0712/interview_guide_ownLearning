package interview.guide.modules.aiobservability.model;

import java.util.List;

public record RagRetrievalLogRequest(
    String traceId,
    Long sessionId,
    String question,
    String rewrittenQuestion,
    List<String> candidateQueries,
    List<Long> knowledgeBaseIds,
    int topK,
    double minScore,
    int hitCount,
    long latencyMs
) {
}
