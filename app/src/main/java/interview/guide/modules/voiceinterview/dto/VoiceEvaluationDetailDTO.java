/**
 * 语音面试评估详情响应体
 *
 * 包含面试评估的完整结果：总分、总评、优势、改进建议及逐题评估详情
 * （问题、分类、用户回答、得分、反馈、参考答案、关键点）。
 * 字段设计与文字面试的 InterviewDetailDTO 对齐，便于前端复用评估面板组件。
 *
 * 联动关系：
 * - 被 VoiceInterviewEvaluationService.getEvaluation 构建并返回
 * - 被 VoiceEvaluationStatusDTO 引用（评估完成时嵌入）
 * - 被 VoiceInterviewController.getEvaluation / generateEvaluation 透传给前端
 */
package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Voice evaluation detail DTO — aligned with text-based interview InterviewDetailDTO.
 * This allows the frontend to reuse InterviewDetailPanel for rendering.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationDetailDTO {

    private Long sessionId;
    private int totalQuestions;
    private int overallScore;
    private String overallFeedback;
    private List<String> strengths;
    private List<String> improvements;
    private List<AnswerDetail> answers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerDetail {
        private int questionIndex;
        private String question;
        private String category;
        private String userAnswer;
        private int score;
        private String feedback;
        private String referenceAnswer;
        private List<String> keyPoints;
    }
}
