# getReport：同步生成面试报告完整调用链

> 接口：`GET /api/interview/sessions/{sessionId}/report`  
> Controller：`InterviewController.getReport`  
> 当前前端状态：`interviewApi.getReport` 已定义，但没有页面调用

## 1. 先区分两条报告链路

当前主流程使用异步评估：最后一题或提前交卷后投递 Redis Stream，历史页轮询评估状态，再通过
`getInterviewDetail` 读取已保存报告。

`getReport` 是另一条保留的同步路径：每次调用都会直接调用 LLM 重新评估，并等待报告生成后才
返回。当前页面没有调用它。

## 2. 如果调用，前端请求

```ts
interviewApi.getReport(sessionId)
  → request.get(`/api/interview/sessions/${sessionId}/report`, {
      timeout: 180000,
    })
```

请求使用 GET，却会调用 LLM、写缓存和数据库，因此不是纯查询接口。

## 3. Controller 层

```java
@GetMapping("/api/interview/sessions/{sessionId}/report")
public Result<InterviewReportDTO> getReport(
    @PathVariable String sessionId
) {
  InterviewReportDTO report = sessionService.generateReport(sessionId);
  return Result.success(report);
}
```

Controller 同步等待 Service 完成全部评估。

## 4. Service：恢复并检查会话状态

`generateReport` 首先调用 `getOrRestoreSession`，优先读取 Redis，未命中则从数据库恢复。

只允许：

- `COMPLETED`
- `EVALUATED`

其他状态抛出：

```java
BusinessException(
    ErrorCode.INTERVIEW_NOT_COMPLETED,
    "面试尚未完成，无法生成报告"
)
```

即使状态已经是 `EVALUATED`，当前代码也不会直接返回数据库中的旧报告，而是继续重新调用 LLM。

## 5. Service：选择 Provider 并同步调用 LLM

```text
CachedSession.getQuestions(ObjectMapper)
  → persistenceService.findBySessionId(sessionId)
  → 从 Entity 读取 llmProvider；查不到时 provider=null
  → llmProviderRegistry.getChatClientOrDefault(provider)
  → evaluationService.evaluateInterview(
      chatClient, sessionId, resumeText, questions)
```

`AnswerEvaluationService.evaluateInterview` 负责构造评估 Prompt，通过结构化输出调用 LLM，并把
通用结果转换为 `InterviewReportDTO`，包括总分、分类得分、逐题反馈、优势、改进建议和参考答案。

这是同步外部调用，耗时直接占用当前 HTTP 请求。

## 6. 附加自适应分析

基础报告生成后调用：

```java
adaptiveInterviewService.attachAdaptiveAnalysis(sessionId, report)
```

它把持久化的能力画像和自适应决策轨迹附加到最终报告中，返回新的不可变 DTO。

## 7. 更新缓存和数据库

先更新 Redis：

```java
sessionCache.updateSessionStatus(sessionId, EVALUATED);
```

然后尝试：

```java
persistenceService.saveReport(sessionId, report);
```

`saveReport` 在事务中更新：

- 会话 `overallScore`、`overallFeedback`；
- `strengthsJson`、`improvementsJson`、`referenceAnswersJson`；
- 会话状态 `EVALUATED` 和完成时间；
- 每道答案的分数、反馈、参考答案和关键点；
- 未回答题目的占位答案记录。

但 `generateReport` 捕获数据库保存异常并继续返回报告，因此前端可能拿到成功报告，而数据库
仍没有保存结果。

## 8. 返回闭环

```text
InterviewReportDTO
  → Controller Result.success(report)
  → HTTP 200
  → Axios 拦截器提取 data
  → Promise<InterviewReport>
```

当前没有页面消费该 Promise。实际报告页调用的是
`historyApi.getInterviewDetail('/details')`。

## 9. 失败路径

| 场景 | 结果 |
|---|---|
| 会话不存在 | `INTERVIEW_SESSION_NOT_FOUND` |
| 面试未完成 | `INTERVIEW_NOT_COMPLETED` |
| Provider/LLM/结构化输出失败 | 对应 `BusinessException`，全局包装 |
| 报告数据库保存失败 | 只记录警告，接口仍成功 |

前端 API 若未来被调用，`request.ts` 会把业务错误转换成 rejected `Error(message)`。

## 10. 当前实现边界

- GET 接口具有昂贵且非幂等的外部调用和写操作，不符合纯查询语义。
- 已经 `EVALUATED` 仍会重复调用 LLM，可能产生重复费用和不同结果。
- 它与 Redis Stream 异步评估链路并存，可能并发生成两份报告并互相覆盖。
- Redis 先更新为 `EVALUATED`，数据库保存失败不会回滚。
- 当前前端未调用，主流程通过异步评估 + `/details` 展示报告。
