package interview.guide.common.transaction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("事务提交后执行器测试")
class TransactionAfterCommitExecutorTest {

  private final TransactionAfterCommitExecutor executor = new TransactionAfterCommitExecutor();

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("无事务时立即执行任务")
  void executesImmediatelyWithoutTransaction() {
    AtomicInteger counter = new AtomicInteger();

    executor.execute(counter::incrementAndGet);

    assertThat(counter).hasValue(1);
  }

  @Test
  @DisplayName("事务存在时提交后才执行任务")
  void executesAfterCommitWhenTransactionIsActive() {
    AtomicInteger counter = new AtomicInteger();
    TransactionSynchronizationManager.initSynchronization();

    executor.execute(counter::incrementAndGet);

    assertThat(counter).hasValue(0);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    assertThat(counter).hasValue(1);
  }

  @Test
  @DisplayName("事务回滚时不执行任务")
  void doesNotExecuteOnRollback() {
    AtomicInteger counter = new AtomicInteger();
    TransactionSynchronizationManager.initSynchronization();

    executor.execute(counter::incrementAndGet);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(synchronization -> synchronization.afterCompletion(
            TransactionSynchronization.STATUS_ROLLED_BACK
        ));

    assertThat(counter).hasValue(0);
  }
}
