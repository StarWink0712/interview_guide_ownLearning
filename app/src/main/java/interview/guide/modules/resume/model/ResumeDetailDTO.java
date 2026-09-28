/**
 * 简历详情响应 DTO
 *
 * 返回给前端的简历详情数据，包含简历基本信息、解析文本、分析状态、
 * 分析历史列表和关联的面试历史列表。内嵌 AnalysisHistoryDTO 记录
 * 展示每次分析的评分、摘要、优点和建议。
 *
 * 联动关系：
 * - 被 ResumeHistoryService 组装并返回给 ResumeController
 * - 由 ResumeMapper（MapStruct）从 ResumeAnalysisEntity 映射 AnalysisHistoryDTO
 * - 由 InterviewMapper（MapStruct）从面试会话映射 InterviewHistoryItemDTO
 * - 引用 interview 模块的 InterviewHistoryItemDTO 展示关联面试记录
 */
package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewHistoryItemDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历详情DTO
 */
public record ResumeDetailDTO(
    Long id,
    String filename,
    Long fileSize,
    String contentType,
    String storageUrl,
    LocalDateTime uploadedAt,
    Integer accessCount,
    String resumeText,
    AsyncTaskStatus analyzeStatus,
    String analyzeError,
    List<AnalysisHistoryDTO> analyses,
    List<InterviewHistoryItemDTO> interviews
) {
    /**
     * 分析历史DTO
     */
    public record AnalysisHistoryDTO(
        Long id,
        Integer overallScore,
        Integer contentScore,
        Integer structureScore,
        Integer skillMatchScore,
        Integer expressionScore,
        Integer projectScore,
        String summary,
        LocalDateTime analyzedAt,
        List<String> strengths,
        List<Object> suggestions
    ) {}
}

