/**
 * OpenAPI/Swagger 文档配置
 *
 * 配置 API 文档的标题、描述和版本信息，供 Swagger UI 展示。
 *
 * 联动关系：
 * - 产出 OpenAPI Bean，被 SpringDoc 自动采集生成 API 文档
 */
package interview.guide.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /**
     * 创建 OpenAPI Bean，配置文档标题、描述和版本
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("智能 AI 面试官平台 API")
                        .description("简历分析、模拟面试、知识库管理 RESTful API 文档")
                        .version("1.0.0"));
    }
}
