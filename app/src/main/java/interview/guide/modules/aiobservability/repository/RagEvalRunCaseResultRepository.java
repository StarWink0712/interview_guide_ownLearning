package interview.guide.modules.aiobservability.repository;

import interview.guide.modules.aiobservability.model.RagEvalRunCaseResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RagEvalRunCaseResultRepository
    extends JpaRepository<RagEvalRunCaseResultEntity, Long> {

  /**
   * 查询一次评测运行下的全部逐用例结果。
   *
   * <p>由 {@code RagEvaluationService.getRun} 调用，
   * 按主键升序返回以保持写入和展示顺序。
   *
   * @param runId 评测运行 ID
   * @return 按主键升序排列的逐用例结果
   */
  List<RagEvalRunCaseResultEntity> findByRunIdOrderByIdAsc(Long runId);
}
