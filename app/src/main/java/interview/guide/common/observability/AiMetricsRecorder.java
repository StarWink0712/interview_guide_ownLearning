/**
 * AI 服务调用指标记录器
 *
 * 基于 Micrometer MeterRegistry 记录 LLM（大语言模型）结构化输出调用的性能指标。
 * 追踪每次 AI 调用的延迟（Timer）、调用次数（Counter）、重试次数和失败次数，
 * 支持按业务场景、Provider、模型等多维度进行切片分析。
 *
 * 设计要点：
 * <ul>
 *   <li>多维度标签：通过 scenario（业务场景）、provider（LLM Provider）、model（模型名称）、
 *       status（成功/失败）四个维度标签，精确分析不同场景和不同 LLM 服务的质量表现</li>
 *   <li>重试追踪：attemptCount 表示包含首次调用和重试在内的总尝试次数，实际重试次数
 *       为 attemptCount - 1，仅在发生重试时记录 app.ai.structured_output.retry.count 指标</li>
 *   <li>失败独立统计：除了通过 status=failure 标签区分外，额外记录独立的失败计数器
 *       app.ai.structured_output.failure.count，便于直接设置失败率告警</li>
 *   <li>标签空值保护：scenario/provider/model 为空时统一替换为 "unknown"，
 *       防止标签值为空或 null 导致 Micrometer 指标被拒绝或归类异常</li>
 *   <li>优雅降级：当 Micrometer 不可用时（meterRegistry 为 null）静默跳过，
 *       不抛异常，不影响业务主流程</li>
 * </ul>
 *
 * 指标说明：
 * <pre>
 *   app.ai.invocation.count              — AI 调用总次数（Counter）
 *     ├── status=success                 — 成功次数
 *     └── status=failure                 — 失败次数
 *
 *   app.ai.invocation.latency            — AI 调用延迟（Timer，纳秒精度）
 *
 *   app.ai.structured_output.retry.count — 结构化输出重试次数（Counter）
 *                                         只有发生重试（attemptCount > 1）时才记录
 *
 *   app.ai.structured_output.failure.count — 结构化输出失败独立计数（Counter）
 *                                            与 invocation.count(status=failure) 等价，
 *                                            方便直接在 Prometheus 中单独告警
 * </pre>
 *
 * 参数说明：
 * <ul>
 *   <li>scenario：业务场景标识，如 "interview_evaluation"（面试评估）、
 *       "question_generation"（题目生成）、"knowledge_base"（知识库问答）</li>
 *   <li>provider：LLM Provider ID，如 "openai"、"deepseek"、"qwen"，来源于
 *       app.ai.providers.{providerId} 配置</li>
 *   <li>model：模型名称，如 "gpt-4o"、"deepseek-chat"、"qwen-turbo"</li>
 *   <li>attemptCount：该次调用的总尝试次数（首次 + 重试），由
 *       StructuredOutputInvoker 返回</li>
 * </ul>
 *
 * 联动关系：
 * <ul>
 *   <li>被 StructuredOutputInvoker 调用：每次结构化输出调用完成后记录指标，
 *       包括重试次数和最终成功/失败状态</li>
 *   <li>被 LlmProviderRegistry 关联：provider 标签值来源于注册的 LLM Provider ID</li>
 *   <li>依赖 Micrometer MeterRegistry：与 Prometheus/Grafana 等监控系统集成，
 *       支持按 Provider/模型/场景维度制作 Grafana Dashboard</li>
 * </ul>
 *
 * 使用示例：
 * <pre>
 *   // 记录一次成功的结构化输出调用
 *   long start = System.nanoTime();
 *   // ... 执行 LLM 调用和结构化输出解析 ...
 *   metricsRecorder.recordStructuredInvocation(
 *       "面试评估", "openai", "gpt-4o", true, 2, start
 *   );
 *   // attemptCount=2 表示首次调用失败后重试了1次最终成功
 *   // 会记录 invocation.count(status=success)、invocation.latency、
 *   // retry.count(+1)、failure.count(不记录，因为最终成功)
 * </pre>
 */
package interview.guide.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AiMetricsRecorder {

  /** 标签空值时的默认填充值，防止空标签被 Micrometer 拒绝 */
  private static final String UNKNOWN = "unknown";

  /** Micrometer 指标注册中心，用于创建和管理 Counter/Timer */
  private final MeterRegistry meterRegistry;

  /**
   * 构造函数：通过可选注入获取 MeterRegistry
   *
   * 使用 required = false 实现优雅降级：在缺少 Micrometer 的运行环境中
   * 不会启动失败，所有记录操作静默跳过。
   *
   * @param meterRegistry Micrometer 指标注册中心，不可用时为 null
   */
  public AiMetricsRecorder(@Autowired(required = false) MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /**
   * 记录一次完整的 AI 结构化输出调用指标
   *
   * 在一次 LLM 结构化输出调用结束后（无论成功或失败）调用此方法，同时记录：
   * <ul>
   *   <li>本次调用的耗时（从 startNanos 到当前时间的纳秒差值）</li>
   *   <li>调用次数计数（按 scenario/provider/model/status 维度）</li>
   *   <li>重试次数（仅当 attemptCount > 1 时记录，表示发生了实际重试）</li>
   *   <li>失败独立计数（仅当 success=false 时记录）</li>
   * </ul>
   *
   * 标签构建：
   * <ol>
   *   <li>scenario、provider、model 通过 safe() 方法处理空值，空值替换为 "unknown"</li>
   *   <li>status 根据 success 参数映射为 "success" 或 "failure"</li>
   * </ol>
   *
   * @param scenario     业务场景标识（如 "面试评估"、"题目生成"）
   * @param provider     LLM Provider ID（如 "openai"、"deepseek"）
   * @param model        模型名称（如 "gpt-4o"、"deepseek-chat"）
   * @param success      本次调用是否最终成功
   * @param attemptCount 总尝试次数（首次调用 + 重试次数），由 StructuredOutputInvoker 返回
   * @param startNanos   调用开始时的 System.nanoTime() 值，用于计算耗时
   */
  public void recordStructuredInvocation(
      String scenario,
      String provider,
      String model,
      boolean success,
      int attemptCount,
      long startNanos
  ) {
    if (meterRegistry == null) {
      return;
    }
    String status = success ? "success" : "failure";
    Tags tags = Tags.of(
        "scenario", safe(scenario),
        "provider", safe(provider),
        "model", safe(model),
        "status", status
    );
    // 调用总次数计数（按成功/失败分类）
    meterRegistry.counter("app.ai.invocation.count", tags).increment();
    // 调用延迟记录（纳秒精度）
    meterRegistry.timer("app.ai.invocation.latency", tags)
        .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    // 重试次数记录：实际重试次数 = 总尝试次数 - 1（首次调用不算重试）
    int retryCount = Math.max(0, attemptCount - 1);
    if (retryCount > 0) {
      meterRegistry.counter("app.ai.structured_output.retry.count", tags).increment(retryCount);
    }
    // 失败独立计数（仅失败时记录）
    if (!success) {
      meterRegistry.counter("app.ai.structured_output.failure.count", tags).increment();
    }
  }

  /**
   * 空值保护：将 null 或空白字符串替换为默认值 "unknown"
   *
   * Micrometer 不接受 null 或空白字符串作为标签值，会导致指标记录失败或归类异常。
   * 此方法确保所有标签值都是有效的非空字符串。
   *
   * @param value 原始标签值，可能为 null 或空白
   * @return 有效的标签值，原始值为空时返回 "unknown"
   */
  private String safe(String value) {
    return value == null || value.isBlank() ? UNKNOWN : value;
  }
}
