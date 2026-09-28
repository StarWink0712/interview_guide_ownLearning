/**
 * TTS（语音合成）配置更新请求体
 *
 * 封装前端提交的 TTS 配置更新数据，包括模型、API Key、音色、音频格式、
 * 采样率、合成模式、语言类型、语速和音量。所有字段可选，仅更新非 null 字段。
 *
 * 联动关系：
 * - 被 LlmProviderController 的 TTS 配置更新接口接收
 * - 传递给 LlmProviderConfigService.updateTtsConfig() 方法处理
 */
package interview.guide.modules.llmprovider.dto;

public record TtsConfigRequest(
    String model,
    String apiKey,
    String voice,
    String format,
    Integer sampleRate,
    String mode,
    String languageType,
    Float speechRate,
    Integer volume
) {}
