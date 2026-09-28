package interview.guide.modules.interview.adaptive;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.interview.adaptive.model.AdaptiveDecisionDTO;
import interview.guide.modules.interview.adaptive.model.TurnEvaluationDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试自适应决策服务测试")
class AdaptiveDecisionServiceTest {

  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;

  @Mock
  private LlmProviderRegistry llmProviderRegistry;

  @Test
  @DisplayName("回答高分时兜底策略生成升难度决策")
  void highScoreFallbackRaisesDifficulty() throws Exception {
    AdaptiveDecisionService service = createService();
    InterviewQuestionDTO question = InterviewQuestionDTO.create(
        0,
        "如何设计订单系统？",
        "SYSTEM_DESIGN",
        "系统设计"
    );

    AdaptiveDecisionDTO decision = service.fallbackDecision(
        question,
        new TurnEvaluationDTO(0, 92, 5, List.of(), "", "系统设计"),
        "mid"
    );

    assertThat(decision.action()).isEqualTo("RAISE_DIFFICULTY");
    assertThat(decision.targetCategory()).isEqualTo("系统设计");
    assertThat(decision.nextDifficulty()).isEqualTo("senior");
    assertThat(decision.nextQuestion()).contains("高并发");
  }

  @Test
  @DisplayName("回答缺少关键点时兜底策略生成追问决策")
  void missingKeyPointsFallbackCreatesFollowUp() throws Exception {
    AdaptiveDecisionService service = createService();
    InterviewQuestionDTO question = InterviewQuestionDTO.create(
        1,
        "Redis 缓存穿透怎么处理？",
        "REDIS",
        "Redis"
    );

    AdaptiveDecisionDTO decision = service.fallbackDecision(
        question,
        new TurnEvaluationDTO(
            1,
            68,
            2,
            List.of("布隆过滤器", "空值缓存"),
            "请补充布隆过滤器和空值缓存的取舍",
            "Redis"
        ),
        "mid"
    );

    assertThat(decision.action()).isEqualTo("FOLLOW_UP");
    assertThat(decision.targetCategory()).isEqualTo("Redis");
    assertThat(decision.nextDifficulty()).isEqualTo("mid");
    assertThat(decision.nextQuestion()).contains("布隆过滤器");
  }

  private AdaptiveDecisionService createService() throws Exception {
    return new AdaptiveDecisionService(
        structuredOutputInvoker,
        llmProviderRegistry,
        new ObjectMapper(),
        new DefaultResourceLoader()
    );
  }
}
