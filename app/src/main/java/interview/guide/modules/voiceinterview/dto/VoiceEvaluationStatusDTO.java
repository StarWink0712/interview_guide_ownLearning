/**
 * 语音面试评估状态响应体
 *
 * 用于前端轮询评估进度，包含评估任务状态（PENDING/PROCESSING/COMPLETED/FAILED）、
 * 失败时的错误信息，以及评估完成时的完整评估详情。
 *
 * 联动关系：
 * - 被 VoiceInterviewController.getEvaluation / generateEvaluation 构建并返回
 * - 依赖 VoiceEvaluationDetailDTO（评估完成时嵌入详情）
 */
package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Voice evaluation status response DTO
 * 语音面试评估状态响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationStatusDTO {

    /**
     * Evaluation task status: PENDING / PROCESSING / COMPLETED / FAILED
     */
    private String evaluateStatus;

    /**
     * Error message when status is FAILED
     */
    private String evaluateError;

    /**
     * Full evaluation result, only present when status is COMPLETED
     */
    private VoiceEvaluationDetailDTO evaluation;
}
