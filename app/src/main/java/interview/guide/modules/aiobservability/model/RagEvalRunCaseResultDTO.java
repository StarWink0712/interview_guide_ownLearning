package interview.guide.modules.aiobservability.model;

public record RagEvalRunCaseResultDTO(
    Long id,
    Long runId,
    Long caseId,
    String question,
    String expectedKeywordsJson,
    Integer matchedKeywordCount,
    Boolean expectedKeywordHit,
    Integer hitCount,
    Long latencyMs,
    String retrievedPreviewJson,
    String errorMessage
) {
}
