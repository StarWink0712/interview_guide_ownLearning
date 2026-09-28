package interview.guide.modules.resume.listener;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.observability.AsyncTaskMetricsRecorder;
import interview.guide.common.transaction.TransactionAfterCommitExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.model.AsyncTaskDTO;
import interview.guide.modules.asynctask.model.AsyncTaskEnqueueResult;
import interview.guide.modules.asynctask.model.AsyncTaskStatusDTO;
import interview.guide.modules.asynctask.model.AsyncTaskType;
import interview.guide.modules.asynctask.service.AsyncTaskService;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历分析任务 afterCommit 入队测试")
class AnalyzeStreamProducerAfterCommitTest {

  @Mock
  private RedisService redisService;

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private AsyncTaskService asyncTaskService;

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("事务提交后才写入 Redis Stream")
  @SuppressWarnings("unchecked")
  void sendsStreamAfterCommit() {
    AnalyzeStreamProducer producer = new AnalyzeStreamProducer(
        redisService,
        resumeRepository,
        asyncTaskService,
        new TransactionAfterCommitExecutor(),
        new AsyncTaskMetricsRecorder(null)
    );
    AsyncTaskDTO task = taskDTO();
    when(asyncTaskService.createOrReusePendingTask(
        eq(AsyncTaskType.RESUME_ANALYZE),
        eq("101"),
        eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY),
        eq("{\"resumeId\":101,\"contentLength\":4}")
    )).thenReturn(new AsyncTaskEnqueueResult(task, true));
    when(redisService.streamAdd(
        eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY),
        any(Map.class),
        eq(AsyncTaskStreamConstants.STREAM_MAX_LEN)
    )).thenReturn("1-0");
    TransactionSynchronizationManager.initSynchronization();

    producer.sendAnalyzeTask(101L, "text");

    verify(redisService, never()).streamAdd(any(), any(), eq(AsyncTaskStreamConstants.STREAM_MAX_LEN));
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verify(redisService).streamAdd(
        eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY),
        any(Map.class),
        eq(AsyncTaskStreamConstants.STREAM_MAX_LEN)
    );
    verify(asyncTaskService).markEnqueued(7L, "1-0");
  }

  private AsyncTaskDTO taskDTO() {
    return new AsyncTaskDTO(
        7L,
        AsyncTaskType.RESUME_ANALYZE,
        "101",
        AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
        null,
        AsyncTaskStatusDTO.PENDING,
        0,
        3,
        null,
        null,
        "{}",
        null,
        true,
        LocalDateTime.now(),
        LocalDateTime.now()
    );
  }
}
