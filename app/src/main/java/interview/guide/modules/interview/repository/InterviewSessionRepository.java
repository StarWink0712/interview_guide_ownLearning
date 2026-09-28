/**
 * 面试会话 JPA Repository
 *
 * 提供 InterviewSessionEntity 的数据访问，支持按 sessionId 查找（含/不含简历关联）、
 * 按简历 ID 查找历史记录、查找未完成会话、按 skillId 查找（通用模式去重）、
 * 按 resumeId + skillId 精确匹配查找等。
 *
 * 联动关系：
 * - 被 InterviewPersistenceService 调用进行会话的增删改查
 * - 被 EvaluateStreamConsumer/EvaluateStreamProducer 直接调用更新评估状态
 * - 被 InterviewSessionCache 恢复会话时间接使用
 */
package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试会话Repository
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {

    /**
     * 根据会话ID查找
     *
     * <p>由持久化服务、评估 Producer 和 Consumer 的状态写入路径调用。
     */
    Optional<InterviewSessionEntity> findBySessionId(String sessionId);

    /**
     * 根据会话ID查找（同时加载关联的简历）
     *
     * <p>由 {@code EvaluateStreamConsumer.processBusiness} 调用，
     * 使用 fetch join 避免异步线程读取简历时触发懒加载异常。
     */
    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume WHERE s.sessionId = :sessionId")
    Optional<InterviewSessionEntity> findBySessionIdWithResume(@Param("sessionId") String sessionId);
    
    /**
     * 根据简历查找所有面试记录
     *
     * <p>当前业务按 resumeId 查询，保留给已持有 ResumeEntity 的调用场景。
     */
    List<InterviewSessionEntity> findByResumeOrderByCreatedAtDesc(ResumeEntity resume);
    
    /**
     * 根据简历ID查找所有面试记录
     *
     * <p>由持久化服务查询和批量删除简历关联面试时调用。
     */
    List<InterviewSessionEntity> findByResumeIdOrderByCreatedAtDesc(Long resumeId);

    /**
     * 根据简历ID查找最近的面试记录（用于历史题去重）
     *
     * <p>当前历史题逻辑同时按 skillId 过滤，该方法暂未直接调用。
     */
    List<InterviewSessionEntity> findTop10ByResumeIdOrderByCreatedAtDesc(Long resumeId);
    
    /**
     * 查找简历的未完成面试（CREATED或IN_PROGRESS状态）
     *
     * <p>由 {@code InterviewPersistenceService.findUnfinishedSession} 调用，
     * 只返回创建时间最新的一条会话。
     */
    Optional<InterviewSessionEntity> findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId, 
        List<SessionStatus> statuses
    );
    
    /**
     * 根据简历ID和状态查找会话
     *
     * <p>当前业务使用带排序和 first 限制的方法，该方法暂未直接调用。
     */
    Optional<InterviewSessionEntity> findByResumeIdAndStatusIn(
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 查找所有面试会话（按创建时间倒序）
     *
     * <p>由 {@code InterviewPersistenceService.findAll} 调用，
     * 用于面试记录总览。
     */
    List<InterviewSessionEntity> findAllByOrderByCreatedAtDesc();

    /**
     * 根据 skillId 查找最近的面试记录（用于通用模式历史题去重）
     *
     * <p>由持久化服务在没有 resumeId 时加载历史题目。
     */
    List<InterviewSessionEntity> findTop10BySkillIdOrderByCreatedAtDesc(String skillId);

    /**
     * 根据 resumeId + skillId 查找最近的面试记录（精确匹配）
     *
     * <p>由持久化服务在基于简历出题时加载历史题目。
     */
    List<InterviewSessionEntity> findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(Long resumeId, String skillId);
}
