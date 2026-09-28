package interview.guide.modules.aiobservability.repository;

import interview.guide.modules.aiobservability.model.AiProviderAttemptLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Provider HTTP attempt 日志仓库。
 *
 * <p>负责查询 ai_provider_attempt_logs 表，也就是“每一次真实 HTTP 请求”。
 * 前端在查看某次 invocation 详情时，会通过 invocationId 下钻到这些 attempt，
 * 从而看到底层第几次请求失败、状态码是什么、是否可重试。
 */
@Repository
public interface AiProviderAttemptLogRepository extends JpaRepository<AiProviderAttemptLogEntity, Long> {

  /** 查询某次逻辑调用下的所有 HTTP attempt，按 attemptNo 从小到大排序。 */
  List<AiProviderAttemptLogEntity> findByInvocationIdOrderByAttemptNoAsc(String invocationId);
}
