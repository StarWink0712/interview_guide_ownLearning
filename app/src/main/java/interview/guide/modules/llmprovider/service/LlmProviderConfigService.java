/**
 * LLM Provider 配置管理核心服务
 *
 * 编排 Provider 的完整配置管理，支持数据库和 YAML 文件两种后端模式。
 * 提供 Provider 的增删改查、默认 Provider 设置、连通性测试、配置热加载（同步到 LlmProviderRegistry）、
 * ASR/TTS 配置的读写，以及 YAML 文件和 .env 文件的文本级编辑（保留注释和格式）。
 * 使用 ReentrantReadWriteLock 保证配置读写的线程安全。
 *
 * 联动关系：
 * - 被 LlmProviderController 调用，处理所有 Provider 和语音配置相关请求
 * - 依赖 LlmProviderRegistry 在配置变更后重新加载 ChatClient
 * - 依赖 ApiKeyEncryptionService 进行 API Key 的加密/解密
 * - 依赖 LlmProviderRepository / LlmGlobalSettingRepository 执行数据库操作
 * - 依赖 VoiceInterviewProperties 管理 ASR/TTS 配置
 * - 依赖 QwenAsrService / QwenTtsService 在配置更新后重新加载服务
 * - 依赖 LlmProviderProperties 读取 YAML 配置和默认值
 */
