/**
 * RAG（检索增强生成）指标记录器
 *
 * 基于 Micrometer MeterRegistry 记录知识库 RAG 查询全流程的性能指标。
 * 覆盖查询改写（Query Rewrite）、文档检索（Retrieval）、答案生成（Answer）三个核心阶段，
 * 提供延迟（Timer）、命中数（Summary）和空结果计数（Counter）三种指标类型。
 *
 * RAG 查询流程与指标映射：
 * <pre>
 *   Phase 1: 查询改写（Query Rewrite）
 *     - 将用户输入的问题优化为更适合向量检索的查询语句
 *     - 指标: app.rag.query_rewrite.latency（延迟）、app.rag.query_rewrite.count（计数）
 *
 *   Phase 2: 文档检索（Retrieval）
 *     - 使用优化后的查询向量在 pgvector 中检索相关文档块
 *     - 指标: app.rag.retrieval.latency（延迟）、app.rag.retrieval.hit.count（命中数）
 *            app.rag.no_result.count（零命中计数）
 *
 *   Phase 3: 答案生成（Answer）
 *     - 将检索到的文档作为上下文，调用 LLM 生成最终答案
 *     - 指标: app.rag.answer.latency（延迟）、app.rag.answer.count（计数）
 * </pre>
 *
 * 设计要点：
 * <ul>
 *   <li>延迟记录：每个阶段使用 Timer 记录耗时，从方法调用开始的时间戳（startNanos）
 *       到记录时刻的差值，精确到纳秒级</li>
 *   <li>结果计数：检索阶段的命中数使用 Summary 类型记录分布（max/mean/percentile），
 *       零命中结果单独计数器便于设置告警</li>
 *   <li>状态标签：所有指标携带 status 标签（success/failure），便于区分正常和异常
 *       情况下的性能表现</li>
 *   <li>优雅降级：当 Micrometer 不可用时（meterRegistry 为 null）静默跳过，
 *       所有记录操作不抛异常</li>
 * </ul>
 *
 * 联动关系：
 * <ul>
 *   <li>被 RagService 调用：在查询改写、文档检索、答案生成各阶段记录指标</li>
 *   <li>依赖 Micrometer MeterRegistry：与 Prometheus/Grafana 等监控系统集成</li>
 *   <li>异常场景监控：通过 status=failure 标签统计各阶段失败率，辅助故障定位</li>
 * </ul>
 *
 * 使用示例：
 * <pre>
 *   // Phase 1: 查询改写
 *   long start = System.nanoTime();
 *   String rewrittenQuery = rewriteService.rewrite(originalQuery);
 *   metricsRecorder.recordRewrite(true, true, start);
 *
 *   // Phase 2: 文档检索
 *   long retrieveStart = System.nanoTime();
 *   List&lt;Document&gt; docs = vectorStore.similaritySearch(rewrittenQuery, topK);
 *   metricsRecorder.recordRetrieval(true, topK, docs.size(), true, retrieveStart);
 *
 *   // Phase 3: 答案生成
 *   long answerStart = System.nanoTime();
 *   String answer = chatClient.call(createPrompt(docs, originalQuery));
 *   metricsRecorder.recordAnswer(false, true, answerStart);
 * </pre>
 */
