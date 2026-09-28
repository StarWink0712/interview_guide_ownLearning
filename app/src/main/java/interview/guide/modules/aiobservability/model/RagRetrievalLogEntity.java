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
@Table(name = "rag_retrieval_logs", indexes = {
    @Index(name = "idx_rag_retrieval_trace", columnList = "trace_id"),
    @Index(name = "idx_rag_retrieval_session", columnList = "session_id"),
    @Index(name = "idx_rag_retrieval_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagRetrievalLogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "trace_id", nullable = false, length = 64)
  private String traceId;

  @Column(name = "session_id")
  private Long sessionId;

  @Column(columnDefinition = "TEXT")
  private String question;

  @Column(name = "rewritten_question", columnDefinition = "TEXT")
  private String rewrittenQuestion;

  @Column(name = "candidate_queries_json", columnDefinition = "TEXT")
  private String candidateQueriesJson;

  @Column(name = "knowledge_base_ids_json", columnDefinition = "TEXT")
  private String knowledgeBaseIdsJson;

  @Column(name = "top_k")
  private Integer topK;

  @Column(name = "min_score")
  private Double minScore;

  @Column(name = "hit_count")
  private Integer hitCount;

  @Column(name = "latency_ms")
  private Long latencyMs;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /**
   * 在 RAG 检索主日志首次持久化前补齐创建时间。
   *
   * <p>由 JPA 在执行 INSERT 前通过 {@link PrePersist} 自动调用；
   * 已显式设置时间时不覆盖，便于测试或数据回放保留原始时间。
   */
  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
