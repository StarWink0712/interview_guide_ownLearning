package interview.guide.modules.resume.listener;

import interview.guide.common.async.AsyncTaskCancelledException;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.service.AsyncTaskService;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumeParseService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历分析 Stream 消费者测试")
class AnalyzeStreamConsumerTest {

  @Mock
  private RedisService redisService;

  @Mock
  private ResumeGradingService gradingService;

  @Mock
  private ResumePersistenceService persistenceService;

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private ResumeParseService parseService;

  @Mock
  private AsyncTaskService asyncTaskService;

  @Test
  @DisplayName("parsePayload 只解析 taskId 和 resumeId")
  void parsePayloadReadsTaskIdAndResumeIdOnly() {
    AnalyzeStreamConsumer consumer = createConsumer();

    AnalyzeStreamConsumer.AnalyzePayload payload = consumer.parsePayload(
        new StreamMessageId(1, 0),
        Map.of(
            AsyncTaskStreamConstants.FIELD_TASK_ID, "10",
            AsyncTaskStreamConstants.FIELD_RESUME_ID, "20",
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        )
    );

    assertThat(payload.taskId()).isEqualTo(10L);
    assertThat(payload.resumeId()).isEqualTo(20L);
  }

  @Test
  @DisplayName("简历已删除时抛出取消异常，避免无限重试")
  void deletedResumeCancelsTask() {
    AnalyzeStreamConsumer consumer = createConsumer();
    when(resumeRepository.findById(20L)).thenReturn(Optional.empty());

    AnalyzeStreamConsumer.AnalyzePayload payload =
        new AnalyzeStreamConsumer.AnalyzePayload(10L, 20L);

    assertThatThrownBy(() -> consumer.processBusiness(payload))
        .isInstanceOf(AsyncTaskCancelledException.class);
  }

  @Test
  @DisplayName("重试入队消息不包含 content 大字段")
  @SuppressWarnings("unchecked")
  void retryMessageDoesNotIncludeContent() {
    AnalyzeStreamConsumer consumer = createConsumer();
    when(redisService.streamAdd(
        eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY),
        any(Map.class),
        eq(AsyncTaskStreamConstants.STREAM_MAX_LEN)
    )).thenReturn("2-0");

    consumer.retryMessage(new AnalyzeStreamConsumer.AnalyzePayload(10L, 20L), 1);

    ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
    verify(redisService).streamAdd(
        eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY),
        captor.capture(),
        eq(AsyncTaskStreamConstants.STREAM_MAX_LEN)
    );
    assertThat(captor.getValue()).containsEntry(AsyncTaskStreamConstants.FIELD_TASK_ID, "10");
    assertThat(captor.getValue()).containsEntry(AsyncTaskStreamConstants.FIELD_RESUME_ID, "20");
    assertThat(captor.getValue()).doesNotContainKey(AsyncTaskStreamConstants.FIELD_CONTENT);
    verify(asyncTaskService).markRetrying(10L, 1, "2-0");
  }

  private AnalyzeStreamConsumer createConsumer() {
    return new AnalyzeStreamConsumer(
        redisService,
        gradingService,
        persistenceService,
        resumeRepository,
        parseService,
        asyncTaskService,
        null
    );
  }
}
