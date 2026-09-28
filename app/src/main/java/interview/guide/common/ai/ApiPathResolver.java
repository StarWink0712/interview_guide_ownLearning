/**
 * OpenAI API 路径解析与客户端构建工具
 *
 * 根据 baseUrl 是否包含版本号（如 /v1）自动调整 API 路径，解决不同 LLM 厂商
 * API 路径差异问题。提供统一的 OpenAiApi 实例构建方法，内置默认超时配置。
 *
 * 联动关系：
 * - 被 LlmProviderRegistry 调用，构建 ChatModel 和 EmbeddingModel 所需的 OpenAiApi 实例
 * - 被 LlmProviderConfigService 调用，用于 Provider 连通性测试
 */
package interview.guide.common.ai;

import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.regex.Pattern;

public final class ApiPathResolver {

  /**
   * 默认连接超时时间（毫秒）
   * 建立TCP连接的时间
   * 想LLM厂商发起连接时，如果10内练不上就超时
   */
  private static final int DEFAULT_CONNECT_TIMEOUT = 10000;
  /**
   * 默认读取超时时间（毫秒）
   * 等llm返回数据的等待时间，5分钟
   */
  private static final int DEFAULT_READ_TIMEOUT = 300000;
  //正则表达式，看baseurl末尾是否带有版本号
  private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

  private ApiPathResolver() {}

  public static OpenAiApi buildOpenAiApi(String baseUrl, String apiKey) {
    //传入四个参数，网址，apikey，还有默认时间，这样就配置好了一个llm大模型。
    return buildOpenAiApi(baseUrl, apiKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
  }

  public static OpenAiApi buildOpenAiApi(
      String baseUrl,
      String apiKey,
      ClientHttpRequestInterceptor requestInterceptor,
      ExchangeFilterFunction exchangeFilter
  ) {
    return buildOpenAiApi(
        baseUrl,
        apiKey,
        DEFAULT_CONNECT_TIMEOUT,
        DEFAULT_READ_TIMEOUT,
        requestInterceptor,
        exchangeFilter
    );
  }

  /**
   * 构建 OpenAiApi 实例，自动根据 baseUrl 调整 API 路径。
   *
   * <p>构建步骤：
   * <ol>
   *   <li>创建 SimpleClientHttpRequestFactory 并设置超时时间</li>
   *   <li>通过 RestClient.Builder 绑定自定义请求工厂</li>
   *   <li>设置 baseUrl 和 apiKey 到 OpenAiApi.Builder</li>
   *   <li><b>关键步骤：</b>检测 baseUrl 是否包含版本号（如 /v1），
   *       如果包含则设置短路径（/chat/completions），
   *       否则使用 Spring AI 默认的长路径（/v1/chat/completions）</li>
   *   <li>调用 build() 创建最终实例</li>
   * </ol>
   *
   * @param baseUrl         API 基础地址，如 https://dashscope.aliyuncs.com/compatible-mode/v1
   * @param apiKey          API 密钥
   * @param connectTimeout  连接超时时间（毫秒）
   * @param readTimeout     读取超时时间（毫秒），LLM 响应可能较长，建议设置较大值
   * @return 配置完成的 OpenAiApi 实例
   */
  public static OpenAiApi buildOpenAiApi(String baseUrl, String apiKey,
      int connectTimeout, int readTimeout) {
    return buildOpenAiApi(baseUrl, apiKey, connectTimeout, readTimeout, null, null);
  }

  public static OpenAiApi buildOpenAiApi(
      String baseUrl,
      String apiKey,
      int connectTimeout,
      int readTimeout,
      ClientHttpRequestInterceptor requestInterceptor,
      ExchangeFilterFunction exchangeFilter
  ) {
    // 创建 HTTP 请求工厂，配置连接和读取超时
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(connectTimeout);  // TCP 连接建立超时
    requestFactory.setReadTimeout(readTimeout);        // 等待响应数据的超时

    // 构建 RestClient.Builder 并绑定自定义请求工厂
    RestClient.Builder restClientBuilder = RestClient.builder()
        .requestFactory(requestFactory);
    WebClient.Builder webClientBuilder = WebClient.builder();

    // AI_PROVIDER_OBSERVABILITY: 普通 call() 走 RestClient，stream() 走 WebClient，两条都需要挂观测。
    if (requestInterceptor != null) {
      restClientBuilder.requestInterceptor(requestInterceptor);
    }
    if (exchangeFilter != null) {
      webClientBuilder.filter(exchangeFilter);
    }

    // 构建 OpenAiApi.Builder，设置基础 URL 和 API Key
    //单个确定的 OpenAI 厂商， baseUrl （比如 https://api.openai.com ）和 apiKey （比如 sk-xxx ）确实是固定的值。
    OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
        .baseUrl(baseUrl)
        .apiKey(apiKey)
        .restClientBuilder(restClientBuilder)
        .webClientBuilder(webClientBuilder);

    // 智能路径适配：根据 baseUrl 是否包含版本号决定 API 路径格式
    // 包含 /v1 → 设置短路径 /chat/completions（避免重复版本号）
    // 不包含   → 使用 Spring AI 默认 /v1/chat/completions
    if (baseUrlContainsVersion(baseUrl)) {
      apiBuilder.completionsPath("/chat/completions").embeddingsPath("/embeddings");
    }
    return apiBuilder.build();
  }

  /**
   * 检查 baseUrl 是否包含版本号（如 /v1）
   * @param baseUrl API 基础 URL
   * @return 是否包含版本号
   */
  public static boolean baseUrlContainsVersion(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    String stripped = stripTrailingSlashes(baseUrl.trim());
    return TRAILING_VERSION.matcher(stripped).find();
  }

  public static String stripTrailingSlashes(String value) {
    if (value == null) {
      return "";
    }
    String result = value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
