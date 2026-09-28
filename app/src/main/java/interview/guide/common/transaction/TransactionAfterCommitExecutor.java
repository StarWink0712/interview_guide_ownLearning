/**
 * 事务提交后执行器
 *
 * 将指定任务注册为 Spring 事务同步回调，确保任务在数据库事务成功提交后才被执行。
 * 用于解决"事务内写入数据 → 事务外通知其他系统"的时序一致性问题。
 * 先判断一下当前线程是否处于活动事务中，如果没有事务的话就直接执行线程下一步，如果有的话，
 * 就注册一个事务同步回调，事务完成之后，再执行下一步，比如发送消息到redis stream
 * 核心价值：
 * <ul>
 *   <li>避免"事务还未提交，外部系统就来查数据"导致的数据不一致</li>
 *   <li>避免在事务方法内直接调用外部 API（LLM、S3、Redis Stream 等），
 *       因为这可能长时间占用数据库连接（违反项目规则）</li>
 * </ul>
 *
 * 执行策略：
 * <ol>
 *   <li>如果当前没有活动事务（如在 Controller 或普通方法中调用），
 *       则直接同步执行任务，不做任何延迟</li>
 *   <li>如果当前存在活动事务（如在 @Transactional 方法中调用），
 *       则将任务注册到 TransactionSynchronizationManager 的同步回调列表中，
 *       等待事务提交成功后由 Spring 自动触发 afterCommit() 回调执行</li>
 *   <li>如果事务回滚，afterCommit() 不会触发，任务也不会执行，
 *       确保任务只在数据确实落库后才运行</li>
 * </ol>
 *
 * 异常处理：
 * <ul>
 *   <li>afterCommit 回调中的异常被 catch 并记录错误日志，不会向外抛出</li>
 *   <li>原因是事务已经提交成功，再抛异常也无法回滚，反而会污染上层调用栈</li>
 * </ul>
 *
 * 联动关系：
 * <ul>
 *   <li>被各类 Service 调用：在 @Transactional 方法末尾，将事后操作注册为回调</li>
 *   <li>常用于：事务成功后发送 Redis Stream 消息、发送通知等场景</li>
 *   <li>依赖 TransactionSynchronizationManager：Spring 事务基础设施，管理事务同步回调</li>
 * </ul>
 *
 * 使用示例：
 * <pre>
 *   {@literal @}Transactional
 *   public void saveAndNotify() {
 *       repository.save(entity);
 *       // 事务提交成功后才发消息，保证消费者能查到刚写入的数据
 *       afterCommitExecutor.execute(() ->
 *           streamProducer.sendTask(new Task(entity.getId()))
 *       );
 *   }
 * </pre>
 */
package interview.guide.common.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
public class TransactionAfterCommitExecutor {

  /**
   * 执行指定任务，确保在事务提交成功后才运行
   * ？什么是事务同步回调
   * 根据当前是否处于活动事务中执行不同策略：
   * <ul>
   *   <li><b>无事务</b>：直接同步执行 task.run()，不做等待</li>
   *   <li><b>有事务</b>：注册到事务同步管理器，等事务提交成功后由
   *       afterCommit 回调触发执行</li>
   * </ul>
   *
   * @param task 需要在事务提交后执行的任务，通过 Runnable 表示
   */
  public void execute(Runnable task) {
    // 判断当前线程是否处于活动事务中
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      // 没有事务：直接执行，不延迟
      task.run();
      return;
    }

    // 有事务：注册事务同步回调
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      /**
       * 事务提交成功后的回调
       *
       * Spring 在事务提交成功后自动调用此方法。
       * 注意：
       * <ul>
       *   <li>如果事务回滚，此方法不会被调用，任务不会执行</li>
       *   <li>回调中的异常被 catch 住并记录日志，不会向上抛出——
       *       因为事务已经提交，抛异常也无法回滚</li>
       * </ul>
       */
      @Override
      public void afterCommit() {
        try {
          task.run();
        } catch (Exception e) {
          // 事务已提交，抛异常也无法回滚，因此只记录日志
          log.error("afterCommit 回调执行失败", e);
        }
      }
    });
  }
}
