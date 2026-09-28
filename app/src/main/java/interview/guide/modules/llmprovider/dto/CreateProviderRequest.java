/**
 * 创建 LLM Provider 请求体
 *
 * 封装创建新 Provider 所需的参数，包括 Provider ID、baseUrl、apiKey、模型名称、
 * Embedding 模型、向量维度、是否支持 Embedding 和温度参数。
 * id、baseUrl、apiKey、model 为必填字段。
 *
 * 联动关系：
 * - 被 LlmProviderController 的 createProvider 接口接收
 * - 传递给 LlmProviderConfigService.createProvider() 方法处理
 */
package interview.guide.modules.llmprovider.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProviderRequest(
    @NotBlank String id,
    @NotBlank String baseUrl,
    @NotBlank String apiKey,
    @NotBlank String model,
    String embeddingModel,
    Integer embeddingDimensions,
    Boolean supportsEmbedding,
    Double temperature
) {
    public CreateProviderRequest(
        String id,
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Double temperature
    ) {
        this(id, baseUrl, apiKey, model, embeddingModel, null, null, temperature);
    }
}
