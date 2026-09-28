/**
 * 业务指标查询服务
 *
 * 聚合多个数据源的统计信息，生成系统的业务概览指标。
 * 作为 MonitoringController 的后端支撑，每个请求执行一次完整的数据聚合，
 * 从四个不同的数据源获取最近 24 小时的数据并计算关键指标。
 *
 * 数据源：
 * <ul>
 *   <li>AsyncTaskRepository — 异步任务总数和失败数（数据库 count 查询）</li>
 *   <li>AiProviderInvocationLogRepository — Provider 级 AI 调用次数和成功率</li>
 *   <li>RagRetrievalLogRepository — RAG 检索次数和平均命中数（数据库查询 + 内存计算）</li>
 *   <li>MeterRegistry（Micrometer） — 语音面试平均轮次延迟（内存 Timer，零数据库开销）</li>
 * </ul>
 *
 * 设计要点：
 * <ul>
 *   <li>统一时间窗口：所有指标都以"最近 24 小时"为统计范围，在 getBusinessSummary()
 *       方法开始时取当前时间作为基准</li>
 *   <li>混合数据源：数据库查询（持久化历史数据）和 Micrometer 内存指标（实时流式数据）
 *       共存于同一个 DTO 中</li>
 *   <li>优雅降级：MeterRegistry 使用 @Autowired(required = false) 可选注入，
 *       没有 Micrometer 集成时语音面试延迟返回 0</li>
 *   <li>失败状态聚合：AsyncTaskStatusDTO.FAILED 和 DEAD_LETTER 两种状态
 *       统一计入失败任务计数</li>
 * </ul>
 *
 * 联动关系：
 * <ul>
 *   <li>被 MonitoringController.getBusinessSummary() 调用</li>
 *   <li>依赖 aiobservability 模块的 Repository：跨模块查询 AI 调用日志和 RAG 检索日志</li>
 *   <li>依赖 asynctask 模块的 Repository：查询异步任务的状态分布</li>
 *   <li>依赖 Micrometer MeterRegistry：读取语音面试轮次延迟的内存统计值</li>
 * </ul>
 */
package interview.guide.modules.monitoring.service;

