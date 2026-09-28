/**
 * 知识库列表项 DTO
 *
 * 用于知识库列表、详情、搜索等接口的响应数据载体，
 * 由 KnowledgeBaseMapper 从 KnowledgeBaseEntity 转换而来，避免直接暴露 Entity。
 *
 * 联动关系：
 * - 由 KnowledgeBaseMapper（MapStruct）从 KnowledgeBaseEntity 转换生成
 * - 被 KnowledgeBaseListService 返回给 KnowledgeBaseController
 * - 被 RagChatDTO.SessionDetailDTO 引用（会话详情中的知识库列表）
 * - 被 RagChatSessionService 使用
 */
package interview.guide.modules.knowledgebase.model;

import java.time.LocalDateTime;

/**
 * 知识库列表项DTO
 * 使用MapStruct进行转换，见KnowledgeBaseMapper
 */
public record KnowledgeBaseListItemDTO(
    Long id,
    String name,
    String category,
    String originalFilename,
    Long fileSize,
    String contentType,
    LocalDateTime uploadedAt,
    LocalDateTime lastAccessedAt,
    Integer accessCount,
    Integer questionCount,
    VectorStatus vectorStatus,
    String vectorError,
    Integer chunkCount
) {
}

