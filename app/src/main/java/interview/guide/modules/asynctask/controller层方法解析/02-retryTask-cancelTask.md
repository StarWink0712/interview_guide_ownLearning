# retryTask 与 cancelTask：从按钮到 Redis Consumer 的完整闭环

> 对应后端：`AsyncTaskController.retryTask`、`AsyncTaskController.cancelTask`  
> 对应前端：`AsyncTaskPage`、`ConfirmActionButton`、`asyncTaskApi`  
> 对应异步执行：`AbstractStreamConsumer` 及四个具体 Consumer

## 1. 两个操作的本质区别

| 对比项 | `retryTask` | `cancelTask` |
|---|---|---|
| 页面入口 | “重试任务”按钮 | “取消任务”按钮 |
| 允许的状态 | `FAILED`、`CANCELLED`、`DEAD_LETTER` | `PENDING`、`PROCESSING` |
| 是否修改数据库 | 是 | 是 |
| 是否操作 Redis | 是，向原 Stream 执行 XADD | 否 |
| 是否立即执行业务 | 否，由 Consumer 异步执行 | 不涉及业务执行 |
| HTTP 返回含义 | “重新投递成功”，不是“业务执行成功” | “取消标记已保存”，不是“运行线程已中断” |

前端会根据状态禁用按钮，但这只是用户体验层保护。真正保证状态合法的是 Service 中的
`RETRYABLE_STATUSES` 和 `ACTIVE_STATUSES` 二次校验。

---

## 2. retryTask：手动重试完整调用链

### 2.1 页面怎样允许用户点击

`AsyncTaskPage` 根据当前选中任务计算：

```ts
const canRetry = selectedTask
  && ['FAILED', 'CANCELLED', 'DEAD_LETTER'].includes(selectedTask.status);
```

这个值传给 `ConfirmActionButton.disabled`。按钮在以下状态不可点击：

- `PENDING`
- `PROCESSING`
- `COMPLETED`
- 没有选中任务
- 另一个操作正在执行，`actionLoading === true`

用户点击可用按钮后，`ConfirmActionButton.handleClick` 先执行：

```ts
if (!window.confirm('确认重新投递这个任务？')) {
  return;
}
await onConfirm();
```

用户取消确认时，链路在前端结束，不会发 HTTP 请求。确认后调用父组件传入的
`handleRetry`。

### 2.2 handleRetry 怎样发请求

`AsyncTaskPage.handleRetry` 的调用顺序：

```text
检查 selectedTask 是否存在
  → setActionLoading(true)
  → asyncTaskApi.retry(selectedTask.id)
  → 成功后 setSelectedTask(updated)
  → await loadTasks() 再查一次完整列表
  → finally setActionLoading(false)
```

API 层执行：

```ts
retry(id: number): Promise<AsyncTask> {
  return request.post(`/api/async-tasks/${id}/retry`);
}
```

`request.post` 没有传请求体，因此发送的是空 POST，请求中的任务 ID 完全来自路径。例如：

```http
POST /api/async-tasks/42/retry
```

### 2.3 Controller 怎样接住请求

Spring MVC 匹配：

```java
@PostMapping("/api/async-tasks/{id}/retry")
public Result<AsyncTaskDTO> retryTask(@PathVariable Long id) {
  return Result.success(asyncTaskService.retryTask(id));
}
```

逐步动作：

1. 将路径中的 `42` 绑定为 `Long id = 42L`。
2. 调用 `AsyncTaskService.retryTask(42L)`。
3. Service 正常返回时，使用 `Result.success` 包装 DTO。
4. Service 抛异常时，Controller 不捕获，由 `GlobalExceptionHandler` 统一处理。

Controller 不构造 Redis 消息，也不判断状态。

### 2.4 Service 第一步：查询任务并做状态保护

`AsyncTaskService.retryTask` 标注 `@Transactional`，首先调用：

```java
AsyncTaskEntity task = getEntity(id);
```

`getEntity` 内部执行 `taskRepository.findById(id)`：

- 找到：返回 `AsyncTaskEntity`。
- 找不到：抛出 `BusinessException(ErrorCode.NOT_FOUND, "异步任务不存在")`。

接着执行服务端白名单校验：

```java
if (!RETRYABLE_STATUSES.contains(task.getStatus())) {
  throw new BusinessException(ErrorCode.BAD_REQUEST, "当前任务状态不允许重试");
}
```

