package interview.guide.modules.aiobservability.repository;

import interview.guide.modules.aiobservability.model.RagEvalDatasetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RagEvalDatasetRepository extends JpaRepository<RagEvalDatasetEntity, Long> {
}
