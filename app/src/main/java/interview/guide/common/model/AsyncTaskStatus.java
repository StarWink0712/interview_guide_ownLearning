/**
 * 异步任务状态枚举
 *
 * 定义异步任务的四种状态：PENDING（待处理）、PROCESSING（处理中）、
 * COMPLETED（完成）、FAILED（失败）。
 * 用于知识库向量化、简历分析、面试评估等异步任务的状态追踪。
 *
 * 联动关系：
 * - 被各异步任务 Entity（如 KnowledgeBaseEntity、ResumeEntity）使用，标记任务处理状态
 * - 被 AbstractStreamConsumer 子类在 markProcessing/markCompleted/markFailed 中更新
 */
package interview.guide.common.model;

public enum AsyncTaskStatus {
    /** 待处理 */
    PENDING,
    /** 处理中 */
    PROCESSING,
    /** 已完成 */
    COMPLETED,
    /** 失败 */
    FAILED,
    /** 已取消 */
    CANCELLED,
    /** 超过最大重试次数，进入死信 */
    DEAD_LETTER
}
