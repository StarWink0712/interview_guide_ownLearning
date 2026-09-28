# resumeSession 方法完整实现流程详解

> 接口路径：`PUT /api/voice-interview/sessions/{sessionId}/resume`  
> 所属类：`VoiceInterviewController`  
> 功能：恢复已暂停的语音面试会话，返回更新后的会话信息（含 WebSocket URL）  
> 限流策略：无

---

## 一、前端交互触发点

### 触发场景

用户在暂停界面点击"继续面试"按钮。

- **界面元素**：暂停界面中的"继续面试"按钮
- **触发方式**：用户点击 → 调用 API → 获取新的 WebSocket URL → 重新建立连接

```typescript
// 前端 API 调用（voiceInterviewApi.ts）
const session = await voiceInterviewApi.resumeSession(sessionId);
// 重新建立 WebSocket 连接
const ws = new WebSocket(session.webSocketUrl);
// 界面恢复为面试进行中
setInterviewStatus("IN_PROGRESS");
```

### API 层封装

```typescript
async resumeSession(sessionId: number): Promise<SessionResponseDTO> {
    return request.put(`/api/voice-interview/sessions/${sessionId}/resume`);
}
```

---

## 二、后端处理全链路

### 2.1 路由映射

```
PUT /api/voice-interview/sessions/{sessionId}/resume
  │
  ▼
VoiceInterviewController.resumeSession(sessionId)
```

### 2.2 Controller 层

```java
@PutMapping("/sessions/{sessionId}/resume")
public Result<SessionResponseDTO> resumeSession(@PathVariable Long sessionId) {
    log.info("Resuming session: {}", sessionId);
    SessionResponseDTO session = voiceInterviewService.resumeSession(sessionId.toString());
    return Result.success(session);
}
```

### 2.3 Service 层 — resumeSession()

**文件**：`VoiceInterviewService.java` 第 379-402 行

```java
@Transactional
public SessionResponseDTO resumeSession(String sessionId) {
    Long sessionIdLong = parseSessionId(sessionId);

    // 1. 查询会话
    VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));

    // 2. 状态校验：仅 PAUSED 可恢复
    if (session.getStatus() != VoiceInterviewSessionStatus.PAUSED) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "会话状态为 " + session.getStatus() + "，无法恢复");
    }

    // 3. 更新状态
    session.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
    session.setResumedAt(LocalDateTime.now());

    // 4. 保存并回写缓存
    VoiceInterviewSessionEntity saved = sessionRepository.save(session);
    cacheSession(saved);  // 重新写入 Redis 缓存（1 小时 TTL）

    log.info("Session {} resumed with {} messages in conversation history",
        sessionId, messageRepository.countBySessionId(sessionIdLong));

    return buildSessionResponse(saved);
}
```

**关键流程**：

1. **状态校验**：只有 `PAUSED` 状态的会话才能恢复
2. **状态变更**：`PAUSED` → `IN_PROGRESS`
3. **记录恢复时间**：`resumedAt = LocalDateTime.now()`
4. **重新缓存**：恢复后重新写入 Redis 缓存（`cacheSession(saved)`）
5. **返回 DTO**：包含新的 WebSocket URL（前端需要重新建立连接）

### 2.4 缓存写入 — cacheSession()

```java
private void cacheSession(VoiceInterviewSessionEntity session) {
    String cacheKey = "voice:interview:session:" + session.getId();
    RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
    bucket.set(session, Duration.ofHours(CACHE_TTL_HOURS));  // 1 小时 TTL
}
```

---

## 三、返回 JSON 示例

**成功**：
```json
{
    "code": 200,
    "message": "success",
    "data": {
        "sessionId": 42,
        "roleType": "java-backend",
        "currentPhase": "TECH",
        "status": "IN_PROGRESS",
        "startTime": "2026-06-18T10:30:00",
        "plannedDuration": 30,
        "webSocketUrl": "ws://localhost:8080/ws/voice-interview/42"
    }
}
```

**状态不允许恢复**：
```json
{
    "code": 400,
    "message": "会话状态为 IN_PROGRESS，无法恢复",
    "data": null
}
```

---

## 四、代码模块调用关系

```
PUT /api/voice-interview/sessions/{id}/resume
  │
  ▼
VoiceInterviewController.resumeSession(sessionId)
  └── voiceInterviewService.resumeSession(sessionId.toString())
      ├── parseSessionId() → Long
      ├── sessionRepository.findById() → 查询实体
      ├── [不存在] → BusinessException(ErrorCode.NOT_FOUND)
      ├── [状态 != PAUSED] → BusinessException(ErrorCode.BAD_REQUEST)
      ├── 设置 status=IN_PROGRESS, resumedAt=now
      ├── sessionRepository.save() → DB 持久化
      ├── cacheSession(saved) → Redis 缓存（1h TTL）
      └── buildSessionResponse(saved) → 构建 DTO（含 WebSocket URL）

  Result.success(session)
  ▼ JSON { code: 200, data: {...} }
```

---

## 五、异常处理机制

| 场景 | 错误码 | 处理方式 |
|------|--------|---------|
| 会话不存在 | 404 (NOT_FOUND) | `BusinessException(ErrorCode.NOT_FOUND)` |
| 状态不是 PAUSED | 400 (BAD_REQUEST) | `BusinessException(ErrorCode.BAD_REQUEST)`，告知当前状态 |
| 数据库保存失败 | 内部错误 | `@Transactional` 回滚，异常向上抛出 |

---

## 六、与 pauseSession 的协同关系

| 操作 | 状态变化 | 缓存处理 | 返回值 |
|------|---------|---------|--------|
| `pauseSession` | IN_PROGRESS → PAUSED | 清除缓存 | `Result<Void>` |
| `resumeSession` | PAUSED → IN_PROGRESS | 重新写入缓存 | `Result<SessionResponseDTO>` |

`pauseSession` 清除缓存后，下一次 `getSession` 从 DB 读取 PAUSED 状态；`resumeSession` 恢复状态后重新写入缓存，后续 `getSession` 直接从缓存命中。

---

## 七、流程总结

`resumeSession` 是语音面试的**恢复接口**，与 `pauseSession` 配对使用。流程：查询会话（不存在抛 404）→ 校验状态必须为 PAUSED（否则抛 400 说明当前状态）→ 设置状态为 IN_PROGRESS、记录恢复时间 → 保存到 DB → **重新写入 Redis 缓存**（1 小时 TTL）→ 返回包含新 WebSocket URL 的 `SessionResponseDTO`。前端收到后重新建立 WebSocket 连接继续面试。
