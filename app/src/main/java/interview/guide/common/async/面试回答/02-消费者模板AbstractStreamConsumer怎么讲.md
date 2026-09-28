# AbstractStreamConsumer 消费者模板怎么讲

## 1. 这部分解决什么问题

`AbstractStreamConsumer<T>` 是异步任务框架最核心的类。它把 Redis Stream 消费者最容易写错的部分统一封装起来：

- 消费者组创建。
- 消费者线程启动和关闭。
- 阻塞读取消息。
- payload 解析。
- 任务状态流转。
- 业务执行。
- 成功 ACK。
- 异常重试。
- 业务取消。
- pending 消息恢复。
- traceId MDC 恢复。
- 指标记录。

面试中可以这样说：

> Consumer 父类是一个模板方法。它定义了异步任务从 Redis Stream 被取出来之后的完整生命周期，业务子类只需要实现 payload 怎么解析、业务怎么处理、状态怎么更新。

## 2. 生命周期：启动和关闭

### 2.1 `@PostConstruct init()`

Spring Bean 初始化完成后，父类会：

1. 生成唯一消费者名：`consumerPrefix() + UUID前8位`。
2. 创建单线程 `ThreadPoolExecutor`。
3. 设置 `running=true`。
4. 提交 `startConsumer()` 到后台线程。

为什么消费者名要带 UUID？

> 同一个服务多实例部署时，每个实例都要有不同的 consumerName，这样 Redis Stream 消费者组才能把消息分配给不同消费者。如果名字固定，多个实例会相互覆盖，pending 归属也会混乱。

### 2.2 `startConsumer()`

`startConsumer()` 会先创建消费者组：

```java
redisService.createStreamGroup(streamKey(), groupName());
```

如果组已经存在，`RedisService` 会忽略 `BUSYGROUP`。

然后进入：

```java
consumeLoop();
```

### 2.3 `@PreDestroy shutdown()`

Spring 容器关闭时：

1. 设置 `running=false`。
2. 调用 `executorService.shutdown()`。
3. 消费循环下一轮退出。

面试回答：

> 这里不是随便 new 一个线程，而是把消费者线程跟 Spring Bean 生命周期绑定。应用启动时自动消费，应用关闭时停止循环并关闭线程池。

## 3. 消费循环怎么工作

消费循环核心逻辑是：

```java
while (running.get()) {
  redisService.streamConsumeMessages(
      streamKey(),
      groupName(),
      consumerName,
      BATCH_SIZE,
      POLL_INTERVAL_MS,
      this::processMessage
  );
}
```

对应 Redis Stream 行为：

- 使用消费者组模式读取。
- `BATCH_SIZE=10`，一次最多处理 10 条。
- `POLL_INTERVAL_MS=1000`，阻塞等待 1 秒。
- 读取的是 `neverDelivered()`，也就是还没有投递过的新消息。
- 已经 pending 的旧消息不在主循环里读，由 pending 恢复服务单独 `XAUTOCLAIM`。

面试官问“为什么主循环不读 pending”时，可以答：

> 主循环只处理新消息，pending 消息说明之前已经投递给某个消费者但没有 ACK。它需要按 idle timeout 判断是否真的需要接管，所以放到单独的恢复逻辑里做，避免正常消费和故障恢复混在一起。

## 4. `processMessage()` 的完整骨架

这是最重要的面试内容。可以按阶段讲：

### 4.1 恢复 TraceId

父类先保存当前线程 MDC 里的旧 traceId，然后从消息字段中取 `traceId`，写入 MDC。

为什么？

消费者线程是线程池线程，会被复用。如果不在 finally 中恢复，上一条消息的 traceId 可能污染下一条消息。

### 4.2 解析 payload

```java
T payload = parsePayload(messageId, data);
```

payload 解析由子类实现。例如：

- 简历分析解析出 `taskId`、`resumeId`、`retryCount`。
- 知识库向量化解析出 `taskId`、`kbId`、`retryCount`。
- 面试评估解析出 `taskId`、`sessionId`、`retryCount`。

