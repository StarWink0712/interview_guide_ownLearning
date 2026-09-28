/**
 * LLM Provider 管理控制器
 *
 * 提供 LLM Provider 的 RESTful API，包括 Provider 的增删改查、连通性测试、
 * 默认 Provider 设置、配置热加载，以及语音面试 ASR/TTS 配置的管理。
 * 使用 @RateLimit 注解进行全局限流保护。
 *
 * 联动关系：
 * - 依赖 LlmProviderConfigService 处理所有 Provider 配置相关业务逻辑
 * - 使用 @RateLimit 注解进行接口限流
 * - 使用 Result<T> 统一响应包装
 */
package interview.guide.modules.llmprovider.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/llm-provider")
@RequiredArgsConstructor
@Slf4j
public class LlmProviderController {
  // 依赖 LlmProviderConfigService 处理所有 Provider 配置相关业务逻辑
  private final LlmProviderConfigService configService;

  /**
   * 获取所有 LLM Provider 配置
   * 用于获取系统中所有已配置的 LLM Provider 信息
   */
  @GetMapping("/list")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<List<ProviderDTO>> listProviders() {
    return Result.success(configService.listProviders());
  }

  /**
   * 根据ID获取Provider
   * 用于根据 Provider ID 获取详细配置信息
   */
  @GetMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<ProviderDTO> getProvider(@PathVariable String id) {
    return Result.success(configService.getProvider(id));
  }

  /**
   * 创建新的 LLM Provider
   *
   * <p>接收 Provider 配置（ID、名称、baseUrl、model、apiKey 等），
   * 经 Jakarta Validation 校验后调用 LlmProviderConfigService 持久化。
   * API Key 在数据库模式下会经过加密存储，创建成功后自动热重载。
   *
   * @param request Provider 创建请求体（@Valid 校验必填字段）
   */
  @PostMapping
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> createProvider(@RequestBody @Valid CreateProviderRequest request) {
    configService.createProvider(request);
    return Result.success();
  }

  /**
   * 更新已有的 LLM Provider 配置
   *
   * <p>按路径中的 Provider ID 定位目标，请求体中非 null 字段覆盖原有值。
   * API Key 变更时重新加密，更新成功后自动热重载。
   *
   * @param id Provider 唯一标识
   * @param request Provider 更新请求体（所有字段可选，null 表示不修改）
   */
  @PutMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateProvider(@PathVariable String id,
      @RequestBody UpdateProviderRequest request) {
    configService.updateProvider(id, request);
    return Result.success();
  }

  /**
   * 删除指定的 LLM Provider
   *
   * <p>禁止删除当前设为默认聊天或默认嵌入的 Provider，必须先切换默认值。
   * 删除成功后自动热重载。
   *
   * @param id 待删除的 Provider 唯一标识
   */
  @DeleteMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> deleteProvider(@PathVariable String id) {
    configService.deleteProvider(id);
    return Result.success();
  }

  /**
   * 测试 Provider 的连通性
   *
   * <p>通过 RestClient 向 {baseUrl}/chat/completions 发送最小化请求
   * （仅要求 LLM 回复 "OK"），验证 baseUrl + apiKey + model 三者正确性。
   * 超时配置：连接 5s，读取 10s。支持多候选 URL 遍历。
   *
   * @param id 待测试的 Provider 唯一标识
   * @return 测试结果（success、message、实际使用的 model）
   */
  @PostMapping("/{id}/test")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<ProviderTestResult> testProvider(@PathVariable String id) {
    return Result.success(configService.testProvider(id));
  }

  /**
   * 手动触发热重载所有 Provider 配置
   *
   * <p>清除 LlmProviderRegistry 中所有缓存的 ChatClient，重新从配置源
   * （数据库或 YAML）加载 Provider 列表。用于直接修改配置文件后的手动刷新，
   * 以及多实例部署时的统一重载。
   */
  @PostMapping("/reload")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> reloadProviders() {
    configService.reloadProviders();
    return Result.success();
  }

