/**
 * 通用面试问答记录（文字面试和语音面试共用）
 *
 * 记录单条问答信息：题目序号、问题内容、分类标签、用户回答（null 表示未回答）。
 *
 * 联动关系：
 * - 由 AnswerEvaluationService（文字面试）、VoiceInterviewEvaluationService（语音面试）构建
 * - 被 UnifiedEvaluationService 接收，用于分批评估
 */
package interview.guide.common.evaluation;

/**
 * 通用面试问答记录（文字面试和语音面试共用）
 */
public record QaRecord(
    int questionIndex,
    String question,
    String category,
    String userAnswer   // null 表示未回答
) {}
