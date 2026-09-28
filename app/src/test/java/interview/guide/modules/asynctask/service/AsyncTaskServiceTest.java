package interview.guide.modules.asynctask.service;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.model.AsyncTaskDTO;
import interview.guide.modules.asynctask.model.AsyncTaskEnqueueResult;
import interview.guide.modules.asynctask.model.AsyncTaskEntity;
import interview.guide.modules.asynctask.model.AsyncTaskStatusDTO;
import interview.guide.modules.asynctask.model.AsyncTaskType;
import interview.guide.modules.asynctask.repository.AsyncTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("统一异步任务服务测试")
class AsyncTaskServiceTest {

  @Mock
  private AsyncTaskRepository taskRepository;

  @Mock
  private RedisService redisService;

  @Test
  @DisplayName("正常入队后任务可从 PENDING 流转到 COMPLETED")
  void taskStatusMovesFromPendingToCompleted() {
    AsyncTaskService service = new AsyncTaskService(taskRepository, redisService);
    AsyncTaskEntity created = AsyncTaskEntity.builder()
        .id(1L)
        .taskType(AsyncTaskType.RESUME_ANALYZE)
        .businessId("101")
        .streamKey(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY)
        .status(AsyncTaskStatusDTO.PENDING)
        .retryCount(0)
        .maxRetryCount(3)
        .activeFlag(true)
        .activeKey("RESUME_ANALYZE:101")
        .payloadJson("{}")
        .build();
    when(taskRepository
        .findFirstByTaskTypeAndBusinessIdAndStatusInOrderByCreatedAtDesc(
            eq(AsyncTaskType.RESUME_ANALYZE),
            eq("101"),
            any()
        )).thenReturn(Optional.empty());
    when(taskRepository.save(any(AsyncTaskEntity.class))).thenReturn(created);
    when(taskRepository.findById(1L)).thenReturn(Optional.of(created));

    AsyncTaskEnqueueResult result = service.createOrReusePendingTask(
        AsyncTaskType.RESUME_ANALYZE,
        "101",
        AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
        "{}"
    );
    service.markEnqueued(1L, "1-0");
    service.markProcessing(1L);
    service.markCompleted(1L);

    assertThat(result.created()).isTrue();
    assertThat(created.getStatus()).isEqualTo(AsyncTaskStatusDTO.COMPLETED);
    assertThat(created.getActiveFlag()).isFalse();
    assertThat(created.getActiveKey()).isNull();
  }

  @Test
  @DisplayName("同业务对象存在活跃任务时复用旧任务，不重复创建")
  void activeTaskIsReused() {
    AsyncTaskService service = new AsyncTaskService(taskRepository, redisService);
    AsyncTaskEntity existing = AsyncTaskEntity.builder()
        .id(2L)
        .taskType(AsyncTaskType.KB_VECTORIZE)
        .businessId("88")
        .streamKey(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY)
        .status(AsyncTaskStatusDTO.PROCESSING)
        .retryCount(0)
        .maxRetryCount(3)
        .activeFlag(true)
        .activeKey("KB_VECTORIZE:88")
        .build();
    when(taskRepository
        .findFirstByTaskTypeAndBusinessIdAndStatusInOrderByCreatedAtDesc(
            eq(AsyncTaskType.KB_VECTORIZE),
            eq("88"),
            any()
        )).thenReturn(Optional.of(existing));

    AsyncTaskEnqueueResult result = service.createOrReusePendingTask(
        AsyncTaskType.KB_VECTORIZE,
        "88",
        AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
        "{}"
    );

    assertThat(result.created()).isFalse();
    assertThat(result.task().id()).isEqualTo(2L);
  }

  @Test
  @DisplayName("失败任务手动重试会重置重试次数并发送轻量 Stream 消息")
  @SuppressWarnings("unchecked")
  void retryTaskRequeuesLightweightMessage() {
    AsyncTaskService service = new AsyncTaskService(taskRepository, redisService);
    AsyncTaskEntity failed = AsyncTaskEntity.builder()
        .id(3L)
        .taskType(AsyncTaskType.INTERVIEW_EVALUATE)
        .businessId("session-1")
        .streamKey(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY)
        .status(AsyncTaskStatusDTO.DEAD_LETTER)
        .retryCount(3)
        .maxRetryCount(3)
        .activeFlag(false)
        .lastError("LLM failed")
        .build();
    when(taskRepository.findById(3L)).thenReturn(Optional.of(failed));
    when(taskRepository.save(any(AsyncTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(redisService.streamAdd(
        eq(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY),
        any(Map.class),
        eq(AsyncTaskStreamConstants.STREAM_MAX_LEN)
    )).thenReturn("9-0");

    AsyncTaskDTO dto = service.retryTask(3L);

    assertThat(dto.status()).isEqualTo(AsyncTaskStatusDTO.PENDING);
    assertThat(dto.retryCount()).isZero();
    assertThat(dto.messageId()).isEqualTo("9-0");

    ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
    verify(redisService).streamAdd(
        eq(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY),
        captor.capture(),
        eq(AsyncTaskStreamConstants.STREAM_MAX_LEN)
    );
    assertThat(captor.getValue()).containsEntry(AsyncTaskStreamConstants.FIELD_TASK_ID, "3");
    assertThat(captor.getValue()).containsEntry(AsyncTaskStreamConstants.FIELD_BUSINESS_ID, "session-1");
    assertThat(captor.getValue()).doesNotContainKey(AsyncTaskStreamConstants.FIELD_CONTENT);
  }
}
