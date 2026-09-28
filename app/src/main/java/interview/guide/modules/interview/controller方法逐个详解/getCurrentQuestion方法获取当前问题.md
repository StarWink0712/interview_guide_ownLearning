# getCurrentQuestion：获取当前题目完整调用链

> 接口：`GET /api/interview/sessions/{sessionId}/question`  
> Controller：`InterviewController.getCurrentQuestion`  
> 当前前端状态：`interviewApi.getCurrentQuestion` 已定义，但没有页面调用

## 1. 先说明真实使用情况

当前 `InterviewPage` 不通过这个接口取题：

- 创建会话时，`createSession` 已返回完整 `questions`；
- 恢复会话时，`getSession` 也返回完整 `questions`；
- 提交答案时，`submitAnswer` 直接返回 `nextQuestion`。

页面的当前题来自上述响应中的本地对象。`frontend/src/api/interview.ts` 虽然封装了
`getCurrentQuestion(sessionId)`，但代码库里没有调用点。

## 2. 如果客户端调用，前端 API 做什么

```ts
interviewApi.getCurrentQuestion(sessionId)
  → request.get(`/api/interview/sessions/${sessionId}/question`)
```

返回类型是：

```ts
interface CurrentQuestionResponse {
  completed: boolean;
  question?: InterviewQuestion;
  message?: string;
}
```

## 3. Controller 层

```java
@GetMapping("/api/interview/sessions/{sessionId}/question")
public Result<Map<String, Object>> getCurrentQuestion(
    @PathVariable String sessionId
) {
  return Result.success(
      sessionService.getCurrentQuestionResponse(sessionId));
}
```

Controller 只绑定 `sessionId`，把响应结构组装委托给 Service。

## 4. Service 第一层：组装接口响应

`getCurrentQuestionResponse` 先调用：

```java
InterviewQuestionDTO question = getCurrentQuestion(sessionId);
```

然后有两个返回分支：

```text
question != null
  → { completed: false, question: InterviewQuestionDTO }

question == null
  → { completed: true, message: "所有问题已回答完毕" }
```

使用的是 `Map.of`，因此响应没有固定 Java Response record，字段正确性依赖字符串常量。

## 5. Service 第二层：获取或恢复会话

`getCurrentQuestion` 调用 `getOrRestoreSession(sessionId)`：

```text
sessionCache.getSession(sessionId)
  ├─ 命中 → refreshSessionTTL(sessionId) → 返回 CachedSession
  └─ 未命中
      → restoreSessionFromDatabase
      → findBySessionId
      → 反序列化题目并合并答案
      → 回填 Redis
```

无法恢复时抛出 `BusinessException(INTERVIEW_SESSION_NOT_FOUND)`。

## 6. 判断是否已经答完

Service 反序列化缓存中的题目，然后比较：

```java
session.getCurrentIndex() >= questions.size()
```

成立时返回 `null`，由上一层转换为 `completed=true`。此方法不会在完成分支再次投递评估任务；
最终评估由最后一次 `submitAnswer` 或 `completeInterview` 负责。

## 7. 首次取题会改变会话状态

如果缓存状态是 `CREATED`，这个看似查询的 GET 接口会执行写操作：

```text
CachedSession.status = IN_PROGRESS
  → sessionCache.updateSessionStatus(sessionId, IN_PROGRESS)
  → persistenceService.updateSessionStatus(sessionId, IN_PROGRESS)
  → InterviewSessionRepository.save
```

数据库更新放在 `try/catch` 中，失败只记录警告，不影响题目返回。因此可能出现 Redis 已是
`IN_PROGRESS`，数据库仍是 `CREATED`。

最后返回：

```java
questions.get(session.getCurrentIndex())
```

## 8. HTTP 返回闭环

有当前题时：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "completed": false,
    "question": {
      "questionIndex": 0,
      "question": "...",
      "category": "..."
    }
  }
}
```

全部完成时：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "completed": true,
    "message": "所有问题已回答完毕"
  }
}
```

Axios 拦截器会提取 `data`；但当前没有页面消费这个结果。

## 9. 当前实现边界

- 这是 GET 接口，却可能把状态从 `CREATED` 改成 `IN_PROGRESS`，不具备纯查询语义。
- Controller 返回 `Map<String, Object>`，缺少编译期固定响应模型。
- 数据库状态更新失败被吞掉，Redis 和 PostgreSQL 可能暂时不一致。
- 当前前端完全没有调用它；把它描述成“页面每题都会请求”是不准确的。
