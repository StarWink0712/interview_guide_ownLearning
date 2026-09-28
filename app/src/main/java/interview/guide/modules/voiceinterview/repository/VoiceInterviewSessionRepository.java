/**
 * 语音面试会话 Repository
 *
 * 提供 VoiceInterviewSessionEntity 的 JPA 数据访问层，
 * 支持按用户 ID 查询会话列表（按时间排序）、按用户和状态联合查询、
 * 以及按评估状态和结束时间查询等。
 *
 * 联动关系：
 * - 被 VoiceInterviewService 核心依赖（会话 CRUD、状态查询、列表展示）
 * - 被 VoiceInterviewEvaluationService 依赖（查询会话信息用于评估）
 * - 被 VoiceEvaluateStreamConsumer 间接依赖（通过 Service 更新评估状态）
 * - 管理 VoiceInterviewSessionEntity 实体
 */
package interview.guide.modules.voiceinterview.repository;

import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity.InterviewPhase;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 语音面试会话Repository
 */
@Repository
public interface VoiceInterviewSessionRepository extends JpaRepository<VoiceInterviewSessionEntity, Long> {

    /**
     * 查询用户会话并按开始时间倒序排列。
     *
     * 保留给按创建时序展示的内部调用场景，查询由 Spring Data 根据方法名生成。
     *
     * @param userId 用户 ID
     * @return 最近开始的会话优先
     */
    List<VoiceInterviewSessionEntity> findByUserIdOrderByStartTimeDesc(String userId);

    /**
     * 查询指定状态且结束时间早于阈值的一条会话。
     *
     * 用于历史任务清理或补偿扫描。注意参数类型对应异步任务状态，而不是面试阶段；调用方应确保
     * 查询语义与实体字段一致。
     *
     * @param status 异步任务状态
     * @param time 结束时间上界
     * @return 命中的会话，不存在时为空
     */
    Optional<VoiceInterviewSessionEntity> findByStatusAndEndTimeBefore(
        interview.guide.common.model.AsyncTaskStatus status,
        LocalDateTime time
    );

    /**
     * 查询用户的全部会话，并按最近更新时间倒序排列。
     *
     * 由会话历史列表在未指定状态过滤时调用。
     *
     * @param userId 用户 ID
     * @return 最近更新的会话优先
     */
    List<VoiceInterviewSessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    /**
     * 按用户和会话状态查询，并按最近更新时间倒序排列。
     *
     * 由会话历史列表在前端提供状态筛选时调用。
     *
     * @param userId 用户 ID
     * @param status 会话生命周期状态
     * @return 符合状态的会话列表
     */
    List<VoiceInterviewSessionEntity> findByUserIdAndStatusOrderByUpdatedAtDesc(
        String userId,
        VoiceInterviewSessionStatus status
    );
}
