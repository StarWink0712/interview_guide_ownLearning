# Redis Stream 执行链路与可靠性

## 1. 模块基础讲解

本项目的异步任务执行链路由三部分组成：

1. `AbstractStreamProducer`：统一发送任务到 Redis Stream。
2. `AbstractStreamConsumer`：统一消费、ACK、重试和指标记录。
3. `PendingMessageRecoveryService`：统一恢复 pending 消息。

`asynctask` 模块虽然不直接实现 Redis Stream 的底层操作，但它和 Stream 链路深度绑定：Producer 创建任务后入队，Consumer 处理时回写任务状态，Recovery 通过所有 Consumer 的 `recoverPendingMessages()` 兜底。

Redis Stream 的核心语义包括：

| 概念 | 在项目中的体现 |
|---|---|
| Stream Key | 四条业务管道，如 `resume:analyze:stream` |
| Consumer Group | 每类任务一个 group，如 `analyze-group` |
| Consumer Name | 前缀 + UUID，区分消费者实例 |
| XADD | Producer 投递任务 |
| XREADGROUP | Consumer 读取 never-delivered 消息 |
| XACK | 业务处理完成后确认 |
| PEL | 已投递未 ACK 的 pending 消息列表 |
| XAUTOCLAIM | 恢复空闲超时的 pending 消息 |

可靠性设计有三层：

| 层级 | 机制 | 解决的问题 |
|---|---|---|
| 自动重试 | Consumer 捕获异常后重新 XADD | LLM 超时、网络抖动等临时失败 |
| Pending 恢复 | 启动和每 60 秒 XAUTOCLAIM | 消费者崩溃导致未 ACK |
| 人工兜底 | 管理后台 retry/cancel | 自动重试耗尽后的运维介入 |

## 2. 基础面试问题

### Q1：为什么用 Redis Stream 而不是普通 Redis List？

普通 List 可以做简单队列，但缺少消费者组和 pending 恢复能力。Redis Stream 支持消费者组，一个消息可以被组内某个消费者处理；消息投递后未 ACK 会进入 PEL，消费者崩溃后可以用 XAUTOCLAIM 恢复。

对异步 AI 任务来说，最怕的是“消息被消费者拿走但还没处理完，进程挂了”。Redis Stream 的 PEL 正好解决这个问题。

### Q2：Producer 发送的消息里有什么？

消息是轻量化的，只包含标识字段。例如简历分析：

```text
taskId=42
businessId=123
resumeId=123
retryCount=0
traceId=可选
```

不会把大段简历文本或知识库文档内容塞进 Redis Stream。消费者拿到 `resumeId` 或 `kbId` 后，再去业务表或文件存储读取内容。

### Q3：消费者处理一条消息的流程是什么？

`AbstractStreamConsumer.processMessage()` 的模板流程是：

```text
读取 traceId 写入 MDC
  -> parsePayload()
  -> parseRetryCount()
  -> markProcessing()
  -> processBusiness()
  -> markCompleted()
  -> XACK
异常：
  -> AsyncTaskCancelledException：markCancelled() + XACK
  -> 普通异常且 retryCount < 3：retryMessage() + XACK
  -> 普通异常且 retryCount >= 3：markFailed() + XACK
```

注意：具体业务 Consumer 的 `markFailed()` 当前多实现为 `asyncTaskService.markDeadLetter()`，所以自动重试耗尽后会进入 `DEAD_LETTER`。

### Q4：什么情况下消息会进入 pending？

消费者通过消费者组读取消息后，如果还没有 XACK，这条消息就会在 PEL 中。常见原因包括：

1. 消费者处理过程中 JVM 崩溃。
2. 容器被 kill。
3. 网络中断导致 ACK 没发出去。
4. 业务逻辑执行时间过长，超过恢复服务的 idle 阈值。

## 3. 进阶面试问题

### Q1：为什么失败重试时是重新 XADD 一条消息，而不是不 ACK 旧消息？

当前实现选择“重新 XADD 新消息，并 ACK 旧消息”。好处是重试次数写在新消息里，处理链路简单，旧消息不会一直堆在 PEL 中。

如果不 ACK 旧消息，依赖 pending 恢复来重试，也能实现，但重试次数、退避时间、错误记录会更难管理，而且 PEL 会承载普通失败和消费者崩溃两类语义，排查时不够清晰。

当前策略可以理解为：

1. 业务失败：显式重新投递新消息。
2. 消费者崩溃：交给 PEL + XAUTOCLAIM 恢复。

这两个故障通道分开，更容易解释和运维。

### Q2：`recoverPendingMessages()` 会不会导致重复执行？

可能会。Redis Stream 的消费者组语义通常是至少一次投递。如果消费者业务已经执行成功，但在 XACK 前崩溃，pending 恢复后会重新处理同一条消息。

因此消费者必须做幂等。当前代码里有幂等判断：

1. 简历分析：如果分析状态已完成且已有最新分析结果，就跳过。
2. 知识库向量化：如果向量状态已完成且 chunk 数大于 0，就跳过。
3. 面试评估：如果会话已评估且总分不为空，就跳过。

面试回答可以强调：可靠消息系统通常不是 exactly-once，而是 at-least-once + 幂等业务处理。

### Q3：`StreamReadGroupArgs.neverDelivered()` 有什么含义？

它表示消费者主循环只读取从未投递给消费者组的消息。已经投递但未 ACK 的消息不会被主循环重复读取，而是留在 PEL 中，交给恢复服务定期 `XAUTOCLAIM`。

这种设计能避免主循环和恢复逻辑抢同一类消息：

