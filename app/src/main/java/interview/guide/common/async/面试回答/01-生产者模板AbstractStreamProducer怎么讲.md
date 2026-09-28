# AbstractStreamProducer 生产者模板怎么讲

## 1. 这部分解决什么问题

`AbstractStreamProducer<T>` 解决的是“不同业务如何用统一方式把任务投递到 Redis Stream”。

业务里有四类任务：

- 知识库向量化。
- 简历 AI 分析。
- 模拟面试评估。
- 语音面试评估。

它们投递的 Stream key、payload 字段、失败后的状态更新都不同，但公共动作是一样的：

1. 构造 Redis Stream 消息。
2. 自动带上 traceId。
3. 写入 Redis Stream。
4. 记录日志和指标。
5. 发送失败时回调业务侧做补偿。

所以父类只保留一个核心入口：

```java
protected String sendTask(T payload)
```

业务 Producer 只需要实现：

```java
protected abstract String taskDisplayName();
protected abstract String streamKey();
protected abstract Map<String, String> buildMessage(T payload);
protected abstract String payloadIdentifier(T payload);
protected abstract void onSendFailed(T payload, String errorMessage);
```

面试中可以说：

> Producer 父类把“如何发消息”标准化，子类只提供“发到哪里、消息长什么样、失败后怎么更新业务状态”。这样新增一个异步任务管道时，不需要再复制 Redis Stream 的发送、日志、trace 和异常处理逻辑。

## 2. `sendTask()` 的执行流程

可以按源码顺序这样讲：

1. 子类传入业务 payload，例如 `AnalyzeTaskPayload(taskId, resumeId, retryCount)`。
2. 父类调用 `buildMessageWithTrace(payload)`。
3. `buildMessageWithTrace()` 先调用子类 `buildMessage(payload)` 构造基础字段。
4. 如果当前请求有 `TraceIdFilter.currentTraceId()`，就追加 `traceId` 字段。
5. 调用 `redisService.streamAdd(streamKey(), message, STREAM_MAX_LEN)` 写入 Redis Stream。
6. 写入成功后记录 created 指标和日志，返回 Redis messageId。
7. 写入失败时记录 failed 指标和错误日志，并调用 `onSendFailed()` 让子类更新业务状态。
8. 失败时返回 `null`，子类可以据此决定是否 `markEnqueued()`。

关键代码思想可以这样概括：

```java
try {
  Map<String, String> message = buildMessageWithTrace(payload);
  String messageId = redisService.streamAdd(streamKey(), message, STREAM_MAX_LEN);
  return messageId;
} catch (Exception e) {
  onSendFailed(payload, errorMessage);
  return null;
}
```

## 3. 为什么消息里只放轻量字段

当前项目的业务 Producer 基本只放这些字段：

| 字段                                                   | 含义      |
| ---------------------------------------------------- | ------- |
| `taskId`                                             | 异步任务表主键 |
| `businessId`                                         | 业务对象 ID |
| `resumeId` / `kbId` / `sessionId` / `voiceSessionId` | 具体业务字段  |
| `retryCount`                                         | 当前重试次数  |
| `traceId`                                            | 日志链路追踪  |

它不会把完整简历文本、文档内容、面试记录直接塞进 Stream。

面试官问“为什么不把内容直接放进消息”时，可以这样答：

> 我这里把 Redis Stream 当任务索引，不当大对象存储。简历正文、知识库文档、面试记录可能很大，放到 Redis 会增加内存压力，也会让重试消息很重。所以消息里只放 ID，Consumer 需要时再从数据库、对象存储或业务服务重新取。这样消息轻量、可重试，也方便排查。

## 4. 为什么要带 traceId

同步请求和异步消费发生在不同线程，甚至可能在不同时间。如果不透传 traceId，排查问题时只能看到：

- 用户上传接口日志。
- 后台消费日志。
- LLM 调用日志。

但它们之间没有关联。

`AbstractStreamProducer` 做了一个很实用的事情：入队时把当前 MDC 里的 traceId 放进消息。Consumer 消费时再写回 MDC。

面试回答：

> 异步任务最大的问题之一是链路断裂。用户请求已经返回了，但后台任务还在跑。我在 Producer 里把 traceId 放进 Stream 消息，在 Consumer 里恢复到 MDC，这样从接口到后台消费的日志能串起来，线上排查会容易很多。

## 5. 为什么业务 Producer 里通常配合 `AsyncTaskService`

`AbstractStreamProducer` 只负责“发 Redis 消息”，但项目实际业务还需要任务状态和幂等，所以各业务 Producer 会先调用：

```java
asyncTaskService.createOrReusePendingTask(...)
```

作用是：

- 同一个 `taskType + businessId` 如果已经有活跃任务，直接复用，不重复入队。
- 如果没有活跃任务，创建新的 `AsyncTaskEntity`。
- 后续 XADD 成功后调用 `markEnqueued(taskId, messageId)`，把 Redis messageId 写回任务表。

面试回答：

