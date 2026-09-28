package interview.guide.modules.aiobservability.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AddRagEvalCaseRequest(
    @NotBlank(message = "评测问题不能为空")
    String question,

    @NotEmpty(message = "至少选择一个知识库")
    List<Long> knowledgeBaseIds,

    List<String> expectedKeywords
) {
}