```text
新消息 -> 主消费循环处理
pending 旧消息 -> PendingMessageRecoveryService 处理
```

### Q4：`STREAM_MAX_LEN=1000` 有什么风险？

`streamAdd()` 使用近似裁剪控制 Stream 最大长度，防止 Redis 内存无限增长。风险是如果消费严重滞后，旧消息可能被裁剪。Redis Stream 对已经在 PEL 的消息和被裁剪消息之间还存在一些边界行为，生产上需要结合吞吐、消费者速度和任务重要性调大长度或关闭裁剪。

对本项目来说，1000 更像开发和中小规模场景的保护值。生产环境应该按任务峰值、处理耗时和恢复窗口重新评估。

## 4. 技术延伸问题

### Q1：Redis Stream 的可靠性和 RabbitMQ 有什么不同？

RabbitMQ 是专门 MQ，ACK、NACK、重回队列、死信交换机、延迟消息、优先级等能力更成熟。Redis Stream 的优势是轻量、低接入成本、和 Redis 生态统一，消费者组与 PEL 足够支撑中等规模后台任务。

如果项目未来拆成多服务、消息量明显增加、需要复杂路由和严格死信治理，可以迁移到 RabbitMQ。迁移时 `AsyncTaskEntity` 和 `AsyncTaskService` 可以保留，只替换 Producer/Consumer 底层实现。

### Q2：如果用 Kafka 怎么设计？

Kafka 更适合高吞吐事件流。可以把每类任务作为 topic，消费者组消费任务事件。Kafka 的 offset 提交相当于 ACK，但它不适合直接做“单任务状态查询”。所以仍然需要数据库任务表记录状态。

Kafka 方案下：

```text
AsyncTaskEntity -> 状态真源
Kafka topic -> 任务事件流
Consumer -> 执行业务并更新状态
失败处理 -> retry topic / dead-letter topic
```

适合大吞吐、可回放、跨服务事件驱动场景。

### Q3：如何做延迟队列？

Redis Stream 本身没有非常直接的延迟消息语义。可以用 Redis ZSET：

```text
失败任务 -> ZADD retry:zset score=nextRetryTimestamp
定时调度器 -> 到期后 XADD 到 Stream
```

或者换 RabbitMQ TTL + DLX / 延迟插件。当前代码已有 `nextRetryAt` 字段，适合未来接入延迟重试，但目前没有真正使用。

## 5. 改进方案问题

### Q1：当前 Redis Stream 链路的优点是什么？

1. 使用消费者组，支持水平扩展消费者实例。
2. 消息未 ACK 会进入 PEL，不会因消费者崩溃直接丢失。
3. 启动和定时恢复 pending 消息，有自愈能力。
4. 消息体轻量，只传 ID，不把大文本塞进 Redis。
5. 公共 Producer/Consumer 模板减少重复代码。
6. TraceId 写入消息并恢复到 MDC，方便跨异步链路追踪日志。

### Q2：当前链路有哪些可以增强的点？

1. 重试使用立即重投，缺少指数退避，外部服务故障时可能放大压力。
2. 单个 Consumer 是单线程，顺序清晰但吞吐有限。
3. `parsePayload()` 里 `Long.parseLong()` 如果格式错误会抛异常，目前不会返回 null，可能进入普通失败重试。
4. `streamAck()` 异常只记录日志，ACK 失败后可能产生重复消费，需要结合幂等兜底。
5. `STREAM_MAX_LEN` 固定 1000，生产上需要配置化。
6. 恢复服务没有分布式锁，多实例部署时可能同时恢复，需要确认 XAUTOCLAIM 语义和业务幂等。

### Q3：如何提升吞吐？

可以从低风险到高风险逐步优化：

1. 调大 `BATCH_SIZE`。
2. 为不同任务类型配置不同消费者线程数。
3. 对 CPU/IO 密集任务拆分不同线程池。
4. 对 LLM 或向量化调用做限流和隔离。
5. 将耗时特别长的任务进一步拆分为子任务。
6. 迁移到专门 MQ 或分布式任务调度系统。

## 6. 故障处理问题

### Q1：Redis 挂了会怎样？

Producer 入队会失败，`onSendFailed()` 会更新业务状态并调用 `asyncTaskService.markFailed()`。消费者主循环会记录消费失败日志，pending 恢复也会失败并记录 warn。恢复 Redis 后，可以通过管理后台重试失败任务。

如果要更可靠，应该引入 Outbox：即使 Redis 当时不可用，任务投递请求也先落库，之后由后台投递器继续尝试。

### Q2：消息格式错误怎么处理？

如果必要字段缺失，`parsePayload()` 返回 null，父类会直接 ACK 并跳过，不重试。这是合理的，因为格式错误通常不是临时错误，重试没有意义。

但如果字段存在但格式非法，例如 `taskId=abc`，当前 `Long.parseLong()` 可能抛出异常，进入普通失败重试。可以优化为捕获 `NumberFormatException`，记录错误后 ACK 丢弃或标记任务失败。

### Q3：pending 恢复一直失败怎么办？

排查方向：

1. 确认消费者组存在。
2. 确认 Redis 版本和 Redisson 的 `autoClaim()` 能力正常。
3. 查看 `consumerName` 是否为空或消费者是否未初始化。
4. 查看 PEL 中消息是否已经被裁剪或格式损坏。
5. 查看业务幂等逻辑是否因为重复处理导致异常。

必要时可以手动查看 Stream 和消费者组状态，确认消息 ID、idle time、delivery count，再决定手动 ACK、手动重试还是修复业务数据。

