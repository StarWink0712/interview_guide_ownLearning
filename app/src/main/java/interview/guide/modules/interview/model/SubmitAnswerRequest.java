/**
 * 提交答案请求体
 *
 * 携带会话 ID、问题索引和用户答案，通过 Jakarta Validation 校验必填字段。
 * 同时用于正式提交（进入下一题）和暂存（不进入下一题）两种场景。
 *
 * 联动关系：
 * - 由 InterviewController 构建后传递给 InterviewSessionService.submitAnswer() / saveAnswer()
 */
package interview.guide.modules.interview.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交答案请求
 */
public record SubmitAnswerRequest(
    @NotBlank(message = "会话ID不能为空")
    String sessionId,
    
    @NotNull(message = "问题索引不能为空")
    @Min(value = 0, message = "问题索引无效")
    Integer questionIndex,
    
    @NotBlank(message = "答案不能为空")
    String answer
) {}
