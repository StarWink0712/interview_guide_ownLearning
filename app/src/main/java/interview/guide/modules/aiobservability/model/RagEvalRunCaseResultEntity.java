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
@Table(name = "rag_eval_run_case_results", indexes = {
    @Index(name = "idx_rag_eval_result_run", columnList = "run_id"),
    @Index(name = "idx_rag_eval_result_case", columnList = "case_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalRunCaseResultEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false)
  private Long runId;

  @Column(name = "case_id", nullable = false)
  private Long caseId;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String question;

  @Column(name = "expected_keywords_json", columnDefinition = "TEXT")
  private String expectedKeywordsJson;

  @Column(name = "matched_keyword_count")
  private Integer matchedKeywordCount;

  @Column(name = "expected_keyword_hit")
  private Boolean expectedKeywordHit;

  @Column(name = "hit_count")
  private Integer hitCount;

  @Column(name = "latency_ms")
  private Long latencyMs;

  @Column(name = "retrieved_preview_json", columnDefinition = "TEXT")
  private String retrievedPreviewJson;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /**
   * 在逐用例评测结果首次持久化前补齐创建时间。
   *
   * <p>由 JPA 在成功或失败结果执行 INSERT 前通过 {@link PrePersist} 自动调用；
   * 已显式提供创建时间时保留原值。
   */
  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
