/**
 * CORS 跨域配置属性
 * 允许前端5173端口访问后端8080端口
 * 映射 app.cors 前缀的配置项，当前仅包含 allowedOrigins（允许的来源域名，逗号分隔）。
 *
 * 联动关系：
 * - 被 CorsConfig 注入，用于构建 CorsFilter
 */
package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /** 允许的来源域名（逗号分隔），默认 http://localhost:5173（Vite 开发服务器） */
    private String allowedOrigins = "http://localhost:5173";
}
