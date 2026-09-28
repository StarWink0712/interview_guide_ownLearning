package interview.guide.modules.monitoring.service;

import interview.guide.modules.aiobservability.model.AiProviderInvocationLogEntity;
import interview.guide.modules.aiobservability.model.RagRetrievalLogEntity;
import interview.guide.modules.aiobservability.repository.AiProviderInvocationLogRepository;
import interview.guide.modules.aiobservability.repository.RagRetrievalLogRepository;
import interview.guide.modules.asynctask.model.AsyncTaskStatusDTO;
import interview.guide.modules.asynctask.repository.AsyncTaskRepository;
import interview.guide.modules.monitoring.model.BusinessMetricsSummaryDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("业务指标摘要查询服务测试")
class BusinessMetricsQueryServiceTest {

  @Mock
  private AsyncTaskRepository asyncTaskRepository;

  @Mock
  private AiProviderInvocationLogRepository aiProviderInvocationLogRepository;

  @Mock
  private RagRetrievalLogRepository ragRetrievalLogRepository;

  @Test
  @DisplayName("聚合最近任务、AI 成功率、RAG 命中和语音 turn 延迟")
  void aggregatesBusinessSummary() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    meterRegistry.timer("app.voice.interview.turn.duration")
        .record(120, TimeUnit.MILLISECONDS);
    when(asyncTaskRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(10L);
    when(asyncTaskRepository.countByStatusInAndCreatedAtAfter(
        anyCollection(),
        any(LocalDateTime.class)
    )).thenReturn(2L);
    when(aiProviderInvocationLogRepository.findByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(List.of(
        AiProviderInvocationLogEntity.builder().success(true).build(),
        AiProviderInvocationLogEntity.builder().success(false).build()
    ));
    when(ragRetrievalLogRepository.findByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(List.of(
        RagRetrievalLogEntity.builder().hitCount(2).build(),
        RagRetrievalLogEntity.builder().hitCount(4).build()
    ));
    BusinessMetricsQueryService service = new BusinessMetricsQueryService(
        asyncTaskRepository,
        aiProviderInvocationLogRepository,
        ragRetrievalLogRepository,
        meterRegistry
    );

    BusinessMetricsSummaryDTO summary = service.getBusinessSummary();

    assertThat(summary.recentTaskCount()).isEqualTo(10);
    assertThat(summary.failedTaskCount()).isEqualTo(2);
    assertThat(summary.aiInvocationCount()).isEqualTo(2);
    assertThat(summary.aiSuccessRate()).isEqualTo(0.5);
    assertThat(summary.ragQueryCount()).isEqualTo(2);
    assertThat(summary.ragAverageHitCount()).isEqualTo(3);
    assertThat(summary.voiceAverageTurnLatencyMs()).isEqualTo(120);
  }
}
