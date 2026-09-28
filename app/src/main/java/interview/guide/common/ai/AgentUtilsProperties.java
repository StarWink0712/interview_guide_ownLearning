/**
 * Agent 工具配置属性
 *
 * 映射 app.ai.agent-utils 前缀的配置项，当前仅包含 skillsRoot（技能文件根目录路径）。
 *
 * 联动关系：
 * - 被 AgentUtilsConfiguration 注入，用于定位 SkillsTool 的资源根目录
 */
package interview.guide.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
/*
 * 把 YAML 配置自动绑到 Java 类上
 * 把 YAML 中 app.ai.agent-utils 下的所有配置自动映射到这个类的同名字段上，
 * 替代散落在各处的 @Value 注解。配置集中管理、字段有类型安全、IDE 有自动补全。
 */
@ConfigurationProperties(prefix = "app.ai.agent-utils")
public class AgentUtilsProperties {
    /**
     * 技能文件根目录路径
     */
    private String skillsRoot = "classpath:skills";
    /**
     * 这个路径最终的作用是，在AgentUtilsConfiguration.java中，根据这个
     * 路径，加载路径下的skill.md文件，让llm调用这些skill工具来出题或面试。
     * Spring Boot 启动
     * ↓
     * 发现 AgentUtilsProperties 上有 @ConfigurationProperties(prefix =
     * "app.ai.agent-utils")
     * ↓
     * 去 YAML 中找 app.ai.agent-utils.skills-root
     * ↓
     * 找到了 → skillsRoot = YAML 配的值
     * 没找到 → skillsRoot = "classpath:skills"（默认值）
     * ↓
     * AgentUtilsConfiguration 读取这个值作为 SkillsTool 的资源目录
     */
}
