/**
 * 面试日程数据传输对象
 *
 * 用于 Service 层向前端返回面试日程数据，包含所有日程字段及状态、创建/更新时间。
 * 避免直接暴露 Entity 给前端。
 *
 * 联动关系：
 * - 由 InterviewScheduleService 的 toDTO 方法从 InterviewScheduleEntity 转换而来
 * - 被 InterviewScheduleController 作为所有查询/创建/更新接口的响应体返回
 */
package interview.guide.modules.interviewschedule.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class InterviewScheduleDTO {
    private Long id;
    private String companyName;
    private String position;

    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private java.time.LocalDateTime interviewTime;
    private String interviewType;
    private String meetingLink;
    private Integer roundNumber;
    private String interviewer;
    private String notes;
    private Boolean reminderEnabled;
    private String reminderEmail;
    private InterviewStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