如果解析失败返回 `null`，父类会直接 ACK。

面试中要说明：

> 解析失败属于坏消息。如果不 ACK，它会一直卡在 pending 或被反复恢复，形成 poison message。所以这里选择记录日志并 ACK 丢弃。

### 4.3 读取 retryCount

父类从消息字段里读 `retryCount`。如果格式异常，默认按 0 处理。

这保证了旧消息或异常消息不会因为一个字段格式问题让整个消费线程崩掉。

### 4.4 标记处理中

```java
markProcessing(payload);
```

子类通常会同时更新：

- 异步任务表状态为 `PROCESSING`。
- 业务对象状态，例如简历分析状态、知识库向量化状态、面试评估状态。

### 4.5 执行业务逻辑

```java
processBusiness(payload);
```

这里才是真正的业务处理：

- 文件下载和解析。
- 调用 LLM。
- 生成 Embedding。
- 写入 pgvector。
- 保存评估报告。

父类不知道业务细节，只保证异常会被统一处理。

### 4.6 成功完成

如果没有异常：

1. 调用 `markCompleted(payload)`。
2. 记录 completed 指标。
3. ACK 当前消息。
4. 打成功日志。

### 4.7 业务取消

如果子类抛 `AsyncTaskCancelledException`：

1. 调用 `markCancelled(payload, reason)`。
2. ACK 当前消息。
3. 不重试。

典型场景：

- 简历或知识库在异步处理前已经被删除。
- 文件内容为空，继续向量化没有意义。
- 用户手动取消任务。

面试表达：

> 取消不是系统异常，所以不应该重试。比如知识库被用户删除了，重试 3 次也不会成功，只会浪费资源。所以这里用专门的取消异常走独立分支。

### 4.8 普通异常重试

如果抛出其他异常：

- `retryCount < MAX_RETRY_COUNT`：调用子类 `retryMessage(payload, retryCount + 1)` 重新入队，然后 ACK 当前消息。
- `retryCount >= MAX_RETRY_COUNT`：调用 `markFailed(payload, error)` 标记最终失败，然后 ACK 当前消息。

重点是：

> 重试不是把同一条 pending 消息继续留着，而是重新 XADD 一条带新 retryCount 的消息，然后 ACK 当前消息。这样每次尝试都有新的 Redis messageId，任务表也能记录当前重试次数。

### 4.9 finally 恢复 MDC

最后一定恢复原来的 traceId：

- 原来有 traceId，就恢复。
- 原来没有，就移除。

这是线程池复用场景下的细节亮点。

## 5. 为什么使用单线程消费者

当前每个 Consumer Bean 内部是单线程消费。

好处：

- 同一业务管道内处理顺序更可控。
- 避免同一 JVM 内过多并发 LLM 调用、文件解析、向量化写入。
- 状态更新逻辑更容易推理。
- 对当前项目规模足够简单稳定。

但边界也要说清楚：

> 单线程不是性能最优方案，而是复杂度和稳定性的权衡。如果任务量上来，可以通过多应用实例扩容消费者组，也可以把父类改造成可配置并发数，让不同任务类型有不同并发。

## 6. 为什么 ACK 放在状态更新之后

正常成功路径是：

1. `processBusiness()` 完成。
2. `markCompleted()` 更新任务和业务状态。
3. `ackMessage()` 确认消息。

如果反过来先 ACK，再更新 DB，风险是：

- Redis 认为消息处理完了。
- 但 DB 更新失败。
- 消息不会再被恢复，任务状态可能永远卡住。

所以面试回答：

> ACK 应该放在业务状态更新之后。先确保业务结果和任务状态落库，再告诉 Redis 这条消息可以从 pending 中移除。这样 ACK 失败最多导致重复处理，而不是业务没完成却丢消息。

## 7. ACK 失败怎么办

`ackMessage()` 内部会捕获异常并打日志，不会继续抛出。

