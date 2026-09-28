/**
 * Provider 连通性测试结果
 *
 * 封装 Provider 或 ASR 配置的连通性测试结果，包含是否成功、消息描述和模型名称。
 *
 * 联动关系：
 * - 由 LlmProviderConfigService 的 testProvider/testAsrConfig 方法构建
 * - 被 LlmProviderController 的测试接口作为响应体返回
 */
package interview.guide.modules.llmprovider.dto;

import lombok.Builder;

@Builder
public record ProviderTestResult(
    boolean success,
    String message,
    String model
) {}
