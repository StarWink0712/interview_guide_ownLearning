package interview.guide.modules.aiobservability.repository;

import interview.guide.modules.aiobservability.model.RagEvalRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RagEvalRunRepository extends JpaRepository<RagEvalRunEntity, Long> {
}
