/**
 * 面试答案 JPA Repository
 *
 * 提供 InterviewAnswerEntity 的数据访问，支持按会话实体、会话主键 ID、
 * 会话 sessionId 字符串查询答案列表，以及按 sessionId + questionIndex 精确查找单条答案（用于 upsert）。
 *
 * 联动关系：
 * - 被 InterviewPersistenceService 调用进行答案的保存和查询
 * - 被 EvaluateStreamConsumer 间接通过 InterviewPersistenceService 使用
 */
package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试答案Repository
 */
@Repository
public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswerEntity, Long> {
    
    /**
     * 根据会话查找所有答案
     *
     * <p>当前业务代码未直接调用，保留给已持有会话实体的查询场景。
     */
    List<InterviewAnswerEntity> findBySessionOrderByQuestionIndex(InterviewSessionEntity session);
    
    /**
     * 根据会话ID查找所有答案
     *
     * <p>这里的 ID 是会话表数据库主键；当前业务统一使用字符串 sessionId，
     * 因此该方法暂未直接调用。
     */
    List<InterviewAnswerEntity> findBySessionIdOrderByQuestionIndex(Long sessionId);
    
    /**
     * 根据会话 sessionId 字符串查找所有答案
     *
     * <p>由 {@code InterviewPersistenceService} 在报告保存和答案列表查询时调用，
     * 按题目索引升序返回以便与问题列表对齐。
     */
    List<InterviewAnswerEntity> findBySession_SessionIdOrderByQuestionIndex(String sessionId);

    /**
     * 根据会话 sessionId 和问题索引查找单条答案（用于 upsert）
     *
     * <p>由持久化服务保存答案和写入即时评估时调用。
     */
    Optional<InterviewAnswerEntity> findBySession_SessionIdAndQuestionIndex(String sessionId, Integer questionIndex);
}
