# completeInterview：提前交卷并触发异步评估完整调用链

> 接口：`POST /api/interview/sessions/{sessionId}/complete`  
> Controller：`InterviewController.completeInterview`  
> 当前前端调用：`InterviewPage.handleCompleteEarly`

## 1. 前端触发与确认

用户点击“提前交卷”后，页面先显示 `ConfirmDialog`：

```text
提示：未回答的问题将按 0 分计算
  → 用户取消：不请求后端
  → 用户确认：handleCompleteEarly
```

确认路径：

```text
setIsSubmitting(true)
  → interviewApi.completeInterview(session.sessionId)
  → POST /api/interview/sessions/{sessionId}/complete
```

请求没有 body。当前输入框里尚未通过 submit/save 保存的文字不会随提前交卷请求发送。

## 2. Controller 层

```java
@PostMapping("/api/interview/sessions/{sessionId}/complete")
public Result<Void> completeInterview(@PathVariable String sessionId) {
  sessionService.completeInterview(sessionId);
  return Result.success(null);
}
```

Controller 记录日志、委托 Service、返回空成功结果，没有直接操作数据库或 Stream。

## 3. Service：获取会话并保护状态

```text
getOrRestoreSession(sessionId)
  → Redis 命中并刷新 TTL
  → 或从数据库恢复
```

如果状态已经是 `COMPLETED` 或 `EVALUATED`，抛出：

```java
BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED)
```

`CREATED` 和 `IN_PROGRESS` 都允许提前交卷。

## 4. Service：先更新 Redis

```java
sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);
```

缓存状态变为完成，同时 `InterviewSessionCache` 删除该简历对应的“未完成会话”映射。未回答题目
不会生成答案记录；最终评估会从完整 questions 列表中看到 `userAnswer=null`。

## 5. Service：尝试更新数据库

try 块中依次调用：

```text
persistenceService.updateSessionStatus(sessionId, COMPLETED)
  → 保存 completedAt

persistenceService.updateEvaluateStatus(sessionId, PENDING, null)
  → 标记最终评估等待中
```

数据库异常被捕获并只记录警告，Service 仍继续投递评估任务。因此可能出现缓存已完成，但会话表
状态或 `evaluateStatus` 没有同步成功。

## 6. Service：投递评估任务

数据库 try/catch 之后调用：

```java
evaluateStreamProducer.sendEvaluateTask(sessionId);
```

Producer 执行：

```text
AsyncTaskService.createOrReusePendingTask
  → taskType=INTERVIEW_EVALUATE
  → businessId=sessionId
  → streamKey=interview:evaluate:stream
  → payloadJson={"sessionId":"..."}
```

已有 `PENDING`/`PROCESSING` 评估任务时直接复用并跳过重复 XADD。新任务则构造
`EvaluateTaskPayload(taskId, sessionId, 0)`。

`completeInterview` 本身没有事务，因此 `TransactionAfterCommitExecutor.execute` 检测不到活动事务，
会立即同步执行：

```text
AbstractStreamProducer.sendTask
  → Redis XADD interview:evaluate:stream
  → AsyncTaskService.markEnqueued(taskId, messageId)
```

XADD 完成后 HTTP 才返回，但真正 LLM 评估由 `EvaluateStreamConsumer` 异步执行。

## 7. Consumer 后续闭环

```text
EvaluateStreamConsumer.parsePayload
  → AsyncTaskService.markProcessing
  → 会话 evaluateStatus=PROCESSING
  → processBusiness
      → 查询会话和已回答题目
      → LlmProviderRegistry 获取 ChatClient
      → AnswerEvaluationService.evaluateInterview
      → InterviewPersistenceService.saveReport
  → AsyncTaskService.markCompleted
  → 会话 evaluateStatus=COMPLETED
  → ACK Stream 消息
```

未回答题目如果出现在 LLM 返回的逐题评估中，报告保存阶段会建立占位答案记录，从而能在详情页
完整展示；具体分数取自评估结果，持久化代码本身没有强制写成 0 分。

普通失败会最多自动重试 3 次；耗尽后统一异步任务进入 `DEAD_LETTER`，会话评估状态进入
`FAILED`。

## 8. HTTP 返回和页面跳转

投递成功后：

```text
Result.success(null)
  → Axios Promise<void> resolve
  → 关闭确认框
  → onInterviewComplete()
  → navigate('/interviews')
```

历史页发现 `evaluateStatus=PENDING/PROCESSING` 时，每 3 秒调用 `listSessions` 轮询，直到评估
完成或失败。

请求失败时页面显示“提前交卷失败，请重试”，并停留在面试页。

## 9. 当前实现边界

- 当前输入框内容不会在提前交卷前自动调用 `saveAnswer`，可能直接丢失。
- Redis 状态先改为完成，数据库更新失败也不会回滚。
- 数据库更新失败后仍会投递任务；若数据库根本没有会话，Consumer 会把任务按取消处理。
- `TransactionAfterCommitExecutor` 在此调用链没有活动事务，实际是立即执行，不是延迟到某个事务提交后。
- HTTP 成功只代表任务入队流程完成，不代表报告已生成。
