/**
 * WebSocket 字幕消息
 *
 * 服务端通过 WebSocket 推送给前端的实时字幕数据，包含字幕文本和是否为最终定稿标记。
 * 用于在语音面试过程中实时显示用户语音识别结果。
 *
 * 联动关系：
 * - 被 VoiceInterviewWebSocketHandler.sendSubtitle 构建并推送
 * - 前端接收后在界面上展示实时字幕
 */
package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketSubtitleMessage {
    private String type; // "subtitle"
    private String text;
    private Boolean isFinal;
}
