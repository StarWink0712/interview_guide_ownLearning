# generateEvaluation 方法完整实现流程详解

> 接口路径：`POST /api/voice-interview/sessions/{sessionId}/evaluation`  
> 所属类：`VoiceInterviewController`  
> 功能：手动触发面试评估任务，通过 Redis Stream 异步执行。支持幂等保护（已完成直接返回、进行中返回状态）  
> 限流策略：无

---

## 一、前端交互触发点

### 触发场景

前端在以下场景调用此接口：
- 评估失败后用户点击"重新评估"按钮
- 面试结束后长时间未出评估结果，用户主动触发评估
- 前端轮询超时后的兜底操作

```typescript
// 前端 API 调用（voiceInterviewApi.ts）
const result = await voiceInterviewApi.generateEvaluation(sessionId);
// 根据返回的状态决定后续行为
if (result.evaluateStatus === 'PENDING') {
    startPolling();  // 开始轮询 GET /evaluation
} else if (result.evaluateStatus === 'COMPLETED') {
    setEvaluation(result.evaluation);  // 已有完成结果，直接展示
}
```

### 与 GET /evaluation 的区别

| 维度 | GET /evaluation | POST /evaluation |
|------|----------------|------------------|
| HTTP 方法 | GET（读） | POST（写/触发） |
| 副作用 | 无 | 触发异步任务 |
| 幂等性 | 天然幂等 | 条件幂等（已完成不重复触发） |
| 用途 | 轮询查询进度 | 主动触发评估 |

---

## 二、后端处理全链路

### 2.1 路由映射

```
POST /api/voice-interview/sessions/{sessionId}/evaluation
  │
  ▼
VoiceInterviewController.generateEvaluation(sessionId)
```

### 2.2 Controller 层

```java
@PostMapping("/sessions/{sessionId}/evaluation")
public Result<VoiceEvaluationStatusDTO> generateEvaluation(@PathVariable Long sessionId) {
    log.info("Triggering async evaluation for session: {}", sessionId);

    // ===== 步骤1: 校验会话存在性 =====
    VoiceInterviewSessionEntity session = voiceInterviewService.getSession(sessionId);
    if (session == null) {
        throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
    }

    // ===== 步骤2: 已完成 → 直接返回缓存结果 =====
    if (session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED) {
        VoiceEvaluationDetailDTO evaluation = evaluationService.getEvaluation(sessionId);
        return Result.success(VoiceEvaluationStatusDTO.builder()
                .evaluateStatus(AsyncTaskStatus.COMPLETED.name())
                .evaluation(evaluation)
                .build());
    }

    // ===== 步骤3: 已在进行中 → 返回当前状态 =====
    if (session.getEvaluateStatus() == AsyncTaskStatus.PENDING
            || session.getEvaluateStatus() == AsyncTaskStatus.PROCESSING) {
        return Result.success(VoiceEvaluationStatusDTO.builder()
                .evaluateStatus(session.getEvaluateStatus().name())
                .build());
    }

    // ===== 步骤4: 触发新评估任务 =====
    voiceInterviewService.triggerEvaluation(sessionId);

    return Result.success(VoiceEvaluationStatusDTO.builder()
            .evaluateStatus(AsyncTaskStatus.PENDING.name())
            .build());
}
```

### 2.3 三种返回路径

| 当前 evaluateStatus | 行为 | 返回值 |
|--------------------|------|--------|
| `COMPLETED` | 直接返回已有的评估详情 | `{ evaluateStatus: "COMPLETED", evaluation: {...} }` |
| `PENDING` 或 `PROCESSING` | 不重复触发，返回当前状态 | `{ evaluateStatus: "PENDING" }` |
| `FAILED` 或 `null` | 触发新的异步评估任务 | `{ evaluateStatus: "PENDING" }` |

**幂等保护**：COMPLETED 直接返回结果不重复评估，PENDING/PROCESSING 不重复入队 Redis Stream。

### 2.4 Service 层 — triggerEvaluation()

**文件**：`VoiceInterviewService.java` 第 633-638 行

```java
@Transactional
public void triggerEvaluation(Long sessionId) {
    // 1. 更新会话评估状态为 PENDING
    updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);

    // 2. 发送评估任务到 Redis Stream
    voiceEvaluateStreamProducer.sendEvaluateTask(sessionId.toString());
}
```

### 2.5 评估任务入队 — sendEvaluateTask()

将评估任务发送到 Redis Stream，`VoiceEvaluateStreamConsumer` 后台消费执行：

```
Redis Stream: voice:evaluate:stream
  │
  │ XADD voice:evaluate:stream * sessionId "42"
  │
  ▼
VoiceEvaluateStreamConsumer.onMessage()
  └── VoiceInterviewEvaluationService.generateEvaluation(sessionId)
      ├── 查询会话和对话消息
      ├── 构建 QaRecord 列表
      ├── 调用 UnifiedEvaluationService.evaluate() → LLM 评估
      └── saveEvaluationTransactional() → 持久化评估结果
```

### 2.6 异步消费者评估流程

**文件**：`VoiceInterviewEvaluationService.java` 第 102-144 行

