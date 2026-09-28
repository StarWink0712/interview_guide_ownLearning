# submitAnswer：提交答案并推进面试完整调用链

> 接口：`POST /api/interview/sessions/{sessionId}/answers`  
> Controller：`InterviewController.submitAnswer`  
> 当前前端调用：`InterviewPage.handleSubmitAnswer`

## 1. 前端怎样触发

用户输入答案并点击提交时，`handleSubmitAnswer` 先校验：

```text
answer.trim() 非空
session 已存在
currentQuestion 已存在
```

随后：

1. `setIsSubmitting(true)` 禁止重复操作。
2. 先把用户消息追加到聊天记录，属于前端乐观展示。
3. 调用：

```ts
interviewApi.submitAnswer({
  sessionId: session.sessionId,
  questionIndex: currentQuestion.questionIndex,
  answer: answer.trim(),
});
```

API 层发送：

```http
POST /api/interview/sessions/{sessionId}/answers
Content-Type: application/json

{ "questionIndex": 0, "answer": "..." }
```

该请求设置 180 秒超时，因为提交过程中可能执行单轮自适应 LLM 评估。

## 2. Controller 执行前：全局限流

方法标注：

```java
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
```

默认窗口是 1 秒，因此所有用户合计每秒最多 10 次。`RateLimitAspect` 在 Controller 前执行
Redis Lua 滑动窗口检查；超过限制时请求不会进入方法。

这个接口没有 IP 维度限流。

## 3. Controller 怎样构造 Service 请求

Controller 接收的不是 `SubmitAnswerRequest`，而是原始 Map：

```java
@RequestBody Map<String, Object> body
```

然后直接强制转换：

```java
Integer questionIndex = (Integer) body.get("questionIndex");
String answer = (String) body.get("answer");
SubmitAnswerRequest request =
    new SubmitAnswerRequest(sessionId, questionIndex, answer);
sessionService.submitAnswer(request);
```

路径中的 `sessionId` 是权威会话 ID，请求体不需要再传。

当前没有 `@Valid`，`SubmitAnswerRequest` 上的 `@NotNull`、`@Min`、`@NotBlank` 不会执行。
字段缺失或 JSON 类型不符可能产生 `NullPointerException`/`ClassCastException`，最终进入全局未知异常。

## 4. Service：加载会话

`InterviewSessionService.submitAnswer` 调用：

```text
getOrRestoreSession(sessionId)
  → Redis 命中：刷新 24 小时 TTL
  → Redis 未命中：从数据库恢复题目、答案、索引和状态，再回填缓存
```

会话不存在或恢复失败时抛出 `INTERVIEW_SESSION_NOT_FOUND`。

## 5. Service：校验并更新题目

Service 反序列化完整题单，检查：

```java
index < 0 || index >= questions.size()
```

非法时抛出 `BusinessException(INTERVIEW_QUESTION_NOT_FOUND)`。

合法时：

```text
questions[index]
  → question.withAnswer(request.answer())
  → 替换 questions[index]
  → newIndex = index + 1
  → 判断是否还有下一题
```

当前只校验索引是否在题目范围内，没有校验 `index == session.currentIndex`。直接调用 API 可以
重复提交旧题或跳着提交后面的题，并让会话索引跟随请求中的 index 前进。

## 6. Service：先更新 Redis

数据库操作之前，Service 已执行：

```text
sessionCache.updateQuestions(sessionId, questions)
  → 保存带 userAnswer 的完整题单

sessionCache.updateCurrentIndex(sessionId, newIndex)
  → 推进当前索引

如果没有下一题
  → sessionCache.updateSessionStatus(sessionId, COMPLETED)
```

因此 Redis 是先发生状态变化的。如果后续数据库、自适应评估或入队失败，这些缓存变化不会回滚。
另一个细节是：还有下一题时，代码没有把 Redis 会话状态显式改成 `IN_PROGRESS`，所以从
`createSession` 直接进入答题的当前页面可能出现“缓存状态仍为 CREATED、数据库已为
IN_PROGRESS”的不一致；调用未被当前页面使用的 `getCurrentQuestion` 才会修正缓存状态。

## 7. Service：持久化答案和进度

后续所有操作被一个大的 `try/catch (Exception)` 包围：

