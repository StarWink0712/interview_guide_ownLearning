/**
 * TTS（语音合成）配置数据传输对象
 *
 * 封装语音合成服务的配置信息，包括模型、脱敏 API Key、音色、音频格式、
 * 采样率、合成模式、语言类型、语速和音量。用于向前端返回 TTS 配置。
 *
 * 联动关系：
 * - 由 LlmProviderConfigService.getTtsConfig() 从 VoiceInterviewProperties 转换构建
 * - 被 LlmProviderController 的 TTS 配置查询接口返回给前端
 */
package interview.guide.modules.llmprovider.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TtsConfigDTO {
  private String model;
  private String maskedApiKey;
  private String voice;
  private String format;
  private int sampleRate;
  private String mode;
  private String languageType;
  private float speechRate;
  private int volume;
}