这意味着：

- 业务可能已经完成。
- Redis 没 ACK 成功。
- 消息仍留在 PEL。
- 后续 pending 恢复可能再次处理。

所以系统必须允许重复处理，不能依赖 exactly-once。

当前业务子类有一些幂等保护，例如：

- 简历已经有最新分析结果则跳过。
- 知识库已经向量化完成且有 chunk 则跳过。
- 面试会话已经评估完成且有分数则跳过。
- 语音面试已有评估则跳过。

面试回答：

> ACK 失败不是立刻让线程崩溃，而是保留在 PEL 里后续恢复。代价是可能重复处理，所以业务层要做幂等判断。这个设计选择的是至少一次投递语义。

## 8. `recoverPendingMessages()` 怎么讲

父类暴露：

```java
public int recoverPendingMessages(long idleTimeoutMs, int count)
```

它做的事情：

1. 调用 `redisService.streamAutoClaim()`。
2. 把空闲超过阈值的 pending 消息认领到当前消费者。
3. 对每条消息重新调用 `processMessage()`。
4. 返回恢复数量。

调用方是 `PendingMessageRecoveryService`：

- 应用启动完成后恢复一次。
- 每 60 秒定时恢复一次。

面试回答：

> Redis Stream 的消费者组不会自动把 pending 消息重新投给别人。消费者崩溃后，消息会留在 PEL。我们用 XAUTOCLAIM 把空闲超过阈值的消息认领回来，再走同一套 processMessage 流程，所以恢复逻辑和正常消费逻辑是复用的。

## 9. 面试官可能追问和回答

### Q1：这个消费者是 exactly-once 吗？

回答：

> 不是。它是至少一次投递模型。Redis Stream 加 ACK 和 PEL 可以降低消息丢失风险，但 ACK 失败、消费者崩溃恢复、重试入队都可能带来重复处理。所以业务侧要做幂等，比如任务表去重和已完成状态跳过。

### Q2：为什么坏消息解析失败后要 ACK？

回答：

> 如果消息字段缺失或格式坏了，重试也不会好。继续留在 pending 只会造成无限重试或阻塞恢复。所以解析失败直接 ACK，并通过日志暴露问题。

### Q3：如果业务执行到一半 JVM 崩溃怎么办？

回答：

> 因为还没 ACK，消息会留在消费者组的 PEL。应用重启后，PendingMessageRecoveryService 会通过 XAUTOCLAIM 认领空闲消息并重新处理。业务处理可能重复，所以需要幂等。

### Q4：为什么捕获 `AsyncTaskCancelledException` 后不重试？

回答：

> 它表示业务上已经不需要或不能继续处理，比如关联实体被删除。重试无法改变结果，所以标记 CANCELLED 并 ACK，比浪费重试次数更合理。

### Q5：为什么消费线程用 daemon thread？

回答：

> 这个线程属于后台异步消费线程，不应该阻止 JVM 退出。应用关闭时通过 `@PreDestroy` 设置 running=false 并 shutdown 线程池，正常情况下会优雅退出；异常退出时未 ACK 的消息会进入 pending 恢复链路。

### Q6：长时间任务超过 60 秒会不会被 pending 恢复重复处理？

回答：

> 这是一个真实边界。Redis Stream 的 pending idle 是从投递后未 ACK 的时间计算的，如果 LLM 或文件处理本身超过 idleTimeout，就有可能被恢复逻辑认领并重复处理。生产上应该把 `PENDING_IDLE_TIMEOUT_MS` 设置得大于正常任务耗时，或者增加心跳、租约续期、处理中任务保护等机制。

## 10. 面试表达小结

> Consumer 模板的关键价值是把异步任务的可靠消费流程固定下来：消费者组读取、状态机流转、业务处理、ACK、重试、取消、pending 恢复和 trace 恢复都在父类完成。子类只写业务差异，从而让四条异步管道的可靠性行为保持一致。
