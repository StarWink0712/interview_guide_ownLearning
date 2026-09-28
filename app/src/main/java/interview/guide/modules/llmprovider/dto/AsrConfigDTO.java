/**
 * ASR（语音识别）配置数据传输对象
 *
 * 封装语音识别服务的配置信息，包括服务 URL、模型、脱敏 API Key、语言、音频格式、
 * 采样率以及语音活动检测（Turn Detection）相关参数。用于向前端返回 ASR 配置。
 *
 * 联动关系：
 * - 由 LlmProviderConfigService.getAsrConfig() 从 VoiceInterviewProperties 转换构建
 * - 被 LlmProviderController 的 ASR 配置查询接口返回给前端
 */
package interview.guide.modules.llmprovider.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AsrConfigDTO {
  private String url;
  private String model;
  private String maskedApiKey;
  private String language;
  private String format;
  private int sampleRate;
  private boolean enableTurnDetection;
  private String turnDetectionType;
  private float turnDetectionThreshold;
  private int turnDetectionSilenceDurationMs;
}
