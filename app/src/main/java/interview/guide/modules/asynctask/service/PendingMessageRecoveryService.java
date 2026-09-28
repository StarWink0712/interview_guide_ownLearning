/**
 * Redis Stream Pending 消息恢复服务
 *
 * 负责在应用启动时和运行期间定期扫描所有 AbstractStreamConsumer 对应的
 * Redis Stream 消费者组的 PEL（Pending Entries List），通过 XAUTOCLAIM 命令
 * 认领空闲超时的 pending 消息并重新提交处理，
 * 确保因消费者异常崩溃或网络故障
 * 而导致未 ACK 的消息不会永久丢失。
 *
 * Pending 消息产生的场景：
 * - 消费者在处理消息中途 JVM 崩溃（OOMKilled / kill -9）
 * - 消息处理耗时过长（超过 60 秒），消息被标记为空闲
 * - Redis Stream 消费者组中某消费者实例宕机，其未 ACK 的消息挂起
 * - 应用重启后 PEL 中残留的上次运行未完成的消息
 *
 * 恢复流程：
 * 1. 遍历所有注入的 AbstractStreamConsumer Bean（Spring 自动收集泛型 List）
 * 2. 调用每个消费者的 recoverPendingMessages() 方法
 * 3. 消费者内部通过 RedisService.streamAutoClaim() 执行 XAUTOCLAIM 命令
 * 4. 认领的消息逐条调用 processMessage() 重新处理
 * 5. 汇总恢复数量并记录日志
 *
 * 触发时机（双重保障）：
 * - @EventListener(ApplicationReadyEvent)：应用启动后立即恢复一次
 * - @Scheduled(fixedDelay=60_000)：每 60 秒恢复新的空闲 pending 消息
 *
 * 联动关系：
 * - 依赖 Spring 自动注入 List<AbstractStreamConsumer<?>> 获取所有消费者 Bean
 * - 调用 AbstractStreamConsumer.recoverPendingMessages() 执行实际恢复逻辑
 * - 使用 AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS（60000ms）作为空闲超时阈值
 * - 使用 AsyncTaskStreamConstants.PENDING_RECOVERY_BATCH_SIZE（10）限制单次恢复数量
 * - 服务于 VectorizeStreamConsumer、AnalyzeStreamConsumer、EvaluateStreamConsumer、
 *   VoiceEvaluateStreamConsumer 四个消费者管道
 */
package interview.guide.modules.asynctask.service;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis Stream Pending 消息恢复服务
 * 定时扫描消费者组的 PEL 列表，将空闲超时的挂起消息重新投入处理队列
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingMessageRecoveryService {

  /**
   * 所有 AbstractStreamConsumer 子类 Bean 的集合
   * Spring 自动发现并注入所有匹配类型的 Bean：VectorizeStreamConsumer、
   * AnalyzeStreamConsumer、EvaluateStreamConsumer、VoiceEvaluateStreamConsumer
   */
  private final List<AbstractStreamConsumer<?>> consumers;

  /**
   * 应用启动完成后立即执行一次 pending 消息恢复
   *
   * <p>调用入口：Spring 在发布 {@link ApplicationReadyEvent} 后自动调用，
   * 用于恢复上次运行因异常退出而残留在 PEL 中的未 ACK 消息。
   *
   * <p>主要调用：直接委托 {@link #recoverPendingMessages()} 扫描全部消费者，
   * 不单独实现认领逻辑。
   */
  @EventListener(ApplicationReadyEvent.class)
  public void recoverOnStartup() {
    log.info("应用启动完成，开始恢复 Redis Stream pending 消息");
    recoverPendingMessages();
  }

  /**
   * 定时恢复 Redis Stream 的 pending 消息
   *
   * <p>调用入口：由 Spring Scheduler 每次延迟 60 秒触发，
   * 同时也由 {@link #recoverOnStartup()} 在应用就绪时主动调用一次。
   *
   * 每 60 秒执行一次，fixedDelay 从上一次执行完成后开始计算，
   * 首次延迟 60 秒（initialDelay 避免与启动时的 recoverOnStartup 并发执行）。
   * 遍历所有消费者，调用各自的 recoverPendingMessages() 方法，
   * 通过 XAUTOCLAIM 认领空闲超过 60 秒的 pending 消息并重新处理。
   *
   * 单个消费者恢复失败不会影响其他消费者的恢复流程（独立 try-catch）。
   * 有恢复行为时记录汇总日志，无恢复行为时静默跳过。
   *
   * <p>主要调用：遍历 Spring 注入的所有 {@link AbstractStreamConsumer}，调用其
   * {@code recoverPendingMessages(idleTimeoutMs, count)}；消费者内部负责
   * XAUTOCLAIM、重新处理以及 ACK/重试，本服务只负责调度和汇总。
   */
  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void recoverPendingMessages() {
    int recovered = 0;
    for (AbstractStreamConsumer<?> consumer : consumers) {
      try {
        recovered += consumer.recoverPendingMessages(
            AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS,
            AsyncTaskStreamConstants.PENDING_RECOVERY_BATCH_SIZE
        );
      } catch (Exception e) {
        log.warn("恢复 pending 消息失败: consumer={}",
            consumer.getClass().getSimpleName(), e);
      }
    }
    if (recovered > 0) {
      log.info("Redis Stream pending 消息恢复完成: recovered={}", recovered);
    }
  }
}
