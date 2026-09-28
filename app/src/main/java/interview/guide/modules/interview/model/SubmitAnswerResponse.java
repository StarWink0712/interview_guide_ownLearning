/**
 * 提交答案响应体
 *
 * 返回提交答案后的状态：是否还有下一题、下一题内容、当前题目索引和总题数。
 * 前端据此决定是继续答题还是跳转到报告页。
 *
 * 联动关系：
 * - 由 InterviewSessionService.submitAnswer() 返回
 * - 由 InterviewController 转发给前端
 */
package interview.guide.modules.interview.model;

import interview.guide.modules.interview.adaptive.model.AbilityProfileDTO;
import interview.guide.modules.interview.adaptive.model.AdaptiveDecisionDTO;
import interview.guide.modules.interview.adaptive.model.TurnEvaluationDTO;

/**
 * 提交答案响应
 */
public record SubmitAnswerResponse(
    boolean hasNextQuestion,
    InterviewQuestionDTO nextQuestion,
    int currentIndex,
    int totalQuestions,
    TurnEvaluationDTO turnEvaluation,
    AdaptiveDecisionDTO adaptiveDecision,
    AbilityProfileDTO abilityProfile
) {
    /**
     * 创建不包含自适应评估数据的基础提交响应。
     *
     * <p>供关闭自适应能力或无需即时反馈的调用场景使用，
     * 三个自适应字段统一初始化为 {@code null}。
     */
    public SubmitAnswerResponse(
        boolean hasNextQuestion,
        InterviewQuestionDTO nextQuestion,
        int currentIndex,
        int totalQuestions
    ) {
        this(hasNextQuestion, nextQuestion, currentIndex, totalQuestions, null, null, null);
    }
}
