# 前置步骤：CreateSession 创建语音面试会话

## 概述

在 WebSocket 语音面试的完整流程中，第一步是用户通过 HTTP 接口创建一个面试会话。需要明确一个关键认知：`createSession` 接口的职责仅仅是**准备会话数据并将 WebSocket 连接地址返回给前端**，它不创建 WebSocket 连接，也不参与任何实时通信。从这一刻起，Controller 的 HTTP 层面职责就结束了——后续的实时语音通信全部通过 WebSocket 协议在 `VoiceInterviewWebSocketHandler` 中进行，不再经过任何 Controller。

## 触发场景与请求结构

用户在前端页面配置好面试参数后点击"开始面试"。前端封装 `CreateSessionRequest` 调用 `POST /api/voice-interview/sessions`。由于仅涉及数据库写入操作，无需像文本面试那样设置 180 秒超时，通常 1 秒内即可完成。请求体结构如下：

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CreateSessionRequest {
    private String roleType;           // 向后兼容字段，新请求可不传
    private String skillId;            // 模板 ID，如 "java-backend"
    private String difficulty;         // "junior", "mid", "senior"
    private String customJdText;       // 自定义 JD 文本
    private Long resumeId;             // 关联简历 ID
    @Builder.Default private Boolean introEnabled = false;     // 自我介绍阶段开关
    @Builder.Default private Boolean techEnabled = true;      // 技术面阶段开关
    @Builder.Default private Boolean projectEnabled = true;   // 项目面阶段开关
    @Builder.Default private Boolean hrEnabled = true;        // HR面阶段开关
    @Builder.Default private Integer plannedDuration = 30;    // 计划时长（分钟）
    private String llmProvider;        // LLM 提供商 ID，不传则取全局默认
}
```

`@Builder.Default` 注解确保了四个阶段开关和计划时长都有合理的默认值，前端只需传入需要修改的字段。`skillId` 是最关键的字段——它决定了面试的技能方向，后续会用于确定首个面试阶段、选择开场问题模板和构建 LLM 系统提示。

---

## 一、Controller 层：纯路由委托

Controller 的方法极简，仅做路由转发，不做任何业务处理：

```java
@PostMapping("/sessions")
public Result<SessionResponseDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
    log.info("Creating voice interview session for role: {}", request.getRoleType());
    SessionResponseDTO session = voiceInterviewService.createSession(request);
    return Result.success(session);
}
```

`@Valid` 注解触发 Jakarta Validation 自动校验请求参数。Controller 从 `request.getRoleType()` 取日志所需信息后，完全委托给 `VoiceInterviewService.createSession()`，自身将结果包装为 `Result<SessionResponseDTO>` 返回。注入的三个依赖（`voiceInterviewService`、`evaluationService`、`voiceEvaluateStreamProducer`）中只有第一个被此方法使用，其余两个分别是评估和异步任务的方法所需。

---

## 二、Service 层核心编排

`VoiceInterviewService.createSession()` 标注了 `@Transactional`，在三个有序步骤中完成会话实体的构建和持久化：

```java
@Transactional
public SessionResponseDTO createSession(CreateSessionRequest request) {
    // ===== 步骤1: 处理默认值 =====
    // skillId 是面试模板 ID，如 "java-backend", "bytedance-backend"
    String effectiveSkillId = request.getSkillId() != null
        ? request.getSkillId() : InterviewDefaults.SKILL_ID;
    // llmProvider 为 null 或空字符串时不赋值，下游 LlmProviderRegistry 自动取全局默认
    String effectiveLlmProvider = (request.getLlmProvider() != null
        && !request.getLlmProvider().isBlank())
        ? request.getLlmProvider()
        : null;

    // ===== 步骤2: 创建会话实体 =====
    VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .userId(DEFAULT_USER_ID)       // 固定 "default"，预留多用户扩展
            .roleType(effectiveSkillId)    // 作为面试角色类型（向后兼容）
            .skillId(effectiveSkillId)     // 技能模板 ID
            .difficulty(request.getDifficulty() != null
                ? request.getDifficulty() : InterviewDefaults.DIFFICULTY)
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
    // 必须先写 DB 获取自增 ID（Redis 缓存 Key 依赖此 ID）
    VoiceInterviewSessionEntity saved = sessionRepository.save(session);
    cacheSession(saved);  // Redis 缓存，Key: voice:interview:session:{id}，TTL: 1h

    log.info("Created voice interview session: {} with template: {}, phase: {}",
            saved.getId(), effectiveSkillId, saved.getCurrentPhase());

    return buildSessionResponse(saved);
}
```

这里有一个与文本面试的重要区别：保存顺序必须是**先 DB 后 Redis**，而非文本面试的"先 Redis 后 DB（非容错）"。原因是语音面试使用数据库自增 Long 型 ID 作为 sessionId（文本面试使用 16 位随机 UUID），而 Redis 缓存 Key 是 `voice:interview:session:{id}`，只有当 DB 写入成功获取到自增 ID 后才能在 Redis 中建立缓存。

### 步骤1：默认值处理的策略

`effectiveSkillId` 若为 null 取 `InterviewDefaults.SKILL_ID`。`effectiveLlmProvider` 若为 null 或空字符串保持 null——不给它赋默认值，而是让下游的 `LlmProviderRegistry.getChatClientOrDefault(provider)` 在 provider 为 null 时自动选取全局默认 LLM 提供商。这种"延迟决策"的设计让全局默认 Provider 的变更不需要修改 createSession 的代码，只需修改注册表配置。

### 步骤2：首个阶段确定

`determineFirstPhase()` 按优先级顺序检查四个阶段开关：

```java
private VoiceInterviewSessionEntity.InterviewPhase determineFirstPhase(CreateSessionRequest request) {
    if (request.getIntroEnabled())  return InterviewPhase.INTRO;
    if (request.getTechEnabled())   return InterviewPhase.TECH;
    if (request.getProjectEnabled()) return InterviewPhase.PROJECT;
    if (request.getHrEnabled())    return InterviewPhase.HR;
    return InterviewPhase.COMPLETED;  // 所有阶段都关闭
}
```

优先级从高到低为："自我介绍 > 技术面 > 项目面 > HR面"。这不是按面试真实顺序排的——真实面试顺序是 INTRO → TECH → PROJECT → HR，但 `determineFirstPhase` 处理的是"用户可能部分关闭阶段"的情况。例如用户只开启了项目面和 HR 面，首个阶段应该是 PROJECT 而不是 INTRO。开关全部关闭时返回 COMPLETED，面试立即结束。

### 步骤3：Redis 缓存

```java
private void cacheSession(VoiceInterviewSessionEntity session) {
    String cacheKey = "voice:interview:session:" + session.getId();
    RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
    bucket.set(session, Duration.ofHours(CACHE_TTL_HOURS));  // 1 小时 TTL
}
```

TTL 设为 1 小时而非 24 小时（文本面试的 24h），因为语音面试是实时交互，1 小时内不结束的概率极低。短期 TTL 也减少了 Redis 内存占用。

---

## 三、构建响应 DTO

`buildSessionResponse()` 将持久化后的实体转换为前端可用的 `SessionResponseDTO`，核心是拼接 WebSocket 连接 URL：

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

响应 DTO 的结构：

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SessionResponseDTO {
    private Long sessionId;          // 数据库自增 ID，WebSocket URL 中的动态路径参数
    private String roleType;         // 面试模板
    private String currentPhase;     // 当前阶段（INTRO/TECH/PROJECT/HR/COMPLETED）
    private String status;           // 会话状态（IN_PROGRESS/PAUSED/COMPLETED）
    private LocalDateTime startTime; // 开始时间
    private Integer plannedDuration; // 计划时长
    private String webSocketUrl;     // 前端用于建立 WebSocket 连接的地址
}
```

