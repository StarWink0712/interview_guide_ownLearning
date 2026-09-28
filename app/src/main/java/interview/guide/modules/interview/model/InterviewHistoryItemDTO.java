/**
 * 面试历史列表项 DTO
 *
 * 用于简历详情页展示关联面试记录的轻量摘要，包含会话 ID、题目数、状态、
 * 评估状态、总分和时间信息，不含题目/答案等大字段。
 *
 * 联动关系：
 * - 由简历模块（ResumeService）查询关联面试记录时使用
 * - 数据来源为 InterviewSessionEntity 的部分字段映射
 */
package interview.guide.modules.interview.model;

import java.time.LocalDateTime;

/**
 * 面试历史列表项 DTO（用于简历详情页展示关联面试记录）
 */
public record InterviewHistoryItemDTO(
    Long id,
    String sessionId,
    Integer totalQuestions,
    String status,
    String evaluateStatus,
    String evaluateError,
    Integer overallScore,
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {}
