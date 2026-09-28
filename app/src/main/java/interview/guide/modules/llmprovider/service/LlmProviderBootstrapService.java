/**
 * LLM Provider 启动引导服务
 *
 * <p>功能概述：
 * 在应用启动时（@PostConstruct）自动检测并初始化 Provider 数据：若数据库中无 Provider 记录，
 * 则从 application.yml 中的种子配置（app.ai.providers）批量导入并加密存储；
 * 同时确保全局设置单例记录存在，设置默认 Chat/Embedding Provider。
 *
 * <p>执行流程：
 * <pre>
 * 应用启动
 *     ↓
 * @PostConstruct: seedProvidersIfNecessary()
 *     ↓
 * 检查 Provider 表是否为空
 *     ├── 为空 → seedProviders() 导入种子数据
 *     └── 非空 → 跳过
 *     ↓
 * ensureGlobalSetting() 确保全局设置存在
 *     ↓
 * 完成初始化
 * </pre>
 *
 * <p>联动关系：
 * <ul>
 *   <li>依赖 LlmProviderRepository 执行 Provider 数据的持久化</li>
 *   <li>依赖 LlmGlobalSettingRepository 检查和初始化全局设置</li>
 *   <li>依赖 ApiKeyEncryptionService 对种子 API Key 进行加密</li>
 *   <li>依赖 LlmProviderProperties 读取 YAML 种子配置和默认 Provider 设置</li>
 * </ul>
 *
 * <p>配置示例（application.yml）：
 * <pre>
 * app:
 *   ai:
 *     default-provider: dashscope
 *     default-embedding-provider: dashscope
 *     embedding-dimensions: 1024
 *     providers:
 *       dashscope:
 *         base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *         api-key: ${DASHSCOPE_API_KEY}
 *         model: qwen-plus
 *         embedding-model: text-embedding-v3
 *         embedding-dimensions: 1024
 *         supports-embedding: true
 *         temperature: 0.7
 * </pre>
 */
