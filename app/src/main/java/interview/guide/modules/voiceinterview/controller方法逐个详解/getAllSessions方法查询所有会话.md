# getAllSessions 方法完整实现流程详解

> 接口路径：`GET /api/voice-interview/sessions`  
> 所属类：`VoiceInterviewController`  
> 功能：查询用户的所有语音面试会话，支持按状态过滤，返回会话元信息列表  
> 限流策略：无

---

## 一、前端交互触发点

### 触发场景

用户在语音面试历史记录页面查看所有已完成的面试会话。

- **界面元素**：语音面试历史列表页
- **触发方式**：页面加载时自动调用，支持下拉刷新

```typescript
// 前端 API 调用（voiceInterviewApi.ts）
const sessions = await voiceInterviewApi.getAllSessions({ 
    userId: "default",
    status: "COMPLETED" 
});
// 渲染历史列表
renderSessionList(sessions);
```

### API 层封装

```typescript
async getAllSessions(params?: { userId?: string; status?: string }): Promise<SessionMetaDTO[]> {
    return request.get('/api/voice-interview/sessions', { params });
}
```

---

## 二、后端处理全链路

### 2.1 路由映射

```
GET /api/voice-interview/sessions?userId=xxx&status=xxx
  │
  ▼
VoiceInterviewController.getAllSessions(userId, status)
```

### 2.2 Controller 层

```java
@GetMapping("/sessions")
public Result<List<SessionMetaDTO>> getAllSessions(
    @RequestParam(required = false) String userId,
    @RequestParam(required = false) String status
) {
    log.info("Getting sessions for user: {}, status: {}", userId, status);
    List<SessionMetaDTO> sessions = voiceInterviewService.getAllSessions(userId, status);
    return Result.success(sessions);
}
```

查询参数均可选：
- `userId`：为空时默认查询 `"default"` 用户
- `status`：为空时不过滤，返回所有状态的会话

### 2.3 Service 层 — getAllSessions()

**文件**：`VoiceInterviewService.java` 第 411-437 行

```java
public List<SessionMetaDTO> getAllSessions(String userId, String status) {
    // 1. 默认用户处理
    userId = userId != null ? userId : DEFAULT_USER_ID;  // DEFAULT_USER_ID = "default"

    // 2. 根据 status 参数选择查询方式
    List<VoiceInterviewSessionEntity> sessions;
    if (status != null && !status.isEmpty()) {
        VoiceInterviewSessionStatus statusEnum =
            VoiceInterviewSessionStatus.valueOf(status.toUpperCase());
        sessions = sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, statusEnum);
    } else {
        sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    // 3. 转换为 DTO 列表
    return sessions.stream()
        .map(session -> SessionMetaDTO.builder()
            .sessionId(session.getId())
            .roleType(session.getRoleType())
            .status(session.getStatus().name())
            .currentPhase(session.getCurrentPhase().name())
            .createdAt(session.getCreatedAt())
            .updatedAt(session.getUpdatedAt())
            .actualDuration(session.getActualDuration())
            .messageCount(messageRepository.countBySessionId(session.getId()))  // ★ 实时统计消息数
            .evaluateStatus(session.getEvaluateStatus() != null
                ? session.getEvaluateStatus().name() : null)
            .evaluateError(session.getEvaluateError())
            .build())
        .collect(Collectors.toList());
}
```

**按更新时间倒序**排列，最近操作的会话排前面。

### 2.4 特殊处理 — 消息数实时统计

`messageCount` 不是 SessionEntity 的持久化字段，而是每次查询时通过 `messageRepository.countBySessionId(session.getId())` 实时统计，确保返回值与实际消息数量一致。

---

## 三、数据模型说明

### 3.1 SessionMetaDTO（响应体）

```java
@Data @Builder
public class SessionMetaDTO {
    private Long sessionId;          // 会话 ID
    private String roleType;         // 面试模板
    private String status;           // IN_PROGRESS / PAUSED / COMPLETED
    private String currentPhase;     // INTRO / TECH / PROJECT / HR / COMPLETED
    private LocalDateTime createdAt; // 创建时间
    private LocalDateTime updatedAt; // 更新时间
    private Integer actualDuration;  // 实际时长（秒）
    private Long messageCount;       // 消息数量
    private String evaluateStatus;   // 评估状态（PENDING/PROCESSING/COMPLETED/FAILED）
    private String evaluateError;    // 评估失败时的错误信息
}
```

### 3.2 实际返回 JSON 示例

```json
{
    "code": 200,
    "message": "success",
    "data": [
        {
            "sessionId": 42,
            "roleType": "java-backend",
            "status": "COMPLETED",
            "currentPhase": "COMPLETED",
            "createdAt": "2026-06-18T10:30:00",
            "updatedAt": "2026-06-18T10:45:00",
            "actualDuration": 900,
            "messageCount": 24,
            "evaluateStatus": "COMPLETED",
            "evaluateError": null
        },
        {
            "sessionId": 41,
            "roleType": "python-backend",
            "status": "IN_PROGRESS",
            "currentPhase": "TECH",
            "createdAt": "2026-06-18T09:00:00",
            "updatedAt": "2026-06-18T09:30:00",
            "actualDuration": null,
            "messageCount": 12,
            "evaluateStatus": null,
            "evaluateError": null
        }
    ]
}
```

### 状态过滤支持的值

status 参数支持的值（对应 `VoiceInterviewSessionStatus` 枚举）：

| 值 | 含义 |
|----|------|
| `IN_PROGRESS` | 面试进行中 |
| `PAUSED` | 面试已暂停 |
| `COMPLETED` | 面试已结束 |

---

## 四、代码模块调用关系

```
GET /api/voice-interview/sessions?userId=default&status=COMPLETED
  │
  ▼
VoiceInterviewController.getAllSessions(userId, status)
  └── voiceInterviewService.getAllSessions(userId, status)
      ├── userId 为空 → 默认 "default"
      ├── status 有值 → sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc()
      ├── status 为空 → sessionRepository.findByUserIdOrderByUpdatedAtDesc()
      └── 逐条转换为 SessionMetaDTO
          └── messageRepository.countBySessionId() → 实时统计消息数

  Result.success(sessions)
  ▼ JSON { code: 200, data: [...] }
```

---

## 五、异常处理机制

| 场景 | 处理方式 |
|------|---------|
| status 参数值非法 | `VoiceInterviewSessionStatus.valueOf()` 抛 `IllegalArgumentException`，全局异常处理器处理 |
| 数据库查询失败 | 异常向上抛出 |
| 空结果 | 返回空列表 `[]`，不抛异常 |

---

## 六、流程总结

`getAllSessions` 是语音面试的**列表查询接口**。接收可选的 `userId`（默认 "default"）和 `status` 过滤参数 → 按更新时间倒序查询数据库 → 逐条转换为 `SessionMetaDTO`（其中 `messageCount` 通过 `countBySessionId` 实时统计）→ 返回列表。当前版本只支持单用户（userId 固定为 "default"），无分页参数。
