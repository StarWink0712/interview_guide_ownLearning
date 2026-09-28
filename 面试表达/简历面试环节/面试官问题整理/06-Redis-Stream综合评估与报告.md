# Redis Stream 综合评估与报告

## 1. 综合评估在什么时候触发？

**回答：** 当用户提交最后一道题后，系统先把会话状态设为 COMPLETED，再把独立的 `evaluateStatus` 设为 PENDING，然后调用 `EvaluateStreamProducer.sendEvaluateTask(sessionId)`。综合报告在后台异步生成，不阻塞最后一次答题响应。

## 2. sessionStatus 和 evaluateStatus 为什么要分开？

**回答：** `sessionStatus=COMPLETED` 表示题目已经全部回答完，`evaluateStatus=PENDING/PROCESSING/COMPLETED/FAILED` 表示综合报告的处理进度。答题结束和报告生成是两个不同生命周期，拆开后前端可以立即结束面试，同时继续轮询评估状态。

## 3. Producer 如何避免同一会话重复入队？

**回答：** `AsyncTaskService.createOrReusePendingTask()` 通过 activeFlag 机制查找或复用同一会话的活跃 PENDING 任务，只有没有可复用任务时才创建新记录。这样重复提交或重复触发不会为同一会话产生多个并行综合评估任务。

其实就是设置一个标记，当会话开始时就会把标记改成面试中，这样就不会导致同一个会话重复入队了。并且开启新的会话的话，会话的id也是不同的，也不太可能出现同一个会话重复入队的情况。

## 4. 为什么评估消息也要在事务提交后发送？

**回答：** 最后一题的答案、会话状态和 evaluateStatus 需要先提交到数据库，消费者随后才能读取到完整数据。Producer 使用 `TransactionAfterCommitExecutor`，等事务成功提交后再把 `{taskId, sessionId, retryCount:0}` XADD 到 Redis Stream，避免消费者抢先读取未提交状态。

即事务消息的机制，肯定要在事务提交之后再发送。

两阶段提交：先发"半消息"（消费者不可见）→ 执行本地事务 → 成功则提交半消息让消费者可见，失败则回滚。如果提交/回滚因为网络问题没发到 Broker，Broker 会定期回调 check 接口确认最终状态。好处是即使本地事务提交后立刻宕机，半消息已经在 Broker 了，不会丢。

## 5. EvaluateStreamConsumer 的标准消费流程是什么？

**回答：** 消费者继承 `AbstractStreamConsumer`，从消费者组拉取消息，解析 sessionId 后把异步任务和会话评估状态都标记为 PROCESSING。随后加载会话和全部答案，执行综合评估并保存报告；成功后由 `markCompleted()` 把任务和会话评估状态更新为 COMPLETED。

## 6. 消费者如何构建完整问答数据集？

**回答：** 消费者先反序列化会话的 `questionsJson`，再从 `interview_answers` 查询该会话的全部答案，按 questionIndex 把用户答案和已有单题评分填回对应问题，形成包含题目、答案、分类等信息的完整数据集，供最终报告评估使用。

从数据库中查询答案啊。用qusetion完全体拼到提示词中，给llm进行评估。

## 7. AnswerEvaluationService 如何准备统一评估输入？

**回答：** 它把面试题目转换成通用 `QaRecord` 列表，并结合候选人的简历文本以及 Skill 参考基线交给 `UnifiedEvaluationService`。这样统一评估既能判断回答本身质量，也能结合候选人经历和目标技能要求给出针对性反馈。

## 8. 综合评估的第一阶段“分批评估”是什么？

**回答：** 题目按配置的 `batchSize` 分组，每个批次独立调用 LLM，生成该批问题的逐题评分、反馈以及阶段性综合结论。分批可以控制单次 Prompt 长度，避免题目和答案过多导致上下文超限或一次调用不稳定。

## 9. 第二阶段如何合并多个批次？

**回答：** 系统按题目索引合并各批次的逐题评分，缺失项使用零分兜底，保证最终题目数量完整；同时汇总优势和改进建议，进行去重与数量截断，得到稳定的批次聚合结果。

## 10. 为什么还要进行第三阶段二次汇总？

**回答：** 各批次独立评估可能存在口径差异，因此系统再调用一次 LLM，基于批次中间结果生成统一的总分、分类结论和综合评价。如果二次汇总调用失败，就降级使用已经完成的批次聚合结果，不让最后一步失败导致整份报告丢失。

## 11. InterviewReportDTO 包含哪些内容？

**回答：** 报告包含总分、各分类得分、逐题评分与反馈、总体评价、优势列表、改进建议和参考答案。随后 `attachAdaptiveAnalysis()` 还会从会话表反序列化能力画像与决策追踪链，将单题自适应分析注入最终报告。

## 12. 综合报告如何持久化？

**回答：** `persistenceService.saveReport()` 把完整报告写入 `interview_sessions`，并把最终逐题评分回填到 `interview_answers` 对应记录。处理结束后会话进入 EVALUATED 或评估状态 COMPLETED，保证会话汇总和单题明细保持一致。

## 13. 消费失败后的重试和终态是什么？

**回答：** 消费过程中的异常由 `AbstractStreamConsumer` 统一捕获，最多重试 3 次。超过上限后进入死信终态，把会话 `evaluateStatus` 标记为 FAILED；成功时 `markCompleted()` 将任务和会话评估状态都置为 COMPLETED。

## 14. 前端如何获取最终报告？

**回答：** 前端可以根据 evaluateStatus 展示等待、处理中或失败状态；处理完成后调用 `GET /api/interview/sessions/{sessionId}/report` 获取完整 `InterviewReportDTO`，展示总分、分类得分、逐题反馈、优势、改进建议、参考答案和自适应能力画像。
