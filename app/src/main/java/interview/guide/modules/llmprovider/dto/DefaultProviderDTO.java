/**
 * 默认 Provider 配置数据传输对象
 *
 * 封装默认 Chat Provider 和默认 Embedding Provider 的 ID，
 * 用于查询和设置全局默认 Provider。
 *
 * 联动关系：
 * - 被 LlmProviderController 的默认 Provider 查询/更新接口使用
 * - 传递给 LlmProviderConfigService 的 getDefaultProvider/updateDefaultProvider 方法
 */
package interview.guide.modules.llmprovider.dto;

public record DefaultProviderDTO(
    String defaultProvider,
    String defaultEmbeddingProvider
) {
    public DefaultProviderDTO(String defaultProvider) {
        this(defaultProvider, null);
    }
}