package interview.guide.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class RagMetricsRecorder {

  /** Micrometer 指标注册中心，用于创建和管理 Timer/Counter/Summary */
  private final MeterRegistry meterRegistry;

  /**
   * 构造函数：通过可选注入获取 MeterRegistry
   *
   * 使用 required = false 实现优雅降级：在缺少 Micrometer 的运行环境中
   * 不会启动失败，所有记录操作静默跳过。
   *
   * @param meterRegistry Micrometer 指标注册中心，不可用时为 null
   */
  public RagMetricsRecorder(@Autowired(required = false) MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /**
   * 记录查询改写阶段的指标
   *
   * 在用户原始查询被改写为向量检索优化查询后调用。
   * 同时记录延迟（从方法调用开始到记录时刻）和计数（区分是否启用改写功能）。
   *
   * 标签说明：
   * <ul>
   *   <li>enabled：是否启用了查询改写功能（true/false），用于对比改写前后的效果差异</li>
   *   <li>status：改写是否成功（success/failure），失败时便于排查是 LLM 调用问题还是
   *       输入格式问题</li>
   * </ul>
   *
   * @param enabled    是否启用了查询改写功能
   * @param success    查询改写是否执行成功
   * @param startNanos 查询改写开始时的 System.nanoTime() 值，用于计算耗时
   */
  public void recordRewrite(boolean enabled, boolean success, long startNanos) {
    if (meterRegistry == null) {
      return;
    }
    Tags tags = Tags.of("enabled", String.valueOf(enabled), "status", success ? "success" : "failure");
    meterRegistry.timer("app.rag.query_rewrite.latency", tags)
        .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    meterRegistry.counter("app.rag.query_rewrite.count", tags).increment();
  }

  /**
   * 记录文档检索阶段的指标
   *
   * 在 pgvector 向量检索完成后调用，记录检索延迟、命中数量和零命中情况。
   *
   * 标签说明：
   * <ul>
   *   <li>rewrite_enabled：是否启用了查询改写，用于对比改写对检索命中率的影响</li>
   *   <li>top_k：检索请求的 Top-K 值，用于分析不同 K 值下的召回表现</li>
   *   <li>status：检索是否成功（success/failure），成功不保证有命中结果</li>
   * </ul>
   *
   * 特殊处理：
   * <ul>
   *   <li>hitCount 使用 Summary 类型（而非 Counter），记录分布信息（max/mean/percentile），
   *       帮助了解检索结果的典型数量范围</li>
   *   <li>hitCount 为负数时自动修正为 0，防止异常数据污染指标</li>
   *   <li>hitCount <= 0 时额外记录 app.rag.no_result.count，便于设置"零命中率过高"告警</li>
   * </ul>
   *
   * @param rewriteEnabled 是否启用了查询改写功能
   * @param topK           检索请求的 Top-K 值
   * @param hitCount       实际检索到的文档数量
   * @param success        检索操作是否成功（无异常）
   * @param startNanos     检索开始时的 System.nanoTime() 值
   */
  public void recordRetrieval(
      boolean rewriteEnabled,
      int topK,
      int hitCount,
      boolean success,
      long startNanos
  ) {
    if (meterRegistry == null) {
      return;
    }
    Tags tags = Tags.of(
        "rewrite_enabled", String.valueOf(rewriteEnabled),
        "top_k", String.valueOf(topK),
        "status", success ? "success" : "failure"
    );
    meterRegistry.timer("app.rag.retrieval.latency", tags)
        .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    meterRegistry.summary("app.rag.retrieval.hit.count", tags).record(Math.max(0, hitCount));
    if (hitCount <= 0) {
      meterRegistry.counter("app.rag.no_result.count", tags).increment();
    }
  }

  /**
   * 记录答案生成阶段的指标
   *
   * 在 LLM 根据检索到的文档上下文生成最终答案后调用。
   *
   * 标签说明：
   * <ul>
   *   <li>streaming：是否为流式输出模式（true/false），流式输出通常首次响应快但总时间更长</li>
   *   <li>status：答案生成是否成功（success/failure），失败通常来自 LLM 调用异常</li>
   * </ul>
   *
   * @param streaming  是否使用流式输出模式
   * @param success    答案生成是否成功
   * @param startNanos 答案生成开始时的 System.nanoTime() 值
   */
  public void recordAnswer(boolean streaming, boolean success, long startNanos) {
    if (meterRegistry == null) {
      return;
    }
    Tags tags = Tags.of("streaming", String.valueOf(streaming), "status", success ? "success" : "failure");
    meterRegistry.counter("app.rag.answer.count", tags).increment();
    meterRegistry.timer("app.rag.answer.latency", tags)
        .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
  }
}
