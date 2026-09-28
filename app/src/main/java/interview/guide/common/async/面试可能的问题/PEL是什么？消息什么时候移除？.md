# PEL 详解 — 完整面试回答（带代码）

***

## 一、PEL 是什么

> 「PEL 全称 Pending Entries List，是 Redis Stream 消费者组里的一个核心数据结构。它不是消息副本，而是一个**已投递但未确认的消息索引列表**。」

用一个比喻来理解：

```
公司邮件系统（Redis Stream）
    │
    ├── 收件箱：所有邮件（消息本体）
    │
    └── 「已分配待处理」标签：
          ├── 邮件 #001 → 分配给张三 → 还没回复"已处理"
          ├── 邮件 #002 → 分配给李四 → 还没回复"已处理"
          └── 邮件 #003 → 分配给张三 → 还没回复"已处理"
                                    ↑
                              这就是 PEL
```

**PEL 只记录"谁拿了哪条消息但还没确认"，消息内容本身还在 Stream 主体里。**

***

## 二、PEL 的数据结构

用 Redis 命令查看：

```bash
> XPENDING resume:analyze:stream analyze-group

1) (integer) 3                    # PEL 中有 3 条待确认消息
2) "1747300000000-0"              # 最早的消息 ID
3) "1747300003000-0"              # 最新的消息 ID
4) 1) 1) "analyze-consumer-a3f2b01c"   # 消费者 1
      2) "2"                            # 有 2 条待确认
   2) 1) "analyze-consumer-b4c3d02d"   # 消费者 2  
      2) "1"                            # 有 1 条待确认
```

查看详细：

```bash
> XPENDING resume:analyze:stream analyze-group - + 10

1) 1) "1747300000000-0"           # 消息 ID
   2) "analyze-consumer-a3f2b01c" # 被分配给的消费者
   3) (integer) 55000             # 空闲时间（毫秒），55 秒没处理完
   4) (integer) 1                 # 投递次数，这是第一次投递

2) 1) "1747300001000-0"
   2) "analyze-consumer-a3f2b01c"
   3) (integer) 25000             # 空闲 25 秒
   4) (integer) 1
```

每一条 PEL 记录包含四个字段：

| 字段    | 含义            | 示例                          |
| ----- | ------------- | --------------------------- |
| 消息 ID | Stream 中的唯一标识 | `1747300000000-0`           |
| 消费者名  | 这条消息分配给谁了     | `analyze-consumer-a3f2b01c` |
| 空闲时间  | 从投递到现在过了多少毫秒  | `55000`（55 秒）               |
| 投递次数  | 这条消息被投递了几次    | `1`（首次）                     |

***

## 三、消息什么时候进入 PEL？

