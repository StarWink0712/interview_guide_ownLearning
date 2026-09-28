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
 * Provider 级 AI HTTP attempt 日志实体。
 *
 * <p>表名：ai_provider_attempt_logs。
 *
 * <p>一条记录代表一次真实发送到 LLM Provider 的 HTTP 请求。一次 invocation
 * 可能包含多条 attempt，例如第一次 500、第二次 500、第三次 200。
 *
 * <p>典型用途：
 * <ul>
 *   <li>查看一次 LLM 调用到底重试了几次</li>
 *   <li>定位失败发生在第几次 HTTP 请求</li>
 *   <li>区分 Provider 返回 5xx、业务配置 4xx、网络超时等问题</li>
 *   <li>分析底层 HTTP 延迟，而不是只看最终业务调用耗时</li>
 * </ul>
 */
@Entity
@Table(name = "ai_provider_attempt_logs", indexes = {
    @Index(name = "idx_ai_provider_attempt_invocation", columnList = "invocation_id"),
    @Index(name = "idx_ai_provider_attempt_provider", columnList = "provider_id,created_at"),
    @Index(name = "idx_ai_provider_attempt_success", columnList = "success,created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiProviderAttemptLogEntity {

  /** 数据库主键。 */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 所属逻辑调用 ID，关联 ai_provider_invocation_logs.invocation_id。 */
  @Column(name = "invocation_id", nullable = false, length = 64)
  private String invocationId;

  /** Provider 标识，如 dashscope、openai。 */
  @Column(name = "provider_id", length = 80)
  private String providerId;

  /** 模型名称。 */
  @Column(length = 120)
  private String model;

  /** ChatClient 变体：DEFAULT / PLAIN / VOICE / EMBEDDING。 */
  @Column(name = "client_variant", length = 30)
  private String clientVariant;

  /** 操作类型：CHAT / STREAM_CHAT / EMBEDDING。 */
  @Column(length = 40)
  private String operation;

  /** 第几次 HTTP attempt，从 1 开始。 */
  @Column(name = "attempt_no")
  private Integer attemptNo;

  /** HTTP 方法，通常为 POST。 */
  @Column(name = "http_method", length = 20)
  private String httpMethod;

  /** 请求 URL，截断保存，避免完整 URL 过长。 */
  @Column(name = "uri", length = 500)
  private String uri;

  /** HTTP 状态码；如果连接阶段失败，可能为 null。 */
  @Column(name = "http_status")
  private Integer httpStatus;

  /** 本次 HTTP 请求是否成功，不代表整次 invocation 是否成功。 */
  private Boolean success;

  /** 当前失败是否属于默认重试策略下的可重试方向。 */
  private Boolean retryable;

  /** 本次 HTTP 请求耗时，单位毫秒。 */
  @Column(name = "latency_ms")
  private Long latencyMs;

  /** 错误分类，如 provider_5xx、client_4xx、timeout、network。 */
  @Column(name = "error_category", length = 50)
  private String errorCategory;

  /** 异常全限定类名。 */
  @Column(name = "exception_type", length = 160)
  private String exceptionType;

  /** 截断后的错误消息。 */
  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  /** 日志创建时间。 */
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /** 插入前自动补齐 createdAt。 */
  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
