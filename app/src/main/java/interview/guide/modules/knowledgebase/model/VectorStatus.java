/**
 * 知识库向量化状态枚举
 *
 * 定义向量化任务的四阶段生命周期：PENDING（待处理）→ PROCESSING（处理中）→ COMPLETED（完成）/ FAILED（失败）。
 * 存储在 KnowledgeBaseEntity.vectorStatus 字段中，由 VectorizeStreamConsumer 和 VectorizeStreamProducer 更新。
 *
 * 联动关系：
 * - 被 KnowledgeBaseEntity 的 vectorStatus 字段使用
 * - 被 VectorizeStreamConsumer 在消费过程中更新状态
 * - 被 VectorizeStreamProducer 在发送失败时标记为 FAILED
 * - 被 KnowledgeBaseListService 用于按状态过滤知识库列表
 * - 被 KnowledgeBaseController 用于解析前端传入的状态参数
 */
package interview.guide.modules.knowledgebase.model;

/**
 * 知识库向量化状态
 */
public enum VectorStatus {
    PENDING,     // 待处理
    PROCESSING,  // 处理中
    COMPLETED,   // 完成
    FAILED       // 失败
}
