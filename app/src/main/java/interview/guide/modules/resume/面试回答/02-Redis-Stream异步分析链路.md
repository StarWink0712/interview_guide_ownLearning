# Redis Stream 异步分析链路

## 1. 模块基础讲解

简历 AI 分析通过 Redis Stream 异步执行，涉及：

| 类 | 职责 |
|---|---|
| `AnalyzeStreamProducer` | 创建/复用异步任务，事务提交后 XADD 到 Stream |
| `AnalyzeStreamConsumer` | 消费消息，调用 AI 分析，保存结果，更新状态 |
| `AsyncTaskService` | 统一任务台账、状态流转、重试、死信 |
| `AbstractStreamProducer/Consumer` | 通用 Stream 发送和消费模板 |

消息体是轻量的：

```text
taskId
businessId
resumeId
retryCount
traceId（父类可附加）
```

不会把完整简历正文放入 Redis Stream。正文保存在 `resumes.resumeText` 或对象存储中，消费者通过 `resumeId` 重新获取。

## 2. 基础面试问题

### Q1：为什么简历分析用 Redis Stream？

因为 LLM 分析耗时、不稳定，需要从上传请求中解耦。Redis Stream 支持消费者组、ACK、pending 恢复和重试，比本地线程池更可靠。

### Q2：Producer 做了什么？

`AnalyzeStreamProducer.sendAnalyzeTask()` 做三件事：

1. 调用 `AsyncTaskService.createOrReusePendingTask()` 创建或复用任务。
2. 如果任务已存在，跳过重复入队。
3. 如果新建任务，在事务提交后调用父类 `sendTask()` 写入 Redis Stream，并 `markEnqueued()` 记录 messageId。

### Q3：为什么要 afterCommit 入队？

为了避免消息早于数据库事务提交。如果简历记录还没提交，消费者已经拿到消息，就可能查不到简历。

已有测试验证：事务提交前不会写 Redis Stream，执行 `afterCommit` 后才发送消息。

### Q4：Consumer 做了什么？

`AnalyzeStreamConsumer` 从 Stream 读取消息，解析出 `taskId` 和 `resumeId`：

```text
markProcessing()
  -> 查询 ResumeEntity
  -> 如果已完成且有分析结果，跳过
  -> resolveResumeText()
  -> ResumeGradingService.analyzeResume()
  -> ResumePersistenceService.saveAnalysis()
  -> markCompleted()
  -> ACK
```

异常时由父类模板处理重试、死信或取消。

## 3. 进阶面试问题

### Q1：为什么 Stream 消息不放简历正文？

简历正文可能很长，把大文本放入 Redis 会增加内存压力，也不利于重试和排查。当前设计只放 ID，正文从数据库或对象存储取。

这体现了一个原则：消息队列传递任务标识，不当作大字段数据库。

测试也验证了重试消息不包含 `content` 字段。

### Q2：消费者如何保证幂等？

消费前会检查：

```java
if (resume.getAnalyzeStatus() == COMPLETED
    && persistenceService.getLatestAnalysis(resumeId).isPresent()) {
  return;
}
```

如果分析结果已存在，说明可能是 ACK 失败后重复消费或 pending 恢复导致重复投递，此时直接跳过，避免重复生成分析记录。

### Q3：简历被删除时为什么抛 `AsyncTaskCancelledException`？

如果消费者发现 `resumeId` 查不到，说明业务对象已不存在。继续重试没有意义，所以抛出取消异常。父类捕获后会标记任务取消并 ACK，不会无限重试。

测试覆盖了“简历已删除时抛取消异常”。

### Q4：自动重试耗尽后状态是什么？

父类抽象方法叫 `markFailed()`，但当前 `AnalyzeStreamConsumer.markFailed()` 实际调用 `asyncTaskService.markDeadLetter()`，同时把简历分析状态设为 `FAILED`。

面试中要讲清楚：统一异步任务表进入 `DEAD_LETTER`，简历业务表显示 `FAILED`，两者面向不同视角。

## 4. 技术延伸问题

### Q1：如何改成 RabbitMQ？

保留 `ResumeUploadService`、`ResumeGradingService`、`ResumePersistenceService` 和异步任务表，替换 `AnalyzeStreamProducer/Consumer` 的底层实现。RabbitMQ 可以用 ACK/NACK、DLX、TTL 实现重试和死信。

### Q2：如何实现延迟重试？

当前失败后立即重新 XADD。可以接入：

1. Redis ZSET 延迟队列。
2. RabbitMQ 延迟队列。
3. 数据库 `nextRetryAt` 定时扫描。

对 LLM 服务超时场景，指数退避会比立即重试更稳。

### Q3：如何支持批量简历分析？

可以给每份简历创建一个子任务，父任务汇总进度。当前 `asynctask` 已有 `parentTaskId` 字段，可扩展批量任务场景。

## 5. 改进方案问题

### Q1：当前异步链路优点是什么？

1. 上传和 AI 分析解耦。
2. 事务提交后入队，避免读未提交数据。
3. Stream 消息轻量。
4. 支持自动重试和死信。
5. 支持幂等跳过。
6. 和统一异步任务模块打通。

### Q2：当前异步链路不足是什么？

1. 取消处理中任务不是强中断。
2. `parsePayload()` 对非法数字没有捕获，会进入异常重试。
3. 重试缺少退避。
4. 分析耗时没有写回分析记录。
5. `DEAD_LETTER` 和业务 `FAILED` 语义需要在前端统一展示。

### Q3：如何提升可观测性？

1. 在分析记录中保存 taskId。
2. 保存 LLM provider/model/promptVersion。
3. 记录分析耗时。
4. 将异步任务状态和简历详情页打通。
5. 展示 retryCount 和 lastError。

## 6. 故障处理问题

### Q1：Stream 发送失败怎么办？

`AbstractStreamProducer.sendTask()` 捕获异常后调用 `onSendFailed()`。简历 Producer 会把简历状态设为 `FAILED`，并把异步任务标记为 `FAILED`。

排查 Redis 连接、Stream key、消费者组和 Redis 内存。

### Q2：消费者重复执行怎么办？

先确认是否 ACK 失败或 pending 恢复导致重复消费。当前通过“结果已存在则跳过”保证幂等。如果仍重复生成记录，说明幂等检查和保存结果之间有并发窗口，可以在数据库层加唯一约束或乐观锁。

### Q3：任务进入死信怎么办？

看 `async_tasks.lastError` 和简历 `analyzeError`。如果是 LLM 超时或 Redis 短暂故障，可以修复后手动重试；如果是简历文本为空或文件无法解析，应先修数据或重新上传。