[RedisService.java#L250-L285](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/redis/RedisService.java#L250-L285)：

```java
public boolean streamConsumeMessages(...) {
    RStream<String, String> stream = redissonClient.getStream(streamKey, ...);
    
    // XREADGROUP GROUP analyze-group analyze-consumer-a3f2b01c
    //             BLOCK 1000 COUNT 10 STREAMS resume:analyze:stream >
    Map<StreamMessageId, Map<String, String>> messages = stream.readGroup(
        groupName,
        consumerName,
        StreamReadGroupArgs.neverDelivered()
            .count(count)
            .timeout(Duration.ofMillis(blockTimeoutMs))
    );
    // ↑ 消息被拉取的那一刻，自动进入 PEL
    // ↑ 不需要任何额外操作，Redis 自动维护
```

**结论**：`XREADGROUP` 拉取消息 → 自动进入 PEL，这是 Redis 服务端行为。

***

## 四、消息什么时候从 PEL 移除？

只有一种方式：**显式 XACK**。

[AbstractStreamConsumer.java#L247-L253](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java#L247-L253)：

```java
private void ackMessage(StreamMessageId messageId) {
    try {
        redisService.streamAck(streamKey(), groupName(), messageId);
        // → XACK resume:analyze:stream analyze-group 1747300000000-0
        // → Redis 从 PEL 中删除这条记录
    } catch (Exception e) {
        log.error("Failed to ack stream message: messageId={}", messageId, e);
    }
}
```

**项目中哪些情况会执行 XACK？** 四条路径都 ACK：

```java
// processMessage() 中
try {
    processBusiness(payload);        // 业务成功
    ackMessage(messageId);           // → 从 PEL 移除 ✅
} catch (AsyncTaskCancelledException e) {
    ackMessage(messageId);           // → 取消路径，从 PEL 移除 ✅
} catch (Exception e) {
    if (retryCount < 3) {
        retryMessage(...);           // → 重试，新消息已入 Stream
    } else {
        markFailed(...);             // → 最终失败
    }
    ackMessage(messageId);           // → 原消息从 PEL 移除 ✅
}
```

***

## 五、消息一直不 ACK 会怎样？

消息会永远留在 PEL 中，变成"僵尸消息"。这就是为什么项目用 `PendingMessageRecoveryService` 定期清理。

[PendingMessageRecoveryService.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/asynctask/service/PendingMessageRecoveryService.java)：

```java
@Scheduled(fixedDelay = 60_000)
public void recoverPendingMessages() {
    for (AbstractStreamConsumer<?> consumer : consumers) {
        consumer.recoverPendingMessages(
            PENDING_IDLE_TIMEOUT_MS,  // 60000ms → 只认领空闲超过 60 秒的消息
            PENDING_RECOVERY_BATCH_SIZE  // 10
        );
    }
}
```

[AbstractStreamConsumer.java#L262-L277](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java#L262-L277)：

```java
public int recoverPendingMessages(long idleTimeoutMs, int count) {
    // XAUTOCLAIM resume:analyze:stream analyze-group 
    //     analyze-consumer-a3f2b01c 60000 COUNT 10
    Map<StreamMessageId, Map<String, String>> messages = 
        redisService.streamAutoClaim(streamKey(), groupName(), consumerName, idleTimeoutMs, count);

    messages.forEach(this::processMessage);  // 重新走完整处理流程
    return messages.size();
}
```

**为什么是 60000 毫秒？** 因为 AI 分析可能耗时 10-30 秒，加上重试延迟。设置 60 秒既能覆盖正常处理时长，又能及时回收真的僵尸消息。

***

## 六、XAUTOCLAIM vs XCLAIM 的区别

| 命令         | 行为                | 使用场景             |
| ---------- | ----------------- | ---------------- |
| XAUTOCLAIM | 自动查找空闲超时的消息，变更所有权 | **定时任务**，被动恢复    |
| XCLAIM     | 手动指定消息 ID，变更所有权   | **精确控制**，已知消息 ID |

项目用的 XAUTOCLAIM 是更自动化的版本：「把 analyze-group 组里空闲超过 60 秒的消息，所有权转给我，我重新处理。」

***

## 七、PEL 完整生命周期图解

```
消息 m1 进入 Stream（Producer XADD）
    │
    ▼
Consumer XREADGROUP 拉取 m1
    │
    ▼
m1 进入 PEL                         ←  Redis 自动维护
    │ 记录：{m1, analyze-consumer-xxx, idle=0ms, delivery=1}
    │
    ▼
Consumer 开始处理 m1
    │
    │  [正常路径]
    ├─ 处理成功 → XACK → m1 从 PEL 移除 ✅
    │
    │  [取消路径]  
    ├─ 实体被删 → XACK → m1 从 PEL 移除 ✅
    │
    │  [重试路径]
    ├─ 处理失败 → retryMessage() → XADD m2(retryCount+1) → XACK m1 → m1 从 PEL 移除 ✅
    │                                                   m2 进入 PEL，等下轮消费
    │
    │  [崩溃场景]
    └─ Consumer JVM 崩溃
           │
           ▼
       m1 留在 PEL
           │ idle 时间累积：0ms → 30s → 60s → 70s
           │
           ▼
       PendingMessageRecoveryService 定时触发（60 秒后）
           │
           ▼
       XAUTOCLAIM 认领 m1，所有权转移给当前消费者
           │
           ▼
       processMessage(m1) → 重新走完整流程 → 最终 XACK → m1 从 PEL 移除 ✅
```

***

## 八、面试话术

> 「PEL 是 Redis Stream 消费者组的核心机制。消费者通过 XREADGROUP 拉取消息时，消息会自动进入 PEL，记录"哪条消息分配给了哪个消费者、空闲了多久、投递了几次"。
>
> 消息从 PEL 移除的唯一方式是显式 XACK。在我们的项目中，无论处理成功、任务取消、还是重试新消息入队后，原消息都会立即 XACK，保证 PEL 不残留僵尸消息。
>
> PEL 的另一个重要作用是故障恢复。如果消费者在处理中途崩溃，消息留在 PEL 里，我们通过 PendingMessageRecoveryService 每 60 秒执行一次 XAUTOCLAIM，把空闲超过 60 秒的消息重新拉回来处理。这 60 秒的阈值是考虑了 AI 调用耗时的平衡点——既不会在正常处理中被误认领，又能及时回收真的僵尸消息。」

<br />

`PendingMessageRecoveryService` 的作用很简单：**定时把卡在 PEL（Pending Entries List）里的"僵尸消息"捞回来重新处理。**

***

## 它解决什么问题

消费者在处理消息中途崩溃了（JVM OOM / kill -9 / 机器宕机）：

```
消费者拉取了消息 m1 → 进入了 PEL → 开始处理 → JVM 崩溃！
                                         ↓
                                  消息既没 ACK，也没完成
                                  永远卡在 PEL 里
```

没有这个 Service，这些消息就永久丢失了。有它，消息会被自动找回。

***

## 两行代码说清楚核心逻辑

```java
// 找：去 PEL 里认领空闲超过 60 秒、还没被处理完的消息
consumer.recoverPendingMessages(60000, 10)

// 重新处理：走完整流程（解析 → markProcessing → processBusiness → markCompleted → ACK）
messages.forEach(this::processMessage)
```

***

## 两个触发时机

| 触发时机         | 注解                                            | 目的              |
| :----------- | :-------------------------------------------- | :-------------- |
| **应用启动完成**   | `@EventListener(ApplicationReadyEvent.class)` | 恢复上次运行残留的消息     |
| **每 60 秒定时** | `@Scheduled(fixedDelay = 60_000)`             | 持续监控，恢复新出现的超时消息 |

***

## 和项目其他组件的关系

```
PendingMessageRecoveryService
    │
    │  Spring 自动注入所有消费者
    ▼
List<AbstractStreamConsumer<?>> consumers
    │
    ├── VectorizeStreamConsumer   → 恢复向量化管道的僵尸消息
    ├── AnalyzeStreamConsumer     → 恢复简历分析管道的僵尸消息
    ├── EvaluateStreamConsumer    → 恢复面试评估管道的僵尸消息
    └── VoiceEvaluateStreamConsumer → 恢复语音面试管道的僵尸消息
```

它是整个异步任务系统的**最后一道保险**，确保四条管道中没有任何消息因为消费者异常退出而永久丢失。
