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
@Table(name = "ai_invocation_logs", indexes = {
    @Index(name = "idx_ai_invocation_trace", columnList = "trace_id"),
    @Index(name = "idx_ai_invocation_scenario", columnList = "scenario"),
    @Index(name = "idx_ai_invocation_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiInvocationLogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "trace_id", nullable = false, length = 64)
  private String traceId;

  @Column(length = 100)
  private String scenario;

  @Column(name = "provider_id", length = 80)
  private String providerId;

  @Column(length = 120)
  private String model;

  @Column(name = "prompt_version", length = 80)
  private String promptVersion;

  @Column(name = "prompt_hash", length = 64)
  private String promptHash;

  @Column(name = "prompt_length")
  private Integer promptLength;

  private Boolean streaming;

  @Column(name = "structured_output")
  private Boolean structuredOutput;

  @Column(name = "attempt_count")
  private Integer attemptCount;

  private Boolean success;

  @Column(name = "latency_ms")
  private Long latencyMs;

  @Column(name = "error_code", length = 80)
  private String errorCode;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /**
   * 在 AI 调用日志首次持久化前补齐创建时间。
   *
   * <p>由 JPA 在执行 INSERT 前通过 {@link PrePersist} 自动调用；
   * 若构建实体时已显式提供时间则保留原值，否则使用当前时间。
   */
  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
