# createSession 方法完整实现流程详解

> 接口路径：`POST /api/voice-interview/sessions`  
> 所属类：`VoiceInterviewController`  
> 功能：创建一个新的语音面试会话，初始化阶段配置并返回会话信息（含 WebSocket 连接 URL）  
> 限流策略：无

---

## 一、前端交互触发点

### 触发场景

用户在语音面试配置页面完成面试模板、难度、阶段开关等参数配置后，点击"开始语音面试"按钮。

- **界面元素**：语音面试配置面板中的"开始面试"按钮
- **触发方式**：用户点击按钮，前端调用 API 创建会话，获取 WebSocket URL 后建立实时语音连接

```typescript
// 前端 API 调用（voiceInterviewApi.ts）
const session = await voiceInterviewApi.createSession({
  skillId: "java-backend",
  difficulty: "mid",
  introEnabled: true,
  techEnabled: true,
  projectEnabled: true,
  hrEnabled: true,
  plannedDuration: 30,
  llmProvider: "dashscope",
});
// 获取 WebSocket URL 并建立连接
const ws = new WebSocket(session.webSocketUrl);
```

### API 层封装

请求体为 `CreateSessionRequest`，超时通常为 30 秒（无 AI 调用，仅写数据库）。

---

## 二、后端处理全链路

### 2.1 路由映射

```
POST /api/voice-interview/sessions
  │
  │ [@Valid 触发 Jakarta Validation 参数校验]
  │
  ▼
VoiceInterviewController.createSession(request)
```

无 @RateLimit 注解。参数校验由 `@Valid` + `@RequestBody` 自动完成。

### 2.2 Controller 层

```java
@PostMapping("/sessions")
public Result<SessionResponseDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
    log.info("Creating voice interview session for role: {}", request.getRoleType());
    SessionResponseDTO session = voiceInterviewService.createSession(request);
    return Result.success(session);
}
```

**依赖注入**（构造器注入）：

| 字段 | 类型 | 作用 |
|------|------|------|
| `voiceInterviewService` | `VoiceInterviewService` | 会话生命周期管理 |
| `evaluationService` | `VoiceInterviewEvaluationService` | 在此方法中未使用 |
| `voiceEvaluateStreamProducer` | `VoiceEvaluateStreamProducer` | 在此方法中未使用 |

**职责**：纯路由委托，无业务逻辑。

### 2.3 Service 层 — VoiceInterviewService.createSession()

**文件**：`modules/voiceinterview/service/VoiceInterviewService.java` 第 110-143 行

```java
@Transactional
public SessionResponseDTO createSession(CreateSessionRequest request) {
    // ===== 步骤1: 处理默认值 =====
    String effectiveSkillId = request.getSkillId() != null
        ? request.getSkillId() : InterviewDefaults.SKILL_ID;
    String effectiveLlmProvider = (request.getLlmProvider() != null
        && !request.getLlmProvider().isBlank())
        ? request.getLlmProvider() : null;  // null 时由下游取默认 Provider

    // ===== 步骤2: 创建会话实体 =====
    VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .userId(DEFAULT_USER_ID)       // 固定 "default"
            .roleType(effectiveSkillId)    // 模板 ID
            .skillId(effectiveSkillId)     // 面试技能 ID
            .difficulty(...)               // junior/mid/senior
            .customJdText(request.getCustomJdText())
            .resumeId(request.getResumeId())
            .introEnabled(request.getIntroEnabled())
            .techEnabled(request.getTechEnabled())
            .projectEnabled(request.getProjectEnabled())
            .hrEnabled(request.getHrEnabled())
            .llmProvider(effectiveLlmProvider)
            .plannedDuration(request.getPlannedDuration())
            .currentPhase(determineFirstPhase(request))  // 确定第一个阶段
            .build();

    // ===== 步骤3: 保存会话 =====
    VoiceInterviewSessionEntity saved = sessionRepository.save(session);  // DB 持久化
    cacheSession(saved);  // Redis 缓存（1 小时 TTL）

    log.info("Created voice interview session: {} with template: {}, phase: {}",
            saved.getId(), effectiveSkillId, saved.getCurrentPhase());

    return buildSessionResponse(saved);
}
```

### 2.4 首次阶段确定 — determineFirstPhase()

```java
private VoiceInterviewSessionEntity.InterviewPhase determineFirstPhase(CreateSessionRequest request) {
    if (request.getIntroEnabled()) return InterviewPhase.INTRO;
    if (request.getTechEnabled())  return InterviewPhase.TECH;
    if (request.getProjectEnabled()) return InterviewPhase.PROJECT;
    if (request.getHrEnabled())   return InterviewPhase.HR;
    return InterviewPhase.COMPLETED;
}
```

按优先级依次检查启用开关，返回第一个启用的阶段。

### 2.5 会话响应构建 — buildSessionResponse()

```java
private SessionResponseDTO buildSessionResponse(VoiceInterviewSessionEntity session) {
    return SessionResponseDTO.builder()
            .sessionId(session.getId())
            .roleType(session.getRoleType())
            .currentPhase(session.getCurrentPhase().name())
            .status(session.getStatus().name())
            .startTime(session.getStartTime())
            .plannedDuration(session.getPlannedDuration())
            .webSocketUrl(String.format("ws://localhost:8080/ws/voice-interview/%d", session.getId()))
            .build();
}
```

