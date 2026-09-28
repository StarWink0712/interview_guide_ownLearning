/**
 * 面试邀约解析请求体
 *
 * 封装前端提交的待解析文本和来源平台标识（feishu/tencent/zoom/other），
 * 用于 AI 或规则解析面试邀约信息。
 *
 * 联动关系：
 * - 被 InterviewScheduleController 的 parse 接口接收
 * - 传递给 InterviewParseService.parse() 方法进行解析
 */
package interview.guide.modules.interviewschedule.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ParseRequest {
    @NotBlank(message = "文本不能为空")
    private String rawText;

    private String source; // feishu, tencent, zoom, other
}
