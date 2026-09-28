# getEvaluation 方法完整实现流程详解

> 接口路径：`GET /api/voice-interview/sessions/{sessionId}/evaluation`  
> 所属类：`VoiceInterviewController`  
> 功能：获取指定会话的评估状态和结果（评估完成时），前端轮询此接口获取评估进度  
> 限流策略：无

---

## 一、前端交互触发点

### 触发场景

面试结束后（`endSession` 返回成功），前端跳转到评估等待页面，以固定间隔轮询此接口获取评估进度。

- **轮询频率**：通常每 2-3 秒轮询一次
- **停止条件**：`evaluateStatus` 为 `COMPLETED` 或 `FAILED`
- **UI 状态**：
  - `PENDING` → 显示"评估排队中..."
  - `PROCESSING` → 显示"AI 正在评估您的面试表现..."
  - `COMPLETED` → 渲染评估详情面板
  - `FAILED` → 显示错误信息和"重新评估"按钮

```typescript
// 前端轮询（VoiceEvaluationPage.tsx）
const pollInterval = setInterval(async () => {
    const result = await voiceInterviewApi.getEvaluation(sessionId);
    if (result.evaluateStatus === 'COMPLETED') {
        clearInterval(pollInterval);
        setEvaluation(result.evaluation);  // 渲染完整评估
    } else if (result.evaluateStatus === 'FAILED') {
        clearInterval(pollInterval);
        setError(result.evaluateError);
    }
}, 2000);  // 每 2 秒轮询
```

### API 层封装

```typescript
async getEvaluation(sessionId: number): Promise<VoiceEvaluationStatusDTO> {
    return request.get(`/api/voice-interview/sessions/${sessionId}/evaluation`);
}
```

---

## 二、后端处理全链路

### 2.1 路由映射

```
GET /api/voice-interview/sessions/{sessionId}/evaluation
  │
  ▼
VoiceInterviewController.getEvaluation(sessionId)
```

### 2.2 Controller 层

```java
@GetMapping("/sessions/{sessionId}/evaluation")
public Result<VoiceEvaluationStatusDTO> getEvaluation(@PathVariable Long sessionId) {
    log.info("Getting evaluation status for session: {}", sessionId);

    // 1. 校验会话存在性
    VoiceInterviewSessionEntity session = voiceInterviewService.getSession(sessionId);
    if (session == null) {
        throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
    }

    // 2. 构建状态响应
    AsyncTaskStatus status = session.getEvaluateStatus();
    VoiceEvaluationStatusDTO.VoiceEvaluationStatusDTOBuilder builder = VoiceEvaluationStatusDTO.builder()
            .evaluateStatus(status != null ? status.name() : null)
            .evaluateError(session.getEvaluateError());

    // 3. 评估完成时附加详情
    if (status == AsyncTaskStatus.COMPLETED) {
        VoiceEvaluationDetailDTO evaluation = evaluationService.getEvaluation(sessionId);
        builder.evaluation(evaluation);
    }

    return Result.success(builder.build());
}
```

**关键逻辑**：评估状态（`evaluateStatus`）存储在 `VoiceInterviewSessionEntity` 中，由 `endSession` 时设为 `PENDING`，异步 `VoiceEvaluateStreamConsumer` 消费时更新为 `PROCESSING → COMPLETED/FAILED`。

### 2.3 评估状态枚举 — AsyncTaskStatus

```java
public enum AsyncTaskStatus {
    PENDING,      // 排队等待处理
    PROCESSING,   // 正在处理中
    COMPLETED,    // 处理完成
    FAILED        // 处理失败
}
```

### 2.4 评估详情查询 — evaluationService.getEvaluation()

**文件**：`VoiceInterviewEvaluationService.java` 第 152-159 行

```java
public VoiceEvaluationDetailDTO getEvaluation(Long sessionId) {
    VoiceInterviewEvaluationEntity evaluation = evaluationRepository.findBySessionId(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_EVALUATION_NOT_FOUND,
            "评估结果不存在: " + sessionId));

    return buildDetailDTO(evaluation);
}
```

`buildDetailDTO()` 从 `VoiceInterviewEvaluationEntity` 反序列化 JSON 字段：
- `questionEvaluationsJson` → 逐题评估详情列表
- `strengthsJson` → 优势列表
- `improvementsJson` → 改进建议列表
- `referenceAnswersJson` → 参考答案列表

---

## 三、数据模型说明

### 3.1 VoiceEvaluationStatusDTO（响应体）

```java
@Data @Builder
public class VoiceEvaluationStatusDTO {
    private String evaluateStatus;           // PENDING / PROCESSING / COMPLETED / FAILED
    private String evaluateError;            // 失败时的错误信息
    private VoiceEvaluationDetailDTO evaluation;  // COMPLETED 时才返回
}
```

### 3.2 VoiceEvaluationDetailDTO（评估详情）

