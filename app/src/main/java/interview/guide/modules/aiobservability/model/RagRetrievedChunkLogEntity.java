package interview.guide.modules.aiobservability.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "rag_retrieved_chunk_logs", indexes = {
    @Index(name = "idx_rag_chunk_retrieval", columnList = "retrieval_log_id"),
    @Index(name = "idx_rag_chunk_kb", columnList = "knowledge_base_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagRetrievedChunkLogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "retrieval_log_id", nullable = false)
  private Long retrievalLogId;

  @Column(name = "knowledge_base_id")
  private Long knowledgeBaseId;

  @Column(name = "chunk_text_preview", columnDefinition = "TEXT")
  private String chunkTextPreview;

  private Double score;

  @Column(name = "rank_order")
  private Integer rank;

  @Column(name = "metadata_json", columnDefinition = "TEXT")
  private String metadataJson;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /**
   * 在命中文档块日志首次持久化前补齐创建时间。
   *
   * <p>由 JPA 在执行 INSERT 前通过 {@link PrePersist} 自动调用；
   * 已显式提供创建时间时保留原值。
   */
  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
