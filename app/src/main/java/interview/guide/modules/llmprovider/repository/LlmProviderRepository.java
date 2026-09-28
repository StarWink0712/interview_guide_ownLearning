/**
 * LLM Provider 配置数据访问层
 *
 * 提供 LlmProviderEntity 的 JPA 持久化操作，包括按启用状态查询已启用的 Provider 列表。
 *
 * 联动关系：
 * - 被 LlmProviderConfigService 依赖，执行 Provider 的增删改查
 * - 被 LlmProviderBootstrapService 在启动时检查和种子初始化 Provider 数据
 * - 被 LlmProviderRegistry 通过 Service 间接访问 Provider 配置
 */
package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmProviderRepository extends JpaRepository<LlmProviderEntity, String> {
  //llmproviderentity 按启用状态查询已启用的 Provider 列表
  //llmproviderentity 是 LLMProvider 配置的 JPA 持久化实体，包含 Provider 的完整配置
  //这个是自定义查询方法，用于按启用状态查询已启用的 Provider 列表，按 ID 升序排序
  //返回值是一个 List<LlmProviderEntity>，包含所有已启用的 Provider 实体
  //具体实现在 LlmProviderRepositoryImpl 类中
  List<LlmProviderEntity> findByEnabledTrueOrderByIdAsc();
}