URL 中硬编码了 `localhost:8080`，生产环境需通过配置文件或动态获取当前主机名替换。

实际返回示例：

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

## 四、与文本面试 createSession 的关键区别

两者的差异源于设计理念的根本不同——文本面试是"AI 出题 + 用户依次作答"的结构化面试，语音面试是"AI 面试官 + 候选人自由对话"的非结构化面试：

| 维度 | 语音面试 createSession | 文本面试 createSession |
|------|----------------------|----------------------|
| AI 出题 | 不出题（自由对话，AI 在 WebSocket 中实时生成回复） | AI 生成 3-20 道题 |
| 限流 | 无 | GLOBAL 5次/秒 + IP 5次/秒 |
| Redis TTL | 1 小时 | 24 小时 |
| 超时 | ~1s（纯 DB 写入） | ~180s（含 LLM 出题） |
| 未完成会话复用 | 不检查（每次都新建） | 自动复用 |
| 阶段管理 | 四阶段（INTRO→TECH→PROJECT→HR） | 无阶段概念 |
| WebSocket URL | 返回 ws:// 连接地址 | 无 |
| sessionId 类型 | 数据库自增 Long | 16 位随机 UUID 字符串 |

---

## 五、总结

`createSession` 是整个语音面试流程的准备工作。它不做任何 AI 处理，只负责在数据库中创建一条会话记录、在 Redis 中缓存该记录、并将包含 WebSocket 地址的 DTO 返回给前端。至此 Controller 的 HTTP 层面职责结束——后续实时语音通信全部通过 WebSocket 协议在 `VoiceInterviewWebSocketHandler` 中进行，不再经过任何 Controller。前端拿到 `webSocketUrl` 后，由浏览器原生 `WebSocket` API 发起 HTTP Upgrade 握手，进入真正的实时语音面试阶段。