> 父类只解决消息发送的一致性，业务 Producer 还会通过 AsyncTaskService 做任务去重和状态记录。这样 Redis 负责流转，DB 负责状态，两者职责分开。

## 6. 为什么要事务提交后再发消息

业务 Producer 不是在数据库事务中立刻 XADD，而是常见地通过 `TransactionAfterCommitExecutor` 在事务提交后发送。

原因是：

如果事务还没提交，消息已经进了 Redis，Consumer 可能马上消费。这时它去数据库查 `taskId` 或业务对象，有可能查不到，或者读到未完成状态。

所以更稳的顺序是：

1. 业务数据落库。
2. 异步任务记录落库。
3. 数据库事务提交。
4. afterCommit 回调里发送 Redis Stream 消息。
5. 发送成功后标记 `markEnqueued()`。

面试回答：

> 我这里避免了“消息先到，数据未提交”的问题。Redis Stream 和数据库不是同一个事务资源，所以至少要保证 DB commit 之后再发消息。如果生产级要求更强一致，可以进一步演进成 Outbox 模式，由扫描任务统一投递。

## 7. 发送失败怎么处理

`sendTask()` 捕获异常后会调用：

```java
onSendFailed(payload, "任务入队失败: " + e.getMessage())
```

这个回调由业务子类实现，例如：

- 简历分析入队失败：把简历分析状态置为 `FAILED`，任务标记失败。
- 知识库向量化入队失败：把知识库向量化状态置为 `FAILED`。
- 面试评估入队失败：把会话评估状态置为 `FAILED`。

面试中重点讲：

> Producer 失败不是简单打日志，而是通过 onSendFailed 交给业务子类做状态补偿。这样前端能看到任务失败，而不是用户以为任务还在排队。

## 8. 为什么 `sendTask()` 失败返回 null，而不是继续抛异常

这取决于使用场景。当前业务里很多发送动作是在 afterCommit 的异步回调中执行，请求事务已经结束，继续向外抛异常对用户请求没有意义，反而可能导致后台线程异常不可控。

返回 `null` 的价值：

- 子类可以判断 `messageId != null` 再 `markEnqueued()`。
- 失败逻辑已经通过 `onSendFailed()` 补偿。
- 不影响原业务事务提交。

但面试里也要补一句边界：

> 这种设计适合当前项目。更强的方案可以引入 Outbox 表，把“待投递消息”持久化，发送失败后由后台补偿投递，而不是只依赖一次 afterCommit 回调。

## 9. 子类落地时的典型模板

业务 Producer 大致都长这样：

```java
public void sendAnalyzeTask(Long resumeId) {
  AsyncTaskEnqueueResult result = asyncTaskService.createOrReusePendingTask(...);
  if (!result.created()) {
    return;
  }

  TransactionAfterCommitExecutor.afterCommitExecutor.execute(() -> {
    String messageId = sendTask(payload);
    if (messageId != null) {
      asyncTaskService.markEnqueued(taskId, messageId);
    }
  });
}
```

这个模板里有三个关键点：

| 关键点                          | 作用                           |
| ---------------------------- | ---------------------------- |
| `createOrReusePendingTask()` | 防止重复入队                       |
| `afterCommit`                | 防止消息先于 DB 提交被消费              |
| `markEnqueued()`             | 记录 Redis messageId，便于排查和人工重试 |

## 10. 面试官可能追问和回答

### Q2：Producer 如何保证同一个任务不重复入队？

回答：

> 严格说 `AbstractStreamProducer` 本身不做去重，去重放在 `AsyncTaskService.createOrReusePendingTask()`。它会按任务类型和业务 ID 查询活跃任务，如果已经存在 PENDING 或 PROCESSING 的任务，就返回 created=false，业务 Producer 直接跳过发送。

### Q3：为什么要先创建任务表记录，再发 Redis 消息？

回答：

> 因为消息只是触发器，任务表才是业务状态。先有 taskId，消息里才能带 taskId，Consumer 处理时才能更新状态、重试次数、失败原因。否则 Redis 里有消息，但系统没有可查询、可重试、可展示的任务状态。

### Q4：如果 XADD 成功了，但是 markEnqueued 失败怎么办？

回答：

> 当前设计里 XADD 和 markEnqueued 不是一个分布式事务。消息已经带了 taskId，所以 Consumer 仍有机会处理任务，但任务表里的 messageId 可能缺失。生产上可以通过 Outbox 或补偿扫描进一步增强，保证投递状态最终一致。这是后续可以演进的点。

### Q5：为什么使用 Redis Stream，而不是直接开线程池？

回答：

> 线程池只能解决当前 JVM 内的异步，服务重启后任务就没了，也不方便多实例分摊。Redis Stream 有持久化消息、消费者组、ACK 和 pending 列表，可以做跨实例消费和失败恢复，更适合这里的后台 AI 任务。

## 11. 面试表达小结

> Producer 模板的核心是标准化入队：消息轻量、trace 可追踪、失败可补偿、任务可去重。它不单独解决所有可靠性问题，而是和 AsyncTaskService、事务 afterCommit、Consumer 重试恢复一起形成完整闭环。

