# saveAnswer：暂存答案但不推进题目完整调用链

> 接口：`PUT /api/interview/sessions/{sessionId}/answers`  
> Controller：`InterviewController.saveAnswer`  
> 当前前端状态：`interviewApi.saveAnswer` 已定义，但没有页面调用

## 1. 与 submitAnswer 的区别

`saveAnswer` 只保存当前输入：

- 不修改 `currentQuestionIndex`；
- 不返回下一题；
- 不执行单轮自适应评估；
- 不把会话标记为完成；
- 不投递最终评估任务。

当前 `InterviewPage` 没有自动保存定时器，也没有调用 `interviewApi.saveAnswer`，所以用户未正式
提交就离开页面时，输入框内容不会通过此接口保存。

## 2. 如果调用，前端请求链

```ts
interviewApi.saveAnswer({ sessionId, questionIndex, answer })
  → request.put(`/api/interview/sessions/${sessionId}/answers`, {
      questionIndex,
      answer,
    })
```

成功返回类型为 `void`。

## 3. Controller 层

Controller 接收 `Map<String, Object>`，强制转换两个字段，再手工创建请求 record：

```text
body["questionIndex"] → Integer
body["answer"] → String
路径 sessionId + 两个字段
  → new SubmitAnswerRequest(...)
  → sessionService.saveAnswer(request)
  → Result.success(null)
```

方法没有 `@Valid`，因此 `SubmitAnswerRequest` 的校验注解不生效；缺字段或类型错误会进入全局
异常处理，而不是得到规范的字段校验错误。

## 4. Service：获取会话并校验索引

`InterviewSessionService.saveAnswer`：

```text
getOrRestoreSession(sessionId)
  → Redis 命中并刷新 TTL
  → 或从数据库恢复并回填 Redis
  → 反序列化 questions
  → 检查 0 <= questionIndex < questions.size
```

索引非法时抛出 `BusinessException(INTERVIEW_QUESTION_NOT_FOUND)`。

## 5. Service：更新 Redis

合法时执行：

```text
question.withAnswer(answer)
  → 替换 questions[index]
  → sessionCache.updateQuestions(sessionId, questions)
```

如果会话原状态为 `CREATED`，还会调用：

```java
sessionCache.updateSessionStatus(sessionId, IN_PROGRESS)
```

当前索引保持不变，所以重新获取会话时仍停在同一道题，但该题带有已暂存答案。

## 6. Service：尝试写数据库

数据库部分放在 try/catch 中：

```text
persistenceService.saveAnswer
  → 按 sessionId + questionIndex 查询
  → 已有则覆盖，没有则新建
  → 保存 question/category/userAnswer，score=0

persistenceService.updateSessionStatus(sessionId, IN_PROGRESS)
```

数据库失败时只记录警告，不向 Controller 抛出。Redis 暂存已经完成，因此接口仍返回成功。

## 7. 返回闭环

```text
Service 正常结束
  → Controller Result.success(null)
  → HTTP 200 + { code: 200, message: "success", data: null }
  → Axios 拦截器提取 null
  → Promise<void> resolve
```

当前没有页面接收这一结果。

## 8. 当前实现边界

- 前端 API 已封装，但当前没有任何调用者，注释中的“自动保存”尚未实现。
- Controller 使用 Map 强转且没有 `@Valid`。
- Redis 先写、数据库后写；数据库失败仍返回成功。
- 只检查索引范围，不检查是否为当前题。
- 暂存会把 `CREATED` 改为 `IN_PROGRESS`，但不会推进索引。
