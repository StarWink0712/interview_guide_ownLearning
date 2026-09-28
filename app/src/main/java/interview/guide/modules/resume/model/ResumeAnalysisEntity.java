/**
 * 简历评测结果 JPA 实体
 *
 * 持久化 AI 对简历的分析结果，包括总分、各维度评分（内容完整性、结构清晰度、
 * 技能匹配度、表达专业性、项目经验）、摘要、优点列表和改进建议列表。
 * 优点和建议以 JSON 字符串形式存储在 TEXT 列中。
 * 通过 @ManyToOne 关联到 ResumeEntity，支持同一简历多次分析的历史记录。
 *
 * 联动关系：
 * - @ManyToOne 关联 ResumeEntity，属于某份简历
 * - 被 ResumePersistenceService 读写（保存分析结果、查询历史记录）
 * - 被 ResumeAnalysisRepository 管理
 * - 被 ResumeHistoryService 通过 PersistenceService 间接查询
 * - 被 ResumeMapper（MapStruct）映射为 ResumeDetailDTO.AnalysisHistoryDTO
 */
package interview.guide.modules.resume.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 简历评测结果实体
 * Resume Analysis Entity
 */
@Entity
@Table(name = "resume_analyses")
public class ResumeAnalysisEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // 关联的简历
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    //那也就是说，多份分析结果，以resumu_id为基础，多个结果关联到一个id的简历。
    //前端展示分析历史时，就是根据 resume_id 查出这条线所有的分析记录，按时间排列展示历次评分变化。
    private ResumeEntity resume;
    
    // 总分 (0-100)
    private Integer overallScore;
    
    // 各维度评分
    private Integer contentScore;      // 内容完整性 (0-25)
    private Integer structureScore;    // 结构清晰度 (0-20)
    private Integer skillMatchScore;   // 技能匹配度 (0-25)
    private Integer expressionScore;   // 表达专业性 (0-15)
    private Integer projectScore;      // 项目经验 (0-15)
    
    // 简历摘要
    @Column(columnDefinition = "TEXT")
    private String summary;
    
    // 优点列表 (JSON格式)
    @Column(columnDefinition = "TEXT")
    private String strengthsJson;
    
    // 改进建议列表 (JSON格式)
    @Column(columnDefinition = "TEXT")
    private String suggestionsJson;
    
    // 评测时间
    @Column(nullable = false)
    private LocalDateTime analyzedAt;
    
    @PrePersist
    protected void onCreate() {
        analyzedAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public ResumeEntity getResume() {
        return resume;
    }
    
    public void setResume(ResumeEntity resume) {
        this.resume = resume;
    }
    
    public Integer getOverallScore() {
        return overallScore;
    }
    
    public void setOverallScore(Integer overallScore) {
        this.overallScore = overallScore;
    }
    
    public Integer getContentScore() {
        return contentScore;
    }
    
    public void setContentScore(Integer contentScore) {
        this.contentScore = contentScore;
    }
    
    public Integer getStructureScore() {
        return structureScore;
    }
    
    public void setStructureScore(Integer structureScore) {
        this.structureScore = structureScore;
    }
    
    public Integer getSkillMatchScore() {
        return skillMatchScore;
    }
    
    public void setSkillMatchScore(Integer skillMatchScore) {
        this.skillMatchScore = skillMatchScore;
    }
    
    public Integer getExpressionScore() {
        return expressionScore;
    }
    
    public void setExpressionScore(Integer expressionScore) {
        this.expressionScore = expressionScore;
    }
    
    public Integer getProjectScore() {
        return projectScore;
    }
    
    public void setProjectScore(Integer projectScore) {
        this.projectScore = projectScore;
    }
    
    public String getSummary() {
        return summary;
    }
    
    public void setSummary(String summary) {
        this.summary = summary;
    }
    
    public String getStrengthsJson() {
        return strengthsJson;
    }
    
    public void setStrengthsJson(String strengthsJson) {
        this.strengthsJson = strengthsJson;
    }
    
    public String getSuggestionsJson() {
        return suggestionsJson;
    }
    
    public void setSuggestionsJson(String suggestionsJson) {
        this.suggestionsJson = suggestionsJson;
    }
    
    public LocalDateTime getAnalyzedAt() {
        return analyzedAt;
    }
    
    public void setAnalyzedAt(LocalDateTime analyzedAt) {
        this.analyzedAt = analyzedAt;
    }
}
