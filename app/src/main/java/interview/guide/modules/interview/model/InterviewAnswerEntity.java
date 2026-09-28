/**
 * 面试答案 JPA 实体
 *
 * 持久化每道题的用户答案、评分、反馈、参考答案和关键点。
 * 与 InterviewSessionEntity 为多对一关系，通过 (session_id, question_index) 唯一约束保证一题一答。
 *
 * 联动关系：
 * - 隶属于 InterviewSessionEntity（@ManyToOne），级联删除
 * - 由 InterviewPersistenceService 负责保存和查询
 * - 由 InterviewAnswerRepository 提供数据访问
 * - 被 InterviewHistoryService 用于构建面试详情 DTO
 */
package interview.guide.modules.interview.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 面试答案实体
 */
@Entity
@Table(name = "interview_answers",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_interview_answer_session_question", columnNames = {"session_id", "question_index"})
    },
    indexes = {
        @Index(name = "idx_interview_answer_session_question", columnList = "session_id,question_index")
    })
public class InterviewAnswerEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // 关联的会话
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSessionEntity session;
    
    // 问题索引
    @Column(name = "question_index")
    private Integer questionIndex;
    
    // 问题内容
    @Column(columnDefinition = "TEXT")
    private String question;
    
    // 问题类别
    private String category;
    
    // 用户答案
    @Column(columnDefinition = "TEXT")
    private String userAnswer;
    
    // 得分 (0-100)
    private Integer score;
    
    // 反馈
    @Column(columnDefinition = "TEXT")
    private String feedback;
    
    // 参考答案
    @Column(columnDefinition = "TEXT")
    private String referenceAnswer;
    
    // 关键点 (JSON)
    @Column(columnDefinition = "TEXT")
    private String keyPointsJson;
    
    // 回答时间
    @Column(nullable = false)
    private LocalDateTime answeredAt;

    /**
     * 在答案首次持久化前设置回答时间。
     *
     * <p>由 JPA 通过 {@link PrePersist} 自动调用；
     * 当前实现始终使用保存时刻覆盖字段值。
     */
    @PrePersist
    protected void onCreate() {
        answeredAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public InterviewSessionEntity getSession() {
        return session;
    }
    
    public void setSession(InterviewSessionEntity session) {
        this.session = session;
    }
    
    public Integer getQuestionIndex() {
        return questionIndex;
    }
    
    public void setQuestionIndex(Integer questionIndex) {
        this.questionIndex = questionIndex;
    }
    
    public String getQuestion() {
        return question;
    }
    
    public void setQuestion(String question) {
        this.question = question;
    }
    
    public String getCategory() {
        return category;
    }
    
    public void setCategory(String category) {
        this.category = category;
    }
    
    public String getUserAnswer() {
        return userAnswer;
    }
    
    public void setUserAnswer(String userAnswer) {
        this.userAnswer = userAnswer;
    }
    
    public Integer getScore() {
        return score;
    }
    
    public void setScore(Integer score) {
        this.score = score;
    }
    
    public String getFeedback() {
        return feedback;
    }
    
    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }
    
    public String getReferenceAnswer() {
        return referenceAnswer;
    }
    
    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }
    
    public String getKeyPointsJson() {
        return keyPointsJson;
    }
    
    public void setKeyPointsJson(String keyPointsJson) {
        this.keyPointsJson = keyPointsJson;
    }
    
    public LocalDateTime getAnsweredAt() {
        return answeredAt;
    }
    
    public void setAnsweredAt(LocalDateTime answeredAt) {
        this.answeredAt = answeredAt;
    }
}