### 2.6 Redis 缓存 — cacheSession()

```java
private void cacheSession(VoiceInterviewSessionEntity session) {
    String cacheKey = "voice:interview:session:" + session.getId();
    RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
    bucket.set(session, Duration.ofHours(CACHE_TTL_HOURS));  // 1 小时 TTL
}
```

---

## 三、数据模型说明

### 3.1 CreateSessionRequest（请求体）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `roleType` | String | 无 | 向后兼容字段，新请求可不传 |
| `skillId` | String | 无 | 面试模板 ID，如 "java-backend" |
| `difficulty` | String | "mid" | 难度：junior / mid / senior |
| `customJdText` | String | 无 | 自定义 JD 文本 |
| `resumeId` | Long | 无 | 关联简历 ID |
| `introEnabled` | Boolean | false | 是否启用自我介绍阶段 |
| `techEnabled` | Boolean | true | 是否启用技术面阶段 |
| `projectEnabled` | Boolean | true | 是否启用项目面阶段 |
| `hrEnabled` | Boolean | true | 是否启用 HR 面阶段 |
| `plannedDuration` | Integer | 30 | 计划时长（分钟） |
| `llmProvider` | String | 无 | LLM 提供商 ID |

### 3.2 SessionResponseDTO（响应体）

```java
@Data @Builder
public class SessionResponseDTO {
    private Long sessionId;          // 数据库自增 ID（用于 WebSocket 连接）
    private String roleType;         // 面试模板
    private String currentPhase;     // 当前阶段（INTRO/TECH/PROJECT/HR/COMPLETED）
    private String status;           // 会话状态（IN_PROGRESS/PAUSED/COMPLETED）
    private LocalDateTime startTime; // 开始时间
    private Integer plannedDuration; // 计划时长
    private String webSocketUrl;     // WebSocket 连接 URL
}
```

### 3.3 实际返回 JSON 示例

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "sessionId": 42,
        "roleType": "java-backend",
        "currentPhase": "INTRO",
        "status": "IN_PROGRESS",
        "startTime": "2026-06-18T10:30:00",
        "plannedDuration": 30,
        "webSocketUrl": "ws://localhost:8080/ws/voice-interview/42"
    }
}
```

---

## 四、代码模块调用关系

```
前端 POST /api/voice-interview/sessions
  │
  ▼ HTTP
VoiceInterviewController.createSession(request)
  ├── [@Valid Jakarta Validation] 参数校验
  └── voiceInterviewService.createSession(request)
      ├── 处理默认值（skillId/difficulty/llmProvider）
      ├── determineFirstPhase(request) → 确定首个面试阶段
      ├── VoiceInterviewSessionEntity.builder() → 构建实体
      ├── sessionRepository.save() → DB 持久化
      ├── cacheSession() → Redis 缓存（1h TTL，key: voice:interview:session:{id}）
      └── buildSessionResponse(saved) → 构建 DTO（含 WebSocket URL）

  Result.success(session)
  ▼ JSON { code: 200, data: {...} }
```

---

## 五、异常处理机制

| 阶段 | 异常场景 | 处理方式 |
|------|---------|---------|
| **参数校验** | `skillId` 为空等 | Jakarta Validation → 自动 400 |
| **数据库保存** | PostgreSQL 不可达 | `@Transactional` 回滚，异常向上抛出 |
| **Redis 缓存** | Redis 不可达 | 异常向上抛出（缓存非核心依赖但会记录错误） |
| **会话创建失败** | 未知异常 | 全局异常处理器返回统一错误格式 |

---

## 六、与文本面试 createSession 的区别

| 维度 | 语音面试 createSession | 文本面试 createSession |
|------|----------------------|----------------------|
| AI 出题 | ❌ 不出题（自由对话） | ✅ AI 生成 3-20 题 |
| Redis | 1h TTL 实体缓存 | 24h TTL 会话缓存 |
| 阶段管理 | ✅ 四阶段（INTRO→TECH→PROJECT→HR） | ❌ 无阶段概念 |
| 限流 | 无 | GLOBAL 5/s + IP 5/s |
| 超时 | ~1s（纯 DB 写） | ~180s（含 AI 出题） |
| 未完成会话复用 | ❌ 不检查（每次都新建） | ✅ 自动复用未完成会话 |
| WebSocket URL | ✅ 返回 ws:// 连接地址 | ❌ 无 |
| 返回 ID | 数据库自增 Long | 16 位随机 UUID 字符串 |

---

## 七、流程总结

`createSession` 是语音面试的**入口接口**。用户在前端配置面试参数 → 前端调用 `POST /api/voice-interview/sessions` → `@Valid` 自动校验参数 → Controller 委托 `VoiceInterviewService.createSession()` 执行 3 步编排：

1. 处理默认值（skillId 默认 "java-backend"，llmProvider 为 null 时下游取全局默认）
2. 构建 `VoiceInterviewSessionEntity`（含阶段开关、首阶段判定）
3. 保存到数据库 + 写入 Redis 缓存（1 小时 TTL）

整个流程不涉及 AI 调用，仅写数据库和缓存，响应快速。与文本面试不同，**不出题、不复用未完成会话、不限制流**——语音面试是面试官与候选人自由对话，题目由 WebSocket 连接的 AI 实时生成。