```java
@Data @Builder
public class VoiceEvaluationDetailDTO {
    private Long sessionId;
    private int totalQuestions;
    private int overallScore;                // 总分 0-100
    private String overallFeedback;          // 总体评价
    private List<String> strengths;          // 优势列表
    private List<String> improvements;       // 改进建议
    private List<AnswerDetail> answers;      // 逐题详情

    @Data @Builder
    public static class AnswerDetail {
        private int questionIndex;
        private String question;             // AI 提问内容
        private String category;             // 分类（技术问题/项目深挖等）
        private String userAnswer;           // 用户回答（ASR 文本）
        private int score;                   // 单题得分
        private String feedback;             // 反馈
        private String referenceAnswer;      // 参考答案
        private List<String> keyPoints;      // 关键点
    }
}
```

### 3.3 实际返回 JSON 示例

**评估进行中**：
```json
{
    "code": 200,
    "message": "success",
    "data": {
        "evaluateStatus": "PROCESSING",
        "evaluateError": null,
        "evaluation": null
    }
}
```

**评估完成**：
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
            "overallFeedback": "您的技术基础较为扎实，在项目经验部分表现突出...",
            "strengths": ["Java基础扎实", "表达清晰", "项目经验丰富"],
            "improvements": ["系统设计方面需加强", "部分问题的回答深度不够"],
            "answers": [
                {
                    "questionIndex": 0,
                    "question": "请描述一下你使用Spring Boot构建微服务的经验",
                    "category": "技术问题",
                    "userAnswer": "我主要使用Spring Boot和MyBatis...",
                    "score": 82,
                    "feedback": "回答条理清晰，但可以更深入地描述服务间通信...",
                    "referenceAnswer": "微服务架构中建议采用...",
                    "keyPoints": ["服务注册与发现", "API网关", "服务间通信"]
                }
            ]
        }
    }
}
```

**评估失败**：
```json
{
    "code": 200,
    "message": "success",
    "data": {
        "evaluateStatus": "FAILED",
        "evaluateError": "LLM调用超时",
        "evaluation": null
    }
}
```

---

## 四、代码模块调用关系

```
GET /api/voice-interview/sessions/{sessionId}/evaluation
  │
  ▼
VoiceInterviewController.getEvaluation(sessionId)
  ├── voiceInterviewService.getSession(sessionId)
  │   ├── [null] → BusinessException(10001)
  │   └── 返回 entity（含 evaluateStatus / evaluateError）
  ├── 构建 VoiceEvaluationStatusDTO（status + error）
  └── [status == COMPLETED]
      └── evaluationService.getEvaluation(sessionId)
          ├── evaluationRepository.findBySessionId()
          │   └── [不存在] → BusinessException(10011)
          └── buildDetailDTO(entity)
              ├── 反序列化 questionEvaluationsJson
              ├── 反序列化 strengthsJson / improvementsJson
              ├── 反序列化 referenceAnswersJson
              └── 构建 VoiceEvaluationDetailDTO

  Result.success(statusDTO)
  ▼ JSON { code: 200, data: {...} }
```

---

## 五、异常处理机制

| 场景 | 错误码 | 处理方式 |
|------|--------|---------|
| 会话不存在 | 10001 (VOICE_SESSION_NOT_FOUND) | `BusinessException` 抛出 |
| evaluateStatus 为 null | 正常返回 | `evaluateStatus` 字段为 null（未触发评估） |
| 评估实体不存在 | 10011 (VOICE_EVALUATION_NOT_FOUND) | 仅 COMPLETED 状态时查询，理论上不应出现 |
| 评估 JSON 反序列化失败 | 10010 (VOICE_EVALUATION_FAILED) | `buildDetailDTO` 内部抛 `BusinessException` |

---

## 六、与 generateEvaluation 的协同

| 操作 | 用途 | 返回 |
|------|------|------|
| `GET /evaluation` | 查询评估状态（轮询） | 状态 + 详情（完成时） |
| `POST /evaluation` | 触发评估任务（主动） | 状态（PENDING 或当前状态） |

前端通常的协作流程：
1. 面试结束 → `endSession` 内部自动触发异步评估
2. 前端轮询 `GET /evaluation` 监控进度
3. 如果轮询超时或评估失败，前端可调用 `POST /evaluation` 手动重新触发

---

## 七、流程总结

`getEvaluation` 是语音面试的**评估状态轮询接口**。前端在面试结束后以固定间隔调用 → Controller 从 `VoiceInterviewSessionEntity` 读取 `evaluateStatus`（PENDING/PROCESSING/COMPLETED/FAILED）和 `evaluateError` → 构建 `VoiceEvaluationStatusDTO` → 如果状态为 COMPLETED，额外调用 `evaluationService.getEvaluation()` 从 `voice_interview_evaluations` 表反序列化完整评估详情（总分、逐题评估、优势、改进建议、参考答案）→ 返回给前端渲染评估面板。

评估流程的关键路径：`endSession` 设置 evaluateStatus=PENDING → 入队 Redis Stream → `VoiceEvaluateStreamConsumer` 后台调用 LLM 评估 → 保存结果到 `voice_interview_evaluations` 表 → 更新 evaluateStatus 为 COMPLETED → 前端轮询感知到状态变化并拉取评估详情。
