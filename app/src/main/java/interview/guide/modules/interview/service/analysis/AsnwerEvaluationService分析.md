## AnswerEvaluationService 分析

这是一个**DTO 适配器服务**，职责很单一：在面试专用 DTO 和通用评估模型之间做转换，不执行任何 AI 调用。

核心就是在把面试评估转换到通用评估模型，因为不止有面试结果需要评估，简历也需要评估。

***

### 核心职责

```
InterviewQuestionDTO  →  QaRecord → UnifiedEvaluationService → EvaluationReport → InterviewReportDTO
（面试专用）              （通用模型）   （通用评估引擎/LLM调用）      （通用结果）         （面试专用）
```

`AnswerEvaluationService` 不调用 LLM，只是把面试的数据格式"翻译"给通用评估引擎，然后再把结果"翻译"回来。

***

### 依赖项（3 个）

| 依赖                            | 作用                                             |
| ----------------------------- | ---------------------------------------------- |
| `UnifiedEvaluationService`    | 通用评估引擎，做真正的 LLM 调用 + 结构化输出                     |
| `InterviewPersistenceService` | 查会话的 `skillId`，用于获取评估参考基线                      |
| `InterviewSkillService`       | 根据 `skillId` 构建评估参考基线（知识点的标准答案/评分标准），注入 Prompt |

***

### 唯一公共方法：`evaluateInterview()`

**参数**：

| 参数           | 含义                                                                            |
| ------------ | ----------------------------------------------------------------------------- |
| `chatClient` | 由调用方（`generateReport` 或 `EvaluateStreamConsumer`）传入的 ChatClient，已配置好 Provider |
| `sessionId`  | 会话 ID，用于日志和报告关联                                                               |
| `resumeText` | 简历文本，作为评估上下文之一（无简历时为空字符串）                                                     |
| `questions`  | 完整题目列表，**含用户答案**（`userAnswer` 不为 null 的才参与评估）                                 |

**3 步处理**：

```
① DTO → 通用模型
   questions.stream().map(q -> new QaRecord(index, question, category, userAnswer))

② 构建评估参考基线
   skillService.buildEvaluationReferenceSectionSafe(skillId)
   → 根据面试主题获取该方向的知识点标准，注入 Prompt 帮助 LLM 更准确打分

③ 调用通用引擎 → 转回面试 DTO
   unifiedEvaluationService.evaluate(chatClient, sessionId, qaRecords, resumeText, referenceContext)
   → EvaluationReport
   → toInterviewReportDTO() 转换
```

***

### 异常处理

```java
catch (BusinessException e) {
    throw e;           // 业务异常原样透传
} catch (Exception e) {
    throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "面试评估失败：" + e.getMessage());
    // 其他异常包装为 3005
}
```

`BusinessException` 原样抛出，其他异常统一包装为 `INTERVIEW_EVALUATION_FAILED(3005)`。

***

### 调用方（2 个）

| 调用方                                        | 场景                      | ChatClient 来源                       |
| ------------------------------------------ | ----------------------- | ----------------------------------- |
| `InterviewSessionService.generateReport()` | 同步评估（用户请求 /report）      | `getChatClientOrDefault`（带 Advisor） |
| `EvaluateStreamConsumer.processBusiness()` | 异步评估（Redis Stream 后台消费） | `getChatClientOrDefault`（带 Advisor） |

两个路径调用**完全相同的方法**，区别只是同步/异步和 ChatClient 的 Provider 不同。

***

### 设计评价

- **职责单一**：只做格式适配，不涉及 LLM 调用逻辑
- **复用通用引擎**：`UnifiedEvaluationService` 是 `common.evaluation` 包下的通用组件，语音面试等模块也可复用
- **参考基线注入**：通过 `InterviewSkillService` 获取知识点标准，让 LLM 评分更客观准确
- **BusinessException 透传**：避免重复包装，保留原始业务错误码

