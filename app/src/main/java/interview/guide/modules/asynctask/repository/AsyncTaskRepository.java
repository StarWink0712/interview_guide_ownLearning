/**
 * 异步任务数据访问仓库
 *
 * 基于 Spring Data JPA 提供异步任务实体的持久化查询能力。
 * 所有查询方法遵循 Spring Data 方法命名约定，由框架自动生成 JPQL/SQL。
 * 查询结果按创建时间倒序排列，支持按任务类型和状态组合过滤。
 *
 * 联动关系：
 * - 被 AsyncTaskService 调用（所有查询方法的唯一调用方）
 * - 操作实体：AsyncTaskEntity（异步任务持久化模型）
 * - 依赖枚举：AsyncTaskType（任务类型）、AsyncTaskStatusDTO（任务状态）
 * - countByCreatedAtAfter / countByStatusInAndCreatedAtAfter 由监控模块用于指标统计
 */
package interview.guide.modules.asynctask.repository;

import interview.guide.modules.asynctask.model.AsyncTaskEntity;
import interview.guide.modules.asynctask.model.AsyncTaskStatusDTO;
import interview.guide.modules.asynctask.model.AsyncTaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 异步任务数据访问仓库
 * 继承 {@code JpaRepository<AsyncTaskEntity, Long>}，自动获得基础 CRUD 方法
 */
@Repository
public interface AsyncTaskRepository extends JpaRepository<AsyncTaskEntity, Long> {

  /**
   * 查询同一业务对象下的第一个活跃任务（按创建时间倒序）
   *
   * <p>调用入口：{@code AsyncTaskService.createOrReusePendingTask}。
   * Spring Data 根据方法名生成“任务类型 + 业务 ID + 状态集合”查询，
   * 只返回创建时间最新的一条记录，用于任务去重。
   *
   * 用于 createOrReusePendingTask 方法的去重逻辑：
   * 在创建新任务前先查是否存在同类型、同业务对象且处于
   * 活跃状态（PENDING/PROCESSING）的已有任务。
   * 存在则复用已有任务，不存在则创建新任务。
   *
   * 活跃状态由调用方传入 ACTIVE_STATUSES = {PENDING, PROCESSING}，
   * 保证同一业务对象在活跃状态下只有一个任务实例。
   *
   * @param taskType 异步任务类型
   * @param businessId 业务对象 ID（如简历 ID、知识库 ID、面试会话 ID）
   * @param statuses   活跃状态集合（PENDING 和 PROCESSING）
   * @return 找到的最新活跃任务，不存在时返回空 Optional
   */
  Optional<AsyncTaskEntity> findFirstByTaskTypeAndBusinessIdAndStatusInOrderByCreatedAtDesc(
      AsyncTaskType taskType,
      String businessId,
      Collection<AsyncTaskStatusDTO> statuses
  );

  /**
   * 查询全部任务（按创建时间倒序）
   *
   * <p>由 {@code AsyncTaskService.listTasks} 在没有过滤条件时调用。
   *
   * @return 全部任务实体列表（按创建时间倒序）
   */
  List<AsyncTaskEntity> findAllByOrderByCreatedAtDesc();

  /**
   * 按任务类型查询（按创建时间倒序）
   *
   * <p>由 {@code AsyncTaskService.listTasks} 在只指定任务类型时调用。
   *
   * @param taskType 异步任务类型
   * @return 指定类型的任务实体列表（按创建时间倒序）
   */
  List<AsyncTaskEntity> findByTaskTypeOrderByCreatedAtDesc(AsyncTaskType taskType);

  /**
   * 按任务状态查询（按创建时间倒序）
   *
   * <p>由 {@code AsyncTaskService.listTasks} 在只指定任务状态时调用。
   * 运维常用：查找所有 FAILED 状态任务以进行批量处理。
   *
   * @param status 任务状态
   * @return 指定状态的任务实体列表（按创建时间倒序）
   */
  List<AsyncTaskEntity> findByStatusOrderByCreatedAtDesc(AsyncTaskStatusDTO status);

  /**
   * 按任务类型和状态组合查询（按创建时间倒序）
   *
   * <p>由 {@code AsyncTaskService.listTasks} 在同时指定类型和状态时调用。
   * 运维常用：查找特定类型下的失败任务（如 RESUME_ANALYZE + FAILED）。
   *
   * @param taskType 异步任务类型
   * @param status   任务状态
   * @return 同时满足类型和状态条件的任务实体列表（按创建时间倒序）
   */
  List<AsyncTaskEntity> findByTaskTypeAndStatusOrderByCreatedAtDesc(
      AsyncTaskType taskType,
      AsyncTaskStatusDTO status
  );

  /**
   * 统计指定时间之后创建的任务总数
   *
   * <p>由 {@code BusinessMetricsQueryService} 调用，
   * 用于统计监控时间窗口内的任务吞吐量。
   *
   * @param createdAt 起始时间
   * @return 符合条件的任务数量
   */
  long countByCreatedAtAfter(LocalDateTime createdAt);

  /**
   * 统计指定时间之后创建的特定状态任务数量
   *
   * <p>由 {@code BusinessMetricsQueryService} 调用，
   * 用于统计监控时间窗口内失败和死信任务的数量。
   *
   * @param statuses  状态集合（如 {FAILED, DEAD_LETTER}）
   * @param createdAt 起始时间
   * @return 符合条件的任务数量
   */
  long countByStatusInAndCreatedAtAfter(
      Collection<AsyncTaskStatusDTO> statuses,
      LocalDateTime createdAt
  );
}
