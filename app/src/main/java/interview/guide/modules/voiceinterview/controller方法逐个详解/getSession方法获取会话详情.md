# getSession 方法完整实现流程详解

> 接口路径：`GET /api/voice-interview/sessions/{sessionId}`  
> 所属类：`VoiceInterviewController`  
> 功能：根据会话 ID 获取语音面试会话的详细信息，用于查看会话状态或恢复面试  
> 限流策略：无

---

## 一、前端交互触发点

### 触发场景

前端在以下场景调用此接口：
- 用户从历史记录页点击某条记录，查看会话详情
- 实时面试页面刷新后需要恢复当前会话状态
- 面试中需要获取 WebSocket 连接状态时补充查询

```typescript
// 前端 API 调用（voiceInterviewApi.ts）
const session = await voiceInterviewApi.getSession(sessionId);
// 根据 currentPhase 恢复 UI 状态
updatePhaseIndicator(session.currentPhase);
```

### API 层封装

无特殊超时配置，普通 HTTP GET 请求。

---

## 二、后端处理全链路

### 2.1 路由映射

```
GET /api/voice-interview/sessions/{sessionId}
  │
  ▼
VoiceInterviewController.getSession(sessionId)
```

### 2.2 Controller 层

```java
@GetMapping("/sessions/{sessionId}")
public Result<SessionResponseDTO> getSession(@PathVariable Long sessionId) {
    log.info("Getting session details for: {}", sessionId);
    SessionResponseDTO session = voiceInterviewService.getSessionDTO(sessionId);
    if (session == null) {
        return Result.error("Session not found: " + sessionId);
    }
    return Result.success(session);
}
```

**注意**：`sessionId` 参数类型为 `Long`（数据库自增 ID），与文本面试的 16 位 UUID 字符串不同。

### 2.3 Service 层 — 两级缓存查询

**文件**：`VoiceInterviewService.java` 第 210-237 行

```java
public VoiceInterviewSessionEntity getSession(Long sessionId) {
    if (sessionId == null) {
        return null;
    }

    // L1: 查询 Redis 缓存
    String cacheKey = "voice:interview:session:" + sessionId;
    RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
    VoiceInterviewSessionEntity cached = bucket.get();

    if (cached != null) {
        log.debug("Session {} found in cache", sessionId);
        return cached;
    }

    // L2: 缓存未命中，查询数据库（不写回缓存）
    return sessionRepository.findById(sessionId).orElse(null);
}
```

**两级查询策略**：

| 层级 | 存储介质 | Key 格式 | 过期策略 | 行为 |
|------|---------|---------|---------|------|
| L1 缓存 | Redis | `voice:interview:session:{id}` | 1h TTL | 命中直接返回 |
| L2 持久化 | PostgreSQL | `voice_interview_sessions` 表主键 | 不自动过期 | 未命中返回 null |

与文本面试 `getSession` 不同：**语音面试缓存未命中时不会自动回写 Redis**（只在 createSession/resumeSession 时写入缓存）。

### 2.4 DTO 构建 — getSessionDTO()

```java
public SessionResponseDTO getSessionDTO(Long sessionId) {
    VoiceInterviewSessionEntity session = getSession(sessionId);
    if (session == null) {
        return null;
    }
    return buildSessionResponse(session);
}
```

`buildSessionResponse()` 返回的 DTO 包含 WebSocket URL，但缓存过期的旧会话不应再尝试 WebSocket 连接。

---

## 三、数据模型说明

### 3.1 返回值 SessionResponseDTO

同 `createSession` 返回结构一致，包含 `sessionId`、`currentPhase`、`status`、`webSocketUrl` 等字段。

### 3.2 实际返回 JSON 示例

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

**会话不存在**：
```json
{
    "code": 0,
    "message": "Session not found: 999",
    "data": null
}
```

---

## 四、代码模块调用关系

```
GET /api/voice-interview/sessions/{sessionId}
  │
  ▼
VoiceInterviewController.getSession(sessionId)
  └── voiceInterviewService.getSessionDTO(sessionId)
      └── getSession(sessionId)
          ├── [L1] Redis GET "voice:interview:session:{id}"
          │   └── 命中 → 返回实体
          └── [L2] sessionRepository.findById(sessionId)
              └── 返回实体或 null

  │  null 时 Controller 返回 Result.error(...)
  │  非 null 时 buildSessionResponse() → Result.success(...)
  ▼ JSON
```

---

## 五、异常处理机制

| 场景 | 触发条件 | 处理方式 |
|------|---------|---------|
| **会话不存在** | Redis 未命中 + DB 中也无记录 | Controller 层返回 `Result.error("Session not found: ...")`（注意非 `BusinessException`） |
| **Redis 不可达** | Redis 连接失败 | `bucket.get()` 可能抛异常，向上传播 |
| **数据库不可达** | PostgreSQL 连接失败 | `findById` 抛异常，全局异常处理器处理 |

---

## 六、流程总结

`getSession` 是一个**纯读接口**，用于获取语音面试会话详情。先查 Redis 缓存（`voice:interview:session:{id}`，1 小时 TTL），命中则直接返回；未命中则查数据库 → Controller 层判断 null → 返回 `Result.error()`。与文本面试的 `getSession` 不同之处在于：**缓存未命中不自动回写**（语音面试缓存由 createSession/resumeSession 主动写入），且 sessionId 为 Long 类型（数据库自增 ID）而非 UUID 字符串。
