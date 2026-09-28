package interview.guide.modules.aiobservability.repository;

import interview.guide.modules.aiobservability.model.AiProviderInvocationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Provider invocation 日志仓库。
 *
 * <p>负责查询 ai_provider_invocation_logs 表，也就是“一次 LLM 逻辑调用最终结果”。
 * 该仓库主要用于：
 * <ul>
 *   <li>AI 观测页面最近调用列表</li>
 *   <li>按 traceId 查看一次请求下的所有 LLM 调用</li>
 *   <li>BusinessMetricsQueryService 统计最近 24 小时 AI 调用成功率</li>
 * </ul>
 */
@Repository
public interface AiProviderInvocationLogRepository
    extends JpaRepository<AiProviderInvocationLogEntity, Long> {

  /** 查询最近 50 条 Provider invocation，按创建时间倒序。 */
  List<AiProviderInvocationLogEntity> findTop50ByOrderByCreatedAtDesc();

  /** 根据 traceId 查询同一链路下的 Provider invocation，按创建时间正序便于复盘。 */
  List<AiProviderInvocationLogEntity> findByTraceIdOrderByCreatedAtAsc(String traceId);

  /** 查询指定时间之后的 invocation，供监控摘要统计调用量和成功率。 */
  List<AiProviderInvocationLogEntity> findByCreatedAtAfter(LocalDateTime createdAt);
}
