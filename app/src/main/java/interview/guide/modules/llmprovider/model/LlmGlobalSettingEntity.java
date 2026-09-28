/**
 * LLM 全局设置 JPA 持久化实体
 *
 * 映射 llm_global_setting 表，采用单例模式（固定 ID=1）存储全局默认 Chat Provider ID
 * 和默认 Embedding Provider ID。使用 @PrePersist/@PreUpdate 自动维护时间戳。
 *
 * 联动关系：
 * - 被 LlmGlobalSettingRepository 持久化管理
 * - 被 LlmProviderConfigService 依赖，读取和更新默认 Provider 设置
 * - 被 LlmProviderBootstrapService 在启动时初始化默认配置
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
@Table(name = "llm_global_setting")
public class LlmGlobalSettingEntity {

  public static final Long SINGLETON_ID = 1L;

  @Id
  private Long id;

  @Column(name = "default_chat_provider_id", nullable = false, length = 64)
  private String defaultChatProviderId;

  @Column(name = "default_embedding_provider_id", nullable = false, length = 64)
  private String defaultEmbeddingProviderId;

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
