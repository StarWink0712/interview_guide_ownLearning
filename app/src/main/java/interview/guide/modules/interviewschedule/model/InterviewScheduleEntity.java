/**
 * 面试日程 JPA 持久化实体
 *
 * 映射 interview_schedule 表，存储面试日程的完整信息，包括公司名称、岗位、面试时间、
 * 面试形式、会议链接、轮次、面试官、备注和状态。使用 @PrePersist/@PreUpdate 自动维护时间戳。
 *
 * 联动关系：
 * - 被 InterviewScheduleRepository 持久化管理
 * - 被 InterviewScheduleService 通过 BeanUtils.copyProperties 与 DTO/Request 互转
 * - 被 ScheduleStatusUpdater 通过 Repository 批量更新过期状态
 */
package interview.guide.modules.interviewschedule.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "interview_schedule")
@Data
public class InterviewScheduleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(nullable = false)
    private String position;

    @Column(name = "interview_time", nullable = false)
    private LocalDateTime interviewTime;

    @Column(name = "interview_type")
    private String interviewType; // ONSITE, VIDEO, PHONE

    @Column(name = "meeting_link", columnDefinition = "TEXT")
    private String meetingLink;

    @Column(name = "round_number")
    private Integer roundNumber = 1;

    private String interviewer;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "reminder_enabled")
    private Boolean reminderEnabled = true;

    @Column(name = "reminder_email", length = 320)
    private String reminderEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InterviewStatus status = InterviewStatus.PENDING;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    //在 JPA 自动保存/更新数据之前，先把时间戳填好
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
