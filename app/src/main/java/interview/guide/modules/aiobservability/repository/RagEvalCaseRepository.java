package interview.guide.modules.aiobservability.repository;

import interview.guide.modules.aiobservability.model.RagEvalCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RagEvalCaseRepository extends JpaRepository<RagEvalCaseEntity, Long> {

  /**
   * 查询评测集下的全部用例，并按主键升序保持稳定执行顺序。
   *
   * <p>由 {@code RagEvaluationService.runDataset} 调用，
   * 返回结果会被逐条执行向量检索。
   *
   * @param datasetId 评测集 ID
   * @return 按主键升序排列的评测用例
   */
  List<RagEvalCaseEntity> findByDatasetIdOrderByIdAsc(Long datasetId);
}
