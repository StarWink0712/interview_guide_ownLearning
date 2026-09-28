/**
 * 简历分析配置属性
 *
 * 集中管理简历分析相关的配置项，包括系统提示词模板路径和用户提示词模板路径。
 * 使用 @ConfigurationProperties 绑定 app.resume.analysis 前缀的配置。
 *
 * 联动关系：
 * - 被 ResumeGradingService 依赖，用于加载 AI 分析的 Prompt 模板
 * - 绑定 application.yml 中 app.resume.analysis.* 配置项
 */
package interview.guide.modules.resume.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.resume.analysis")
public class ResumeAnalysisProperties {
    // 系统提示词模板路径
    // 用于加载 AI 分析的系统提示词模板
    private String systemPromptPath = "classpath:prompts/resume-analysis-system.st";
    // 用户提示词模板路径
    // 用于加载 AI 分析的用户提示词模板
    private String userPromptPath = "classpath:prompts/resume-analysis-user.st";
}