白名单是 `{FAILED, CANCELLED, DEAD_LETTER}`。即使绕过前端直接调接口，`PENDING`、
`PROCESSING`、`COMPLETED` 也不能重试。

### 2.5 Service 第二步：重置数据库中的任务状态

状态校验通过后，Service 修改实体：

```java
task.setRetryCount(0);
task.setStatus(AsyncTaskStatusDTO.PENDING);
task.setLastError(null);
task.setActiveFlag(true);
task.setActiveKey(activeKey(task.getTaskType(), task.getBusinessId()));
taskRepository.save(task);
```

字段变化含义：

| 字段 | 重试前 | 重试后 | 原因 |
|---|---|---|---|
| `retryCount` | 可能为 0～3 | `0` | 手动重试开启一轮新的自动重试额度 |
| `status` | 终态 | `PENDING` | 重新进入等待消费状态 |
| `lastError` | 可能有旧错误 | `null` | 清除上一轮失败原因 |
| `activeFlag` | `false` | `true` | 重新标记为活跃任务 |
| `activeKey` | 通常为 `null` | `taskType:businessId` | 恢复同业务对象的活跃唯一键 |

此时方法仍处于同一个数据库事务中，尚未向前端返回。

### 2.6 Service 第三步：按任务类型构造 Redis 消息

Service 调用 `buildRetryMessage(task)`。公共字段为：

```text
taskId      = AsyncTaskEntity.id
businessId  = AsyncTaskEntity.businessId
retryCount  = "0"
```

然后根据 `taskType` 增加一个具体 Consumer 能识别的专属字段：

| `taskType` | 增加字段 | 示例 |
|---|---|---|
| `RESUME_ANALYZE` | `resumeId` | `{taskId=42, businessId=9, retryCount=0, resumeId=9}` |
| `KB_VECTORIZE` | `kbId` | `{taskId=42, businessId=9, retryCount=0, kbId=9}` |
| `INTERVIEW_EVALUATE` | `sessionId` | `{taskId=42, businessId=abc, retryCount=0, sessionId=abc}` |
| `VOICE_INTERVIEW_EVALUATE` | `voiceSessionId` | `{taskId=42, businessId=9, retryCount=0, voiceSessionId=9}` |

`buildRetryMessage` 用 Java `switch` 表达式选择字段，`withBusinessField` 把专属字段追加到
`LinkedHashMap`。

手动重试消息没有写入 `traceId`，所以后续 Consumer 不会继承本次 HTTP 请求的 MDC trace。

### 2.7 Service 第四步：把消息写回原 Stream

构造完成后调用：

```java
String messageId = redisService.streamAdd(
    task.getStreamKey(),
    buildRetryMessage(task),
    AsyncTaskStreamConstants.STREAM_MAX_LEN
);
```

注意 Stream 目标来自数据库字段 `task.streamKey`，不是在 `retryTask` 中根据 `taskType`
重新计算。

`RedisService.streamAdd` 的内部调用顺序：

```text
redissonClient.getStream(streamKey, StringCodec.INSTANCE)
  → StreamAddArgs.entries(message)
  → trimNonStrict().maxLen(1000)
  → RStream.add(args)，即 Redis XADD
  → 得到 StreamMessageId
  → messageId.toString()
```

`STREAM_MAX_LEN = 1000` 使用近似裁剪，避免 Stream 无限增长。XADD 成功后，Service 把新
消息 ID 写回当前任务：

```java
task.setMessageId(messageId);
return toDTO(taskRepository.save(task));
```

### 2.8 HTTP 响应怎样回到页面

到这里完成的是“重新投递闭环”，不是“业务执行闭环”：

```text
AsyncTaskService.retryTask 返回 AsyncTaskDTO(status=PENDING, retryCount=0)
  → AsyncTaskController Result.success(dto)
  → HTTP 200 + Result<AsyncTaskDTO>
  → Axios 拦截器提取 data
  → asyncTaskApi.retry resolve(updated)
  → setSelectedTask(updated)
  → await loadTasks() 再调用 GET /api/async-tasks
  → 表格和详情面板显示最新状态
```

`loadTasks()` 很可能看到 `PENDING`，也可能 Consumer 已经抢先处理而看到 `PROCESSING` 或
`COMPLETED`。这是异步系统的正常时序，不应假设重试响应后状态一定停留在 `PENDING`。

