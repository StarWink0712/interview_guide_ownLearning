/**
 * 面试会话 DTO
 *
 * 前端交互的核心数据结构，携带会话 ID、简历文本、题目总数、当前题目索引、
 * 题目列表和会话状态。内嵌 SessionStatus 枚举定义会话生命周期状态。
 *
 * 联动关系：
 * - 由 InterviewSessionService 创建/查询会话时返回
 * - 由 InterviewController 返回给前端
 * - SessionStatus 与 InterviewSessionEntity.SessionStatus 一一对应
 */
package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 面试会话DTO
 */
public record InterviewSessionDTO(
    String sessionId,
    String resumeText,
    int totalQuestions,
    int currentQuestionIndex,
    List<InterviewQuestionDTO> questions,
    SessionStatus status
) {
    public enum SessionStatus {
        CREATED,      // 会话已创建
        IN_PROGRESS,  // 面试进行中
        COMPLETED,    // 面试已完成
        EVALUATED     // 已生成评估报告
    }
}
