/**
 * 语音面试会话创建/恢复响应体
 *
 * 包含会话核心信息及 WebSocket 连接 URL，前端收到后通过该 URL 建立 WebSocket 连接
 * 进入实时语音面试。
 * 会话详情,包含会话ID、角色类型、当前类型、当前阶段、状态、开始时间、计划持续时间、WebSocket 连接 URL。
 * 联动关系：
 * - 被 VoiceInterviewService.createSession / resumeSession 构建
 * - 被 VoiceInterviewController.createSession / getSession / resumeSession 返回给前端
 */
package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Session response with WebSocket URL
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponseDTO {
    private Long sessionId;
    private String roleType;
    private String currentPhase;
    private String status;
    private LocalDateTime startTime;
    private Integer plannedDuration;
    private String webSocketUrl;
}
