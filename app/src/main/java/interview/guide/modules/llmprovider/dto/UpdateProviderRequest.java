/**
 * 更新 LLM Provider 请求体
 *
 * 封装更新 Provider 配置的参数，包括 baseUrl、apiKey、模型名称、Embedding 模型、
 * 向量维度、是否支持 Embedding 和温度参数。所有字段可选，仅更新非 null 字段。
 *
 * 联动关系：
 * - 被 LlmProviderController 的 updateProvider 接口接收
 * - 传递给 LlmProviderConfigService.updateProvider() 方法处理
 */
package interview.guide.modules.llmprovider.dto;

public record UpdateProviderRequest(
    String baseUrl,
    String apiKey,
    String model,
    String embeddingModel,
    Integer embeddingDimensions,
    Boolean supportsEmbedding,
    Double temperature
) {
    public UpdateProviderRequest(
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Double temperature
    ) {
        this(baseUrl, apiKey, model, embeddingModel, null, null, temperature);
    }
}
