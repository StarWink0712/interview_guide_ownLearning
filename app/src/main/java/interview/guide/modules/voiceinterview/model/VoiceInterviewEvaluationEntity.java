/**
 * 语音面试评估结果 JPA 实体
 *
 * 持久化语音面试的评估结果，与会话一对一关联。存储总分、总评、逐题评估、
 * 优势、改进建议和参考答案，其中数组/对象类型数据以 JSON TEXT 列存储。
 * 字段设计与文字面试评估对齐，便于前端复用渲染组件。
 *
 * 联动关系：
 * - 被 VoiceInterviewEvaluationRepository 管理（CRUD）
 * - 被 VoiceInterviewEvaluationService 读写（生成评估、查询评估、构建 DTO）
 * - 被 VoiceInterviewService.deleteSession 级联删除
 * - 对应数据库表 voice_interview_evaluations
 */
package interview.guide.modules.voiceinterview.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Voice Interview Evaluation Entity
 * 语音面试评估实体
 * <p>
 * Stores evaluation results in a format aligned with text-based interviews:
 * per-question evaluations, overall feedback, strengths, improvements, and reference answers.
 * All structured data (arrays/objects) is stored as JSON TEXT columns.
 * </p>
 */
@Entity
@Table(name = "voice_interview_evaluations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", unique = true)
    private Long sessionId;

    @Column(name = "overall_score")
    private Integer overallScore;

    @Column(name = "overall_feedback", columnDefinition = "TEXT")
    private String overallFeedback;

    @Column(name = "question_evaluations_json", columnDefinition = "TEXT")
    private String questionEvaluationsJson;

    @Column(name = "strengths_json", columnDefinition = "TEXT")
    private String strengthsJson;

    @Column(name = "improvements_json", columnDefinition = "TEXT")
    private String improvementsJson;

    @Column(name = "reference_answers_json", columnDefinition = "TEXT")
    private String referenceAnswersJson;

    @Column(name = "interviewer_role")
    private String interviewerRole;

    @Column(name = "interview_date")
    private LocalDateTime interviewDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 在首次持久化评估记录前填充创建时间。
     *
     * 由 JPA {@link PrePersist} 生命周期自动调用，业务服务无需手动维护该审计字段。
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