package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.ApiPathResolver;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class LlmProviderConfigService {
  /**
   * 配置属性
   * 用于读取 YAML 配置和默认值
   */
  private final LlmProviderProperties properties;

  /**
   * LLM Provider 注册中心
   * LLM Provider 注册中心与 ChatClient 缓存
   * 用于同步 Provider 配置到 ChatClient
   */
  private final LlmProviderRegistry registry;
  /**
   * LLM Provider 数据库操作
   * 用于读写 Provider 配置
   */
  private final LlmProviderRepository providerRepository;
  /**
   * 全局设置数据库操作
   * 用于读写全局配置
   */
  private final LlmGlobalSettingRepository globalSettingRepository;
  /**
   * API Key 加密服务
   * 用于加密和解密 Provider API Key
   */
  private final ApiKeyEncryptionService encryptionService;
  /**
   * 配置 YAML 文件路径
   * 用于读写 Provider 配置
   */
  private final String yamlPath;
  /**
   * 配置 .env 文件路径
   * 用于读写 ASR/TTS 配置
   */
  private final String envPath;
  /**
   * 读写锁
   * 用于保护对配置文件的并发访问
   */
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
  /**
   * 语音面试配置
   * 用于管理 ASR/TTS 配置
   */
  private final VoiceInterviewProperties voiceProperties;
  /**
   * ASR 服务
   * 用于处理 ASR 请求
   */
  private final QwenAsrService asrService;
  /**
   * TTS 服务
   * 用于处理 TTS 请求
   */
  private final QwenTtsService ttsService;

  /**
   * 推荐的嵌入模型
   * 将embeddingModel转换为推荐的嵌入模型名
   */
  private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
      "dashscope", "text-embedding-v3",
      "glm", "embedding-3",
      "zhipu", "embedding-3",
      "baidu", "Embedding-V1",
      "minimax", "embo-01");

  /**
   * 构造函数
   * 初始化 LLM Provider 配置服务
   */
  @Autowired
  public LlmProviderConfigService(
      LlmProviderProperties properties,
      LlmProviderRegistry registry,
      LlmProviderRepository providerRepository,
      LlmGlobalSettingRepository globalSettingRepository,
      ApiKeyEncryptionService encryptionService,
      VoiceInterviewProperties voiceProperties,
      QwenAsrService asrService,
      QwenTtsService ttsService) {
    this.properties = properties;
    this.registry = registry;
    this.providerRepository = providerRepository;
    this.globalSettingRepository = globalSettingRepository;
    this.encryptionService = encryptionService;
    this.yamlPath = properties.getConfigYamlPath();
    this.envPath = properties.getConfigEnvPath();
    this.voiceProperties = voiceProperties;
    this.asrService = asrService;
    this.ttsService = ttsService;
  }

  public LlmProviderConfigService(
      LlmProviderProperties properties,
      LlmProviderRegistry registry,
      VoiceInterviewProperties voiceProperties,
      QwenAsrService asrService,
      QwenTtsService ttsService) {
    this(properties, registry, null, null, null, voiceProperties, asrService, ttsService);
  }

  /**
   * 初始化时验证配置文件路径是否可写
   * 确保配置文件路径存在且可写，避免后续操作失败
   */
  @PostConstruct
  void validateWritablePaths() {
    // 调用两个ensureParentWritable方法，验证yamlPath和envPath的父目录是否可写
    ensureParentWritable(yamlPath, "config-yaml-path");
    ensureParentWritable(envPath, "config-env-path");
  }

  /**
   * 验证配置文件路径的父目录是否可写
   * 确保配置文件路径存在且可写，避免后续操作失败
   */
  private void ensureParentWritable(String rawPath, String label) {
    if (rawPath == null || rawPath.isBlank()) {
      log.warn("{} is not configured; runtime Provider edits will be skipped", label);
      return;
    }
    Path parent = Path.of(rawPath).toAbsolutePath().getParent();
    if (parent == null) {
      return;
    }
    try {
      Files.createDirectories(parent);
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          label + " 的父目录不可创建: " + parent, e);
    }
    if (!Files.isWritable(parent)) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          label + " 的父目录不可写: " + parent);
    }
    log.info("{} resolved to {} (parent writable)", label, rawPath);
  }

  // ===== Read operations (read lock) =====
  /**
   * 列出所有 Provider 配置
   * 用于获取所有已配置的 LLM Provider 配置
   */
  public List<ProviderDTO> listProviders() {
    // 读取 Provider 配置
    // 如果数据库未初始化，从 YAML 文件读取
    // 如果数据库已初始化，从数据库读取
    // 同时，将 API Key 加密为掩码
    // 并根据默认值设置默认 Provider
    // 最后，返回 Provider DTO 列表
    // rwlock加读锁，确保并发读取配置文件时的线程安全性
    rwLock.readLock().lock();
    try {
      if (!isDatabaseBacked()) {
        // 先从配置文件中读取providers配置，封装成map，key为providerId，value为ProviderConfig
        // 具体格式可以进配置文件中看，providerConfig是一个record，包含了providerId、baseUrl、model、apiKey、embeddingModel、
        // embeddingDimensions、supportsEmbedding、temperature、defaultChatProvider、defaultEmbeddingProvider等字段
        /**
         * YAML 配置: 返回给前端:
         * Map<String, ProviderConfig> → List<ProviderDTO>
         * "dashscope" → {...} [ { id: "dashscope", ... }, ... ]
         * "glm" → {...}
         */
        Map<String, ProviderConfig> providers = properties.getProviders();
        if (providers == null)
          return List.of();
        // 将providers转换为ProviderDTO列表，也把map转换为list返回
        // 把map的key和value转换为ProviderDTO
        return providers.entrySet().stream()
            .map(e -> ProviderDTO.builder()
                .id(e.getKey())
                .baseUrl(e.getValue().getBaseUrl())
                .maskedApiKey(maskApiKey(e.getValue().getApiKey()))
                .model(e.getValue().getModel())
                .embeddingModel(e.getValue().getEmbeddingModel())
                .embeddingDimensions(resolveEmbeddingDimensions(e.getValue().getEmbeddingDimensions()))
                .supportsEmbedding(Boolean.TRUE.equals(e.getValue().getSupportsEmbedding())
                    || trimOrNull(e.getValue().getEmbeddingModel()) != null)
                .temperature(e.getValue().getTemperature())
                .defaultChatProvider(e.getKey().equals(properties.getDefaultProvider()))
                .defaultEmbeddingProvider(e.getKey().equals(properties.getDefaultEmbeddingProvider()))
                .build())
            .toList();
      }
      //数据库没有初始化的话，就从yaml文件中读取providers配置
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      return providerRepository.findAll().stream()
          .map(provider -> ProviderDTO.builder()
              .id(provider.getId())
              .baseUrl(provider.getBaseUrl())
              .maskedApiKey(maskApiKey(decryptApiKey(provider)))
              .model(provider.getModel())
              .embeddingModel(provider.getEmbeddingModel())
              .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
              .supportsEmbedding(provider.isSupportsEmbedding())
              .temperature(provider.getTemperature())
              .defaultChatProvider(provider.getId().equals(setting.getDefaultChatProviderId()))
              .defaultEmbeddingProvider(provider.getId().equals(setting.getDefaultEmbeddingProviderId()))
              .build())
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  /**
   * 获取指定 Provider 配置
   * 根据 ID 获取单个 LLM Provider 的配置信息
   */
  public ProviderDTO getProvider(String id) {
    // 加读锁，保证并发读取时的线程安全
    rwLock.readLock().lock();
    try {
      // 数据库未初始化时，从 YAML 配置文件读取
      if (!isDatabaseBacked()) {
        ProviderConfig config = getLegacyProviderConfigOrThrow(id);
        return ProviderDTO.builder()
            .id(id)
            .baseUrl(config.getBaseUrl())
            .maskedApiKey(maskApiKey(config.getApiKey()))    // API Key 脱敏
            .model(config.getModel())
            .embeddingModel(config.getEmbeddingModel())
            .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
            // 支持 Embedding 的判断：显式声明 || 配置了 embeddingModel
            .supportsEmbedding(Boolean.TRUE.equals(config.getSupportsEmbedding())
                || trimOrNull(config.getEmbeddingModel()) != null)
            .temperature(config.getTemperature())
            // 与配置中的默认 Provider ID 比对，标记是否为默认
            .defaultChatProvider(id.equals(properties.getDefaultProvider()))
            .defaultEmbeddingProvider(id.equals(properties.getDefaultEmbeddingProvider()))
            .build();
      }
      // 数据库已初始化时，从数据库读取
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      LlmProviderEntity provider = getProviderEntityOrThrow(id);
      return ProviderDTO.builder()
          .id(id)
          .baseUrl(provider.getBaseUrl())
          .maskedApiKey(maskApiKey(decryptApiKey(provider)))  // 先解密再脱敏
          .model(provider.getModel())
          .embeddingModel(provider.getEmbeddingModel())
          .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
          .supportsEmbedding(provider.isSupportsEmbedding())   // 数据库已保证一致性，直接取值
          .temperature(provider.getTemperature())
          // 与全局设置中的默认 Provider ID 比对
          .defaultChatProvider(id.equals(setting.getDefaultChatProviderId()))
          .defaultEmbeddingProvider(id.equals(setting.getDefaultEmbeddingProviderId()))
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  /**
   * 获取默认 Provider 配置
   * 用于获取全局默认的 LLM Provider 配置
   */
  public DefaultProviderDTO getDefaultProvider() {
    rwLock.readLock().lock();
    try {
      if (!isDatabaseBacked()) {
        return new DefaultProviderDTO(properties.getDefaultProvider(), properties.getDefaultEmbeddingProvider());
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      return new DefaultProviderDTO(
          setting.getDefaultChatProviderId(),
          setting.getDefaultEmbeddingProviderId());
    } finally {
      rwLock.readLock().unlock();
    }
  }

  /**
   * 获取 ASR（语音识别）配置
   * 从 YAML 配置文件读取，返回前对 API Key 脱敏
   */
  public AsrConfigDTO getAsrConfig() {
    // 加读锁，保证并发读取时的线程安全
    rwLock.readLock().lock();
    try {
      // 从 voice-interview 配置中读取 ASR 相关参数
      VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
      return AsrConfigDTO.builder()
          .url(asr.getUrl())
          .model(asr.getModel())
          .maskedApiKey(maskApiKey(asr.getApiKey()))              // API Key 脱敏
          .language(asr.getLanguage())                            // 识别语言
          .format(asr.getFormat())                                // 音频格式
          .sampleRate(asr.getSampleRate())                        // 采样率
          .enableTurnDetection(asr.isEnableTurnDetection())       // 是否启用轮次检测
          .turnDetectionType(asr.getTurnDetectionType())          // 轮次检测类型
          .turnDetectionThreshold(asr.getTurnDetectionThreshold())// 轮次检测阈值
          .turnDetectionSilenceDurationMs(asr.getTurnDetectionSilenceDurationMs())// 静音检测时长(ms)
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  // 获取 TTS 配置
  public TtsConfigDTO getTtsConfig() {
    rwLock.readLock().lock();
    try {
      VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
      return TtsConfigDTO.builder()
          .model(tts.getModel())
          .maskedApiKey(maskApiKey(tts.getApiKey()))
          .voice(tts.getVoice())
          .format(tts.getFormat())
          .sampleRate(tts.getSampleRate())
          .mode(tts.getMode())
          .languageType(tts.getLanguageType())
          .speechRate(tts.getSpeechRate())
          .volume(tts.getVolume())
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  /**
   * 测试 Provider 连通性
   * 根据数据来源获取运行时配置，调用底层连接测试逻辑
   */
  public ProviderTestResult testProvider(String id) {
    // 加读锁，保证并发读取时的线程安全
    rwLock.readLock().lock();
    try {
      // 数据库已初始化则从 DB 读取，否则从 YAML 配置读取
      ProviderRuntimeConfig config = isDatabaseBacked()
          ? getProviderRuntimeConfigOrThrow(id)
          : toRuntimeConfig(getLegacyProviderConfigOrThrow(id));
      return doTestProvider(config, id);  // 实际连接测试逻辑
    } finally {
      rwLock.readLock().unlock();
    }
  }

  /**
   * 测试 ASR 配置
   * 用于测试 ASR 配置是否有效
   */
  public ProviderTestResult testAsrConfig() {
    rwLock.readLock().lock();
    try {
      VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
      try {
        java.net.URI wsUri = java.net.URI.create(asr.getUrl());
        String host = wsUri.getHost();
        int port = wsUri.getPort() > 0 ? wsUri.getPort() : (wsUri.getScheme().equals("wss") ? 443 : 80);
        java.net.InetSocketAddress address = new java.net.InetSocketAddress(host, port);
        java.net.Socket socket = new java.net.Socket();
        socket.connect(address, 5000);
        socket.close();
        return ProviderTestResult.builder()
            .success(true)
            .message("ASR WebSocket 连接成功: " + host)
            .model(asr.getModel())
            .build();
      } catch (Exception e) {
        return ProviderTestResult.builder()
            .success(false)
            .message("ASR 连接失败: " + e.getMessage())
            .model(asr.getModel())
            .build();
      }
    } finally {
      rwLock.readLock().unlock();
    }
  }

  // ===== Write operations (write lock) =====
  /**
   * 创建 Provider
   * 用于创建新的 LLM Provider 配置
   */
  @Transactional
  public void createProvider(CreateProviderRequest request) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        createProviderLegacy(request);
        return;
      }
      String providerId = trimOrNull(request.id());
      if (providerRepository.existsById(providerId)) {
        throw new BusinessException(ErrorCode.PROVIDER_ALREADY_EXISTS,
            "Provider '" + request.id() + "' 已存在");
      }
      String baseUrl = requireNonBlank(request.baseUrl(), "baseUrl");
      String model = requireNonBlank(request.model(), "model");
      String apiKey = requireNonBlank(request.apiKey(), "apiKey");
      String embeddingModel = trimOrNull(request.embeddingModel());
      Integer embeddingDimensions = resolveEmbeddingDimensions(request.embeddingDimensions());
      boolean supportsEmbedding = request.supportsEmbedding() != null
          ? request.supportsEmbedding()
          : embeddingModel != null;
      validateEmbeddingConfig(providerId, supportsEmbedding, embeddingModel, embeddingDimensions);

      //加密API Key
      ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);
      providerRepository.save(LlmProviderEntity.builder()
          .id(providerId)
          .baseUrl(baseUrl)
          .apiKeyNonce(encrypted.nonce())
          .apiKeyCiphertext(encrypted.ciphertext())
          .model(model)
          .embeddingModel(embeddingModel)
          .embeddingDimensions(embeddingDimensions)
          .supportsEmbedding(supportsEmbedding)
          .temperature(request.temperature())
          .enabled(true)
          .builtin(false)
          .build());
      registry.reload();
      log.info("Created provider: id={}, baseUrl={}, model={}", providerId, baseUrl, model);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  /**
   * 更新已有 Provider 配置
   *
   * <p>
   * 按非 null 字段覆盖原有值，API Key 变更时重新加密。
   * 空字符串校验拒绝（传入 "" 视为非法），写锁保护 + registry.reload() 热重载。
   *
   * @param id      Provider 唯一标识
   * @param request 更新请求体（所有字段可选）
   */
  @Transactional
  public void updateProvider(String id, UpdateProviderRequest request) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        updateProviderLegacy(id, request);
        return;
      }
      LlmProviderEntity provider = getProviderEntityOrThrow(id);

      String trimmedBaseUrl = trimOrNull(request.baseUrl());
      if (request.baseUrl() != null && trimmedBaseUrl == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串");
      }
      String trimmedModel = trimOrNull(request.model());
      if (request.model() != null && trimmedModel == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串");
      }
      String trimmedApiKey = trimOrNull(request.apiKey());
      if (request.apiKey() != null && trimmedApiKey == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串");
      }

      if (trimmedBaseUrl != null)
        provider.setBaseUrl(trimmedBaseUrl);
      if (trimmedModel != null)
        provider.setModel(trimmedModel);
      if (request.embeddingModel() != null) {
        provider.setEmbeddingModel(trimOrNull(request.embeddingModel()));
      }
      if (request.embeddingDimensions() != null) {
        provider.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
      }
      if (request.supportsEmbedding() != null) {
        provider.setSupportsEmbedding(request.supportsEmbedding());
      }
      validateEmbeddingConfig(
          id,
          provider.isSupportsEmbedding(),
          provider.getEmbeddingModel(),
          resolveEmbeddingDimensions(provider.getEmbeddingDimensions()));
      if (request.temperature() != null) {
        provider.setTemperature(request.temperature());
      }
      if (trimmedApiKey != null) {
        ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(trimmedApiKey);
        provider.setApiKeyNonce(encrypted.nonce());
        provider.setApiKeyCiphertext(encrypted.ciphertext());
      }

      providerRepository.save(provider);
      registry.reload();
      log.info("Updated provider: id={}", id);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  //删除指定的 LLM Provider
  @Transactional
  public void deleteProvider(String id) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        deleteProviderLegacy(id);
        return;
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      if (id.equals(setting.getDefaultChatProviderId()) || id.equals(setting.getDefaultEmbeddingProviderId())) {
        throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
            "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
      }
      getProviderEntityOrThrow(id);

      providerRepository.deleteById(id);
      registry.reload();
      log.info("Deleted provider: id={}", id);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  //设置默认聊天 Provider
  @Transactional
  public void updateDefaultProvider(DefaultProviderDTO request) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        updateDefaultProviderLegacy(request);
        return;
      }
      String providerId = trimOrNull(request.defaultProvider());
      if (providerId == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空");
      }
      getProviderEntityOrThrow(providerId);
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      setting.setDefaultChatProviderId(providerId);
      globalSettingRepository.save(setting);
      registry.reload();
      log.info("Updated default provider: {}", providerId);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  /**
   * 设置默认嵌入（向量化）Provider
   *
   * <p>
   * 额外校验 supportsEmbedding=true 且 embeddingModel 不为空，
   * 写锁保护 + registry.reload() 热重载。
   *
   * @param request DefaultProviderDTO（含 defaultEmbeddingProvider 字段）
   */
  @Transactional
  public void updateDefaultEmbeddingProvider(DefaultProviderDTO request) {
    rwLock.writeLock().lock();
    try {
      String providerId = trimOrNull(request.defaultEmbeddingProvider());
      if (providerId == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultEmbeddingProvider 不能为空");
      }
      LlmProviderEntity provider = getProviderEntityOrThrow(providerId);
      String embeddingModel = trimOrNull(provider.getEmbeddingModel());
      if (!provider.isSupportsEmbedding() || embeddingModel == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "Provider '" + providerId + "' 不支持 Embedding，不能设为默认向量服务");
      }
      validateEmbeddingConfig(
          providerId,
          true,
          embeddingModel,
          resolveEmbeddingDimensions(provider.getEmbeddingDimensions()));
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      setting.setDefaultEmbeddingProviderId(providerId);
      globalSettingRepository.save(setting);
      registry.reload();
      log.info("Updated default embedding provider: {}", providerId);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  /**
   * 更新 ASR 配置并热重载
   *
   * <p>
   * 按非 null 字段更新 VoiceInterviewProperties.AsrConfig 内存对象，
   * API Key 变更时同步 .env，写 YAML 并 asrService.reload()。
   * 写锁保护。
   *
   * @param request ASR 配置请求体
   */
  public void updateAsrConfig(AsrConfigRequest request) {
    rwLock.writeLock().lock();
    try {
      VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
      if (request.url() != null)
        asr.setUrl(request.url());
      if (request.model() != null)
        asr.setModel(request.model());
      if (request.language() != null)
        asr.setLanguage(request.language());
      if (request.format() != null)
        asr.setFormat(request.format());
      if (request.sampleRate() != null)
        asr.setSampleRate(request.sampleRate());
      if (request.enableTurnDetection() != null)
        asr.setEnableTurnDetection(request.enableTurnDetection());
      if (request.turnDetectionType() != null)
        asr.setTurnDetectionType(request.turnDetectionType());
      if (request.turnDetectionThreshold() != null)
        asr.setTurnDetectionThreshold(request.turnDetectionThreshold());
      if (request.turnDetectionSilenceDurationMs() != null)
        asr.setTurnDetectionSilenceDurationMs(request.turnDetectionSilenceDurationMs());
      if (request.apiKey() != null) {
        asr.setApiKey(request.apiKey());
        updateEnvValue("AI_BAILIAN_API_KEY", request.apiKey());
      }

      writeAsrConfigToYaml(asr);
      asrService.reload(voiceProperties);
      log.info("Updated ASR config");
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  /**
   * 更新 TTS 配置并热重载
   *
   * <p>
   * 与 updateAsrConfig 对称设计：更新 VoiceInterviewProperties.TtsConfig 内存对象、
   * 同步 .env（API Key 共享）、写 YAML、ttsService.reload()。写锁保护。
   *
   * @param request TTS 配置请求体
   */
  public void updateTtsConfig(TtsConfigRequest request) {
    rwLock.writeLock().lock();
    try {
      VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
      if (request.model() != null)
        tts.setModel(request.model());
      if (request.voice() != null)
        tts.setVoice(request.voice());
      if (request.format() != null)
        tts.setFormat(request.format());
      if (request.sampleRate() != null)
        tts.setSampleRate(request.sampleRate());
      if (request.mode() != null)
        tts.setMode(request.mode());
      if (request.languageType() != null)
        tts.setLanguageType(request.languageType());
      if (request.speechRate() != null)
        tts.setSpeechRate(request.speechRate());
      if (request.volume() != null)
        tts.setVolume(request.volume());
      if (request.apiKey() != null) {
        tts.setApiKey(request.apiKey());
        updateEnvValue("AI_BAILIAN_API_KEY", request.apiKey());
      }

      writeTtsConfigToYaml(tts);
      ttsService.reload(voiceProperties);
      log.info("Updated TTS config");
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  /**
   * 手动触发热重载所有 Provider 配置
   *
   * <p>
   * 清空 LlmProviderRegistry 中所有缓存的 ChatClient，重新从配置源加载。
   */
  public void reloadProviders() {
    registry.reload();
    log.info("Manual provider reload triggered");
  }

  // ===== Internal helpers =====

  /**
   * 判断当前是否为数据库模式
   *
   * @return true 如果 providerRepository、globalSettingRepository、encryptionService
   *         均非 null
   */
  private boolean isDatabaseBacked() {
    return providerRepository != null && globalSettingRepository != null && encryptionService != null;
  }

  private Map<String, ProviderConfig> getLegacyProvidersOrThrow() {
    Map<String, ProviderConfig> providers = properties.getProviders();
    if (providers == null) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "Provider 配置未初始化");
    }
    return providers;
  }

  ProviderConfig getLegacyProviderConfigOrThrow(String id) {
    ProviderConfig config = getLegacyProvidersOrThrow().get(id);
    if (config == null) {
      throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
          "Provider '" + id + "' 不存在");
    }
    return config;
  }

  private void createProviderLegacy(CreateProviderRequest request) {
    Map<String, ProviderConfig> providers = getLegacyProvidersOrThrow();
    if (providers.containsKey(request.id())) {
      throw new BusinessException(ErrorCode.PROVIDER_ALREADY_EXISTS,
          "Provider '" + request.id() + "' 已存在");
    }

    ProviderConfig config = new ProviderConfig();
    config.setBaseUrl(request.baseUrl());
    config.setApiKey(request.apiKey());
    config.setModel(request.model());
    config.setEmbeddingModel(request.embeddingModel());
    config.setEmbeddingDimensions(request.embeddingDimensions());
    config.setSupportsEmbedding(request.supportsEmbedding());
    config.setTemperature(request.temperature());
    providers.put(request.id(), config);

    String envKey = toEnvKey(request.id());
    writeProviderToYaml(request.id(), config, envKey);
    writeEnvValue(envKey, request.apiKey());
    registry.reload();
  }

  private void updateProviderLegacy(String id, UpdateProviderRequest request) {
    ProviderConfig config = getLegacyProviderConfigOrThrow(id);
    String trimmedBaseUrl = trimOrNull(request.baseUrl());
    if (request.baseUrl() != null && trimmedBaseUrl == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串");
    }
    String trimmedModel = trimOrNull(request.model());
    if (request.model() != null && trimmedModel == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串");
    }
    String trimmedApiKey = trimOrNull(request.apiKey());
    if (request.apiKey() != null && trimmedApiKey == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串");
    }

    if (trimmedBaseUrl != null)
      config.setBaseUrl(trimmedBaseUrl);
    if (trimmedModel != null)
      config.setModel(trimmedModel);
    if (request.embeddingModel() != null) {
      config.setEmbeddingModel(trimOrNull(request.embeddingModel()));
    }
    if (request.embeddingDimensions() != null) {
      config.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
    }
    if (request.supportsEmbedding() != null) {
      config.setSupportsEmbedding(request.supportsEmbedding());
    }
    if (request.temperature() != null) {
      config.setTemperature(request.temperature());
    }
    if (trimmedApiKey != null) {
      config.setApiKey(trimmedApiKey);
      updateEnvValue(toEnvKey(id), trimmedApiKey);
    }

    writeProviderToYaml(id, config, toEnvKey(id));
    registry.reload();
  }

  private void deleteProviderLegacy(String id) {
    if (id.equals(properties.getDefaultProvider())) {
      throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
          "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
    }
    getLegacyProviderConfigOrThrow(id);
    getLegacyProvidersOrThrow().remove(id);
    String envKey = toEnvKey(id);
    removeProviderFromYaml(id);
    removeFromEnv(envKey);
    registry.reload();
  }

  private void updateDefaultProviderLegacy(DefaultProviderDTO request) {
    String providerId = trimOrNull(request.defaultProvider());
    if (providerId == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空");
    }
    getLegacyProviderConfigOrThrow(providerId);
    properties.setDefaultProvider(providerId);
    writeDefaultProviderToYaml(providerId);
    registry.reload();
  }

  private ProviderRuntimeConfig toRuntimeConfig(ProviderConfig config) {
    return new ProviderRuntimeConfig(
        config.getBaseUrl(),
        config.getApiKey(),
        config.getModel(),
        config.getEmbeddingModel(),
        resolveEmbeddingDimensions(config.getEmbeddingDimensions()),
        Boolean.TRUE.equals(config.getSupportsEmbedding()) || trimOrNull(config.getEmbeddingModel()) != null,
        config.getTemperature());
  }

  /**
   * 数据库模式下按 ID 获取 Provider Entity，不存在则抛异常
   *
   * @param id Provider 唯一标识
   * @return LlmProviderEntity
   */
  LlmProviderEntity getProviderEntityOrThrow(String id) {
    return providerRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
            "Provider '" + id + "' 不存在"));
  }

  /**
   * 获取全局设置单例行，不存在则抛异常
   *
   * @return LlmGlobalSettingEntity（SINGLETON_ID 行的默认 Provider 配置）
   */
  private LlmGlobalSettingEntity getGlobalSettingOrThrow() {
    return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
            "默认 Provider 配置未初始化"));
  }

  /**
   * 数据库模式下组装 Provider 运行时配置（含解密后的 API Key）
   *
   * @param id Provider 唯一标识
   * @return ProviderRuntimeConfig（baseUrl、decryptedApiKey、model 等）
   */
  private ProviderRuntimeConfig getProviderRuntimeConfigOrThrow(String id) {
    LlmProviderEntity provider = getProviderEntityOrThrow(id);
    return new ProviderRuntimeConfig(
        provider.getBaseUrl(),
        decryptApiKey(provider),
        provider.getModel(),
        provider.getEmbeddingModel(),
        resolveEmbeddingDimensions(provider.getEmbeddingDimensions()),
        provider.isSupportsEmbedding(),
        provider.getTemperature());
  }

  /**
   * 解密 Entity 中存储的 API Key（nonce + ciphertext → 明文）
   *
   * @param provider LlmProviderEntity
   * @return 解密后的 API Key 明文
   */
  private String decryptApiKey(LlmProviderEntity provider) {
    return encryptionService.decrypt(provider.getApiKeyNonce(), provider.getApiKeyCiphertext());
  }

  /**
   * API Key 脱敏显示：前 3 位 + "***" + 后 3 位，长度不足 6 则返回 "***"
   *
   * @param apiKey 原始或解密后的 API Key
   * @return 脱敏后的字符串
   */
  String maskApiKey(String apiKey) {
    if (apiKey == null || apiKey.length() <= 6) {
      return "***";
    }
    return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 3);
  }

  private String abbreviate(String text) {
    if (text == null || text.isBlank()) {
      return "[no body]";
    }
    String normalized = text.replaceAll("\\s+", " ").trim();
    if (normalized.length() <= 200) {
      return normalized;
    }
    return normalized.substring(0, 200) + "...";
  }

  private List<String> buildConnectivityTestUrls(String baseUrl) {
    String normalizedBaseUrl = ApiPathResolver.stripTrailingSlashes(baseUrl);
    LinkedHashSet<String> candidateUrls = new LinkedHashSet<>();

    candidateUrls.add(normalizedBaseUrl + "/chat/completions");
    if (!ApiPathResolver.baseUrlContainsVersion(normalizedBaseUrl)) {
      candidateUrls.add(normalizedBaseUrl + "/v1/chat/completions");
    }

    return List.copyOf(candidateUrls);
  }

  private Map<String, Object> buildConnectivityTestRequestBody(String model) {
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("model", model);
    requestBody.put("messages", List.of(Map.of(
        "role", "user",
        "content", "Reply with OK only.")));
    requestBody.put("max_tokens", 1);
    return requestBody;
  }

  // 移除字符串首尾空格，若为空则返回 null
  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private String requireNonBlank(String value, String fieldName) {
    String normalized = trimOrNull(value);
    if (normalized == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, fieldName + " 不能为空");
    }
    return normalized;
  }

  /**
   * 校验 Embedding 配置合法性
   *
   * <p>
   * 不支持 Embedding 则跳过；必须填写 embeddingModel（不能是聊天模型名），
   * 向量维度必须为正整数。提供推荐模型名兜底提示。
   *
   * @param providerId          Provider 标识（用于查推荐模型）
   * @param supportsEmbedding   是否声称支持 Embedding
   * @param embeddingModel      Embedding 模型名
   * @param embeddingDimensions 向量维度
   */
  private void validateEmbeddingConfig(
      String providerId,
      boolean supportsEmbedding,
      String embeddingModel,
      Integer embeddingDimensions) {
    String normalizedModel = trimOrNull(embeddingModel);
    if (!supportsEmbedding) {
      return;
    }
    if (normalizedModel == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "支持 Embedding 的 Provider 必须填写 embeddingModel");
    }
    if (looksLikeChatModel(normalizedModel)) {
      // 根据providerId获取推荐模型名
      // toLowerCase方法是统一变为小写，确保推荐模型名与配置中的键名一致
      String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
      String suffix = recommendation != null
          ? "，推荐填写 " + recommendation
          : "，请填写该厂商真实的 Embedding 模型名";
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "Embedding Model 不能填写聊天模型 '" + normalizedModel + "'" + suffix);
    }
    if (embeddingDimensions == null || embeddingDimensions <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "向量维度必须为正整数");
    }
  }

  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  private boolean looksLikeChatModel(String model) {
    String lower = model.toLowerCase();
    return lower.startsWith("glm-")
        || lower.startsWith("deepseek")
        || lower.startsWith("kimi")
        || lower.startsWith("moonshot")
        || lower.startsWith("qwen")
        || lower.startsWith("ernie");
  }

  private String toEnvKey(String providerId) {
    return "PROVIDER_" + providerId.toUpperCase().replace("-", "_") + "_API_KEY";
  }

  // ===== Provider test logic (called under read lock) =====

  /**
   * 实际执行 Provider 连通性测试
   *
   * <p>
   * 构建 RestClient（连接 5s/读取 10s 超时），遍历候选 URL 发送最小化
   * chat/completions 请求（max_tokens=1），验证 baseUrl + apiKey + model。
   *
   * @param config 解密后的运行时配置
   * @param id     Provider ID（仅用于日志）
   * @return ProviderTestResult
   */
  private ProviderTestResult doTestProvider(ProviderRuntimeConfig config, String id) {
    try {
      SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
      requestFactory.setConnectTimeout(5000);
      requestFactory.setReadTimeout(10000);

      RestClient restClient = RestClient.builder()
          .defaultHeader("Authorization", "Bearer " + config.apiKey())
          .requestFactory(requestFactory)
          .build();

      Map<String, Object> requestBody = buildConnectivityTestRequestBody(config.model());

      List<String> candidateUrls = buildConnectivityTestUrls(config.baseUrl());
      String lastFailureMessage = "Unknown error";

      for (String targetUrl : candidateUrls) {
        try {
          restClient.post()
              .uri(URI.create(targetUrl))
              .body(requestBody)
              .retrieve()
              .toEntity(String.class);
          log.info("Provider connectivity test succeeded: providerId={}, baseUrl={}, targetUrl={}, model={}",
              id, config.baseUrl(), targetUrl, config.model());
          return ProviderTestResult.builder()
              .success(true)
              .message("连接成功")
              .model(config.model())
              .build();
        } catch (RestClientResponseException e) {
          String responseBody = abbreviate(e.getResponseBodyAsString());
          lastFailureMessage = String.format(
              "HTTP %s on %s, body=%s",
              e.getStatusCode().value(),
              targetUrl,
              responseBody);
          log.warn(
              "Provider connectivity test failed with response: providerId={}, baseUrl={}, targetUrl={}, model={}, status={}, body={}",
              id,
              config.baseUrl(),
              targetUrl,
              config.model(),
              e.getStatusCode().value(),
              responseBody,
              e);
        } catch (Exception e) {
          lastFailureMessage = String.format(
              "%s on %s: %s",
              e.getClass().getSimpleName(),
              targetUrl,
              e.getMessage());
          log.warn(
              "Provider connectivity test failed: providerId={}, baseUrl={}, targetUrl={}, model={}, error={}",
              id,
              config.baseUrl(),
              targetUrl,
              config.model(),
              e.getMessage(),
              e);
        }
      }
      return ProviderTestResult.builder()
          .success(false)
          .message("连接失败: " + lastFailureMessage)
          .model(config.model())
          .build();
    } catch (Exception e) {
      log.warn("Provider connectivity test setup failed: providerId={}, baseUrl={}, model={}, error={}",
          id, config.baseUrl(), config.model(), e.getMessage(), e);
      return ProviderTestResult.builder()
          .success(false)
          .message("连接失败: " + e.getMessage())
          .model(config.model())
          .build();
    }
  }

  // ===== YAML text editing (preserves comments & formatting) =====

  /**
   * 将 Provider 配置写入 YAML 文件（app.ai.providers.{id} 块）
   *
   * @param id     Provider 标识
   * @param config Provider 配置
   * @param envKey API Key 对应的环境变量名
   */
  private void writeProviderToYaml(String id, ProviderConfig config, String envKey) {
    mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "写入 YAML 配置失败", editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("base-url", config.getBaseUrl());
      values.put("api-key", "${" + envKey + "}");
      values.put("model", config.getModel());
      if (config.getEmbeddingModel() != null) {
        values.put("embedding-model", config.getEmbeddingModel());
      }
      if (config.getEmbeddingDimensions() != null) {
        values.put("embedding-dimensions", config.getEmbeddingDimensions());
      }
      if (config.getTemperature() != null) {
        values.put("temperature", config.getTemperature());
      }
      editor.setBlock(new String[] { "app", "ai", "providers" }, id, values);
    });
  }

  private void removeProviderFromYaml(String id) {
    mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "删除 YAML 配置失败", editor -> {
      editor.removeSection(new String[] { "app", "ai", "providers" }, id);
    });
  }

  private void writeDefaultProviderToYaml(String defaultProvider) {
    mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "写入默认 Provider 配置失败", editor -> {
      editor.setScalar(new String[] { "app", "ai", "default-provider" }, defaultProvider);
      editor.removeSection(new String[] { "app", "ai" }, "module-defaults");
    });
  }

  private void writeAsrConfigToYaml(VoiceInterviewProperties.AsrConfig asr) {
    mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入 ASR 配置失败", editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("url", asr.getUrl());
      values.put("model", asr.getModel());
      values.put("api-key", "${AI_BAILIAN_API_KEY}");
      values.put("language", asr.getLanguage());
      values.put("format", asr.getFormat());
      values.put("sample-rate", asr.getSampleRate());
      values.put("enable-turn-detection", asr.isEnableTurnDetection());
      values.put("turn-detection-type", asr.getTurnDetectionType());
      values.put("turn-detection-threshold", asr.getTurnDetectionThreshold());
      values.put("turn-detection-silence-duration-ms", asr.getTurnDetectionSilenceDurationMs());
      editor.setBlock(new String[] { "app", "voice-interview", "qwen" }, "asr", values);
    });
  }

  private void writeTtsConfigToYaml(VoiceInterviewProperties.QwenTtsConfig tts) {
    mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入 TTS 配置失败", editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("model", tts.getModel());
      values.put("api-key", "${AI_BAILIAN_API_KEY}");
      values.put("voice", tts.getVoice());
      values.put("format", tts.getFormat());
      values.put("sample-rate", tts.getSampleRate());
      values.put("mode", tts.getMode());
      values.put("language-type", tts.getLanguageType());
      values.put("speech-rate", tts.getSpeechRate());
      values.put("volume", tts.getVolume());
      editor.setBlock(new String[] { "app", "voice-interview", "qwen" }, "tts", values);
    });
  }

  /**
   * 文本行级别 YAML 编辑的通用入口
   *
   * <p>
   * 读取 YAML 文件每行为 List，调用 mutator 进行文本级修改，
   * 最后写回文件。所有 YAML 写操作共享此方法。
   *
   * @param errorCode    失败时抛出的错误码
   * @param errorMessage 失败时的错误消息前缀
   * @param mutator      对 YamlTextEditor 的修改操作
   */
  private void mutateYamlText(ErrorCode errorCode, String errorMessage, Consumer<YamlTextEditor> mutator) {
    if (yamlPath == null || yamlPath.isBlank()) {
      log.warn("YAML path not configured, skip writing");
      return;
    }
    try {
      Path path = Path.of(yamlPath);
      List<String> lines;
      if (Files.exists(path)) {
        lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
      } else {
        lines = new ArrayList<>();
      }

      YamlTextEditor editor = new YamlTextEditor(lines);
      mutator.accept(editor);

      String content = String.join("\n", editor.getLines());
      if (!content.endsWith("\n")) {
        content += "\n";
      }
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new BusinessException(errorCode, errorMessage + ": " + e.getMessage());
    }
  }

  // ===== .env file operations =====

  /**
   * 写入 .env 环境变量（新增或更新）
   *
   * @param key   环境变量名
   * @param value 环境变量值
   */
  private void writeEnvValue(String key, String value) {
    if (envPath == null || envPath.isBlank())
      return;
    try {
      Path path = Path.of(envPath);
      if (!Files.exists(path)) {
        Files.writeString(path, key + "=" + value + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return;
      }
      String content = Files.readString(path, StandardCharsets.UTF_8);
      if (content.contains(key + "=")) {
        content = content.replaceAll("(?m)^" + Pattern.quote(key) + "=.*",
            Matcher.quoteReplacement(key + "=" + value));
      } else {
        if (!content.endsWith("\n")) {
          content += "\n";
        }
        content += key + "=" + value + "\n";
      }
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.warn("写入 .env 失败: {}", e.getMessage());
    }
  }

  private void updateEnvValue(String key, String value) {
    writeEnvValue(key, value);
  }

  private void removeFromEnv(String key) {
    if (envPath == null || envPath.isBlank())
      return;
    try {
      Path path = Path.of(envPath);
      if (!Files.exists(path))
        return;
      String content = Files.readString(path, StandardCharsets.UTF_8);
      content = content.replaceAll("(?m)^" + Pattern.quote(key) + "=.*\\R?", "");
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.warn("删除 .env 条目失败: {}", e.getMessage());
    }
  }

  // ===== YamlTextEditor: text-based YAML editing that preserves comments &
  // formatting =====

  /**
   * YAML 文本行编辑器，保留注释和格式
   *
   * <p>
   * 以文本行级别操作 YAML 内容，通过缩进和 key 匹配定位，
   * 支持 setScalar、setBlock、removeSection 三种操作。
   * 避免使用 SnakeYAML 序列化导致注释和格式丢失。
   */
  static class YamlTextEditor {
    private final List<String> lines;

    YamlTextEditor(List<String> lines) {
      this.lines = new ArrayList<>(lines);
    }

    List<String> getLines() {
      return lines;
    }

    void setScalar(String[] path, String value) {
      int searchFrom = path.length > 1
          ? ensureParents(Arrays.copyOf(path, path.length - 1))
          : 0;
      int indent = (path.length - 1) * 2;
      String key = path[path.length - 1];
      String newLine = " ".repeat(indent) + key + ": " + value;

      int found = findKey(key, indent, searchFrom);
      if (found >= 0) {
        lines.set(found, newLine);
      } else {
        int parentIndent = indent >= 2 ? indent - 2 : -1;
        int insertPos = findSectionEnd(searchFrom, parentIndent);
        lines.add(insertPos, newLine);
      }
    }

    void setBlock(String[] parentPath, String blockKey, LinkedHashMap<String, Object> values) {
      int parentSearchFrom = ensureParents(parentPath);
      int blockIndent = parentPath.length * 2;
      int valueIndent = blockIndent + 2;

      int blockLine = findKey(blockKey, blockIndent, parentSearchFrom);
      if (blockLine < 0) {
        int parentEnd = parentPath.length >= 1 ? blockIndent - 2 : -1;
        int insertPos = findSectionEnd(parentSearchFrom, parentEnd);
        lines.add(insertPos, " ".repeat(blockIndent) + blockKey + ":");
        blockLine = insertPos;
      }

      int blockEnd = findSectionEnd(blockLine + 1, blockIndent);

      for (Map.Entry<String, Object> entry : values.entrySet()) {
        String valueLine = " ".repeat(valueIndent) + entry.getKey() + ": " + formatValue(entry.getValue());
        int existing = findKeyInRange(entry.getKey(), valueIndent, blockLine + 1, blockEnd);
        if (existing >= 0) {
          lines.set(existing, valueLine);
        } else {
          lines.add(blockEnd, valueLine);
          blockEnd++;
        }
      }
    }

    void removeSection(String[] parentPath, String sectionKey) {
      int parentSearchFrom = navigateTo(parentPath);
      if (parentSearchFrom < 0)
        return;

      int sectionIndent = parentPath.length * 2;
      int sectionLine = findKey(sectionKey, sectionIndent, parentSearchFrom);
      if (sectionLine < 0)
        return;

      int endLine = sectionLine + 1;
      while (endLine < lines.size()) {
        String line = lines.get(endLine);
        if (line.isBlank()) {
          endLine++;
          continue;
        }
        if (indentOf(line) <= sectionIndent)
          break;
        endLine++;
      }

      for (int i = endLine - 1; i >= sectionLine; i--) {
        lines.remove(i);
      }
    }

    private int ensureParents(String[] path) {
      int searchFrom = 0;
      for (int i = 0; i < path.length; i++) {
        int indent = i * 2;
        int found = findKey(path[i], indent, searchFrom);
        if (found < 0) {
          int parentIndent = i > 0 ? indent - 2 : -1;
          int insertPos = findSectionEnd(searchFrom, parentIndent);
          lines.add(insertPos, " ".repeat(indent) + path[i] + ":");
          searchFrom = insertPos + 1;
        } else {
          searchFrom = found + 1;
        }
      }
      return searchFrom;
    }

    private int navigateTo(String[] path) {
      int searchFrom = 0;
      for (int i = 0; i < path.length; i++) {
        int indent = i * 2;
        int found = findKey(path[i], indent, searchFrom);
        if (found < 0)
          return -1;
        searchFrom = found + 1;
      }
      return searchFrom;
    }

    private int findKey(String key, int indent, int searchFrom) {
      String prefix = " ".repeat(indent) + key + ":";
      for (int i = searchFrom; i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.isBlank() || line.trim().startsWith("#"))
          continue;
        if (line.startsWith(prefix))
          return i;
        if (indentOf(line) < indent)
          break;
      }
      return -1;
    }

    private int findKeyInRange(String key, int indent, int start, int end) {
      String prefix = " ".repeat(indent) + key + ":";
      for (int i = start; i < end && i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.isBlank() || line.trim().startsWith("#"))
          continue;
        if (line.startsWith(prefix))
          return i;
        if (indentOf(line) < indent)
          break;
      }
      return -1;
    }

    private int findSectionEnd(int searchFrom, int parentIndent) {
      for (int i = searchFrom; i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.isBlank() || line.trim().startsWith("#"))
          continue;
        if (indentOf(line) <= parentIndent)
          return i;
      }
      return lines.size();
    }

    private int indentOf(String line) {
      int count = 0;
      while (count < line.length() && line.charAt(count) == ' ')
        count++;
      return count;
    }

    private String formatValue(Object value) {
      if (value instanceof Boolean b)
        return b.toString();
      if (value instanceof Number n)
        return n.toString();
      return value.toString();
    }
  }

  private record ProviderRuntimeConfig(
      String baseUrl,
      String apiKey,
      String model,
      String embeddingModel,
      Integer embeddingDimensions,
      boolean supportsEmbedding,
      Double temperature) {
  }
}