```java
public void generateEvaluation(Long sessionId) {
    // 1. 获取会话信息
    VoiceInterviewSessionEntity session = getSession(sessionId);
    List<VoiceInterviewMessageEntity> messages = messageRepository
        .findBySessionIdOrderBySequenceNumAsc(sessionId);

    // 2. 无消息 → 保存空评估
    if (messages.isEmpty()) {
        saveEmptyEvaluationTransactional(sessionId, session);
        return;
    }

    // 3. 构建 QaRecord 列表（AI提问→用户回答的配对）
    List<QaRecord> qaRecords = buildQaRecords(messages);

    // 4. 获取 LLM ChatClient
    ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(session.getLlmProvider());

    // 5. 获取技能参考上下文
    String referenceContext = skillService.buildEvaluationReferenceSectionSafe(session.getSkillId());

    // 6. 调用统一评估服务（LLM 调用在事务外执行）
    EvaluationReport report = unifiedEvaluationService.evaluate(
        chatClient, sessionIdStr, qaRecords, null, referenceContext);

    // 7. 保存评估结果（事务内）
    saveEvaluationTransactional(sessionId, session, report);
}
```

**设计要点**：LLM 调用（`unifiedEvaluationService.evaluate()`）在 `@Transactional` 外执行，仅数据库写入在事务内。这遵循了"禁止在事务方法内调用外部 API"的编码规范。

---

## 三、返回 JSON 示例

**触发新评估任务**：
```json
{
    "code": 200,
    "message": "success",
    "data": {
        "evaluateStatus": "PENDING",
        "evaluateError": null,
        "evaluation": null
    }
}
```

**评估已完成（直接返回缓存）**：
```json
{
    "code": 200,
    "message": "success",
    "data": {
        "evaluateStatus": "COMPLETED",
        "evaluateError": null,
        "evaluation": {
            "sessionId": 42,
            "totalQuestions": 8,
            "overallScore": 78,
            "overallFeedback": "您的技术基础较为扎实...",
            "strengths": [...],
            "improvements": [...],
            "answers": [...]
        }
    }
}
```

---

## 四、代码模块调用关系

```
POST /api/voice-interview/sessions/{sessionId}/evaluation
  │
  ▼
VoiceInterviewController.generateEvaluation(sessionId)
  ├── voiceInterviewService.getSession(sessionId)
  │   └── [null] → BusinessException(10001)
  │
  ├── [状态 == COMPLETED] → evaluationService.getEvaluation()
  │   └── 返回缓存评估详情
  │
  ├── [状态 == PENDING/PROCESSING] → 返回当前状态
  │
  └── [状态 == FAILED/null] → voiceInterviewService.triggerEvaluation(sessionId)
      ├── updateEvaluateStatus(PENDING) → DB 更新
      └── voiceEvaluateStreamProducer.sendEvaluateTask(sessionId)
          └── Redis Stream XADD → VoiceEvaluateStreamConsumer 后台消费
              └── VoiceInterviewEvaluationService.generateEvaluation()
                  ├── 构建 QaRecord 列表
                  ├── unifiedEvaluationService.evaluate() → LLM 评估（事务外）
                  └── saveEvaluationTransactional() → DB 持久化（事务内）

  Result.success(statusDTO)
  ▼ JSON { code: 200, data: {...} }
```

---

## 五、异常处理机制

| 场景 | 错误码 | 处理方式 |
|------|--------|---------|
| 会话不存在 | 10001 | `BusinessException(VOICE_SESSION_NOT_FOUND)` |
| 评估已完成 | 正常返回 | 直接返回 COMPLETED 状态 + 缓存详情 |
| 评估进行中 | 正常返回 | 返回当前状态防止重复触发 |
| 评估入队失败 | 内部错误 | 异常向上抛出 |
| LLM 评估失败 | 10010 | 消费者最多重试 3 次 → evaluateStatus=FAILED |

---

## 六、与 getEvaluation 的协同

```
面试结束
  │
  ├── endSession() 内部自动调用 sendEvaluateTask()
  │
  ├─→ 前端轮询 GET /evaluation （每 2 秒）
  │     │  evaluateStatus: PENDING
  │     │  evaluateStatus: PROCESSING
  │     └─ evaluateStatus: COMPLETED → 停止轮询，展示评估
  │
  └─→ 轮询超时 / 评估失败 → 前端调用 POST /evaluation 手动触发
        └── triggerEvaluation() → 重新入队 Redis Stream
```

---

## 七、流程总结

`generateEvaluation` 是语音面试的**评估触发接口**（POST）。Controller 层做三级判断：

1. **已完成**：直接返回缓存的评估详情（最快路径）
2. **进行中**（PENDING/PROCESSING）：返回当前状态，不重复触发（幂等保护）
3. **待触发**（FAILED/null）：调用 `triggerEvaluation()` → 更新 evaluateStatus 为 PENDING → 将任务入队 Redis Stream → 返回 PENDING 状态

后台 `VoiceEvaluateStreamConsumer` 异步消费任务 → 调用 `VoiceInterviewEvaluationService.generateEvaluation()` → 构建 QaRecord → 调用 `UnifiedEvaluationService` LLM 评估（事务外） → 持久化评估详情到 `voice_interview_evaluations` 表（事务内）→ 更新 evaluateStatus 为 COMPLETED → 前端轮询感知变化 → 调用 `GET /evaluation` 拉取详情。

关键设计：
- **评估触发与执行分离**：POST 只负责入队，GET 负责轮询结果
- **幂等保护**：COMPLETED 直接返回缓存，PENDING/PROCESSING 不重复入队
- **事务与 LLM 调用分离**：评估的 LLM 调用在事务外，仅 DB 写入在事务内
