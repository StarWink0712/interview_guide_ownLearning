/**
 * 创建语音面试会话请求体
 *
 * 前端发起语音面试时提交的参数，包括技能模板 ID、难度、自定义 JD 文本、
 * 关联简历 ID、各面试阶段开关、计划时长及 LLM Provider 选择。
 *
 * 联动关系：
 * - 被 VoiceInterviewController.createSession 接收
 * - 被 VoiceInterviewService.createSession 消费以构建 SessionEntity
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
public class CreateSessionRequest {
    private String roleType; // 向后兼容，新请求可不传（服务层会用 skillId 填充）
    private String skillId;  // 模板 ID，如 "java-backend", "bytedance-backend" 等
    private String difficulty; // "junior", "mid", "senior"
    private String customJdText;
    private Long resumeId;

    @Builder.Default
    private Boolean introEnabled = false;
    @Builder.Default
    private Boolean techEnabled = true;
    @Builder.Default
    private Boolean projectEnabled = true;
    @Builder.Default
    private Boolean hrEnabled = true;
    @Builder.Default
    private Integer plannedDuration = 30;

    private String llmProvider;
}
