/**
 * 简历分析响应 DTO
 *
 * 携带 AI 简历评分结果：总分、各维度评分详情（内容完整性、结构清晰度、技能匹配度、
 * 表达专业性、项目经验）、简历摘要、优点列表和改进建议。
 * 内嵌 ScoreDetail 和 Suggestion 子记录。
 *
 * 联动关系：
 * - 位于 interview 模块 model 包中，但实际由简历模块的 AI 评分功能使用
 * - 作为 LLM 结构化输出的目标类型
 */
package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 简历分析响应DTO
 */
public record ResumeAnalysisResponse(
    // 总分 (0-100)
    int overallScore,
    
    // 各维度评分
    ScoreDetail scoreDetail,
    
    // 简历摘要
    String summary,
    
    // 优点列表
    List<String> strengths,
    
    // 改进建议列表
    List<Suggestion> suggestions,
    
    // 原始简历文本
    String originalText
) {
    
    /**
     * 各维度评分详情
     */
    public record ScoreDetail(
        int contentScore,       // 内容完整性 (0-25)
        int structureScore,     // 结构清晰度 (0-20)
        int skillMatchScore,    // 技能匹配度 (0-25)
        int expressionScore,    // 表达专业性 (0-15)
        int projectScore        // 项目经验 (0-15)
    ) {}
    
    /**
     * 改进建议
     */
    public record Suggestion(
        String category,        // 建议类别：内容、格式、技能、项目等
        String priority,        // 优先级：高、中、低
        String issue,           // 问题描述
        String recommendation   // 具体建议
    ) {}
}
