# getSession：恢复面试会话完整调用链

> 接口：`GET /api/interview/sessions/{sessionId}`  
> Controller：`InterviewController.getSession`  
> 当前前端调用：`InterviewPage.resumeExistingSession`

## 1. 前端触发场景

用户在面试记录页点击“继续面试”时：

```text
InterviewHistoryPage 继续按钮
  → App.InterviewHistoryWrapper.handleContinueInterview
  → navigate('/interview', { state: { sessionIdToResume } })
  → InterviewWrapper 把 sessionIdToResume 传给 InterviewPage
  → InterviewPage.useEffect
  → resumeExistingSession(sessionId)
  → interviewApi.getSession(sessionId)
```

API 层调用：

```ts
request.get(`/api/interview/sessions/${sessionId}`)
```

当前页面只给未完成且尚未评估成功的文字面试显示“继续面试”按钮。

## 2. Controller 层

```java
@GetMapping("/api/interview/sessions/{sessionId}")
public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
  InterviewSessionDTO session = sessionService.getSession(sessionId);
  return Result.success(session);
}
```

Spring 从 URL 绑定字符串 `sessionId`。Controller 不判断状态、不直接访问 Redis/数据库，只调用
`InterviewSessionService.getSession` 并包装统一响应。

## 3. Service：先查 Redis

```java
Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
if (cachedOpt.isPresent()) {
  return toDTO(cachedOpt.get());
}
```

`InterviewSessionCache.getSession` 根据会话 Key 调用 `RedisService.get`。命中时直接把
`CachedSession` 交给 `toDTO`：

```text
CachedSession.questionsJson
  → ObjectMapper 反序列化 List<InterviewQuestionDTO>
  → InterviewSessionDTO(sessionId, resumeText, total, currentIndex, questions, status)
```

与 `getOrRestoreSession` 不同，公开的 `getSession` 在缓存命中时没有调用
`refreshSessionTTL`，因此单纯恢复查询不会主动续期 24 小时缓存。

## 4. Service：缓存未命中时从数据库恢复

缓存为空时调用：

```text
restoreSessionFromDatabase(sessionId)
  → persistenceService.findBySessionId(sessionId)
  → InterviewSessionRepository.findBySessionId(sessionId)
```

数据库有记录时进入 `restoreSessionFromEntity`：

1. `ObjectMapper.readValue(entity.questionsJson)` 恢复完整题单。
2. `persistenceService.findAnswersBySessionId` 查询已保存答案。
3. 按 `questionIndex` 将 `userAnswer` 合并回对应题目。
4. `convertStatus` 把 Entity 状态转换成 DTO 状态。
5. 调用 `sessionCache.saveSession` 回填 Redis。
6. 再次调用 `sessionCache.getSession` 取得缓存对象。
7. `toDTO` 返回给 Controller。

恢复后的当前题目索引来自数据库 `currentQuestionIndex`，所以即使 Redis 丢失，仍能从上次已成功
持久化的进度继续。

## 5. 恢复失败路径

以下情况会令 `restoreSessionFromDatabase` 返回 `null`：

- 数据库不存在该 `sessionId`；
- `questionsJson` 无法反序列化；
- 查询答案失败；
- 回填 Redis 失败；
- 恢复过程发生其他异常。

Service 随后抛出：

```java
new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND)
```

恢复异常会被统一表现成“会话不存在”，原始原因只记录在后端日志中。

## 6. 响应怎样回到面试页面

成功链路：

```text
InterviewSessionDTO
  → Result.success
  → HTTP 200 + { code: 200, data: ... }
  → Axios 响应拦截器提取 data
  → resumeExistingSession 得到 existingSession
  → initSession(existingSession)
```

`initSession`：

- 保存会话；
- 使用 `Math.min(currentQuestionIndex, questions.length - 1)` 选择当前题；
- 从第 0 题遍历到当前索引，重建 interviewer/user 消息历史；
- 如果当前题已有 `userAnswer`，把它恢复到输入框。

失败时前端显示固定文案“恢复面试失败，请重试”，不会展示后端具体错误消息。

## 7. 当前实现边界

- Redis 是优先数据源；缓存与数据库不一致时，缓存值会覆盖用户看到的数据库状态。
- 数据库恢复将所有已保存答案合并回题单，不只恢复当前题。
- `getSession` 缓存命中不刷新 TTL；答题相关方法使用的 `getOrRestoreSession` 才刷新。
- 删除面试接口只删除数据库，没有调用 `sessionCache.deleteSession`。若缓存仍存在，删除后最多
  24 小时内该接口仍可能从 Redis 返回“已删除”的会话，这是当前真实缺陷。
- 恢复过程把基础设施错误降级成 `INTERVIEW_SESSION_NOT_FOUND`，调用方无法区分真不存在和恢复失败。
