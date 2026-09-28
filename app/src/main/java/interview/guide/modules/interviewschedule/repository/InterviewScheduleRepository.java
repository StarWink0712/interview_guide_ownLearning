/**
 * 面试日程数据访问层
 *
 * 提供 InterviewScheduleEntity 的 JPA 持久化操作，包括按状态和时间范围查询、
 * 批量更新过期面试状态等自定义查询方法。
 *
 * 联动关系：
 * - 被 InterviewScheduleService 依赖，执行日程的 CRUD 和条件查询
 * - 被 ScheduleStatusUpdater 依赖，查询和批量更新过期的 PENDING 状态日程
 */
package interview.guide.modules.interviewschedule.repository;

import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InterviewScheduleRepository extends JpaRepository<InterviewScheduleEntity, Long> {
    //按状态和时间查询面试日程实体
    List<InterviewScheduleEntity> findByStatusAndInterviewTimeBefore(InterviewStatus status, LocalDateTime time);

    //按状态查询
    List<InterviewScheduleEntity> findByStatus(InterviewStatus status);

    //按时间范围查询
    List<InterviewScheduleEntity> findByInterviewTimeBetween(LocalDateTime start, LocalDateTime end);

    //批量更新状态
    @Modifying
    @Query("UPDATE InterviewScheduleEntity e SET e.status = :newStatus WHERE e.status = :oldStatus AND e.interviewTime < :cutoff")
    int updateStatusByStatusAndInterviewTimeBefore(
        @Param("newStatus") InterviewStatus newStatus,
        @Param("oldStatus") InterviewStatus oldStatus,
        @Param("cutoff") LocalDateTime cutoff);
}
