package interview.guide.modules.aiobservability.repository;

import interview.guide.modules.aiobservability.model.AiInvocationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AiInvocationLogRepository extends JpaRepository<AiInvocationLogEntity, Long> {

  /**
   * 查询最新的 50 条 AI 调用日志。
   *
   * <p>由 {@code AiInvocationLogService.listRecentInvocations} 调用，
   * Spring Data 根据方法名生成按创建时间倒序并限制 50 条的查询。
   *
   * @return 从新到旧排列的调用日志，最多 50 条
   */
  List<AiInvocationLogEntity> findTop50ByOrderByCreatedAtDesc();

  /**
   * 查询同一 traceId 下的全部 AI 调用。
   *
   * <p>由 {@code AiInvocationLogService.listByTraceId} 调用，
   * 按创建时间升序返回，便于还原一次请求内的调用顺序。
   *
   * @param traceId 链路追踪 ID
   * @return 按创建时间升序排列的调用日志
   */
  List<AiInvocationLogEntity> findByTraceIdOrderByCreatedAtAsc(String traceId);

  /**
   * 查询指定时间之后产生的 AI 调用日志。
   *
   * <p>由 {@code BusinessMetricsQueryService} 调用，
   * 用于聚合监控时间窗口内的调用指标。
   *
   * @param createdAt 统计窗口起始时间，不包含更早记录
   * @return 创建时间晚于起始时间的调用日志
   */
  List<AiInvocationLogEntity> findByCreatedAtAfter(LocalDateTime createdAt);
}
