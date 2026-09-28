package interview.guide.modules.interviewschedule.reminder.repository;

import interview.guide.modules.interviewschedule.reminder.model.InterviewReminderEntity;
import interview.guide.modules.interviewschedule.reminder.model.ReminderChannel;
import interview.guide.modules.interviewschedule.reminder.model.ReminderStatus;
import interview.guide.modules.interviewschedule.reminder.model.ReminderTiming;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 面试提醒任务的数据访问层。 */
public interface InterviewReminderRepository
    extends JpaRepository<InterviewReminderEntity, Long> {

  Optional<InterviewReminderEntity> findByScheduleIdAndChannelAndTiming(
      Long scheduleId,
      ReminderChannel channel,
      ReminderTiming timing);

  List<InterviewReminderEntity> findByScheduleId(Long scheduleId);

  List<InterviewReminderEntity> findByScheduleIdIn(Collection<Long> scheduleIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select reminder from InterviewReminderEntity reminder
      where reminder.status = :status
        and reminder.remindAt <= :now
      order by reminder.remindAt asc
      """)
  List<InterviewReminderEntity> findDueForUpdate(
      @Param("status") ReminderStatus status,
      @Param("now") LocalDateTime now,
      Pageable pageable);

  @Query("""
      select reminder from InterviewReminderEntity reminder
      where reminder.channel = :channel
        and reminder.remindAt <= :now
        and reminder.status not in :excludedStatuses
        and (:unreadOnly = false or reminder.readAt is null)
      order by reminder.remindAt desc
      """)
  List<InterviewReminderEntity> findVisibleInApp(
      @Param("channel") ReminderChannel channel,
      @Param("now") LocalDateTime now,
      @Param("excludedStatuses") Collection<ReminderStatus> excludedStatuses,
      @Param("unreadOnly") boolean unreadOnly);

  @Query("""
      select count(reminder) from InterviewReminderEntity reminder
      where reminder.channel = :channel
        and reminder.remindAt <= :now
        and reminder.readAt is null
        and reminder.status not in :excludedStatuses
      """)
  long countUnreadInApp(
      @Param("channel") ReminderChannel channel,
      @Param("now") LocalDateTime now,
      @Param("excludedStatuses") Collection<ReminderStatus> excludedStatuses);
}
