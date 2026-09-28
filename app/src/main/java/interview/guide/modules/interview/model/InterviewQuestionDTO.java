/**
 * 面试问题 DTO
 *
 * 表示一道面试题目，携带题目索引、题面、分类 key/type、展示标签、知识点摘要、
 * 用户答案、评分、反馈，以及追问标记和父题索引。
 * type 由 Skill category key 驱动（如 MYSQL、CSS、DYNAMIC_PROGRAMMING）。
 * 提供 withAnswer() 和 withEvaluation() 方法用于不可变更新。
 *
 * 联动关系：
 * - 由 InterviewQuestionService 生成，存入 InterviewSessionEntity.questionsJson
 * - 由 InterviewSessionService 在答题流程中读写
 * - 由 EvaluateStreamConsumer/AnswerEvaluationService 在评估阶段使用
 * - 被 InterviewReportDTO 引用构建评估报告
 */
package interview.guide.modules.interview.model;

/**
 * 面试问题DTO
 * type 由 Skill category key 驱动（如 MYSQL、CSS、DYNAMIC_PROGRAMMING 等），不再使用枚举
 */
public record InterviewQuestionDTO(
    int questionIndex,
    String question,
    String type,           // Skill category key，如 "MYSQL"、"CSS"、"DP"
    String category,       // 展示用标签，如 "MySQL"、"CSS"、"动态规划"
    String topicSummary,   // 知识点摘要，如 "Redis RDB/AOF 持久化对比"，用于历史去重压缩
    String userAnswer,
    Integer score,
    String feedback,
    boolean isFollowUp,
    Integer parentQuestionIndex
) {
    /**
     * 创建不含知识点摘要和追问信息的普通主问题。
     *
     * <p>供简单题目构建场景调用，其余可选字段初始化为空。
     */
    public static InterviewQuestionDTO create(int index, String question, String type, String category) {
        return new InterviewQuestionDTO(index, question, type, category, null, null, null, null, false, null);
    }

    /**
     * 创建带知识点摘要和父子关系的题目。
     *
     * <p>由题目生成服务创建主问题、LLM 追问和本地兜底追问时调用。
     */
    public static InterviewQuestionDTO create(int index, String question, String type, String category,
                                               String topicSummary, boolean isFollowUp, Integer parentQuestionIndex) {
        return new InterviewQuestionDTO(index, question, type, category, topicSummary, null, null, null, isFollowUp, parentQuestionIndex);
    }

    /**
     * 返回只替换用户答案的新题目副本。
     *
     * <p>由会话提交/暂存、缓存恢复和最终评估答案合并流程调用。
     */
    public InterviewQuestionDTO withAnswer(String answer) {
        return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary, answer, score, feedback, isFollowUp, parentQuestionIndex);
    }

    /**
     * 返回只替换评分与反馈的新题目副本。
     *
     * <p>供不可变更新评估结果的调用场景使用。
     */
    public InterviewQuestionDTO withEvaluation(int score, String feedback) {
        return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary, userAnswer, score, feedback, isFollowUp, parentQuestionIndex);
    }
}
