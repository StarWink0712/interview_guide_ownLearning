/**
 * 面试出题配置属性
 *
 * 集中管理面试问题生成相关的配置项，通过 @ConfigurationProperties 绑定
 * app.interview 前缀，包括追问数量、Skill 出题和简历出题的 Prompt 模板路径。
 *
 * 联动关系：
 * - 被 InterviewQuestionService 构造时注入，读取模板路径和追问数量配置
 * - 配置前缀：app.interview
 */
package interview.guide.modules.interview.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview")
public class InterviewQuestionProperties {
    //followUpCount是追问次数
    private int followUpCount = 1;
    //questionSystemPromptPath是Skill 出题的系统提示模板路径
    private String questionSystemPromptPath = "classpath:prompts/interview-question-skill-system.st";
    //questionUserPromptPath是Skill 出题的用户提示模板路径
    private String questionUserPromptPath = "classpath:prompts/interview-question-skill-user.st";
    //resumeQuestionSystemPromptPath是简历出题的系统提示模板路径
    private String resumeQuestionSystemPromptPath = "classpath:prompts/interview-question-resume-system.st";
    //resumeQuestionUserPromptPath是简历出题的用户提示模板路径
    private String resumeQuestionUserPromptPath = "classpath:prompts/interview-question-resume-user.st";
}