package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM Provider 启动引导服务。
 *
 * <p>该服务在应用启动时自动执行，负责：
 * <ul>
 *   <li>从 YAML 配置导入 Provider 种子数据到数据库</li>
 *   <li>对 API Key 进行加密存储</li>
 *   <li>初始化全局默认 Provider 设置</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderBootstrapService {

  /**
   * LLM Provider 配置属性，从 application.yml 读取种子配置。
   * 包含 providers 映射、默认 provider、embedding 维度等配置。
   */
  private final LlmProviderProperties properties;

  /**
   * Provider 数据访问层，用于查询和持久化 Provider 实体。
   * 即DAO（Data Access Object），负责与数据库交互，执行 CRUD 操作。
   * 这里使用 JpaRepository 接口，自动实现数据库操作，如查询、保存、删除等。
   * 该接口继承自 JpaRepository，提供了通用的数据库操作方法，如 findByEnabledTrueOrderByIdAsc()，用于按启用状态查询已启用的 Provider 列表，按 ID 升序排序
   * 该方法在事务中执行，确保数据一致性。
   */
  private final LlmProviderRepository providerRepository;

  /**
   * 全局设置数据访问层，用于管理默认 Provider 配置。
   * 这个是全局设置的数据库记录，不同于 Provider的默认配置。
   */
  private final LlmGlobalSettingRepository globalSettingRepository;

  /**
   * API Key 加密服务，用于对种子配置中的 API Key 进行加密存储。
   * 使用 XChaCha20-Poly1305 算法，生成 nonce 和密文分离存储。
   */
  private final ApiKeyEncryptionService encryptionService;

  /**
   * 应用启动后自动执行的初始化方法。
   *
   * <p>执行逻辑：
   * <ol>
   *   <li>检查数据库中是否存在 Provider 记录</li>
   *   <li>若不存在，从 YAML 配置导入种子数据</li>
   *   <li>确保全局设置单例记录存在</li>
   * </ol>
   *
   * <p>该方法在事务中执行，确保数据一致性。
   */
  @PostConstruct
  @Transactional
  public void seedProvidersIfNecessary() {
    // 检查 Provider 表是否为空，为空则导入种子数据
    if (providerRepository.count() == 0) {
      seedProviders();
    }
    // 确保全局设置单例记录存在
    ensureGlobalSetting();
  }

  /**
   * 从 YAML 配置导入 Provider 种子数据。
   *
   * <p>遍历 app.ai.providers 配置中的每个 Provider，执行：
   * <ol>
   *   <li>校验配置有效性（id、baseUrl、model 必填）</li>
   *   <li>加密 API Key（使用 XChaCha20-Poly1305）</li>
   *   <li>判断是否支持 Embedding（supportsEmbedding 或 embeddingModel 非空）</li>
   *   <li>构建并保存 Provider 实体</li>
   * </ol>
   *
   * <p>导入的 Provider 标记为 builtin=true，表示系统内置 Provider。
   */
  private void seedProviders() {
    // 从配置属性中获取 Provider 配置映射
    Map<String, ProviderConfig> providers = properties.getProviders();

    // 检查种子配置是否存在
    if (providers == null || providers.isEmpty()) {
      log.warn("No app.ai.providers seed configuration found");
      return;
    }

    // 遍历每个 Provider 配置并导入
    providers.forEach((id, config) -> {
      // 校验必填字段：id、baseUrl、model
      if (isBlank(id) || config == null || isBlank(config.getBaseUrl()) || isBlank(config.getModel())) {
        log.warn("Skip invalid provider seed: id={}", id);
        return;
      }

      // 加密 API Key（空字符串也会加密存储）
      ApiKeyEncryptionService.EncryptedValue encrypted =
          encryptionService.encrypt(config.getApiKey() != null ? config.getApiKey() : "");

      // 判断是否支持 Embedding：
      // 1. 显式配置 supportsEmbedding=true
      // 2. 或者配置了 embeddingModel
      boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
          || !isBlank(config.getEmbeddingModel());

      // 构建 Provider 实体
      LlmProviderEntity entity = LlmProviderEntity.builder()
          .id(id)                                                          // Provider 唯一标识
          .baseUrl(config.getBaseUrl())                                   // API 基础 URL
          .apiKeyNonce(encrypted.nonce())                                 // 加密 nonce
          .apiKeyCiphertext(encrypted.ciphertext())                       // 加密后的 API Key
          .model(config.getModel())                                       // 聊天模型名称
          .embeddingModel(trimOrNull(config.getEmbeddingModel()))         // Embedding 模型名称
          .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))  // Embedding 维度
          .supportsEmbedding(supportsEmbedding)                           // 是否支持 Embedding
          .temperature(config.getTemperature())                           // 温度参数
          .enabled(true)                                                  // 默认启用
          .builtin(true)                                                  // 标记为内置 Provider
          .build();

      providerRepository.save(entity);
    });

    log.info("Seeded {} LLM providers from application configuration", providerRepository.count());
  }

  /**
   * 确保全局设置单例记录存在。
   *
   * <p>全局设置用于存储默认的 Chat Provider 和 Embedding Provider ID。
   * 该记录是单例模式，使用固定的 SINGLETON_ID。
   *
   * <p>默认 Provider 选择逻辑：
   * <ul>
   *   <li>Chat Provider：优先使用配置的 defaultProvider，否则取第一个 Provider</li>
   *   <li>Embedding Provider：优先使用配置的 defaultEmbeddingProvider，
   *       否则使用 Chat Provider，再否则查找第一个支持 Embedding 的 Provider</li>
   * </ul>
   */
  private void ensureGlobalSetting() {
    // 检查全局设置是否已存在
    if (globalSettingRepository.existsById(LlmGlobalSettingEntity.SINGLETON_ID)) {
      return;
    }

    // 解析默认 Chat Provider：
    // 1. 优先使用配置的 defaultProvider（需存在于数据库）
    // 2. 否则使用第一个 Provider
    // 3. 若无任何 Provider，使用 "dashscope" 作为兜底
    String defaultChatProvider = resolveExistingProvider(
        properties.getDefaultProvider(),
        providerRepository.findAll().stream().findFirst().map(LlmProviderEntity::getId).orElse("dashscope")
    );

    // 解析默认 Embedding Provider：
    // 1. 优先使用配置的 defaultEmbeddingProvider
    // 2. 否则使用 defaultChatProvider
    String configuredEmbeddingProvider = !isBlank(properties.getDefaultEmbeddingProvider())
        ? properties.getDefaultEmbeddingProvider()
        : defaultChatProvider;

    // 确保选中的 Provider 确实支持 Embedding
    String defaultEmbeddingProvider = resolveExistingEmbeddingProvider(configuredEmbeddingProvider, defaultChatProvider);

    // 保存全局设置单例记录
    globalSettingRepository.save(LlmGlobalSettingEntity.builder()
        .id(LlmGlobalSettingEntity.SINGLETON_ID)
        .defaultChatProviderId(defaultChatProvider)
        .defaultEmbeddingProviderId(defaultEmbeddingProvider)
        .build());

    log.info("Initialized LLM global setting: chatProvider={}, embeddingProvider={}",
        defaultChatProvider, defaultEmbeddingProvider);
  }

  /**
   * 解析存在的 Provider ID。
   *
   * <p>优先使用首选 Provider，若不存在则使用备选 Provider。
   *
   * @param preferredProvider 首选 Provider ID
   * @param fallbackProvider  备选 Provider ID
   * @return 存在于数据库中的 Provider ID
   */
  private String resolveExistingProvider(String preferredProvider, String fallbackProvider) {
    // 首选 Provider 非空且存在于数据库，则使用首选
    if (!isBlank(preferredProvider) && providerRepository.existsById(preferredProvider)) {
      return preferredProvider;
    }
    // 否则使用备选
    return fallbackProvider;
  }

  /**
   * 解析存在的 Embedding Provider ID。
   *
   * <p>选择逻辑：
   * <ol>
   *   <li>检查首选 Provider 是否支持 Embedding</li>
   *   <li>若不支持，遍历所有 Provider 找到第一个支持 Embedding 的</li>
   *   <li>若都不支持，使用备选 Provider（可能是 Chat Provider）</li>
   * </ol>
   *
   * @param preferredProvider 首选 Provider ID
   * @param fallbackProvider  备选 Provider ID
   * @return 支持 Embedding 的 Provider ID
   */
  private String resolveExistingEmbeddingProvider(String preferredProvider, String fallbackProvider) {
    return providerRepository.findById(preferredProvider)
        .filter(this::canProvideEmbedding)                    // 检查是否支持 Embedding
        .map(LlmProviderEntity::getId)
        .orElseGet(() -> providerRepository.findAll().stream()
            .filter(this::canProvideEmbedding)                // 遍历查找支持 Embedding 的 Provider
            .findFirst()
            .map(LlmProviderEntity::getId)
            .orElse(fallbackProvider));                       // 兜底使用备选
  }

  /**
   * 判断 Provider 是否能够提供 Embedding 服务。
   *
   * <p>条件：
   * <ul>
   *   <li>Provider 已启用（enabled=true）</li>
   *   <li>支持 Embedding（supportsEmbedding=true）</li>
   *   <li>配置了 Embedding 模型名称（embeddingModel 非空）</li>
   * </ul>
   *
   * @param provider Provider 实体
   * @return 是否能提供 Embedding 服务
   */
  private boolean canProvideEmbedding(LlmProviderEntity provider) {
    return provider.isEnabled()
        && provider.isSupportsEmbedding()
        && !isBlank(provider.getEmbeddingModel());
  }

  /**
   * 解析 Embedding 维度。
   *
   * <p>优先使用 Provider 配置的维度，若无效则使用全局默认维度。
   *
   * @param configuredDimensions Provider 配置的维度
   * @return 有效的 Embedding 维度
   */
  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    // 配置的维度有效（非空且大于 0），则使用配置值
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    // 否则使用全局默认维度
    return properties.getEmbeddingDimensions();
  }

  /**
   * 字符串修剪并转换为 null。
   *
   * <p>将字符串去除首尾空白，若结果为空则返回 null。
   *
   * @param value 原始字符串
   * @return 修剪后的字符串，或 null
   */
  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * 判断字符串是否为空白。
   *
   * <p>空白定义：null 或仅包含空白字符。
   *
   * @param value 待判断的字符串
   * @return 是否为空白
   */
  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
