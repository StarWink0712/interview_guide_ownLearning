package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;

@DisplayName("结构化输出调用器测试")
class StructuredOutputInvokerTest {

  private static final Logger LOG = LoggerFactory.getLogger(StructuredOutputInvokerTest.class);

  private ChatClient chatClient;
  private StructuredOutputInvoker invoker;
  private BeanOutputConverter<StructuredAnswer> outputConverter;

  @BeforeEach
  void setUp() {
    chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    outputConverter = new BeanOutputConverter<>(StructuredAnswer.class) {};
    invoker = new StructuredOutputInvoker(structuredOutputProperties(2));
  }

  @Test
  @DisplayName("合法 JSON 可直接解析成功")
  void legalJsonParsesSuccessfully() {
    stubResponses("""
        {"name":"Alice","comment":"ready","score":91}
        """);

    StructuredAnswer result = invoke();

    assertThat(result.name()).isEqualTo("Alice");
    assertThat(result.comment()).isEqualTo("ready");
    assertThat(result.score()).isEqualTo(91);
    verify(chatClient, times(1)).prompt();
  }

  @Test
  @DisplayName("Markdown 包裹的非法 JSON 失败后会重试")
  void markdownWrappedInvalidJsonRetriesAndThenSucceeds() {
    stubResponses(
        """
            ```json
            {"name":"Alice","comment":broken,"score":91}
            ```
            """,
        """
            {"name":"Alice","comment":"fixed","score":92}
            """
    );

    StructuredAnswer result = invoke();

    assertThat(result.comment()).isEqualTo("fixed");
    assertThat(result.score()).isEqualTo(92);
    verify(chatClient, times(2)).prompt();
  }

  @Test
  @DisplayName("字符串未转义引号可本地修复后解析")
  void unescapedQuotesAreRepairedLocally() {
    stubResponses("""
        {"name":"Alice","comment":"He said "hello" today","score":88}
        """);

    StructuredAnswer result = invoke();

    assertThat(result.comment()).isEqualTo("He said \"hello\" today");
    assertThat(result.score()).isEqualTo(88);
    verify(chatClient, times(1)).prompt();
  }

  @Test
  @DisplayName("超过最大重试次数后抛出 BusinessException")
  void exceedingMaxAttemptsThrowsBusinessException() {
    stubResponses("not json", "still not json");

    assertThatThrownBy(this::invoke)
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("测试结构化输出失败");
    verify(chatClient, times(2)).prompt();
  }

  private StructuredAnswer invoke() {
    return invoker.invoke(
        chatClient,
        outputConverter.getFormat(),
        "请生成候选人结构化评分",
        outputConverter,
        ErrorCode.AI_SERVICE_ERROR,
        "测试结构化输出失败：",
        "结构化输出测试",
        LOG
    );
  }

  private void stubResponses(String... responses) {
    String[] remainingResponses = Arrays.copyOfRange(responses, 1, responses.length);
    when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
        .thenReturn(responses[0], remainingResponses);
    clearInvocations(chatClient);
  }

  private StructuredOutputProperties structuredOutputProperties(int maxAttempts) {
    StructuredOutputProperties properties = new StructuredOutputProperties();
    properties.setStructuredMaxAttempts(maxAttempts);
    properties.setStructuredIncludeLastError(true);
    properties.setStructuredRetryUseRepairPrompt(true);
    properties.setStructuredRetryAppendStrictJsonInstruction(true);
    properties.setStructuredMetricsEnabled(false);
    return properties;
  }
  private record StructuredAnswer(String name, String comment, int score) {
  }
}
