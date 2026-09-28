/**
 * 知识库统计信息 DTO
 *
 * 聚合知识库的总数、总提问次数、总访问次数、已完成向量化数量和处理中数量，
 * 用于知识库管理页面的统计概览展示。
 *
 * 联动关系：
 * - 由 KnowledgeBaseListService.getStatistics() 构建并返回
 * - 被 KnowledgeBaseController 的 /api/knowledgebase/stats 接口使用
 */
package interview.guide.modules.knowledgebase.model;

/**
 * 知识库统计信息DTO
 */
public record KnowledgeBaseStatsDTO(
    long totalCount,           // 知识库总数
    long totalQuestionCount,   // 总提问次数
    long totalAccessCount,     // 总访问次数
    long completedCount,       // 已完成向量化数量
    long processingCount       // 处理中数量
) {
}
