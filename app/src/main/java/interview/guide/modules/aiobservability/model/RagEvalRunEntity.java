package interview.guide.modules.aiobservability.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "rag_eval_runs", indexes = {
    @Index(name = "idx_rag_eval_run_dataset", columnList = "dataset_id"),
    @Index(name = "idx_rag_eval_run_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalRunEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "dataset_id", nullable = false)
  private Long datasetId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RagEvalRunStatus status;

  @Column(name = "strategy_json", columnDefinition = "TEXT")
  private String strategyJson;

  @Column(name = "total_cases")
  private Integer totalCases;

  @Column(name = "matched_cases")
  private Integer matchedCases;

  @Column(name = "avg_latency_ms")
  private Long avgLatencyMs;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  /**
   * 在评测运行首次持久化前补齐创建时间。
   *
   * <p>由 JPA 在保存 RUNNING 状态的运行记录前通过 {@link PrePersist} 自动调用；
   * 完成时间由评测服务在运行结束后单独设置。
   */
  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
