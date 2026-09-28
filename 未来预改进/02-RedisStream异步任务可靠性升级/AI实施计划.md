# AI 实施计划：Redis Stream 异步任务可靠性升级

## 一、执行前提

请先阅读：

- `AGENTS.md`
- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java`
- `app/src/main/java/interview/guide/common/async/AbstractStreamProducer.java`
- `app/src/main/java/interview/guide/infrastructure/redis/RedisService.java`
- `app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamConsumer.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamConsumer.java`

## 二、第零阶段：先修复现有明显问题

必须先修：

- `AnalyzeStreamConsumer.java` 中 Javadoc 未闭合导致真实实现被注释掉的问题。
- 删除后面的 TODO stub 方法。
- 保留真正的 `payloadIdentifier`、`markProcessing`、`processBusiness`、`markCompleted`、`markFailed` 实现。

验收：

- 后端能编译。
- 简历上传后异步分析任务不会因为 `UnsupportedOperationException` 失败。

## 三、第一阶段：新增统一任务模型

新增包：

```text
app/src/main/java/interview/guide/modules/asynctask/
```

新增：

- `AsyncTaskEntity`
- `AsyncTaskDTO`
- `AsyncTaskType`
- `AsyncTaskStatusDTO`
- `AsyncTaskRepository`
- `AsyncTaskService`
- `AsyncTaskController`

任务类型枚举建议：

- `RESUME_ANALYZE`
- `KB_VECTORIZE`
- `INTERVIEW_EVALUATE`
- `VOICE_INTERVIEW_EVALUATE`

任务状态可复用或映射：

- `PENDING`
- `PROCESSING`
- `COMPLETED`
- `FAILED`
- `CANCELLED`
- `DEAD_LETTER`

## 四、第二阶段：Stream 消息轻量化

改造 Producer：

- `AnalyzeStreamProducer`
- `VectorizeStreamProducer`
- `EvaluateStreamProducer`
- `VoiceEvaluateStreamProducer`

目标：

- 入队前创建 `AsyncTaskEntity`。
- Stream 消息只发送：

```text
taskId
businessId
retryCount
```

不要再把完整 `content` 放进 Redis Stream。

## 五、第三阶段：消费者按 ID 加载内容

改造 Consumer：

- 简历分析：根据 resumeId 读取 `resumeText`，为空时从对象存储解析。
- 知识库向量化：根据 kbId 从对象存储下载并解析内容，或读取缓存文本。
- 面试评估：根据 sessionId 查问题与答案。
- 语音面试评估：根据 sessionId 查语音消息。

注意：

- 实体不存在时 ACK 并将任务标记为 CANCELLED 或 FAILED。
- 业务处理必须幂等。
- 同一 task 重试不应重复生成多份结果。

## 六、第四阶段：Pending 消息恢复

扩展 `RedisService`：

新增方法建议：

- `streamPending(...)`
- `streamAutoClaim(...)` 或 `streamClaim(...)`
- `streamReadPendingMessages(...)`

新增 `PendingMessageRecoveryService`：

- 应用启动后扫描各 Stream 的 pending 消息。
- 定时扫描 idle 超过阈值的 pending 消息。
- claim 到当前消费者后重新处理。

如果 Redisson API 不方便实现 auto-claim，可以先使用底层 Redis 命令或 Redisson 支持的 pending/claim 能力。

## 七、第五阶段：死信和手动重试

超过最大重试次数：

- 更新业务实体 FAILED。
- 更新 `AsyncTaskEntity` 为 `DEAD_LETTER`。
- 保存 `lastError`。

新增接口：

```text
GET /api/async-tasks
GET /api/async-tasks/{id}
POST /api/async-tasks/{id}/retry
POST /api/async-tasks/{id}/cancel
```

重试接口：

- 校验任务状态是否允许重试。
- 重置 retryCount。
- 重新发送 Stream 消息。

## 八、第六阶段：幂等保护

在 `AsyncTaskEntity` 上增加唯一业务键：

```text
taskType + businessId + activeFlag
```

实现策略：

- 如果同类型同业务对象已有 PROCESSING/PENDING 任务，则不重复创建。
- 手动重试时复用原 taskId 或创建新 task 并关联 parentTaskId。

## 九、测试要求

至少新增：

- `AsyncTaskServiceTest`
- `AnalyzeStreamConsumerTest`
- `VectorizeStreamConsumerTest`
- `PendingMessageRecoveryServiceTest`

重点用例：

- 正常入队，任务状态从 PENDING 到 COMPLETED。
- 实体删除后任务被 ACK，不无限重试。
- 处理失败后 retryCount 增加。
- 超过最大重试后进入 DEAD_LETTER。
- pending 消息能被恢复。

## 十、验收标准

完成后必须满足：

- 简历分析、知识库向量化、面试评估、语音评估都能跑通。
- Redis Stream 不再存储大段全文 content。
- 服务在处理任务时异常退出后，pending 消息可以恢复。
- 用户可以通过接口查看任务状态和失败原因。
- 手动重试失败任务可用。

