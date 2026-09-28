/**
 * 面试会话列表项 DTO
 *
 * 轻量级 DTO，用于面试记录列表页展示，不含题目/答案等大字段。
 * 提供 from() 静态工厂方法从 InterviewSessionEntity 直接映射。
 *
 * 联动关系：
 * - 由 InterviewController.listSessions() 使用 from() 方法从 Entity 转换
 * - 数据来源为 InterviewSessionEntity 的部分字段
 */
package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;

import java.time.LocalDateTime;

/**
 * 面试会话列表项 DTO（轻量，不含题目/答案等大字段）
 */
public record SessionListItemDTO(
    String sessionId,
    String skillId,
    String difficulty,
    Long resumeId,
    int totalQuestions,
    SessionStatus status,
    AsyncTaskStatus evaluateStatus,
    String evaluateError,
    Integer overallScore,
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {
    /**
     * 从会话实体创建轻量列表项。
     *
     * <p>由 {@code InterviewController.listSessions} 对查询结果逐条调用，
     * 只复制列表页需要的字段，并把空题目数归零。
     *
     * @param e 会话实体
     * @return 不包含题目和答案大字段的列表 DTO
     */
    public static SessionListItemDTO from(InterviewSessionEntity e) {
        return new SessionListItemDTO(
            e.getSessionId(),
            e.getSkillId(),
            e.getDifficulty(),
            e.getResumeId(),
            e.getTotalQuestions() != null ? e.getTotalQuestions() : 0,
            e.getStatus(),
            e.getEvaluateStatus(),
            e.getEvaluateError(),
            e.getOverallScore(),
            e.getCreatedAt(),
            e.getCompletedAt()
        );
    }
}
