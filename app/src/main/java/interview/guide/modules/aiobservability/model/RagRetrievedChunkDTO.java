package interview.guide.modules.aiobservability.model;

public record RagRetrievedChunkDTO(
    Long id,
    Long knowledgeBaseId,
    String chunkTextPreview,
    Double score,
    Integer rank,
    String metadataJson
) {
}
