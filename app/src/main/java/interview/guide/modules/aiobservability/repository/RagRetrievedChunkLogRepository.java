package interview.guide.modules.aiobservability.repository;

import interview.guide.modules.aiobservability.model.RagRetrievedChunkLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RagRetrievedChunkLogRepository
    extends JpaRepository<RagRetrievedChunkLogEntity, Long> {

  /**
   * 查询某次 RAG 检索命中的全部文档块，并按相似度排名升序返回。
   *
   * <p>由 {@code RagRetrievalLogService.getTrace} 和
   * {@code RagRetrievalLogService.listRecentTraces} 调用，用于补全检索主日志详情。
   *
   * @param retrievalLogId RAG 检索主日志 ID
   * @return 按 rank 从小到大排列的命中文档块
   */
  List<RagRetrievedChunkLogEntity> findByRetrievalLogIdOrderByRankAsc(Long retrievalLogId);
}