### 2.9 消息入队后怎样被正确 Consumer 接走

四个 Consumer 都继承 `AbstractStreamConsumer<T>`，各自的 `streamKey()` 决定监听哪个
Stream：

| Stream | Consumer | `parsePayload` 读取字段 |
|---|---|---|
| `resume:analyze:stream` | `AnalyzeStreamConsumer` | `taskId`、`resumeId` |
| `knowledgebase:vectorize:stream` | `VectorizeStreamConsumer` | `taskId`、`kbId` |
| `interview:evaluate:stream` | `EvaluateStreamConsumer` | `taskId`、`sessionId` |
| `voice:evaluate:stream` | `VoiceEvaluateStreamConsumer` | `taskId`、`voiceSessionId` |

`AbstractStreamConsumer.consumeLoop` 不断调用：

```text
RedisService.streamConsumeMessages(..., this::processMessage)
```

当 Redis 返回刚刚 XADD 的消息时，回调进入父类私有方法
`AbstractStreamConsumer.processMessage(messageId, data)`。

### 2.10 Consumer 模板怎样完成业务执行闭环

父类模板的成功路径是：

```text
parsePayload(messageId, data)
  → parseRetryCount(data)，手动重试消息此时为 0
  → 具体 Consumer.markProcessing(payload)
  → AsyncTaskService.markProcessing(taskId)：PENDING → PROCESSING
  → 具体 Consumer.processBusiness(payload)
  → 具体 Consumer.markCompleted(payload)
  → AsyncTaskService.markCompleted(taskId)：PROCESSING → COMPLETED
  → Redis ACK 当前 messageId
```

四种 `processBusiness` 的真实业务调用：

#### 简历分析

```text
AnalyzeStreamConsumer.processBusiness
  → resumeRepository.findById(resumeId)
  → 检查已有分析结果，保证幂等
  → resolveResumeText(resume)
  → gradingService.analyzeResume(resumeText)
  → 再次确认简历仍存在
  → persistenceService.saveAnalysis(resume, analysis)
  → markCompleted 同时更新统一任务和简历 analyzeStatus
```

#### 知识库向量化

```text
VectorizeStreamConsumer.processBusiness
  → knowledgeBaseRepository.findById(kbId)
  → 检查已有 chunk，保证幂等
  → parseService.downloadAndParseContent(storageKey, originalFilename)
  → vectorService.vectorizeAndStore(kbId, content)
  → markCompleted 同时更新统一任务和知识库 vectorStatus
```

#### 面试评估

```text
EvaluateStreamConsumer.processBusiness
  → sessionRepository.findBySessionIdWithResume(sessionId)
  → 检查已有评分，保证幂等
  → ObjectMapper 解析 questionsJson
  → persistenceService.findAnswersBySessionId(sessionId)
  → 把回答填充到题目
  → llmProviderRegistry.getChatClientOrDefault(provider)
  → evaluationService.evaluateInterview(...)
  → persistenceService.saveReport(sessionId, report)
  → markCompleted 同时更新统一任务和会话 evaluateStatus
```

#### 语音面试评估

```text
VoiceEvaluateStreamConsumer.processBusiness
  → sessionRepository.existsById(sessionId)
  → evaluationRepository.findBySessionId(sessionId) 检查幂等
  → evaluationService.generateEvaluation(sessionId)
  → markCompleted 同时更新统一任务和语音会话 evaluateStatus
```

### 2.11 Consumer 失败后怎样自动重试

`processBusiness` 等步骤抛出普通异常时，父类按消息中的 `retryCount` 分支：

```text
retryCount < 3
  → 具体 Consumer.retryMessage(payload, retryCount + 1)
  → RedisService.streamAdd 写入一条新消息
  → AsyncTaskService.markRetrying(taskId, newRetryCount, newMessageId)
  → 任务回到 PENDING
  → ACK 旧消息

retryCount >= 3
  → 具体 Consumer.markFailed(payload, error)
  → 四个具体实现实际调用 AsyncTaskService.markDeadLetter
  → 统一任务进入 DEAD_LETTER，业务状态进入 FAILED
  → ACK 当前消息
```

如果“自动重试的新消息”本身 XADD 失败，具体 Consumer 的 `retryMessage` 会捕获异常并调用
`markDeadLetter`。因此手动重试给任务重新提供从 0 到 3 的一轮自动重试机会。

