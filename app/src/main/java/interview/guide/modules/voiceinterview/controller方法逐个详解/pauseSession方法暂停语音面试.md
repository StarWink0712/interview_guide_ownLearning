# pauseSession 方法完整实现流程详解

> 接口路径：`PUT /api/voice-interview/sessions/{sessionId}/pause`  
> 所属类：`VoiceInterviewController`  
> 功能：暂停指定的语音面试会话，保存当前状态，记录暂停时间  
> 限流策略：无

---

## 一、前端交互触发点

### 触发场景

用户在语音面试页面点击"暂停"按钮。

- **界面元素**：面试页面顶部工具栏的"暂停"按钮
- **触发方式**：用户点击暂停 → 发送请求 → 界面显示"面试已暂停"

```typescript
// 前端 API 调用（voiceInterviewApi.ts）
await voiceInterviewApi.pauseSession(sessionId, { reason: "user_initiated" });
// 界面切换为"面试已暂停"状态
setInterviewStatus("PAUSED");
```

### API 层封装

```typescript
async pauseSession(sessionId: number, body: { reason?: string }): Promise<void> {
    return request.put(`/api/voice-interview/sessions/${sessionId}/pause`, body);
}
```

---

## 二、后端处理全链路

### 2.1 路由映射

```
PUT /api/voice-interview/sessions/{sessionId}/pause
  │
  ▼
VoiceInterviewController.pauseSession(sessionId, request)
```

### 2.2 Controller 层

```java
@PutMapping("/sessions/{sessionId}/pause")
public Result<Void> pauseSession(
    @PathVariable Long sessionId,
    @RequestBody Map<String, String> request
) {
    log.info("Pausing session: {}", sessionId);
    String reason = request.getOrDefault("reason", "user_initiated");
    voiceInterviewService.pauseSession(sessionId.toString(), reason);
    return Result.success();
}
```

**注意**：
- 请求体使用原始 `Map<String, String>` 而非 DTO 类
- `reason` 有默认值 `"user_initiated"`，也可传 `"timeout"` 等值

### 2.3 Service 层 — pauseSession()

**文件**：`VoiceInterviewService.java` 第 351-371 行

```java
@Transactional
public void pauseSession(String sessionId, String reason) {
    Long sessionIdLong = parseSessionId(sessionId);

    // 1. 查询会话
    VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));

    // 2. 状态校验：仅 IN_PROGRESS 可暂停
    if (session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "会话状态为 " + session.getStatus() + "，无法暂停");
    }

    // 3. 更新状态
    session.setStatus(VoiceInterviewSessionStatus.PAUSED);
    session.setPausedAt(LocalDateTime.now());

    // 4. 保存
    sessionRepository.save(session);
    invalidateSessionCache(sessionIdLong);  // 清除缓存，下次读取时从 DB 获取最新状态

    log.info("Session {} paused, reason: {}", sessionId, reason);
}
```

**关键流程**：

1. **状态校验**：只有 `IN_PROGRESS` 状态的会话才能暂停
2. **状态变更**：`IN_PROGRESS` → `PAUSED`
3. **记录暂停时间**：`pausedAt = LocalDateTime.now()`
4. **清除缓存**：暂停后清除 Redis 缓存，确保下次读取获取最新状态

---

## 三、返回 JSON 示例

```json
{
    "code": 200,
    "message": "success",
    "data": null
}
```

### 异常返回

**会话状态不允许暂停**：
```json
{
    "code": 400,
    "message": "会话状态为 COMPLETED，无法暂停",
    "data": null
}
```

**会话不存在**：
```json
{
    "code": 404,
    "message": "会话不存在: 999",
    "data": null
}
```

---

## 四、代码模块调用关系

```
PUT /api/voice-interview/sessions/{id}/pause
  │ { "reason": "user_initiated" }
  ▼
VoiceInterviewController.pauseSession(sessionId, request)
  └── voiceInterviewService.pauseSession(sessionId.toString(), reason)
      ├── parseSessionId() → Long
      ├── sessionRepository.findById() → 查询实体
      ├── [不存在] → BusinessException(ErrorCode.NOT_FOUND)
      ├── [状态 != IN_PROGRESS] → BusinessException(ErrorCode.BAD_REQUEST)
      ├── 设置 status=PAUSED, pausedAt=now
      ├── sessionRepository.save() → DB 持久化
      └── invalidateSessionCache() → 清除 Redis 缓存

  Result.success()
  ▼ JSON { code: 200, data: null }
```

---

## 五、异常处理机制

| 场景 | 错误码 | 处理方式 |
|------|--------|---------|
| 会话不存在 | 404 (NOT_FOUND) | `BusinessException(ErrorCode.NOT_FOUND)` |
| 状态不是 IN_PROGRESS | 400 (BAD_REQUEST) | `BusinessException(ErrorCode.BAD_REQUEST)`，告知当前状态 |
| 数据库保存失败 | 内部错误 | `@Transactional` 回滚，异常向上抛出 |

---

## 六、流程总结

`pauseSession` 是语音面试的**暂停接口**。流程：查询会话（不存在抛 404）→ 校验状态必须为 IN_PROGRESS（否则抛 400 并说明当前状态）→ 设置状态为 PAUSED、记录暂停时间 → 保存到 DB → 清除 Redis 缓存 → 返回成功。

暂停后 Redis 缓存被清除，下一次读取会话时会从 DB 获取最新状态（含 PAUSED 状态和暂停时间）。恢复面试时调用 `resumeSession` 接口。
