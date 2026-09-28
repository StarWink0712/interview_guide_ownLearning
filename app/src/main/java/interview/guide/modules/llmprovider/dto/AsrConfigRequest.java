/**
 * ASR（语音识别）配置更新请求体
 *
 * 封装前端提交的 ASR 配置更新数据，包括服务 URL、模型、API Key、语言、音频格式、
 * 采样率以及语音活动检测参数。所有字段可选，仅更新非 null 字段。
 *
 * 联动关系：
 * - 被 LlmProviderController 的 ASR 配置更新接口接收
 * - 传递给 LlmProviderConfigService.updateAsrConfig() 方法处理
 */
package interview.guide.modules.llmprovider.dto;

public record AsrConfigRequest(
    String url,
    String model,
    String apiKey,
    String language,
    String format,
    Integer sampleRate,
    Boolean enableTurnDetection,
    String turnDetectionType,
    Float turnDetectionThreshold,
    Integer turnDetectionSilenceDurationMs
) {}
