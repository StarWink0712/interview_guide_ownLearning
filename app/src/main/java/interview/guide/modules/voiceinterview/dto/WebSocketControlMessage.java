/**
 * WebSocket 控制消息
 *
 * 前端通过 WebSocket 发送的控制指令，支持 submit（提交用户文本）、
 * end_interview（结束面试）、start_phase（切换面试阶段）等操作。
 * 使用 @JsonIgnoreProperties 忽略未知字段以保证前向兼容。
 *
 * 联动关系：
 * - 被 VoiceInterviewWebSocketHandler.handleTextMessage 反序列化
 * - 被 VoiceInterviewWebSocketHandler.handleControl 解析并分发处理
 */
package interview.guide.modules.voiceinterview.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketControlMessage {
    private String type; // "control"
    private String action; // "start_phase", "end_phase", "end_interview", "submit"
    private String phase; // "INTRO", "TECH", "PROJECT", "HR"
    private Map<String, Object> data;
}
