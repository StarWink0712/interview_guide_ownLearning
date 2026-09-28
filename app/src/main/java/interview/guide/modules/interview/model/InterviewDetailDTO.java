/**
 * 面试详情 DTO
 *
 * 包含面试会话的完整信息：基本信息、所有题目、优势/改进建议、参考答案和逐题答案详情。
 * 内嵌 AnswerDetailDTO 记录每题的答案、评分、反馈和关键点。
 *
 * 联动关系：
 * - 由 InterviewHistoryService.getInterviewDetail() 组装并返回
 * - 由 InterviewController 通过 /sessions/{sessionId}/details 接口返回给前端
 * - 通过 InterviewMapper（MapStruct）从 Entity 映射生成
 */
package interview.guide.modules.interview.model;

import interview.guide.modules.interview.adaptive.model.AbilityProfileDTO;
import interview.guide.modules.interview.adaptive.model.AdaptiveTraceDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试详情DTO
 */
public record InterviewDetailDTO(
    Long id,
    String sessionId,
    Integer totalQuestions,
    String status,
    String evaluateStatus,
    String evaluateError,
    Integer overallScore,
    String overallFeedback,
    LocalDateTime createdAt,
    LocalDateTime completedAt,
    List<Object> questions,
    List<String> strengths,
    List<String> improvements,
    List<Object> referenceAnswers,
    AbilityProfileDTO abilityProfile,
    List<AdaptiveTraceDTO> adaptiveTrace,
    List<AnswerDetailDTO> answers
) {
    /**
     * 答案详情DTO
     */
    public record AnswerDetailDTO(
        Integer questionIndex,
        String question,
        String category,
        String userAnswer,
        Integer score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints,
        LocalDateTime answeredAt
    ) {}
}
