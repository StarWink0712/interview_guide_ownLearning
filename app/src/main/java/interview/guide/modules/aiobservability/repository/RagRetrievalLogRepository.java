package interview.guide.modules.aiobservability.repository;

import interview.guide.modules.aiobservability.model.RagRetrievalLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RagRetrievalLogRepository extends JpaRepository<RagRetrievalLogEntity, Long> {

  /**
   * 根据 traceId 查询唯一的 RAG 检索主日志。
   *
   * <p>由 {@code RagRetrievalLogService.getTrace} 调用，
   * 查询结果随后会与命中文档块组装。
   *
   * @param traceId 链路追踪 ID
   * @return 对应主日志；不存在时为空
   */
  Optional<RagRetrievalLogEntity> findByTraceId(String traceId);

  /**
   * 查询最新的 20 条 RAG 检索主日志。
   *
   * <p>由 {@code RagRetrievalLogService.listRecentTraces} 调用，
   * Spring Data 根据方法名生成倒序和条数限制。
   *
   * @return 从新到旧排列的检索日志，最多 20 条
   */
  List<RagRetrievalLogEntity> findTop20ByOrderByCreatedAtDesc();

  /**
   * 查询指定时间之后产生的 RAG 检索日志。
   *
   * <p>由 {@code BusinessMetricsQueryService} 调用，
   * 用于计算监控时间窗口内的检索指标。
   *
   * @param createdAt 统计窗口起始时间
   * @return 创建时间晚于起始时间的检索日志
   */
  List<RagRetrievalLogEntity> findByCreatedAtAfter(LocalDateTime createdAt);
}
