# getInterviewDetail：获取面试报告详情完整调用链

> 接口：`GET /api/interview/sessions/{sessionId}/details`  
> Controller：`InterviewController.getInterviewDetail`  
> 前端 API：`historyApi.getInterviewDetail`

## 1. 当前前端调用场景

该接口有多个真实入口：

1. 面试记录页点击文字面试行，路由跳到 `/interviews/{sessionId}`，
   `InterviewDetailPageWrapper.useEffect` 加载详情。
2. 简历详情页点击某次面试，`ResumeDetailPage.handleViewInterview` 加载详情。
3. 从其他页面携带 `viewInterview` state 打开简历详情时自动加载。
4. 自适应洞察页输入会话 ID 后加载详情中的能力画像和轨迹。

统一调用：

```ts
historyApi.getInterviewDetail(sessionId)
  → request.get(`/api/interview/sessions/${sessionId}/details`)
```

## 2. Controller 层

```java
@GetMapping("/api/interview/sessions/{sessionId}/details")
public Result<InterviewDetailDTO> getInterviewDetail(
    @PathVariable String sessionId
) {
  InterviewDetailDTO detail =
      historyService.getInterviewDetail(sessionId);
  return Result.success(detail);
}
```

Controller 只负责路径绑定、委托和统一响应包装。

## 3. HistoryService：查询会话

```text
InterviewHistoryService.getInterviewDetail
  → interviewPersistenceService.findBySessionId
  → InterviewSessionRepository.findBySessionId
```

不存在时抛出 `BusinessException(INTERVIEW_SESSION_NOT_FOUND)`。

## 4. 解析会话中的 JSON 字段

会话 Entity 保存了多组 JSON 字符串。Service 通过通用 `parseJson` 分别解析：

```text
questionsJson         → List<Object> questions
strengthsJson         → List<String> strengths
improvementsJson      → List<String> improvements
referenceAnswersJson  → List<Object> referenceAnswers
abilityProfileJson    → AbilityProfileDTO
adaptiveTraceJson     → List<AdaptiveTraceDTO>
questionsJson         → List<InterviewQuestionDTO> allQuestions
```

`parseJson` 的规则：

- 字段为 null：返回 null；
- JSON 正常：返回目标对象；
- JSON 损坏：记录错误并返回 null，不让单个历史字段破坏整个详情响应。

题目 JSON 被解析两次：一次作为通用 `questions` 返回，一次作为强类型题单构建答案详情。

## 5. 构建完整答案列表

调用：

```java
buildAnswerDetailList(allQuestions, session.getAnswers())
```

处理方式：

1. 把答案 Entity 按 `questionIndex` 建成 Map。
2. 按原始题目顺序遍历所有题目。
3. 已回答题目调用 `InterviewMapper.toAnswerDetailDTO`，并解析 `keyPointsJson`。
4. 未回答题目构造空答案 DTO：`userAnswer=null`、无回答时间，保留题目和可能的分数/反馈。

因此提前交卷后，详情页仍能显示原题单中的全部题目，而不是只显示已回答部分。

如果完整题单为空，Service 降级为只转换数据库中已有的答案。

## 6. MapStruct 组装最终 DTO

最后调用：

```java
interviewMapper.toDetailDTO(
    session,
    questions,
    strengths,
    improvements,
    referenceAnswers,
    abilityProfile,
    adaptiveTrace,
    answerList
)
```

返回的 `InterviewDetailDTO` 包含：

- 会话基本信息和状态；
- `evaluateStatus` / `evaluateError`；
- 总分和总体反馈；
- 全部题目、优势、改进建议、参考答案；
- 能力画像和自适应轨迹；
- 对齐完整题单的逐题答案详情。

Entity 不直接返回前端。

## 7. 响应回到页面

```text
InterviewDetailDTO
  → Result.success
  → Axios 拦截器提取 data
  → historyApi 返回 InterviewDetail
```

路由详情页把结果保存到 `interview` state，交给 `InterviewDetailPanel` 展示总分、分类表现、
逐题答案、反馈、优势、改进建议和参考答案。

简历详情页则保存到 `selectedInterview`，切换到内部详情视图。失败时各页面显示固定错误或 alert。

## 8. 与 getReport 的区别

| 接口 | 行为 |
|---|---|
| `/report` | 同步调用 LLM 重新生成报告并写数据库 |
| `/details` | 只读取数据库中已有会话、答案和报告字段 |

当前前端报告展示使用 `/details`，不是 `/report`。

## 9. 当前实现边界

- JSON 损坏会静默降级为 null，页面可能得到不完整详情但响应仍成功。
- `questionsJson` 被重复解析，可以复用强类型结果减少开销。
- `session.getAnswers()` 依赖 JPA 关联加载；Service 没有显式只读事务，行为依赖当前持久化上下文配置。
- 接口不等待评估完成；若在 `PENDING/PROCESSING` 阶段调用，报告字段可能为空。