### 2.12 retryTask 的异常返回闭环

| 失败位置 | 后端结果 | 前端结果 |
|---|---|---|
| ID 不存在 | `BusinessException(NOT_FOUND)` | 显示“异步任务不存在” |
| 状态不允许 | `BusinessException(BAD_REQUEST)` | 显示“当前任务状态不允许重试” |
| Redis XADD 抛异常 | 进入全局未知异常处理 | `handleRetry.catch` 显示错误 |
| 数据库保存/提交失败 | 进入全局异常处理 | `handleRetry.catch` 显示错误 |

失败时 `handleRetry` 不会执行成功分支的 `loadTasks`，但 `finally` 一定把
`actionLoading` 恢复为 `false`。

### 2.13 retryTask 当前的一致性风险

当前方法在 `@Transactional` 数据库事务内部调用 Redis：

```text
数据库状态改为 PENDING
  → Redis XADD
  → 数据库保存 messageId
  → 数据库事务提交
```

数据库事务和 Redis XADD 不是一个原子事务。如果 XADD 成功后数据库最终提交失败，Redis 中
可能已有消息，而数据库仍保留旧终态。这也违反本项目“事务方法内禁止调用外部 API”的编码
规范。要彻底解决通常需要事务外投递、Outbox 或事务提交后的事件机制；本文只记录现状，不在
文档修改中改业务实现。

---

## 3. cancelTask：手动取消完整调用链

### 3.1 页面怎样允许用户点击

`AsyncTaskPage` 计算：

```ts
const canCancel = selectedTask
  && ['PENDING', 'PROCESSING'].includes(selectedTask.status);
```

只有活跃任务可点击“取消任务”。点击后，`ConfirmActionButton` 调用
`window.confirm('确认取消这个任务？')`；确认后进入 `handleCancel`。

### 3.2 handleCancel 怎样发请求

```text
检查 selectedTask
  → setActionLoading(true)
  → asyncTaskApi.cancel(selectedTask.id)
  → request.post('/api/async-tasks/{id}/cancel')
  → 成功后 setSelectedTask(updated)
  → await loadTasks() 刷新列表
  → finally setActionLoading(false)
```

请求示例：

```http
POST /api/async-tasks/42/cancel
```

同样没有请求体。

### 3.3 Controller 怎样委托 Service

```java
@PostMapping("/api/async-tasks/{id}/cancel")
public Result<AsyncTaskDTO> cancelTask(@PathVariable Long id) {
  return Result.success(asyncTaskService.cancelTask(id));
}
```

Controller 只绑定 `id`、调用 Service、包装成功结果。

### 3.4 Service 怎样校验并保存取消状态

`AsyncTaskService.cancelTask` 的顺序：

```text
getEntity(id)
  → taskRepository.findById(id)
  → ACTIVE_STATUSES 校验
  → status = CANCELLED
  → lastError = "用户手动取消任务"
  → activeFlag = false
  → activeKey = null
  → taskRepository.save(task)
  → toDTO(task)
```

服务端只允许 `{PENDING, PROCESSING}`。其他状态抛出：

```java
new BusinessException(ErrorCode.BAD_REQUEST, "当前任务状态不允许取消")
```

`activeFlag=false` 和 `activeKey=null` 释放了该业务对象的活跃占位，后续 Producer 可以为同一
业务对象创建新任务。

### 3.5 cancelTask 明确没有做什么

这个方法没有调用 `RedisService`，所以它不会：

- 从 Redis Stream 删除尚未消费的消息；
- 对 pending 消息执行 ACK；
- 中断 Consumer 线程；
- 取消已经发出的 LLM、S3、文件解析或数据库操作；
- 向 Consumer 主动发送“停止”信号。

HTTP 返回只表示数据库取消标记已经保存。

### 3.6 HTTP 响应怎样回到页面

```text
AsyncTaskService.cancelTask 返回 status=CANCELLED 的 DTO
  → Controller Result.success(dto)
  → Axios 拦截器提取 data
  → handleCancel.setSelectedTask(updated)
  → await loadTasks() 重新查询列表
  → 取消按钮禁用，重试按钮启用
```

页面在这一刻通常显示 `CANCELLED` 和“用户手动取消任务”。后续是否保持该状态，取决于
取消发生时 Consumer 走到了哪一步。

### 3.7 PENDING 阶段取消：能够形成的闭环

如果消息尚未进入业务处理，时序如下：

