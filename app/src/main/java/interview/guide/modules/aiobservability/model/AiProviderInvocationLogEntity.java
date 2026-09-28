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

/**
 * Provider 级 AI 逻辑调用日志实体。
 *
 * <p>表名：ai_provider_invocation_logs。
 *
 * <p>一条记录代表一次完整的 ChatClient / EmbeddingModel 逻辑调用最终结果。
 * 它不是每一次 HTTP 请求；如果底层因为 RetryTemplate 重试了多次，仍然只会有
 * 一条 invocation 记录，但 attemptCount 会记录底层 HTTP 请求总数。
 *
 * <p>和 ai_provider_attempt_logs 的关系：
 * <pre>
 * ai_provider_invocation_logs.invocation_id
 *   -> ai_provider_attempt_logs.invocation_id
 * </pre>
 *
 * <p>典型用途：
 * <ul>
 *   <li>统计最近 24 小时 Provider 调用总数和成功率</li>
 *   <li>按 provider/model/clientVariant 对比延迟和失败率</li>
 *   <li>通过 traceId 关联一次 HTTP 请求中的所有 LLM 调用</li>
 *   <li>通过 invocationId 下钻查看底层每次 HTTP attempt</li>
 * </ul>
 */
@Entity
@Table(name = "ai_provider_invocation_logs", indexes = {
    @Index(name = "idx_ai_provider_invocation_trace", columnList = "trace_id"),
    @Index(name = "idx_ai_provider_invocation_key", columnList = "invocation_id"),
    @Index(name = "idx_ai_provider_invocation_provider", columnList = "provider_id,created_at"),
    @Index(name = "idx_ai_provider_invocation_success", columnList = "success,created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiProviderInvocationLogEntity {

  /** 数据库主键，仅用于表内唯一标识。 */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 链路追踪 ID，用于关联一次用户请求、异步任务或 RAG 调用链路。 */
  @Column(name = "trace_id", length = 64)
  private String traceId;

  /** 本次 LLM 逻辑调用 ID，用于关联 invocation 表和 attempt 表。 */
  @Column(name = "invocation_id", nullable = false, length = 64)
  private String invocationId;

  /** Provider 标识，如 dashscope、openai、deepseek。 */
  @Column(name = "provider_id", length = 80)
  private String providerId;

  /** 模型名称，如 qwen-plus、gpt-4o-mini。 */
  @Column(length = 120)
  private String model;

  /** ChatClient 变体：DEFAULT / PLAIN / VOICE / EMBEDDING。 */
  @Column(name = "client_variant", length = 30)
  private String clientVariant;

  /** 操作类型：CHAT / STREAM_CHAT / EMBEDDING。 */
  @Column(length = 40)
  private String operation;

  /** 是否流式调用，语音面试 stream() 通常为 true。 */
  private Boolean streaming;

  /** 逻辑调用开始时间，由 Advisor 打开 Scope 时生成。 */
  @Column(name = "started_at", nullable = false)
  private LocalDateTime startedAt;

  /** 逻辑调用结束时间，成功、失败或流式结束时生成。 */
  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  /** 整次逻辑调用总耗时，单位毫秒。 */
  @Column(name = "latency_ms")
  private Long latencyMs;

  /** 最终是否成功，而不是中间某次 attempt 是否成功。 */
  private Boolean success;

  /** 底层真实 HTTP 请求次数，包含首次请求和 RetryTemplate 触发后的请求。 */
  @Column(name = "attempt_count")
  private Integer attemptCount;

  /** RetryTemplate 触发 retry 的次数，不包含首次请求。 */
  @Column(name = "retry_count")
  private Integer retryCount;

  /** 错误分类，如 provider_5xx、client_4xx、timeout、network。 */
  @Column(name = "error_category", length = 50)
  private String errorCategory;

  /** 异常全限定类名，用于定位具体技术异常来源。 */
  @Column(name = "exception_type", length = 160)
  private String exceptionType;

  /** 截断后的错误消息，方便排查单次失败原因。 */
  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  /** 日志创建时间，数据库插入前自动补齐。 */
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /** 插入前补齐 createdAt，避免调用方忘记设置。 */
  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
