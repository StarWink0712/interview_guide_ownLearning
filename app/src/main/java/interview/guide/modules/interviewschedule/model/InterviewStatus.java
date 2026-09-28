/**
 * 面试日程状态枚举
 *
 * 定义面试日程的四种状态：PENDING（待面试）、COMPLETED（已完成）、
 * CANCELLED（已取消）、RESCHEDULED（已改期）。
 *
 * 联动关系：
 * - 被 InterviewScheduleEntity 作为 status 字段的枚举类型
 * - 被 InterviewScheduleService 用于状态变更逻辑
 * - 被 ScheduleStatusUpdater 将过期 PENDING 状态自动更新为 CANCELLED
 * - 被 InterviewScheduleController 作为状态更新接口的参数
 */
package interview.guide.modules.interviewschedule.model;

public enum InterviewStatus {
    PENDING,
    COMPLETED,
    CANCELLED,
    RESCHEDULED
}
