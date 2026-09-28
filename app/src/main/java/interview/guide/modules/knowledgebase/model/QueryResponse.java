/**
 * 知识库 RAG 查询响应
 *
 * 知识库同步问答接口的响应体，包含 AI 回答内容、主知识库 ID 和知识库名称。
 *
 * 联动关系：
 * - 由 KnowledgeBaseQueryService.queryKnowledgeBase() 构建并返回
 * - 被 KnowledgeBaseController 的 /api/knowledgebase/query 接口使用
 */
package interview.guide.modules.knowledgebase.model;

/**
 * 知识库查询响应
 */
public record QueryResponse(
    String answer,
    Long knowledgeBaseId,
    String knowledgeBaseName,
    String traceId
) {}
