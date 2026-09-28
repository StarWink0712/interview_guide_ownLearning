/**
 * AI 面试平台主启动类
 *
 * Spring Boot 应用入口，启用定时调度（@EnableScheduling），
 * 排除 Spring AI 内置的 OpenAI 自动配置（由 LlmProviderRegistry 手动管理），
 * 确保应用使用自定义的多 Provider 注册机制而非默认单 Provider。
 *
 * 联动关系：
 * - 排除的自动配置由 LlmProviderRegistry + LlmProviderProperties 替代
 * - @EnableScheduling 支持定时任务（如 Provider 配置热加载等）
 */
package interview.guide;

import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI Interview Platform - Main Application
 * 智能AI面试官平台 - 主启动类
 */
@EnableScheduling
/*这段代码的核心作用是： 
禁用 Spring AI 内置的 OpenAI 自动配置，
因为项目用的是自定义的多 Provider 机制（LlmProviderRegistry），
不需要 Spring AI 默认的 OpenAI 单 Provider 方案。
这些类来自 Spring AI 的 OpenAI starter 依赖。
如果没有引入，就不需要做这个配置
 */
@SpringBootApplication(exclude = {
    OpenAiAudioSpeechAutoConfiguration.class,
    OpenAiAudioTranscriptionAutoConfiguration.class,
    OpenAiChatAutoConfiguration.class,
    OpenAiEmbeddingAutoConfiguration.class,
    OpenAiImageAutoConfiguration.class,
    OpenAiModerationAutoConfiguration.class
})
public class App {

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
