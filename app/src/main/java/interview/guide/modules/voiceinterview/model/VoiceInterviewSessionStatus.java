/**
 * 语音面试会话状态枚举
 *
 * 定义会话的四种状态：IN_PROGRESS（进行中，WebSocket 已连接）、
 * PAUSED（已暂停，用户主动或超时暂停，状态已保存）、
 * COMPLETED（已完成，面试结束）、FAILED（失败，发生错误）。
 *
 * 联动关系：
 * - 被 VoiceInterviewSessionEntity.status 字段引用
 * - 被 VoiceInterviewService 用于会话状态判断与流转
 * - 被 VoiceInterviewSessionRepository 用于按状态查询
 */
package interview.guide.modules.voiceinterview.model;

/**
 * Voice Interview Session Status
 * 语音面试会话状态
 */
public enum VoiceInterviewSessionStatus {
    /**
     * In progress - WebSocket connected, interview ongoing
     */
    IN_PROGRESS,

    /**
     * Paused - User paused or timeout, state saved to DB
     */
    PAUSED,

    /**
     * Completed - Interview finished
     */
    COMPLETED,

    /**
     * Failed - Error occurred
     */
    FAILED
}
