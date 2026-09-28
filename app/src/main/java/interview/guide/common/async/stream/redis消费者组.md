# Redis Stream 消费者组详解

## 一、先确认：Redis Stream 到底是什么

Redis Stream 是 **Redis 5.0 引入的消息队列数据结构**，它不是第三方中间件，而是 Redis 原生支持的数据类型（和 String、List、Hash、Set、Sorted Set 平级）。

你可以把它理解为：**Redis 内置了一个轻量级的 Kafka**。

```
传统消息队列中间件        Redis Stream
─────────────────      ──────────────
Kafka                   Redis 5.0+
RabbitMQ                原生数据类型
RocketMQ                无需额外部署
Pulsar                  单机/集群都能用
```

---

## 二、消费者组（Consumer Group）是什么

消费者组解决了一个核心问题：**同一条消息，既要能被多个消费者并行处理，又不能被重复处理。**

用生活类比：

> 一个外卖订单（消息），厨房里有 3 个厨师（消费者）。
> 消费者组 = "热菜组"，订单分配给其中一个厨师做，其他厨师不重复做。
> 如果有另一个组 = "打包组"，同样这条订单消息，打包员也能看到。
> 
> **同组内互斥（一条消息只被一个人处理），不同组各自独立消费。**

---

## 三、在这个项目中的实际结构

结合 4 个子类来看，项目定义了 4 个独立的消息管道，每个管道有一个消费者组：

```
Redis Stream 管道              消费者组                   消费者实例
─────────────────             ──────────                ──────────
knowledgebase:vectorize:stream → vectorize-group    → vectorize-consumer-a3f2b01c
resume:analyze:stream          → analyze-group      → analyze-consumer-8e1d9f4a
interview:evaluate:stream      → evaluate-group     → evaluate-consumer-c7b3a5d2
voice:evaluate:stream          → voice-eval-group   → voice-evaluate-consumer-f6a2d01e
```

每个消费者组内目前只有一个消费者实例（单线程守护线程池），但如果将来要扩容，可以在同一组内加多个消费者实例：

```
知识库向量化管道扩容后：
                    vectorize-group（消费者组）
                    ├── vectorize-consumer-a3f2b01c  （实例1）
                    ├── vectorize-consumer-b4c3d02d  （实例2）
                    └── vectorize-consumer-e5f6g03e  （实例3）

消息 [m1] [m2] [m3] [m4] [m5] [m6] ...
        ↓    ↓    ↓
      实例1 实例2 实例3  ← 自动分配，互不重复
```

---

## 四、代码中的消费者组创建

```java
private void startConsumer() {
    try {
        redisService.createStreamGroup(streamKey(), groupName());
        // 底层执行 Redis 命令：XGROUP CREATE streamKey groupName 0 MKSTREAM
        log.info("Redis Stream group is ready: {}", groupName());
    } catch (Exception e) {
        log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
    }
    consumeLoop();
}
```

**为什么 `createStreamGroup` 会失败？**

`XGROUP CREATE` 命令在组已存在时会报错 `BUSYGROUP`，所以第一次启动成功创建，之后重启就会走到 `catch` 分支。这是**正常行为**，不影响运行，只是打个警告日志。

```
第一次启动：XGROUP CREATE → 成功 → 日志 "group is ready"
第二次启动：XGROUP CREATE → BUSYGROUP 错误 → 日志 warn"Failed to prepare..."
          但仍然继续执行 consumeLoop()，用已有组继续消费
```

---

## 五、消费者组 vs 普通消费模式

Redis Stream 有两种消费模式，项目用的是消费者组模式：

| 特性 | 普通消费（XREAD） | 消费者组（XREADGROUP） |
|------|------------------|----------------------|
| 消息确认 | 无 ACK 机制 | 需要 XACK 确认 |
| 同组负载均衡 | 不支持 | 自动分配 |
| 消息重试 | 不支持 | PEL 待处理列表，挂起消息可恢复 |
| 消费进度 | 自己管理 | Redis 自动维护组内进度 |
| 消费者挂了 | 消息丢失 | 消息留在 PEL，可被其他消费者认领 |

这个项目选消费者组，正是因为需要 **消息可靠性** 和 **失败重试**。看代码中消费完成后必须 ACK：

```java
// 在 processMessage() 中
ackMessage(messageId);  // → 执行 XACK，消息才真正从待处理列表移除
```

---

## 六、面试话术

> 「消费者组是 Redis Stream 原生的消息队列概念，和 Kafka 的 Consumer Group 设计理念一致：**同组内多个消费者自动负载均衡，每条消息只被一个消费者处理；不同组之间独立消费，互不影响。**
>
> 我们这个项目用了 4 个消费者组，分别对应知识库向量化、简历分析、面试评估、语音面试评估四条异步管道。
>
> 相比普通消费模式，消费者组的优势是：消息需要显式 ACK 确认（保证不丢）、消费者挂掉后消息留在 PEL 待处理列表（保证可恢复）、同组多个实例自动负载均衡（保证可扩展）。」

---

## 七、补充：PEL（Pending Entries List）

这是消费者组的核心机制，和之前讨论的守护线程、shutdown() 密切相关：

```
消息到达 Stream
    ↓
XREADGROUP 分发给消费者  →  消息进入 PEL（Pending Entries List）
    ↓
消费者处理中...
    ↓
处理成功 → XACK  →  消息从 PEL 移除 ✅
处理失败 → 不 ACK →  消息留在 PEL
    ↓
recoverPendingMessages() 找回 PEL 中的消息重新处理
```

这也解释了之前分析的结论：**即使守护线程被 JVM 暴力杀死，未 ACK 的消息仍安全保留在 PEL 中，下次启动通过 `recoverPendingMessages()` 恢复。**