/**
 * 知识库模块对象映射器
 *
 * 使用 MapStruct 自动生成 KnowledgeBaseEntity 与 KnowledgeBaseListItemDTO 之间的转换代码。
 * 核心职责是将知识库实体转换为列表展示所需的 DTO，支持单个和批量转换。
 *
 * 联动关系：
 * - 被 KnowledgeBaseListService 调用（知识库列表展示时的 DTO 转换）
 * - 被 RagChatMapper 引用（uses = KnowledgeBaseMapper.class，用于 RAG 聊天会话详情中的知识库列表转换）
 * - 依赖 KnowledgeBaseEntity、KnowledgeBaseListItemDTO
 */
package interview.guide.infrastructure.mapper;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 知识库模块对象映射器
 * 使用 MapStruct 自动生成 KnowledgeBaseEntity 与 KnowledgeBaseListItemDTO 之间的转换代码
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface KnowledgeBaseMapper {
    
    /**
     * 将知识库实体转换为列表项DTO
     */
    KnowledgeBaseListItemDTO toListItemDTO(KnowledgeBaseEntity entity);
    
    /**
     * 将知识库实体列表转换为列表项DTO列表
     */
    List<KnowledgeBaseListItemDTO> toListItemDTOList(List<KnowledgeBaseEntity> entities);
}

