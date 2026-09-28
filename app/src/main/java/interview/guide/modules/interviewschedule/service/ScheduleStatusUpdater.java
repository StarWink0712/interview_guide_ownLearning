/**
 * 面试日程状态定时更新器
 *
 * <p>通过 Spring @Scheduled 定时任务，自动将面试时间已过且仍为 PENDING 状态的
 * 日程标记为 CANCELLED，保证日程状态的时效性。</p>
 *
 * <p>为什么需要这个定时任务？<br>
 * 面试时间过去后，如果用户没有手动取消或完成日程，该日程会一直显示为"待面试"状态，
 * 给用户造成"还有面试"的误导。这个定时器每小时检查一次，自动清理过期未处理的日程。</p>
 *
 * <p>联动关系：<br>
 * - 依赖 InterviewScheduleRepository.updateStatusByStatusAndInterviewTimeBefore()
 *   执行批量更新（一条 SQL 更新所有符合条件的记录，而非逐条查询再更新）<br>
 * - 使用 @Scheduled(cron) 由 Spring 调度框架定时触发<br>
 * - 与 InterviewScheduleEntity 的 InterviewStatus 枚举配合使用</p>
 */
package interview.guide.modules.interviewschedule.service;

import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.interviewschedule.reminder.service.InterviewReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试日程过期状态自动更新器 —— 每小时扫描一次，将过期未处理的日程标记为已取消
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleStatusUpdater {

    /** 面试日程数据仓库，用于执行批量状态更新 */
    private final InterviewScheduleRepository repository;
    private final InterviewReminderService reminderService;

    /**
     * 更新过期面试日程状态
     *
     * <p>定时执行策略：每小时整点执行一次（cron = "0 0 * * * ?"）。</p>
     *
     * <p>执行逻辑：</p>
     * <ol>
     *   <li>查询所有 status = PENDING 且 interviewTime &lt; 当前时间的记录</li>
     *   <li>将这些记录的状态批量更新为 CANCELLED</li>
     *   <li>记录本次更新影响的记录条数（>0 时输出日志提醒）</li>
     * </ol>
     *
     * <p>设计要点：</p>
     * <ul>
     *   <li><b>批量更新而非逐条更新</b>：@Modifying + @Query 直接在数据库端执行 UPDATE，
     *       不需要先把数据查到 Java 内存再逐条 save()，性能更好且避免大事务</li>
     *   <li><b>@Transactional 保证原子性</b>：整个批量更新是一个事务，
     *       不会出现"部分更新成功、部分失败"的不一致状态</li>
     *   <li><b>状态统一改为 CANCELLED</b>：过期未处理的面试视为被取消，
     *       区别于主动取消和已完成</li>
     *   <li><b>仅当日志有影响时才打印</b>：大多数时候没有过期记录，不写日志避免日志刷屏</li>
     * </ul>
     */
    @Scheduled(cron = "0 0 * * * ?")    // 每小时整点执行（如 10:00、11:00、12:00）
    @Transactional
    public void updateExpiredInterviews() {
        LocalDateTime cutoff = LocalDateTime.now();
        List<Long> expiredScheduleIds = repository
                .findByStatusAndInterviewTimeBefore(InterviewStatus.PENDING, cutoff)
                .stream()
                .map(interview -> interview.getId())
                .toList();
        // 执行批量更新：将 status=PENDING 且 interviewTime<now 的记录改为 CANCELLED
        int updated = repository.updateStatusByStatusAndInterviewTimeBefore(
                InterviewStatus.CANCELLED,     // 目标状态：已取消
                InterviewStatus.PENDING,       // 当前状态：待面试
                cutoff);                       // 截止时间：当前时刻

        reminderService.cancelActiveReminders(
                expiredScheduleIds,
                "面试时间已过，日程自动取消");

        // 只在有记录被更新时输出日志，避免每次执行都打印无意义的空结果
        if (updated > 0) {
            log.info("已将 {} 条过期面试标记为已取消", updated);
        }
    }
}
