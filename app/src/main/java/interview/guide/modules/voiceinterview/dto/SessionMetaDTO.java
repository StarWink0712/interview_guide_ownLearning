/**
 * 语音面试会话列表元数据
 *
 * 用于会话列表页展示的精简信息，包含会话 ID、角色类型、状态、当前阶段、
 * 创建/更新时间、实际时长、消息数量及评估状态。
 *
 * 联动关系：
 * - 被 VoiceInterviewService.getAllSessions 构建并返回
 * - 被 VoiceInterviewController.getAllSessions 透传给前端
 */
package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Session metadata for list display
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMetaDTO {
    private Long sessionId;
    private String roleType;
    private String status;
    private String currentPhase;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer actualDuration;
    private Long messageCount;
    private String evaluateStatus;
    private String evaluateError;
}
