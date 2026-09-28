/**
 * 语音面试对话消息响应体
 *
 * 用于向前端返回对话历史记录，包含消息 ID、会话 ID、消息类型、面试阶段、
 * 用户语音识别文本、AI 生成文本、时间戳及序号。
 *
 * 联动关系：
 * - 被 VoiceInterviewService.getConversationHistoryDTO 从 MessageEntity 映射构建
 * - 被 VoiceInterviewController.getMessages 返回给前端
 */
package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewMessageDTO {
    private Long id;
    private Long sessionId;
    private String messageType;
    private String phase;
    private String userRecognizedText;
    private String aiGeneratedText;
    private LocalDateTime timestamp;
    private Integer sequenceNum;
}
