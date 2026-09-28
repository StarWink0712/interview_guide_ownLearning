package interview.guide.common.ai;

import interview.guide.common.observability.TraceIdFilter;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provider 级 AI 观测上下文。
 *
 * <p>功能概述：
 * 在一次 ChatClient 调用开始时创建 Scope，把 traceId、invocationId、provider、
 * model、clientVariant、operation 等信息放入 ThreadLocal。后续 RestClient
 * 拦截器、WebClient filter、RetryListener 都可以从当前线程读取同一个 Scope，
 * 从而把多条 HTTP attempt 和最终 invocation 关联到同一个 invocationId。
 *
 * <p>为什么使用 ThreadLocal：
 * Spring AI 的调用链中，ChatClient Advisor、OpenAiChatModel、OpenAiApi、
 * RestClient interceptor 之间没有统一的业务参数传递入口。ThreadLocal 可以在
 * 不侵入 Spring AI 源码的情况下，把一次调用的上下文透传到底层 HTTP 记录点。
 *
 * <p>数据关系：
 * <pre>
 * 一个 Scope / invocationId
 *   ├── ai_provider_invocation_logs  1 条最终结果
 *   └── ai_provider_attempt_logs     N 条 HTTP 尝试
 * </pre>
 *
 * <p>注意：
 * Scope 实现 AutoCloseable，创建方必须在调用结束时 close，避免线程复用时上下文泄漏。
 */
final class AiProviderObservationContext {

  private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

  private AiProviderObservationContext() {}

  /** 获取当前线程正在执行的 Provider 观测上下文，没有则返回 null。 */
  static Scope current() {
    return CURRENT.get();
  }

  /**
   * 打开一次 Provider 调用上下文。
   *
   * @param traceId       上游链路追踪 ID；为空时优先从 TraceIdFilter 获取，再 fallback 到 UUID
   * @param providerId    当前调用的 Provider，如 dashscope、openai、deepseek
   * @param model         当前调用的模型名称，如 qwen-plus
   * @param clientVariant ChatClient 类型：DEFAULT / PLAIN / VOICE / EMBEDDING
   * @param operation     操作类型：CHAT / STREAM_CHAT / EMBEDDING
   * @param streaming     是否是流式调用
   * @return Scope，调用方应使用 try-with-resources 或 finally close
   */
  static Scope open(
      String traceId,
      String providerId,
      String model,
      String clientVariant,
      String operation,
      boolean streaming
  ) {
    Scope previous = CURRENT.get();
    Scope scope = new Scope(
        previous,
        traceId != null && !traceId.isBlank()
            ? traceId
            : TraceIdFilter.currentTraceId().orElseGet(() -> UUID.randomUUID().toString()),
        UUID.randomUUID().toString(),
        AiProviderObservationSupport.safe(providerId),
        AiProviderObservationSupport.safe(model),
        AiProviderObservationSupport.safe(clientVariant),
        AiProviderObservationSupport.safe(operation),
        streaming
    );
    CURRENT.set(scope);
    return scope;
  }

  /**
   * 一次 Provider 调用的观测作用域。
   *
   * <p>字段说明：
   * <ul>
   *   <li>traceId：跨 HTTP 请求、异步任务、RAG 检索的链路 ID</li>
   *   <li>invocationId：本次 LLM 调用内部 ID，用于关联 invocation 表和 attempt 表</li>
   *   <li>attemptCounter：底层 HTTP 请求次数，RestClient/WebClient 每发一次就 +1</li>
   *   <li>retryCounter：Spring AI RetryTemplate 触发 retry 前 +1</li>
   * </ul>
   */
  static final class Scope implements AutoCloseable {

    private final Scope previous;
    private final String traceId;
    private final String invocationId;
    private final String providerId;
    private final String model;
    private final String clientVariant;
    private final String operation;
    private final boolean streaming;
    private final LocalDateTime startedAt;
    private final long startNanos;
    private final AtomicInteger attemptCounter = new AtomicInteger();
    private final AtomicInteger retryCounter = new AtomicInteger();

    private Scope(
        Scope previous,
        String traceId,
        String invocationId,
        String providerId,
        String model,
        String clientVariant,
        String operation,
        boolean streaming
    ) {
      this.previous = previous;
      this.traceId = traceId;
      this.invocationId = invocationId;
      this.providerId = providerId;
      this.model = model;
      this.clientVariant = clientVariant;
      this.operation = operation;
      this.streaming = streaming;
      this.startedAt = LocalDateTime.now();
      this.startNanos = System.nanoTime();
    }

    /** 生成下一次 HTTP attempt 序号，从 1 开始。 */
    int nextAttemptNo() {
      return attemptCounter.incrementAndGet();
    }

    /** 记录一次 RetryTemplate 触发的 retry，不包含初始调用。 */
    int recordRetryAttempt() {
      return retryCounter.incrementAndGet();
    }

    /** 返回当前调用已经消耗的毫秒数，用于数据库 latencyMs 字段。 */
    long elapsedMs() {
      return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 返回当前调用已经消耗的纳秒数，用于 Micrometer Timer。 */
    long elapsedNanos() {
      return System.nanoTime() - startNanos;
    }

    String traceId() {
      return traceId;
    }

    String invocationId() {
      return invocationId;
    }

    String providerId() {
      return providerId;
    }

    String model() {
      return model;
    }

    String clientVariant() {
      return clientVariant;
    }

    String operation() {
      return operation;
    }

    boolean streaming() {
      return streaming;
    }

    LocalDateTime startedAt() {
      return startedAt;
    }

    int attemptCount() {
      return attemptCounter.get();
    }

    int retryCount() {
      return retryCounter.get();
    }

    /** 关闭当前 Scope，并恢复外层 Scope，兼容极少数嵌套调用场景。 */
    @Override
    public void close() {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }
}
