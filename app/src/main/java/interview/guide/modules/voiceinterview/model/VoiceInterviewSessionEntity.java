/**
 * 语音面试会话 JPA 实体
 *
 * 语音面试模块的核心实体，记录会话的完整生命周期信息，包括用户 ID、技能模板、难度、
 * 关联简历、各阶段开关、LLM Provider、当前阶段与状态、计划/实际时长、
 * 起止时间、暂停/恢复时间及异步评估状态。内嵌 InterviewPhase 枚举定义面试阶段。
 *
 * 联动关系：
 * - 被 VoiceInterviewSessionRepository 管理（CRUD）
 * - 被 VoiceInterviewService 核心操作（创建、结束、暂停、恢复、阶段切换、缓存）
 * - 被 VoiceInterviewWebSocketHandler 读取（获取会话信息用于 LLM 上下文构建）
 * - 被 VoiceInterviewEvaluationService 读取（评估时获取会话元信息）
 * - 被 VoiceEvaluateStreamConsumer/Producer 更新（评估状态流转）
 * - 被 VoiceInterviewController 间接使用（通过 Service 返回 DTO）
 * - 对应数据库表 voice_interview_sessions
 */
package interview.guide.modules.voiceinterview.model;

import interview.guide.common.model.AsyncTaskStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "voice_interview_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "role_type", nullable = false)
    private String roleType;

    @Column(name = "skill_id", length = 64)
    @Builder.Default
    private String skillId = "java-backend";

    @Column(name = "difficulty", length = 16)
    @Builder.Default
    private String difficulty = "mid";

    @Column(name = "custom_jd_text", columnDefinition = "TEXT")
    private String customJdText;

    @Column(name = "resume_id")
    private Long resumeId;

    @Column(name = "intro_enabled")
    @Builder.Default
    private Boolean introEnabled = true;

    @Column(name = "tech_enabled")
    @Builder.Default
    private Boolean techEnabled = true;

    @Column(name = "project_enabled")
    @Builder.Default
    private Boolean projectEnabled = true;

    @Column(name = "hr_enabled")
    @Builder.Default
    private Boolean hrEnabled = true;

    @Column(name = "llm_provider", length = 50)
    @Builder.Default
    private String llmProvider = "dashscope";

    @Column(name = "current_phase")
    @Enumerated(EnumType.STRING)
    private InterviewPhase currentPhase;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private VoiceInterviewSessionStatus status = VoiceInterviewSessionStatus.IN_PROGRESS;

    @Column(name = "planned_duration")
    @Builder.Default
    private Integer plannedDuration = 30;

    @Column(name = "actual_duration")
    private Integer actualDuration;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "resumed_at")
    private LocalDateTime resumedAt;

    @Column(name = "evaluate_status")
    @Enumerated(EnumType.STRING)
    private AsyncTaskStatus evaluateStatus;

    @Column(name = "evaluate_error", length = 500)
    private String evaluateError;

    /**
     * 在 JPA 更新会话前刷新最后修改时间。
     *
     * 暂停、恢复、阶段切换、结束和评估状态更新都会触发，供会话列表按更新时间排序。
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 在首次持久化会话前初始化创建、更新和开始时间。
     *
     * 由 JPA {@link PrePersist} 生命周期自动调用，因此创建服务只需设置业务字段。
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.startTime = LocalDateTime.now();
    }

    public enum InterviewPhase {
        INTRO, TECH, PROJECT, HR, COMPLETED
    }
}
