/**
 * 异步任务指标记录器
 *
 * 基于 Micrometer MeterRegistry 记录 Redis Stream 异步任务的各项生命周期指标。
 * 任务从创建到完成（或失败）的全过程通过 Counter 计数器进行度量，支持按任务类型
 * 和 Stream 流名称进行切片分析。
 *
 * 设计要点：
 * <ul>
 *   <li>计数器模式：每个生命周期阶段使用独立的 Counter（如 app.async.task.created），
 *       通过递增计数实时反映各类任务的数量分布</li>
 *   <li>双维度标签：task_type 标识任务类型（如"向量化"、"简历分析"、"面试评估"），
 *       stream 标识所属的 Redis Stream 管道</li>
 *   <li>优雅降级：当 Micrometer 不可用时（如单元测试环境），通过
 *       @Autowired(required = false) 创建，meterRegistry 为 null 时静默跳过，
 *       不抛出异常</li>
 *   <li>标签空值保护：taskDisplayName 或 streamKey 为空时统一替换为 "unknown"，
 *       防止标签值异常导致指标数据污染</li>
 * </ul>
 *
 * 任务生命周期与指标映射：
 * <pre>
 *   创建 → 处理中 → 完成（成功路径）
 *          ↘ 重试 → 完成
 *          ↘ 失败（失败路径）
 *
 * 指标名称:
 *   app.async.task.created     — 任务创建计数
 *   app.async.task.processing  — 任务开始处理计数
 *   app.async.task.completed   — 任务成功完成计数
 *   app.async.task.failed      — 任务最终失败计数（超过最大重试次数）
 *   app.async.task.retry       — 任务重试计数
 * </pre>
 *
 * 联动关系：
 * <ul>
 *   <li>被 AbstractStreamConsumer 调用：在 onMessage 和 afterCompletion 中记录处理状态</li>
 *   <li>被 AbstractStreamProducer 调用：在发送异步任务时记录创建事件</li>
 *   <li>依赖 Micrometer MeterRegistry：与 Prometheus/Grafana 等监控系统集成</li>
 * </ul>
 *
 * 使用示例：
 * <pre>
 *   // 消费者开始处理消息时
 *   metricsRecorder.recordProcessing("简历分析", AsyncTaskStreamConstants.RESUME_ANALYSIS_STREAM);
 *
 *   // 任务处理成功后
 *   metricsRecorder.recordCompleted("简历分析", AsyncTaskStreamConstants.RESUME_ANALYSIS_STREAM);
 *
 *   // 任务处理失败后
 *   metricsRecorder.recordFailed("简历分析", AsyncTaskStreamConstants.RESUME_ANALYSIS_STREAM);
 * </pre>
 */
package interview.guide.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AsyncTaskMetricsRecorder {

  /** Micrometer 指标注册中心，用于创建和管理计数器 */
  private final MeterRegistry meterRegistry;

  /**
   * 构造函数：通过可选注入获取 MeterRegistry
   *
   * 使用 required = false 实现优雅降级：在缺少 Micrometer 的运行环境
   * （如单元测试、无监控集成场景）中不会启动失败，所有记录操作静默跳过。
   *
   * @param meterRegistry Micrometer 指标注册中心，不可用时为 null
   */
  public AsyncTaskMetricsRecorder(@Autowired(required = false) MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /**
   * 记录任务创建事件
   *
   * 生产者在将任务消息写入 Redis Stream 时调用，表示新任务已被提交到队列中。
   *
   * @param taskDisplayName 任务类型的可读名称（如"向量化"、"简历分析"、"面试评估"）
   * @param streamKey       Redis Stream 的 Key 名称，用于区分不同的异步管道
   */
  public void recordCreated(String taskDisplayName, String streamKey) {
    increment("app.async.task.created", taskDisplayName, streamKey);
  }

  /**
   * 记录任务开始处理事件
   *
   * 消费者从 Redis Stream 中取出消息并开始执行 processMessage() 时调用，
   * 表示任务已进入实际执行阶段。
   *
   * @param taskDisplayName 任务类型的可读名称
   * @param streamKey       Redis Stream 的 Key 名称
   */
  public void recordProcessing(String taskDisplayName, String streamKey) {
    increment("app.async.task.processing", taskDisplayName, streamKey);
  }

  /**
   * 记录任务成功完成事件
   *
   * 消费者成功处理完消息并 ACK 确认后调用，表示任务正常结束。
   *
   * @param taskDisplayName 任务类型的可读名称
   * @param streamKey       Redis Stream 的 Key 名称
   */
  public void recordCompleted(String taskDisplayName, String streamKey) {
    increment("app.async.task.completed", taskDisplayName, streamKey);
  }

  /**
   * 记录任务最终失败事件
   *
   * 消费者在重试次数超过最大限制（默认 3 次）后调用，表示任务最终失败。
   * 可配合告警规则（如 failed 计数 > 阈值）触发运维告警。
   *
   * @param taskDisplayName 任务类型的可读名称
   * @param streamKey       Redis Stream 的 Key 名称
   */
  public void recordFailed(String taskDisplayName, String streamKey) {
    increment("app.async.task.failed", taskDisplayName, streamKey);
  }

  /**
   * 记录任务重试事件
   *
   * 消费者在处理消息失败后且重试次数未超过最大限制时调用，
   * 表示任务将重新进入队列等待下一次处理。
   *
   * @param taskDisplayName 任务类型的可读名称
   * @param streamKey       Redis Stream 的 Key 名称
   */
  public void recordRetry(String taskDisplayName, String streamKey) {
    increment("app.async.task.retry", taskDisplayName, streamKey);
  }

  /**
   * 统一递增指定名称的 Counter 指标
   *
   * 所有公开的记录方法最终都委托到此私有方法，确保标签构建和空值保护逻辑集中管理。
   * 当 meterRegistry 为 null 时（Micrometer 不可用）静默跳过，不抛异常。
   *
   * @param metricName      指标名称（如 "app.async.task.created"）
   * @param taskDisplayName 任务类型的可读名称
   * @param streamKey       Redis Stream 的 Key 名称
   */
  private void increment(String metricName, String taskDisplayName, String streamKey) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry.counter(metricName, tags(taskDisplayName, streamKey)).increment();
  }

  /**
   * 构建指标标签集合
   *
   * 包含两个维度的标签：
   * <ul>
   *   <li>task_type：任务类型名称，为空时默认 "unknown"</li>
   *   <li>stream：Redis Stream 管道 Key，为空时默认 "unknown"</li>
   * </ul>
   *
   * 标签用于在 Prometheus/Grafana 中按维度聚合和过滤指标数据。
   *
   * @param taskDisplayName 任务类型名称
   * @param streamKey       Redis Stream Key
   * @return 包含 task_type 和 stream 标签的 Tags 对象
   */
  private Tags tags(String taskDisplayName, String streamKey) {
    return Tags.of(
        "task_type", taskDisplayName == null || taskDisplayName.isBlank() ? "unknown" : taskDisplayName,
        "stream", streamKey == null || streamKey.isBlank() ? "unknown" : streamKey
    );
  }
}
