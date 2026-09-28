/**
 * LLM Provider 配置 JPA 持久化实体
 *
 * 映射 llm_provider_config 表，存储 Provider 的完整配置，包括 ID、baseUrl、
 * 加密后的 API Key（密文+Nonce）、模型名称、Embedding 模型、向量维度、是否支持 Embedding、
 * 温度参数、是否启用和是否为内置 Provider。API Key 以 AES-GCM 加密存储。
 *
 * 联动关系：
 * - 被 LlmProviderRepository 持久化管理
 * - 被 LlmProviderConfigService 依赖，执行 Provider 的增删改查和加密/解密
 * - 被 LlmProviderBootstrapService 在启动时从 YAML 种子数据初始化
 * - API Key 加密/解密依赖 ApiKeyEncryptionService
 */
package interview.guide.modules.llmprovider.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "llm_provider_config")
public class LlmProviderEntity {

  @Id
  @Column(length = 64)
  private String id;

  @Column(name = "base_url", nullable = false, length = 512)
  private String baseUrl;

  @Column(name = "api_key_ciphertext", nullable = false, length = 4096)
  private String apiKeyCiphertext;

  @Column(name = "api_key_nonce", nullable = false, length = 64)
  private String apiKeyNonce;

  @Column(nullable = false, length = 128)
  private String model;

  @Column(name = "embedding_model", length = 128)
  private String embeddingModel;

  @Column(name = "embedding_dimensions")
  private Integer embeddingDimensions;

  @Column(name = "supports_embedding", nullable = false)
  private boolean supportsEmbedding;

  private Double temperature;

  @Column(nullable = false)
  private boolean enabled;

  @Column(nullable = false)
  private boolean builtin;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
