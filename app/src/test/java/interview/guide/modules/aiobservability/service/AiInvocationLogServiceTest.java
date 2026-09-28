package interview.guide.modules.aiobservability.service;

import interview.guide.modules.aiobservability.model.AiInvocationLogCreateRequest;
import interview.guide.modules.aiobservability.model.AiInvocationLogDTO;
import interview.guide.modules.aiobservability.model.AiInvocationLogEntity;
import interview.guide.modules.aiobservability.repository.AiInvocationLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 调用日志服务测试")
class AiInvocationLogServiceTest {

  @Mock
  private AiInvocationLogRepository logRepository;

  @Test
  @DisplayName("recordInvocation 保存摘要日志并生成 prompt hash")
  void recordInvocationSavesSummaryLog() {
    AiInvocationLogService service = new AiInvocationLogService(logRepository);
    when(logRepository.save(any(AiInvocationLogEntity.class))).thenAnswer(invocation -> {
      AiInvocationLogEntity entity = invocation.getArgument(0);
      entity.setId(1L);
      entity.setCreatedAt(LocalDateTime.now());
      return entity;
    });

    Optional<AiInvocationLogDTO> result = service.recordInvocation(new AiInvocationLogCreateRequest(
        "trace-1",
        "简历评分",
        "dashscope",
        "qwen-plus",
        "structured-output-v1",
        false,
        true,
        2,
        true,
        123L,
        null,
        null,
        "system prompt",
        "user prompt"
    ));

    assertThat(result).isPresent();
    assertThat(result.get().promptHash()).hasSize(64);
    assertThat(result.get().promptLength()).isGreaterThan(0);
    assertThat(result.get().attemptCount()).isEqualTo(2);
    assertThat(result.get().success()).isTrue();
  }

  @Test
  @DisplayName("recordInvocation 写入失败时不向主流程抛异常")
  void recordInvocationDoesNotThrowWhenRepositoryFails() {
    AiInvocationLogService service = new AiInvocationLogService(logRepository);
    when(logRepository.save(any(AiInvocationLogEntity.class)))
        .thenThrow(new IllegalStateException("database unavailable"));

    AiInvocationLogCreateRequest request = new AiInvocationLogCreateRequest(
        "trace-2",
        "面试出题",
        "dashscope",
        "qwen-plus",
        "structured-output-v1",
        false,
        true,
        1,
        false,
        50L,
        "AI_SERVICE_ERROR",
        "模型返回非法 JSON",
        "system prompt",
        "user prompt"
    );

    assertThatCode(() -> service.recordInvocation(request)).doesNotThrowAnyException();
    assertThat(service.recordInvocation(request)).isEmpty();
  }

  @Test
  @DisplayName("recordInvocation 只保存 prompt hash 和长度，不保存完整 prompt")
  void recordInvocationDoesNotPersistPromptText() {
    AiInvocationLogService service = new AiInvocationLogService(logRepository);
    when(logRepository.save(any(AiInvocationLogEntity.class))).thenAnswer(invocation -> {
      AiInvocationLogEntity entity = invocation.getArgument(0);
      entity.setId(3L);
      return entity;
    });

    service.recordInvocation(new AiInvocationLogCreateRequest(
        "trace-3",
        "结构化输出",
        "dashscope",
        "qwen-plus",
        "structured-output-v1",
        false,
        true,
        1,
        true,
        10L,
        null,
        null,
        "不要把我完整写入数据库",
        "用户输入"
    ));

    ArgumentCaptor<AiInvocationLogEntity> captor =
        ArgumentCaptor.forClass(AiInvocationLogEntity.class);
    verify(logRepository).save(captor.capture());
    AiInvocationLogEntity saved = captor.getValue();

    assertThat(saved.getPromptHash()).hasSize(64);
    assertThat(saved.getPromptLength()).isGreaterThan(0);
  }
}
