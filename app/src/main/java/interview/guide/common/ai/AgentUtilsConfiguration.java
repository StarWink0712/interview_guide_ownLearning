/**
 * spring-ai-agent-utils 工具配置类
 *
 * <p>功能概述：
 * 将 SkillsTool 注册为 Spring Bean，使 LLM 在对话中能调用面试技能工具。
 * 从 AgentUtilsProperties 读取 skills 根目录路径，加载 resources/skills/{skillId}/SKILL.md 资源。
 *
 * <p>联动关系：
 * <ul>
 *   <li>依赖 AgentUtilsProperties 读取 app.ai.agent-utils.skills-root 配置</li>
 *   <li>产出 "interviewSkillsToolCallback" Bean，被 LlmProviderRegistry 注入到 ChatClient</li>
 * </ul>
 *
 * <p>技能文件目录结构示例：
 * <pre>
 * resources/
 * └── skills/
 *     ├── interview-question/
 *     │   └── SKILL.md
 *     ├── resume-analysis/
 *     │   └── SKILL.md
 *     └── ...
 * </pre>
 */
package interview.guide.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * spring-ai-agent-utils 工具配置。
 *
 * <p>当前先接入 SkillsTool，复用 resources/skills/{skillId}/SKILL.md。
 * 该配置类负责初始化技能工具，使其能够被 AI 模型在对话过程中调用。
 */
@Configuration
@Slf4j
public class AgentUtilsConfiguration {

    /**
     * Spring 资源加载器，用于加载技能文件目录资源。
     * 支持多种资源协议：classpath:、file:、url: 等。
     */
    private final ResourceLoader resourceLoader;

    /**
     * Agent 工具配置属性，包含 skills 根目录路径等配置项。
     * 通过 @ConfigurationProperties 绑定 application.yml 中的配置。
     */
    private final AgentUtilsProperties agentUtilsProperties;

    /**
     * 构造函数注入依赖。
     *
     * @param resourceLoader        Spring 资源加载器，用于定位和加载技能文件
     * @param agentUtilsProperties  Agent 工具配置属性，提供 skills 根目录路径
     */
    public AgentUtilsConfiguration(
        ResourceLoader resourceLoader, AgentUtilsProperties agentUtilsProperties
    ) {
        this.resourceLoader = resourceLoader;
        this.agentUtilsProperties = agentUtilsProperties;
    }

    /**
     * 创建面试技能工具回调 Bean。
     *
     * <p>该方法执行以下步骤：
     * <ol>
     *   <li>从配置属性中读取 skills 根目录路径</li>
     *   <li>对路径进行规范化处理（处理各种格式变体）</li>
     *   <li>加载资源目录并校验其存在性</li>
     *   <li>构建 SkillsTool 实例并返回</li>
     * </ol>
     *
     * <p>该 Bean 会被 LlmProviderRegistry 注入到 ChatClient 中，
     * 使得 LLM 能够在对话过程中调用预定义的面试技能。
     *
     * @return ToolCallback 技能工具回调实例，可被 ChatClient 调用
     * @throws IllegalStateException 当配置的 skills 根目录不存在时抛出
     */
    @Bean("interviewSkillsToolCallback")
    public ToolCallback interviewSkillsToolCallback() {
        // 1. 获取配置的 skills 根目录路径
        String configuredSkillsRoot = agentUtilsProperties.getSkillsRoot();

        // 2. 规范化路径，处理各种格式变体
        String normalizedSkillsRoot = normalizeSkillsRoot(configuredSkillsRoot);

        // 3. 使用 ResourceLoader 加载资源目录
        //pring 的 Resource 是一个抽象，可以代表 任何可加载的东西 ——文件、目录、URL、JAR 里的路径都可以
        Resource skillsRootResource = resourceLoader.getResource(normalizedSkillsRoot);

        // 4. 校验目录存在性，快速失败以避免运行时错误
        if (!skillsRootResource.exists()) {
            throw new IllegalStateException("未找到 skills 根目录，请检查配置: " + normalizedSkillsRoot);
        }

        // 5. 记录启动日志，便于调试和问题排查
        log.info("AgentUtils SkillsTool 已启用，skillsRoot={}, configured={}", normalizedSkillsRoot, configuredSkillsRoot);

        // 6. 构建 SkillsTool 实例
        return SkillsTool.builder()
            .addSkillsResource(skillsRootResource)
            .build();
    }

    /**
     * 规范化 skills 根目录路径。
     *
     * <p>该方法处理用户可能输入的各种路径格式，确保最终输出一个有效的资源路径。
     * 处理的边界情况包括：
     * <ul>
     *   <li>null 或空白字符串：返回默认值 "classpath:skills"</li>
     *   <li>Windows 路径分隔符：将 '\' 转换为 '/'</li>
     *   <li>以 "/SKILL.md" 结尾：移除该后缀，提取目录路径</li>
     *   <li>包含通配符 '*'：截取通配符之前的部分</li>
     *   <li>末尾有多余的 '/'：移除末尾斜杠</li>
     * </ul>
     *
     * <p>示例转换：
     * <pre>
     * null                    → "classpath:skills"
     * "skills\\interview"     → "skills/interview"
     * "skills/interview/SKILL.md" → "skills/interview"
     * "skills/*"              → "skills"
     * "skills/interview//"    → "skills/interview"
     * </pre>
     *
     * @param raw 原始路径字符串，可能为 null 或包含各种格式变体
     * @return 规范化后的路径字符串，保证非空且格式正确
     */
    private String normalizeSkillsRoot(String raw) {
        // 处理 null 或空白字符串，返回默认值
        if (raw == null || raw.isBlank()) {
            return "classpath:skills";
        }

        // 去除首尾空白
        String normalized = raw.trim();

        // 统一路径分隔符：Windows '\' → Unix '/'
        normalized = normalized.replace('\\', '/');

        // 移除末尾的 "/SKILL.md"（用户可能误配置到文件级别）
        if (normalized.endsWith("/SKILL.md")) {
            normalized = normalized.substring(0, normalized.length() - "/SKILL.md".length());
        }

        // 处理通配符：截取 '*' 之前的部分（如 "skills/*" → "skills"）
        int wildcardIndex = normalized.indexOf('*');
        if (wildcardIndex >= 0) {
            normalized = normalized.substring(0, wildcardIndex);
        }

        // 移除末尾多余的 '/'（如 "skills/interview//" → "skills/interview"）
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        // 如果处理后变为空白，返回默认值
        return normalized.isBlank() ? "classpath:skills" : normalized;
    }
}
