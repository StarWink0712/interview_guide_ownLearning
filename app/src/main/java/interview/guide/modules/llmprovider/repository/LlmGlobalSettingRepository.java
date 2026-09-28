/**
 * LLM 全局设置数据访问层
 *
 * 提供 LlmGlobalSettingEntity 的 JPA 持久化操作，管理全局默认 Provider 配置的单例记录。
 *
 * 联动关系：
 * - 被 LlmProviderConfigService 依赖，读取和更新默认 Provider 设置
 * - 被 LlmProviderBootstrapService 在启动时检查和初始化全局设置
 */
package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmGlobalSettingRepository extends JpaRepository<LlmGlobalSettingEntity, Long> {
}
