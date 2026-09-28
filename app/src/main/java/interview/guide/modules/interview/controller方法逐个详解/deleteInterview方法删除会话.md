# deleteInterview：删除面试会话完整调用链

> 接口：`DELETE /api/interview/sessions/{sessionId}`  
> Controller：`InterviewController.deleteInterview`  
> 当前前端调用：面试记录页、简历详情页 `InterviewPanel`

## 1. 面试记录页触发

用户点击删除图标时先阻止表格行点击，然后打开 `DeleteConfirmDialog`。确认后：

```text
InterviewHistoryPage.handleDeleteConfirm
  → 判断文字/语音类型
  → 文字面试调用 historyApi.deleteInterview(sessionId)
  → DELETE /api/interview/sessions/{sessionId}
  → 成功后 await loadAll() 刷新列表
  → 关闭确认框
```

失败时 alert 后端 message 或通用文案。

## 2. 简历详情页触发

`InterviewPanel` 也先显示确认对话框，然后调用同一个 `historyApi.deleteInterview`。成功后通知
`ResumeDetailPage` 重新加载简历详情；如果正在查看被删除会话，则退回面试列表。

## 3. API 和 Controller

前端 API：

```ts
request.delete(`/api/interview/sessions/${sessionId}`)
```

Controller：

```java
@DeleteMapping("/api/interview/sessions/{sessionId}")
public Result<Void> deleteInterview(
    @PathVariable String sessionId
) {
  persistenceService.deleteSessionBySessionId(sessionId);
  return Result.success(null);
}
```

Controller 直接调用持久化 Service，没有经过 `InterviewSessionService`。

## 4. 数据库删除链

`InterviewPersistenceService.deleteSessionBySessionId` 有事务：

```text
InterviewSessionRepository.findBySessionId(sessionId)
  → 不存在：抛 BusinessException(INTERVIEW_SESSION_NOT_FOUND)
  → 存在：sessionRepository.delete(entity)
```

`InterviewSessionEntity.answers` 配置：

```text
@OneToMany(
  mappedBy="session",
  cascade=ALL,
  orphanRemoval=true
)
```

所以删除会话时，关联的 `InterviewAnswerEntity` 会通过 JPA 级联删除，不需要 Controller 循环
删除答案。

## 5. 成功与失败响应

成功：

```text
事务提交
  → Result.success(null)
  → Axios 拦截器提取 null
  → 前端重新加载列表/简历详情
```

不存在：

```text
BusinessException(INTERVIEW_SESSION_NOT_FOUND)
  → GlobalExceptionHandler
  → HTTP 200 + Result.error
  → Axios reject Error(message)
  → 页面 alert
```

## 6. Redis 缓存没有删除

当前调用链只删除 PostgreSQL。虽然 `InterviewSessionCache` 提供：

```java
deleteSession(sessionId)
```

但 `deleteInterview` 没有调用它。因此：

```text
数据库会话已删除
  → listSessions 不再显示
  → Redis CachedSession 可能仍保留最长 24 小时
  → getSession(sessionId) 缓存命中时仍可能返回会话
  → submitAnswer/saveAnswer 也可能先更新这个缓存
  → 后续数据库保存失败却被部分流程吞掉
```

这使“删除”在数据库层完成，但在会话缓存层不是立即完成。

## 7. 活跃异步评估任务没有删除

删除接口也不会取消 Redis Stream 中的评估消息或统一异步任务。如果 Consumer 随后处理：

```text
EvaluateStreamConsumer.processBusiness
  → sessionRepository.findBySessionIdWithResume 返回 empty
  → 抛 AsyncTaskCancelledException
  → 统一异步任务标记 CANCELLED
  → ACK Stream 消息
```

这条异步清理依赖 Consumer 最终拉取消息，而不是删除接口主动操作。
如果 Consumer 在删除前已经加载了会话并开始 LLM 评估，删除接口也不会中断这次执行；后续报告
保存会因为会话不存在而跳过，但统一异步任务仍可能走到完成状态。

## 8. 当前实现边界

- 数据库会话和答案能在一个事务内级联删除。
- Redis 会话缓存和简历→会话映射没有同步清理，删除后可能继续恢复旧会话。
- 活跃评估消息不主动取消，只能等待 Consumer 检测数据库实体不存在。
- 已经开始运行的评估不会被删除操作强制中断。
- Controller 直接依赖 PersistenceService；若要形成完整删除闭环，应由业务 Service 同时编排
  数据库、缓存和异步任务状态。
- 没有限流或权限校验，知道 sessionId 的调用方即可请求删除。
