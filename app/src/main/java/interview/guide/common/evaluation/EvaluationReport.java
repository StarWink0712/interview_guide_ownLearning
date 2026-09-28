/**
 * 通用面试评估报告（文字面试和语音面试共用）
 *
 * 包含会话 ID、总题数、总分、分类得分、逐题评估详情、综合评语、
 * 优势列表、改进建议列表、参考答案列表。
 * 内嵌 CategoryScore、QuestionEvaluation、ReferenceAnswer 三个 record。
 *
 * 联动关系：
 * - 由 UnifiedEvaluationService 构建并返回
 * - 被 AnswerEvaluationService（文字面试）、VoiceInterviewEvaluationService（语音面试）使用
 */
package interview.guide.common.evaluation;

import java.util.List;

public record EvaluationReport(
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
    /**
     * 分类得分
     *
     * @param category 分类名称（如"算法能力"、"项目经验"）
     * @param score 该分类总分
     * @param questionCount 该分类题目数
     */
    public record CategoryScore(
        String category,
        int score,
        int questionCount
    ) {}

    /**
     * 逐题评估详情
     *
     * @param questionIndex 题目序号
     * @param question 题目内容
     * @param category 题目分类
     * @param userAnswer 用户回答
     * @param score 得分
     * @param feedback 评估反馈
     */
    public record QuestionEvaluation(
        // 题目序号
        // 从 1 开始，与用户输入的序号一致
        int questionIndex,
        // 题目内容
        String question,
        // 题目分类
        // 从用户输入的分类中提取
        String category,
        // 用户回答
        String userAnswer,
        // 得分
        int score,
        // 评估反馈
        String feedback
    ) {}

    /**
     * 参考答案
     *
     * @param questionIndex 题目序号
     * @param question 题目内容
     * @param referenceAnswer AI 生成的参考答案
     * @param keyPoints 关键要点列表
     */
    public record ReferenceAnswer(
        int questionIndex,
        String question,
        String referenceAnswer,
        List<String> keyPoints
    ) {}
}
