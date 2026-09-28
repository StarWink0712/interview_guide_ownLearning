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
@Table(name = "rag_eval_cases", indexes = {
    @Index(name = "idx_rag_eval_case_dataset", columnList = "dataset_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalCaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "dataset_id", nullable = false)
  private Long datasetId;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String question;

  @Column(name = "knowledge_base_ids_json", nullable = false, columnDefinition = "TEXT")
  private String knowledgeBaseIdsJson;

  @Column(name = "expected_keywords_json", columnDefinition = "TEXT")
  private String expectedKeywordsJson;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /**
   * 在评测用例首次持久化前补齐创建时间。
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
