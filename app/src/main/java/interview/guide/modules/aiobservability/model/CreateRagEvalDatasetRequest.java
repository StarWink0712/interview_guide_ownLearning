package interview.guide.modules.aiobservability.model;

import jakarta.validation.constraints.NotBlank;

public record CreateRagEvalDatasetRequest(
    @NotBlank(message = "评测集名称不能为空")
    String name,
    String description
) {
}
