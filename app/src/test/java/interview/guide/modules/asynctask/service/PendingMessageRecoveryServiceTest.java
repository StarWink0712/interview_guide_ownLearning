package interview.guide.modules.asynctask.service;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Pending 消息恢复服务测试")
class PendingMessageRecoveryServiceTest {

  @Test
  @DisplayName("定时恢复会委托所有消费者 claim pending 消息")
  void recoveryDelegatesToAllConsumers() {
    AbstractStreamConsumer<?> consumer = mock(AbstractStreamConsumer.class);
    when(consumer.recoverPendingMessages(
        AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS,
        AsyncTaskStreamConstants.PENDING_RECOVERY_BATCH_SIZE
    )).thenReturn(1);
    PendingMessageRecoveryService service = new PendingMessageRecoveryService(List.of(consumer));

    service.recoverPendingMessages();

    verify(consumer).recoverPendingMessages(
        AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS,
        AsyncTaskStreamConstants.PENDING_RECOVERY_BATCH_SIZE
    );
  }
}