```text
T1 管理员调用 cancelTask
   → 数据库状态 PENDING → CANCELLED

T2 Consumer 随后从 Stream 拉到原消息
   → parsePayload 成功
   → AbstractStreamConsumer 调用具体 Consumer.markProcessing
   → AsyncTaskService.markProcessing(taskId)
   → 发现当前状态是 CANCELLED
   → 抛 AsyncTaskCancelledException

T3 AbstractStreamConsumer 捕获取消异常
   → 具体 Consumer.markCancelled(payload, reason)
   → AsyncTaskService.markCancelled
   → finishTask 保持 CANCELLED 并记录原因
   → ACK 原 Stream 消息
   → 不执行业务、不自动重试
```

严格说，Stream 消息不是“永远不再拉取”，而是仍会被拉取一次，在 `markProcessing` 阶段
发现取消后 ACK 丢弃。

### 3.8 PROCESSING 阶段取消：当前存在真实竞态

如果 Consumer 已经执行过 `markProcessing` 并进入耗时的 `processBusiness`，取消接口只能把
数据库状态改成 `CANCELLED`，不能打断正在运行的业务代码。

#### 业务随后正常成功

当前真实时序可能是：

```text
T1 Consumer：markProcessing → PROCESSING
T2 Consumer：开始 LLM / 向量化 / 文件处理
T3 管理员：cancelTask → CANCELLED
T4 Consumer：processBusiness 正常返回
T5 Consumer：markCompleted
   → AsyncTaskService.finishTask 无 CANCELLED 保护
   → 状态被覆盖成 COMPLETED
T6 Consumer：ACK
```

因此，当前实现不能保证 `PROCESSING` 任务取消后最终一定保持 `CANCELLED`。旧文档中“完成
标记会发现取消状态并且不覆盖”的说法与代码不符。

#### 业务随后抛出异常

父类异常分支也不会再次检查任务是否已取消：

```text
retryCount < 3
  → retryMessage 重新 XADD
  → markRetrying 把状态改回 PENDING

retryCount >= 3
  → markFailed
  → 具体 Consumer 把状态改为 DEAD_LETTER
```

所以处理中取消还可能被后续失败/重试状态覆盖。若需要严格取消语义，应在业务完成、异常重试和
终态写入前增加取消检查，或引入可协作的取消令牌；这属于代码改造，不是 Controller 文档能
替代的保证。

### 3.9 cancelTask 的异常返回闭环

| 场景 | Service 行为 | 页面行为 |
|---|---|---|
| ID 不存在 | 抛 `BusinessException(NOT_FOUND)` | 显示“异步任务不存在” |
| 当前已完成/失败/取消/死信 | 抛 `BusinessException(BAD_REQUEST)` | 显示“当前任务状态不允许取消” |
| 数据库保存失败 | 全局异常处理 | `handleCancel.catch` 显示错误 |

无论成功或失败，`finally` 都会把 `actionLoading` 恢复为 `false`。

---

## 4. 前后端双重状态校验为什么都需要

```text
前端 canRetry / canCancel
  → 控制按钮是否可点击
  → 减少明显无效请求

Service RETRYABLE_STATUSES / ACTIVE_STATUSES
  → 抵御直接 API 调用
  → 处理页面数据过期造成的并发状态变化
  → 真正维护状态机约束
```

例如页面加载时任务为 `FAILED`，但另一个管理员已经先重试，当前用户仍可能看到可点击按钮。
第二个请求到达 Service 时任务已是 `PENDING`，服务端会拒绝重复重试。

## 5. 一句话复述两个完整流程

`retryTask` 的闭环是：管理员确认重试，前端 POST 任务 ID，Controller 委托 Service，Service
查询并校验终态、重置任务、按类型构造消息并通过 `RedisService.streamAdd` 写回原 Stream，
响应返回后页面刷新；随后 `AbstractStreamConsumer` 拉取消息，具体 Consumer 标记处理中、执行
简历分析/向量化/面试评估、写入完成或自动重试/死信状态并 ACK。`cancelTask` 的闭环则是：
前端确认后 POST，Service 只把活跃任务标记为 `CANCELLED` 并释放活跃键；等待中的消息会在
Consumer 尝试 `markProcessing` 时被识别并 ACK，但已经运行的任务不会被中断，而且当前代码
存在后续完成或重试覆盖取消状态的竞态。
