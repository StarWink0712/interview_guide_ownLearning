/**
 * CORS 跨域配置
 *
 * 基于 CorsProperties 读取允许的来源域名，注册 /api/** 路径的 CorsFilter Bean。
 * 允许所有标准 HTTP 方法，支持凭证传递，预检请求缓存 3600 秒。
 *
 * 联动关系：
 * - 依赖 CorsProperties 读取 app.cors.allowed-origins 配置
 */
package interview.guide.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 跨域配置
 *
 * 基于 CorsProperties 读取允许的来源域名，注册 /api/** 路径的 CorsFilter Bean。
 * 允许所有标准 HTTP 方法，支持凭证传递，预检请求缓存 3600 秒。
 *
 * 联动关系：
 * - 依赖 CorsProperties 读取 app.cors.allowed-origins 配置
 */
@Configuration
public class CorsConfig {

    /** CORS 配置属性（允许的来源域名等） */
    private final CorsProperties corsProperties;

    public CorsConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    /**
     * 创建 CorsFilter Bean，配置 /api/** 路径的跨域规则。
     *
     * <p>配置说明：
     * <ul>
     *   <li><b>allowedOrigins</b>：从配置文件中读取允许的前端来源（逗号分隔）</li>
     *   <li><b>allowedMethods</b>：允许所有标准 HTTP 方法（含 OPTIONS 预检请求）</li>
     *   <li><b>allowedHeaders</b>：允许所有请求头（{"@literal *}"）</li>
     *   <li><b>allowCredentials</b>：允许携带 Cookie/Authorization 等凭证</li>
     *   <li><b>maxAge</b>：预检请求缓存 3600 秒（1小时），减少 OPTIONS 请求次数</li>
     * </ul>
     *
     * <p>路径匹配：仅对 /api/** 路径生效，避免影响静态资源等非 API 请求。
     *
     * @return CorsFilter 实例
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 从配置中读取允许的来源域名（用逗号分隔的字符串），解析为列表
        String allowedOrigins = corsProperties.getAllowedOrigins();
        Arrays.stream(allowedOrigins.split(","))
              .map(String::trim)
              .forEach(config::addAllowedOrigin);

        // 允许所有标准 HTTP 方法
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        // 允许所有请求头
        config.setAllowedHeaders(List.of("*"));
        // 允许携带凭证（Cookie、Authorization Header 等）
        config.setAllowCredentials(true);
        // 预检请求缓存 1 小时
        config.setMaxAge(3600L);

        // 将 CORS 配置注册到 /api/** 路径
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);

        return new CorsFilter(source);
    }
}