  /**
   * 获取当前的默认 Provider 配置
   *
   * <p>返回默认聊天 Provider ID 和默认嵌入（向量化）Provider ID。
   * 所有未显式指定 llmProvider 的 LLM 调用将使用此默认值。
   *
   * @return DefaultProviderDTO（defaultProviderId + defaultEmbeddingProviderId）
   */
  @GetMapping("/default-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<DefaultProviderDTO> getDefaultProvider() {
    return Result.success(configService.getDefaultProvider());
  }

  /**
   * 设置默认聊天 Provider
   *
   * <p>切换后所有未指定 llmProvider 的聊天请求（出题、评估、JD 解析等）
   * 将使用新的默认 Provider。需要校验目标 Provider 存在性。
   *
   * @param request DefaultProviderDTO（含 defaultProviderId）
   */
  @PutMapping("/default-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateDefaultProvider(@RequestBody DefaultProviderDTO request) {
    configService.updateDefaultProvider(request);
    return Result.success();
  }

  /**
   * 设置默认嵌入（向量化）Provider
   *
   * <p>切换后知识库文档向量化将使用新的默认 Provider。
   * 需要校验目标 Provider 存在且 supportsEmbedding=true、embeddingModel 不为空。
   *
   * @param request DefaultProviderDTO（含 defaultEmbeddingProviderId）
   */
  @PutMapping("/default-embedding-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateDefaultEmbeddingProvider(@RequestBody DefaultProviderDTO request) {
    configService.updateDefaultEmbeddingProvider(request);
    return Result.success();
  }

  // ===== Voice ASR/TTS Config =====

  /**
   * 获取 ASR（语音识别）配置
   *
   * <p>返回语音面试中语音转文字服务的当前配置：WebSocket 地址、模型、
   * API Key（脱敏）、语言、音频格式、采样率、轮次检测参数等。
   */
  @GetMapping("/voice/asr")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<AsrConfigDTO> getAsrConfig() {
    return Result.success(configService.getAsrConfig());
  }

  /**
   * 更新 ASR（语音识别）配置
   *
   * <p>更新语音转文字服务的配置参数，包括 URL、model、apiKey、语言、
   * 音频格式、采样率、轮次检测策略等。API Key 变更时同步写入 .env 文件，
   * 更新成功后自动热重载 ASR 服务。
   *
   * @param request ASR 配置请求体（所有字段可选，null 表示不修改）
   */
  @PutMapping("/voice/asr")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateAsrConfig(@RequestBody AsrConfigRequest request) {
    configService.updateAsrConfig(request);
    return Result.success();
  }

  /**
   * 获取 TTS（语音合成）配置
   *
   * <p>返回语音面试中文字转语音服务的当前配置：模型、API Key（脱敏）、
   * 音色、音频格式、采样率、语速、音量等参数。
   */
  @GetMapping("/voice/tts")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<TtsConfigDTO> getTtsConfig() {
    return Result.success(configService.getTtsConfig());
  }

  /**
   * 更新 TTS（语音合成）配置
   *
   * <p>更新文字转语音服务的配置参数，包括 model、apiKey、音色、语速、
   * 音量、采样率等。API Key 变更时同步写入 .env 文件，
   * 更新成功后自动热重载 TTS 服务。
   *
   * @param request TTS 配置请求体（所有字段可选，null 表示不修改）
   */
  @PutMapping("/voice/tts")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateTtsConfig(@RequestBody TtsConfigRequest request) {
    configService.updateTtsConfig(request);
    return Result.success();
  }

  /**
   * 测试 ASR WebSocket 连通性
   *
   * <p>获取 ASR 的 WebSocket URL，通过 TCP Socket 连接测试服务器端口是否可达
   * （超时 5s），不验证 API Key 有效性（WebSocket 握手需要完整 SDK）。
   *
   * @return 测试结果（success、message）
   */
  @PostMapping("/voice/asr/test")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<ProviderTestResult> testAsrConfig() {
    return Result.success(configService.testAsrConfig());
  }
}