```text
persistenceService.saveAnswer
  → sessionRepository.findBySessionId
  → answerRepository.findBySession_SessionIdAndQuestionIndex
  → 已存在则更新，不存在则新建
  → 保存 userAnswer，初始 score=0、feedback=null

persistenceService.updateCurrentQuestionIndex
  → 保存 newIndex，并暂设数据库会话状态 IN_PROGRESS

persistenceService.updateSessionStatus
  → 非最后一题：IN_PROGRESS
  → 最后一题：COMPLETED，并记录 completedAt
```

这些持久化方法各自有事务，但 `InterviewSessionService.submitAnswer` 本身没有统一事务，所以
三个数据库调用也不是一个原子事务。

## 8. 每题提交后的自适应评估

数据库更新后调用：

```java
adaptiveInterviewService.evaluateAndDecideSafely(
    sessionId, question, answer)
```

它负责当前题即时评估、能力画像和下一步决策，并返回 `AdaptiveInterviewResultDTO`。Service 把
其中的：

- `turnEvaluation`
- `adaptiveDecision`
- `abilityProfile`

放入 `SubmitAnswerResponse`。

当前 `InterviewPage` 并没有读取这三个字段，只使用 `hasNextQuestion` 和 `nextQuestion`。

## 9. 最后一题：投递异步最终评估

当 `hasNextQuestion == false` 时，在同一个 try 块内继续执行：

```text
persistenceService.updateEvaluateStatus(sessionId, PENDING, null)
  → evaluateStreamProducer.sendEvaluateTask(sessionId)
```

Producer 完整路径：

```text
AsyncTaskService.createOrReusePendingTask(INTERVIEW_EVALUATE, sessionId, ...)
  → 已有活跃任务：跳过重复入队
  → 新任务：构造 EvaluateTaskPayload(taskId, sessionId, 0)
  → TransactionAfterCommitExecutor.execute
```

由于 `submitAnswer` 此时没有活动事务，`TransactionAfterCommitExecutor` 会立即同步执行：

```text
AbstractStreamProducer.sendTask
  → Redis XADD interview:evaluate:stream
  → AsyncTaskService.markEnqueued(taskId, messageId)
```

之后由 `EvaluateStreamConsumer` 异步调用 LLM 生成最终报告并保存。HTTP 响应不等待最终评估。

## 10. Service 返回什么

```text
SubmitAnswerResponse(
  hasNextQuestion,
  nextQuestion,
  currentIndex=newIndex,
  totalQuestions,
  turnEvaluation,
  adaptiveDecision,
  abilityProfile
)
```

Controller 使用 `Result.success(response)` 返回。

## 11. 前端怎样继续或结束

Axios 拦截器提取 `data` 后：

```text
hasNextQuestion=true
  → 清空输入框
  → setCurrentQuestion(nextQuestion)
  → 把下一题追加到聊天记录

hasNextQuestion=false
  → onInterviewComplete()
  → App.InterviewWrapper.navigate('/interviews')
  → 历史页轮询 listSessions 查看异步评估状态
```

如果请求失败，前端显示固定文案“提交答案失败，请重试”。因为用户消息在请求前已经追加，失败
后该消息不会从聊天记录撤回，输入框也不会清空。

## 12. 最重要的降级边界

Service 的数据库保存、自适应评估和最终任务入队共用一个宽泛 catch：

```java
catch (Exception e) {
  log.warn("保存答案到数据库失败: {}", e.getMessage());
}
```

这意味着其中任一步失败后：

- 异常被吞掉，接口仍返回成功；
- Redis 中答案和索引已经推进，不会回滚；
- catch 后面的同一 try 块步骤不会执行；
- 最后一题如果在前面的数据库或自适应步骤失败，最终评估任务可能根本没有入队；
- 前端仍会跳到历史页，以为面试已正常提交。

## 13. 当前实现边界

- Controller 应直接接收 `@Valid @RequestBody SubmitAnswerRequest`，当前 Map 强转缺少校验。
- 只检查索引范围，不检查是否等于当前题索引。
- Redis 先更新，多个数据库事务后更新，缺少一致性事务/补偿。
- 非最后一题提交没有把 Redis 会话状态从 `CREATED` 更新为 `IN_PROGRESS`。
- 宽泛 catch 会把持久化与入队失败伪装成 HTTP 成功。
- 最终评估是异步的；提交接口成功不代表报告已经生成。
- 页面当前忽略自适应评估字段。
