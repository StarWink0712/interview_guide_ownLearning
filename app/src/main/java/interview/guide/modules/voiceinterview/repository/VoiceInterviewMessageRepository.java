/**
 * 语音面试对话消息 Repository
 *
 * 提供 VoiceInterviewMessageEntity 的 JPA 数据访问层，
 * 支持按会话 ID 查询消息（按序号升序）、统计消息数量及按会话 ID 删除。
 *
 * 联动关系：
 * - 被 VoiceInterviewService 依赖（保存消息、查询对话历史、统计消息数）
 * - 被 VoiceInterviewEvaluationService 依赖（读取对话记录构建评估输入）
 * - 被 VoiceInterviewService.deleteSession 依赖（级联删除消息）
 * - 管理 VoiceInterviewMessageEntity 实体
 */
package interview.guide.modules.voiceinterview.repository;

import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 语音面试消息Repository
 */
@Repository
public interface VoiceInterviewMessageRepository extends JpaRepository<VoiceInterviewMessageEntity, Long> {

    /**
     * 查询会话的完整消息历史，并按业务序号升序返回。
     *
     * 会话服务用于前端历史和 LLM 上下文，评估服务用于构建问答记录。
     *
     * @param sessionId 语音面试会话 ID
     * @return 按 {@code sequenceNum} 升序的消息列表
     */
    List<VoiceInterviewMessageEntity> findBySessionIdOrderBySequenceNumAsc(Long sessionId);

    /**
     * 统计会话已持久化的消息轮数。
     *
     * 会话服务用它生成下一业务序号，并在历史列表中展示消息数量。
     *
     * @param sessionId 语音面试会话 ID
     * @return 当前消息记录数
     */
    long countBySessionId(Long sessionId);

    /**
     * 删除指定会话的全部消息。
     *
     * 仅由会话删除事务调用，先清理消息再删除主会话。
     *
     * @param sessionId 语音面试会话 ID
     */
    void deleteBySessionId(Long sessionId);
}