import interview.guide.modules.aiobservability.model.AiProviderInvocationLogEntity;
import interview.guide.modules.aiobservability.model.RagRetrievalLogEntity;
import interview.guide.modules.aiobservability.repository.AiProviderInvocationLogRepository;
import interview.guide.modules.aiobservability.repository.RagRetrievalLogRepository;
import interview.guide.modules.asynctask.model.AsyncTaskStatusDTO;
import interview.guide.modules.asynctask.repository.AsyncTaskRepository;
import interview.guide.modules.monitoring.model.BusinessMetricsSummaryDTO;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class BusinessMetricsQueryService {

  /** 被视为"失败"的异步任务状态列表，包含明确失败和死信队列两种状态 */
  private static final List<AsyncTaskStatusDTO> FAILED_TASK_STATUSES = List.of(
      AsyncTaskStatusDTO.FAILED,
      AsyncTaskStatusDTO.DEAD_LETTER
  );

  /** 异步任务仓库，用于查询最近 24 小时的任务总数和失败数 */
  private final AsyncTaskRepository asyncTaskRepository;

  /** AI_PROVIDER_OBSERVABILITY: Provider 级 AI 调用日志仓库，用于统计真实 LLM 调用成功率 */
  private final AiProviderInvocationLogRepository aiProviderInvocationLogRepository;

  /** RAG 检索日志仓库，用于查询最近 24 小时的检索总数并计算平均命中数 */
  private final RagRetrievalLogRepository ragRetrievalLogRepository;

  /** Micrometer 指标注册中心，用于从内存中读取语音面试轮次延迟的均值 */
  private final MeterRegistry meterRegistry;

  /**
   * 构造函数
   *
   * MeterRegistry 使用 @Autowired(required = false) 实现优雅降级：
   * 在没有 Micrometer 集成的环境中（如单元测试），meterRegistry 被注入 null，
   * 语音面试相关指标直接返回 0，不影响其他业务指标的正常查询。
   *
   * @param asyncTaskRepository      异步任务仓库
   * @param aiProviderInvocationLogRepository Provider 级 AI 调用日志仓库
   * @param ragRetrievalLogRepository RAG 检索日志仓库
   * @param meterRegistry            Micrometer 指标注册中心，可为 null
   */
  public BusinessMetricsQueryService(
      AsyncTaskRepository asyncTaskRepository,
      AiProviderInvocationLogRepository aiProviderInvocationLogRepository,
      RagRetrievalLogRepository ragRetrievalLogRepository,
      @Autowired(required = false) MeterRegistry meterRegistry
  ) {
    this.asyncTaskRepository = asyncTaskRepository;
    this.aiProviderInvocationLogRepository = aiProviderInvocationLogRepository;
    this.ragRetrievalLogRepository = ragRetrievalLogRepository;
    this.meterRegistry = meterRegistry;
  }

  /**
   * 获取业务概览指标
   *
   * 同时发起多个数据源的查询，聚合生成过去 24 小时的业务健康指标：
   * <ol>
   *   <li>查询 AsyncTaskRepository：获取新增任务总数（countByCreatedAtAfter）和
   *       失败任务数（countByStatusInAndCreatedAtAfter）</li>
   *   <li>查询 AiProviderInvocationLogRepository：获取所有真实 Provider 调用日志，计算调用次数和成功率</li>
   *   <li>查询 RagRetrievalLogRepository：获取所有 RAG 检索日志，计算检索次数和平均命中数</li>
   *   <li>读取 Micrometer 内存指标：获取语音面试平均轮次延迟</li>
   * </ol>
   *
   * 时间基准：所有指标都以当前时间往前推 24 小时为起始时间（LocalDateTime.now().minusHours(24)）。
   *
   * @return 包含异步任务、AI 调用、RAG 检索、语音面试四项指标的 BusinessMetricsSummaryDTO
   */
  public BusinessMetricsSummaryDTO getBusinessSummary() {
    // 统计时间范围：最近 24 小时
    LocalDateTime since = LocalDateTime.now().minusHours(24);

    // 异步任务统计：使用数据库 count 聚合，不加载全量数据到内存
    // recentTaskCount 为新增任务总数，failedTaskCount 为失败任务数
    long recentTaskCount = asyncTaskRepository.countByCreatedAtAfter(since);
    long failedTaskCount = asyncTaskRepository.countByStatusInAndCreatedAtAfter(
        FAILED_TASK_STATUSES,
        since
    );

    // AI 调用日志和 RAG 检索日志：目前使用 findAll + 内存计算的方式
    // 如果数据量增长到数万条/天，应考虑改为数据库端聚合查询以降低内存开销
    List<AiProviderInvocationLogEntity> aiLogs =
        aiProviderInvocationLogRepository.findByCreatedAtAfter(since);
    List<RagRetrievalLogEntity> ragLogs = ragRetrievalLogRepository.findByCreatedAtAfter(since);

    return new BusinessMetricsSummaryDTO(
        since,
        recentTaskCount,
        failedTaskCount,
        aiLogs.size(),
        calculateAiSuccessRate(aiLogs),
        ragLogs.size(),
        calculateRagAverageHitCount(ragLogs),
        resolveVoiceAverageTurnLatencyMs()
    );
  }

  /**
   * 计算 AI 调用成功率
   *
   * 从 AI 调用日志列表中过滤出 success = true 的记录数，除以总记录数。
   * 布尔值以 Boolean.TRUE.equals() 比较，避免 null == true 为 false 导致的误判。
   *
   * @param aiLogs 最近 24 小时的 AI 调用日志列表
   * @return 成功率（0~1 之间），列表为空时返回 0
   */
  private double calculateAiSuccessRate(List<AiProviderInvocationLogEntity> aiLogs) {
    if (aiLogs.isEmpty()) {
      return 0;
    }
    long successCount = aiLogs.stream()
        .filter(log -> Boolean.TRUE.equals(log.getSuccess()))
        .count();
    return successCount / (double) aiLogs.size();
  }

  /**
   * 计算 RAG 检索平均命中文档块数
   *
   * 对每条 RAG 检索日志的 hitCount 字段求平均值。
   * 先过滤掉 hitCount 为 null 的记录（使用 Optional 语义避免空指针），
   * 再对非空值求平均。
   *
   * @param ragLogs 最近 24 小时的 RAG 检索日志列表
   * @return 平均命中数，列表为空或无命中数据时返回 0
   */
  private double calculateRagAverageHitCount(List<RagRetrievalLogEntity> ragLogs) {
    return ragLogs.stream()
        .filter(log -> log.getHitCount() != null)
        .mapToInt(RagRetrievalLogEntity::getHitCount)
        .average()
        .orElse(0);
  }

  /**
   * 从 Micrometer 内存指标中读取语音面试平均轮次延迟
   *
   * 语音面试的轮次延迟由 VoiceMetricsRecorder 在运行时实时写入 MeterRegistry 的
   * Timer 指标中。与数据库查询不同，此方法直接从内存中读取聚合后的均值，
   * 无需额外的数据库查询。
   *
   * 指标名称：app.voice.interview.turn.duration
   * Timer 类型：Micrometer 的 Timer，包含 count、totalTime、mean、max 等统计值
   *
   * 优雅降级：
   * <ul>
   *   <li>meterRegistry 为 null（无 Micrometer 集成）：返回 0</li>
   *   <li>Timer 指标不存在（尚未产生数据）：返回 0</li>
   * </ul>
   *
   * @return 轮次延迟均值（毫秒），无数据时返回 0
   */
  private double resolveVoiceAverageTurnLatencyMs() {
    if (meterRegistry == null) {
      return 0;
    }
    // 从 Micrometer 内存指标中读取 Timer 指标
    Timer timer = meterRegistry.find("app.voice.interview.turn.duration").timer();
    return timer == null ? 0 : timer.mean(TimeUnit.MILLISECONDS);
  }
}
