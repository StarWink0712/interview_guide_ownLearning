package interview.guide.modules.interview.adaptive;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.adaptive.model.TurnEvaluationDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试单轮即时评估服务测试")
class TurnEvaluationServiceTest {

  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;

  @Mock
  private LlmProviderRegistry llmProviderRegistry;

  @Mock
  private ChatClient chatClient;

  @Test
  @DisplayName("调用结构化输出并规范化评分、深度和缺失关键点")
  void evaluateTurnInvokesStructuredOutputAndNormalizesResult() throws Exception {
    TurnEvaluationService service = new TurnEvaluationService(
        structuredOutputInvoker,
        llmProviderRegistry,
        new DefaultResourceLoader()
    );
    InterviewQuestionDTO question = InterviewQuestionDTO.create(
        2,
        "Redis 缓存一致性怎么处理？",
        "REDIS",
        "Redis"
    );
    TurnEvaluationDTO rawEvaluation = new TurnEvaluationDTO(
        99,
        108,
        9,
        List.of("延迟双删", " "),
        "继续追问并发写场景",
        ""
    );

    when(llmProviderRegistry.getPlainChatClient("dashscope")).thenReturn(chatClient);
    when(structuredOutputInvoker.<TurnEvaluationDTO>invoke(
        same(chatClient),
        contains("depthLevel 范围为 1-5"),
        contains("Redis 缓存一致性"),
        any(BeanOutputConverter.class),
        eq(ErrorCode.INTERVIEW_EVALUATION_FAILED),
        eq("即时评估失败："),
        eq("面试即时评估"),
        any(Logger.class)
    )).thenReturn(rawEvaluation);

    TurnEvaluationDTO result = service.evaluateTurn("dashscope", question, "先删缓存再更新数据库");

    assertThat(result.questionIndex()).isEqualTo(2);
    assertThat(result.score()).isEqualTo(100);
    assertThat(result.depthLevel()).isEqualTo(5);
    assertThat(result.missingKeyPoints()).containsExactly("延迟双删");
    assertThat(result.category()).isEqualTo("Redis");
  }
}
