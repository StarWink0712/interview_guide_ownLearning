package interview.guide.modules.interview.adaptive;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("自适应面试编排服务测试")
class AdaptiveInterviewServiceTest {

  @Mock
  private TurnEvaluationService turnEvaluationService;

  @Mock
  private AbilityProfileService abilityProfileService;

  @Mock
  private AdaptiveDecisionService adaptiveDecisionService;

  @Mock
  private InterviewPersistenceService persistenceService;

  @Test
  @DisplayName("LLM 即时评估失败时返回空结果且不影响主流程")
  void llmFailureDoesNotBreakAnswerFlow() {
    AdaptiveInterviewService service = new AdaptiveInterviewService(
        turnEvaluationService,
        abilityProfileService,
        adaptiveDecisionService,
        persistenceService,
        new ObjectMapper()
    );
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("session-1");
    session.setLlmProvider("dashscope");
    session.setDifficulty("mid");
    InterviewQuestionDTO question = InterviewQuestionDTO.create(
        0,
        "Redis 缓存一致性怎么处理？",
        "REDIS",
        "Redis"
    );

    when(persistenceService.findBySessionId("session-1")).thenReturn(Optional.of(session));
    when(turnEvaluationService.evaluateTurn(eq("dashscope"), eq(question), anyString()))
        .thenThrow(new IllegalStateException("llm unavailable"));

    assertThat(service.evaluateAndDecideSafely("session-1", question, "先删缓存"))
        .isNull();
    verify(persistenceService, never()).updateAnswerEvaluation(
        anyString(),
        anyInt(),
        anyInt(),
        anyString()
    );
    verify(persistenceService, never()).updateAdaptiveState(
        anyString(),
        anyString(),
        anyString()
    );
  }
}
