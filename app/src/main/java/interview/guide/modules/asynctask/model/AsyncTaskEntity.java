package interview.guide.modules.asynctask.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "async_tasks", indexes = {
    @Index(name = "idx_async_task_type_business", columnList = "task_type,business_id"),
    @Index(name = "idx_async_task_status", columnList = "status"),
    @Index(name = "idx_async_task_active_key", columnList = "active_key", unique = true),
    @Index(name = "idx_async_task_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncTaskEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "task_type", nullable = false, length = 50)
  private AsyncTaskType taskType;

  @Column(name = "business_id", nullable = false, length = 120)
  private String businessId;

  @Column(name = "active_key", unique = true, length = 180)
  private String activeKey;

  @Column(name = "stream_key", nullable = false, length = 120)
  private String streamKey;

  @Column(name = "message_id", length = 80)
  private String messageId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AsyncTaskStatusDTO status;

  @Column(name = "retry_count", nullable = false)
  private Integer retryCount;

  @Column(name = "max_retry_count", nullable = false)
  private Integer maxRetryCount;

  @Column(name = "next_retry_at")
  private LocalDateTime nextRetryAt;

  @Column(name = "last_error", columnDefinition = "TEXT")
  private String lastError;

  @Column(name = "payload_json", columnDefinition = "TEXT")
  private String payloadJson;

  @Column(name = "parent_task_id")
  private Long parentTaskId;

  @Column(name = "active_flag", nullable = false)
  private Boolean activeFlag;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  /**
   * 在任务首次持久化前初始化创建时间和更新时间。
   *
   * <p>由 JPA 在执行 INSERT 前通过 {@link PrePersist} 自动调用；
   * 两个字段使用同一时间值，确保新任务的时间语义一致。
   */
  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = createdAt;
  }

  /**
   * 在任务记录更新前刷新最后修改时间。
   *
   * <p>由 JPA 在执行 UPDATE 前通过 {@link PreUpdate} 自动调用；
   * 状态流转、重试次数或错误信息变化都会触发该时间更新。
   */
  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
