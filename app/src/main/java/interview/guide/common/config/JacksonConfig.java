/**
 * Jackson ObjectMapper 配置
 *
 * 提供全局 ObjectMapper Bean，用于 JSON 序列化/反序列化。
 *
 * 联动关系：
 * - 被 Spring 框架自动注入到所有需要 JSON 处理的组件
 */
package interview.guide.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson ObjectMapper 配置
 * 提供全局 ObjectMapper Bean，用于 JSON 序列化/反序列化。
 *
 * 联动关系：
 * - 被 Spring 框架自动注入到所有需要 JSON 处理的组件
 */
@Configuration
public class JacksonConfig {

    /**
     * 创建全局 ObjectMapper Bean，供整个应用使用。
     *
     * <p>设计说明：
     * 显式声明 ObjectMapper Bean 而非使用 Spring Boot 自动配置的默认实例，
     * 原因是项目中同时引入了 tools.jackson 和 com.fasterxml.jackson 两个版本。
     * tools.jackson 用于与 Spring AI 的 MapStruct 等组件兼容，
     * 此处返回 tools.jackson 的 ObjectMapper，确保 AI 结构化输出等场景
     * 使用正确的 Jackson 版本，避免类加载冲突。
     *
     * @return tools.jackson 版本的 ObjectMapper 实例
     */
    @Bean
    public ObjectMapper objectMapper() {
        // 返回 tools.jackson 的 ObjectMapper（与项目中其他 JSON 处理保持一致）
        return new ObjectMapper();
    }
}
