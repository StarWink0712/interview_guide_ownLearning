/**
 * 面试评估配置属性
 *
 * 映射 app.interview.evaluation 前缀的配置项，包括批次大小（batchSize）、
 * 批次评估和汇总评估的 system/user prompt 模板路径。
 *
 * 联动关系：
 * - 被 UnifiedEvaluationService 注入，用于加载评估 prompt 模板和配置批次大小
 */
package interview.guide.common.evaluation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
//自动将配置文件的值绑定到该类的字段 。
@ConfigurationProperties(prefix = "app.interview.evaluation")
public class InterviewEvaluationProperties {

    /** 每批次评估的题目数量，默认 8 题 */
    private int batchSize = 8;
    /** 批次评估系统 Prompt 模板路径 */
    private String systemPromptPath = "classpath:prompts/interview-evaluation-system.st";
    /** 批次评估用户 Prompt 模板路径 */
    private String userPromptPath = "classpath:prompts/interview-evaluation-user.st";
    /** 汇总评估系统 Prompt 模板路径 */
    private String summarySystemPromptPath = "classpath:prompts/interview-evaluation-summary-system.st";
    /** 汇总评估用户 Prompt 模板路径 */
    private String summaryUserPromptPath = "classpath:prompts/interview-evaluation-summary-user.st";
}
