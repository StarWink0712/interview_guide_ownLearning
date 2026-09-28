/**
 * 语音面试评估结果 Repository
 *
 * 提供 VoiceInterviewEvaluationEntity 的 JPA 数据访问层，
 * 核心查询方法为 findBySessionId（按会话 ID 查找评估结果，一对一关系）。
 *
 * 联动关系：
 * - 被 VoiceInterviewEvaluationService 依赖（查询和保存评估结果）
 * - 被 VoiceInterviewService.deleteSession 依赖（级联删除评估记录）
 * - 管理 VoiceInterviewEvaluationEntity 实体
 */
package interview.guide.modules.voiceinterview.repository;

import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 语音面试评估Repository
 */
public interface VoiceInterviewEvaluationRepository extends JpaRepository<VoiceInterviewEvaluationEntity, Long> {

    /**
     * 按会话 ID 查询唯一评估结果。
     *
     * 由评估查询、重复消费判定和会话删除调用；Spring Data 根据方法名生成查询。
     *
     * @param sessionId 语音面试会话 ID
     * @return 评估记录，不存在时为空
     */
    Optional<VoiceInterviewEvaluationEntity> findBySessionId(Long sessionId);
}
