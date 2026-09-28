/**
 * LLM Embedding 模型配置
 *
 * 将 LlmProviderRegistry 的默认 EmbeddingModel 代理为 Spring Bean，
 * 使 Spring AI 自动注入点（如 VectorStore）能使用动态 Provider 的 Embedding 能力。
 *
 * 联动关系：
 * - 依赖 LlmProviderRegistry 获取默认 EmbeddingModel 实例
 * - 产出 EmbeddingModel Bean，被 Spring AI 的 VectorStore 等组件自动注入
 */
package interview.guide.common.config;

import interview.guide.common.ai.LlmProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class LlmEmbeddingConfig {

  /**
   * 创建 EmbeddingModel Bean，将 LlmProviderRegistry 中的默认 EmbeddingModel
   * 代理为一个符合 Spring AI 标准的 EmbeddingModel Bean。
   *
   * <p>设计背景：
   * Spring AI 的 VectorStore 等组件通过 @Autowired 自动注入 EmbeddingModel。
   * 但 LlmProviderRegistry 管理的 EmbeddingModel 是动态实例（可能在运行时通过 API 切换），
   * 无法直接注册为静态 Bean。本方法通过匿名类代理模式实现桥接：
   *
   * <pre>
   * 调用链：
   * VectorStore → @Autowired EmbeddingModel (本 Bean)
   *                    ↓ 匿名类委托
   *              LlmProviderRegistry.getDefaultEmbeddingModel()
   *                    ↓ 获取实际的动态 Provider 实例
   *              OpenAiEmbeddingModel (DashScope/GLM/...)
   * </pre>
   *
   * <p>代理的两个核心方法：
   * <ul>
   *   <li>{@code call(EmbeddingRequest)}：批量文本向量化，传入文本列表</li>
   *   <li>{@code embed(Document)}：单个文档向量化，传入 Document 对象</li>
   * </ul>
   * 每次调用时实时委托到 registry 获取当前生效的 EmbeddingModel 实例，
   * 确保 Provider 切换后自动生效（无需重启）。
   *
   * @param registry LLM Provider 注册中心
   * @return 代理 EmbeddingModel Bean
   */
  @Bean
  public EmbeddingModel embeddingModel(LlmProviderRegistry registry) {
    log.info("EmbeddingModel bean initialized as registry delegate");
    // 使用匿名类实现代理模式，每次调用都委托到 registry 获取实际实例
    return new EmbeddingModel() {
      // 批量向量化：传入 EmbeddingRequest（包含文本列表和选项）
      @Override
      public EmbeddingResponse call(EmbeddingRequest request) {
        return registry.getDefaultEmbeddingModel().call(request);
      }

      // 单个文档向量化：传入 Document 对象
      @Override
      public float[] embed(Document document) {
        return registry.getDefaultEmbeddingModel().embed(document);
      }
    };
  }
}
