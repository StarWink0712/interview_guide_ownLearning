/**
 * LLM Provider 数据传输对象
 *
 * 封装 Provider 的完整配置信息，包括 ID、baseUrl、脱敏 API Key、模型名称、
 * Embedding 模型、向量维度、是否支持 Embedding、温度参数，以及是否为默认 Chat/Embedding Provider。
 * 避免将加密的 API Key 直接暴露给前端。
 *
 * 联动关系：
 * - 由 LlmProviderConfigService 的 listProviders/getProvider 方法构建
 * - 被 LlmProviderController 作为 Provider 列表和详情接口的响应体返回
 */
package interview.guide.modules.llmprovider.dto;

import lombok.Builder;

@Builder
public record ProviderDTO(
    String id,
    String baseUrl,
    String maskedApiKey,
    String model,
    String embeddingModel,
    Integer embeddingDimensions,
    boolean supportsEmbedding,
    Double temperature,
    boolean defaultChatProvider,
    boolean defaultEmbeddingProvider
) {}
