package interview.guide.modules.aiobservability.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "rag_eval_datasets", indexes = {
    @Index(name = "idx_rag_eval_dataset_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalDatasetEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  /**
   * 在评测集首次持久化前初始化创建时间和更新时间。
   *
   * <p>由 JPA 在执行 INSERT 前通过 {@link PrePersist} 自动调用，
   * 两个字段使用同一时间值，确保新建数据的时间语义一致。
   */
  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = createdAt;
  }

  /**
   * 在评测集更新前刷新最后修改时间。
   *
   * <p>由 JPA 在执行 UPDATE 前通过 {@link PreUpdate} 自动调用；
   * 不修改 {@code createdAt}，只维护 {@code updatedAt}。
   */
  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
