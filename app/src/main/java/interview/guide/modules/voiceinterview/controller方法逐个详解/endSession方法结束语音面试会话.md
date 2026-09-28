# endSession 方法完整实现流程详解

> 接口路径：`POST /api/voice-interview/sessions/{sessionId}/end`  
> 所属类：`VoiceInterviewController`  
> 功能：结束指定的语音面试会话，设置结束时间、计算实际时长，并触发异步评估任务  
> 限流策略：无

---

## 一、前端交互触发点

### 触发场景

用户在语音面试页面点击"结束面试"按钮，或通过 WebSocket 发送 `end_interview` 控制消息。

- **触发方式 1**：前端页面"结束面试"按钮 → 调用 REST API
- **触发方式 2**：WebSocket 控制消息 → 由 `VoiceInterviewWebSocketHandler` 处理并调用 `voiceInterviewService.endSession()`

```typescript
// 前端 API 调用（voiceInterviewApi.ts）
await voiceInterviewApi.endSession(sessionId);
// 面试结束后，前端跳转到评估等待页面
navigate(`/voice-interview/${sessionId}/evaluation`);
```

### API 层封装

普通 POST 请求，无请求体，无超时配置。

---

## 二、后端处理全链路

### 2.1 路由映射

```
POST /api/voice-interview/sessions/{sessionId}/end
  │
  ▼
VoiceInterviewController.endSession(sessionId)
```

### 2.2 Controller 层

```java
@PostMapping("/sessions/{sessionId}/end")
public Result<Void> endSession(@PathVariable Long sessionId) {
    log.info("Ending session: {}", sessionId);
    voiceInterviewService.endSession(sessionId.toString());
    return Result.success();
}
```

**注意**：Controller 调用时将 `Long` 转为 `String`，因为 Service 的 `endSession(String)` 内部会再解析回 Long。这是历史兼容设计。

### 2.3 Service 层 — 完整结束流程

**文件**：`VoiceInterviewService.java` 第 171-183 行

```java
@Transactional
public void endSession(String sessionId) {
    Long sessionIdLong = parseSessionId(sessionId);
    VoiceInterviewSessionEntity session = getSession(sessionIdLong);

    if (session == null) {
        log.warn("Session not found: {}", sessionId);
        return;
    }

    endSession(session);                             // 内部结束方法
    voiceEvaluateStreamProducer.sendEvaluateTask(sessionId);  // 触发异步评估
}
```

### 2.4 内部结束方法 — endSession(VoiceInterviewSessionEntity)

```java
private void endSession(VoiceInterviewSessionEntity session) {
    session.setEndTime(LocalDateTime.now());
    session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
    session.setStatus(VoiceInterviewSessionStatus.COMPLETED);
    session.setActualDuration(
        (int) Duration.between(session.getStartTime(), LocalDateTime.now()).toSeconds()
    );
    session.setEvaluateStatus(AsyncTaskStatus.PENDING);  // 标记评估待处理

    sessionRepository.save(session);
    invalidateSessionCache(session.getId());  // 清除 Redis 缓存
}
```

### 2.5 异步评估触发 — sendEvaluateTask()

```java
voiceEvaluateStreamProducer.sendEvaluateTask(sessionId);
```

将评估任务发送到 Redis Stream，`VoiceEvaluateStreamConsumer` 后台异步消费并执行评估。任务入队后前端通过轮询 `GET /evaluation` 获取进度。

### 2.6 异常断连兜底 — endSessionIfInProgress()

```java
@Transactional
public void endSessionIfInProgress(String sessionId) {
    Long sessionIdLong = parseSessionId(sessionId);
    VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong).orElse(null);
    if (session == null || session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
        return;  // 已结束或已暂停，不重复操作
    }
    log.info("Auto-ending IN_PROGRESS session {} after WebSocket disconnect", sessionId);
    endSession(session);
}
```

`endSessionIfInProgress` 在 WebSocket 异常断开时被调用，**仅当会话仍处于 IN_PROGRESS 时**才执行结束操作，避免正常结束的会话（已设为 COMPLETED）被重复结束。

---

## 三、返回 JSON 示例

```json
{
    "code": 200,
    "message": "success",
    "data": null
}
```

返回值固定为 `Result.success()`，无额外数据。

---

## 四、代码模块调用关系

```
POST /api/voice-interview/sessions/{id}/end
  │ (或 WebSocket end_interview 控制消息 → VoiceInterviewWebSocketHandler)
  ▼
VoiceInterviewController.endSession(sessionId)
  └── voiceInterviewService.endSession(sessionId.toString())
      ├── parseSessionId(sessionId) → Long
      ├── getSession(sessionIdLong) → Redis/DB
      ├── [session == null] → warn 后 return（不抛异常）
      ├── endSession(session)
      │   ├── 设置 endTime / phase=COMPLETED / status=COMPLETED
      │   ├── actualDuration = Duration.between(startTime, now)
      │   ├── evaluateStatus = PENDING
      │   ├── sessionRepository.save() → DB 持久化
      │   └── invalidateSessionCache() → 清除 Redis 缓存
      └── voiceEvaluateStreamProducer.sendEvaluateTask(sessionId)
          └── Redis Stream XADD → VoiceEvaluateStreamConsumer 后台消费

  Result.success()
  ▼ JSON { code: 200, data: null }
```

---

## 五、异常处理机制

| 场景 | 处理方式 |
|------|---------|
| **会话不存在** | `log.warn` 后直接 `return`，**不抛异常** |
| **数据库保存失败** | `@Transactional` 回滚，异常向上抛出 |
| **Redis 缓存失效失败** | 不影响主流程 |
| **评估任务入队失败** | 异常向上抛出，评估可能在后续手动触发 |

---

## 六、流程总结

`endSession` 是语音面试的**结束接口**。用户点击结束或 WebSocket 断开 → Controller 委托 Service → 先查会话（不存在则静默返回）→ 设置结束时间、阶段为 COMPLETED、状态为 COMPLETED、计算实际时长（startTime 到 now 的秒数差）、评估状态为 PENDING → 保存到 DB → 清除 Redis 缓存 → **触发异步评估任务**（Redis Stream 入队，后台消费）→ 返回成功。

关键设计：
1. **双重触发**：REST API + WebSocket 断连兜底（`endSessionIfInProgress` 仅处理 IN_PROGRESS 状态）
2. **幂等保护**：`endSessionIfInProgress` 检查状态，避免重复结束
3. **异步评估**：评估不阻塞 HTTP 响应，前端通过轮询 `GET /evaluation` 获取结果
