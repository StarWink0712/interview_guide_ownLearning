/**
 * 知识库 RAG 查询请求
 *
 * 前端发起知识库问答时的请求体，支持多知识库联合查询。
 * 提供单知识库兼容构造函数以保持向后兼容。
 *
 * 联动关系：
 * - 被 KnowledgeBaseController 的 /api/knowledgebase/query 和 /query/stream 接口接收
 * - 被 KnowledgeBaseQueryService 使用（提取 knowledgeBaseIds 和 question）
 * - 使用 @Valid + @NotEmpty/@NotBlank 进行请求校验
 */
package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
 
/**
 * 知识库查询请求
 */
public record QueryRequest(
    @NotEmpty(message = "至少选择一个知识库")
    List<Long> knowledgeBaseIds,  // 支持多个知识库
    
    @NotBlank(message = "问题不能为空")
    String question
) {
    /**
     * 兼容单知识库查询（向后兼容）
     */
    public QueryRequest(Long knowledgeBaseId, String question) {
        this(List.of(knowledgeBaseId), question);
    }
}

