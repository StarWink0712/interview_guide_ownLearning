/**
 * 面试评估报告 DTO
 *
 * 包含会话 ID、总分、各类别得分、逐题评估详情、总体评价、优势、改进建议和参考答案。
 * 内嵌 CategoryScore、QuestionEvaluation、ReferenceAnswer 三个子记录。
 *
 * 联动关系：
 * - 由 AnswerEvaluationService.evaluateInterview() 生成
 * - 由 InterviewPersistenceService.saveReport() 持久化到 InterviewSessionEntity
 * - 由 InterviewController 通过 /sessions/{sessionId}/report 接口返回给前端
 * - 由 InterviewSessionService.generateReport() 同步生成时使用
 */
package interview.guide.modules.interview.model;

import interview.guide.modules.interview.adaptive.model.AbilityProfileDTO;
import interview.guide.modules.interview.adaptive.model.AdaptiveTraceDTO;

import java.util.List;

/**
 * 面试评估报告
 */
public record InterviewReportDTO(
    String sessionId,
    int totalQuestions,
    int overallScore,                          // 总分 (0-100)
    List<CategoryScore> categoryScores,        // 各类别得分
    List<QuestionEvaluation> questionDetails,  // 每题详情
    String overallFeedback,                    // 总体评价
    List<String> strengths,                    // 优势
    List<String> improvements,                 // 改进建议
    List<ReferenceAnswer> referenceAnswers,    // 参考答案
    AbilityProfileDTO abilityProfile,          // 自适应能力画像
    List<AdaptiveTraceDTO> adaptiveTrace       // 自适应追踪
) {
    /**
     * 创建尚未附加自适应分析的基础报告。
     *
     * <p>由 {@code AnswerEvaluationService} 转换通用评估报告时调用，
     * abilityProfile 初始化为 null，adaptiveTrace 初始化为空列表；
     * 后续可由 {@code AdaptiveInterviewService.attachAdaptiveAnalysis} 补齐。
     */
    public InterviewReportDTO(
        String sessionId,
        int totalQuestions,
        int overallScore,
        List<CategoryScore> categoryScores,
        List<QuestionEvaluation> questionDetails,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<ReferenceAnswer> referenceAnswers
    ) {
        this(
            sessionId,
            totalQuestions,
            overallScore,
            categoryScores,
            questionDetails,
            overallFeedback,
            strengths,
            improvements,
            referenceAnswers,
            null,
            List.of()
        );
    }

    /**
     * 类别得分
     */
    public record CategoryScore(
        String category,
        int score,
        int questionCount
    ) {}
    
    /**
     * 问题评估详情
     */
    public record QuestionEvaluation(
        int questionIndex,
        String question,
        String category,
        String userAnswer,
        int score,
        String feedback
    ) {}
    
    /**
     * 参考答案
     */
    public record ReferenceAnswer(
        int questionIndex,
        String question,
        String referenceAnswer,
        List<String> keyPoints
    ) {}
}
