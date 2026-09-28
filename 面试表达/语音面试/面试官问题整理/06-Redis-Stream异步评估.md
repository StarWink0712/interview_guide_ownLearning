# Redis Stream 异步评估

## 1. 语音面试结束后为什么异步生成评估报告？

**回答：** LLM 生成多维度评估可能需要几十秒甚至更久，如果在 `endSession()` 中同步等待，用户退出页面会一直卡住。因此结束会话时只把 `evaluateStatus` 设为 `PENDING`，通过 Redis Stream 投递评估任务后立即返回，由后台消费者调用 LLM 生成报告。

## 2. 评估任务是如何投递和消费的？

**回答：** `endSession()` 末尾调用 `voiceEvaluateStreamProducer.sendEvaluateTask(sessionId)`，生产者继承 `AbstractStreamProducer`，使用 `XADD` 把 sessionId 写入 `voice:evaluate:stream`。`VoiceEvaluateStreamConsumer` 继承 `AbstractStreamConsumer`，在后台线程中轮询消费，再调用 `VoiceInterviewEvaluationService.generateEvaluation()`。

## 3. 消费者如何避免重复评估，失败后怎么处理？

**回答：** 消费前先检查会话的 `evaluateStatus`，如果已经是 `COMPLETED`，说明可能已经由手动流程完成，消费者直接 ACK 跳过。执行失败时最多重试 3 次，超过重试次数后将 `evaluateStatus` 标记为 `FAILED`。

## 4. generateEvaluation 的整体流程是什么？

**回答：** 方法先查询会话实体和按序号升序排列的所有对话消息；没有消息时保存空评估。存在消息时构造 `QaRecord` 列表，按会话配置取得指定或默认 ChatClient，再根据 `skillId` 构建评估参考上下文，在事务外调用 `unifiedEvaluationService.evaluate()` 得到 `EvaluationReport`，最后通过事务方法把总分、总评、逐题评分、优势、改进建议和参考答案保存到数据库。

## 5. 用户一句话没说就结束时，系统如何处理？

**回答：** 如果消息列表为空，系统调用 `saveEmptyEvaluationTransactional()`，保存总分 0、反馈“暂无可评估内容”的空评估，并把状态推进到 `COMPLETED`。这样前端轮询可以正常结束，不会因为没有可评估内容而永远停留在处理中。

## 6. 语音对话消息如何转换成 QaRecord？

**回答：** 语音面试记录中 AI 提问在前、用户回答在后，因此每条 `QaRecord` 的 question 取 `aiGeneratedText`，answer 取 `userRecognizedText`。构建后的问答列表作为统一评估服务的输入。

## 7. 问题分类是怎么推断的？

**回答：** `inferCategory` 根据 AI 问题文本中的关键词做启发式分类：包含“项目”或“实习”归为“项目深挖”，包含“职业规划”或“优缺点”归为“HR 问题”，其余默认归为“技术问题”。这个分类不要求完全精准，主要用于评估报告按类别汇总分数。

## 8. 评估时如何选择模型和技能参考信息？

**回答：** 服务从会话中读取 `llmProvider`，存在就使用指定 Provider，否则使用全局默认 Provider，并取得对应的 ChatClient。随后根据 `skillId` 构建评估参考上下文，把该技能方向的知识点和考察维度一起传给 LLM，使评估更有针对性。

## 9. 为什么 LLM 评估调用必须放在事务外？

**回答：** `unifiedEvaluationService.evaluate()` 是阻塞 I/O，可能持续几十秒。如果在 `@Transactional` 方法中调用，会在等待模型期间长期占用数据库连接，严重时导致连接池耗尽。因此系统在事务外完成 LLM 调用，只把最终结果交给 `saveEvaluationTransactional()` 在短事务中持久化。

## 10. 评估结果为什么使用 JSON 字段保存？

**回答：** `VoiceInterviewEvaluationEntity` 用普通字段保存 `overallScore` 和 `overallFeedback`，并用 `questionEvaluationsJson`、`strengthsJson`、`improvementsJson`、`referenceAnswersJson` 保存逐题评分、优势、改进建议和参考答案。每场面试的轮数和题目数量都不固定，无法设计固定数量的列，因此使用 JSON 更适合保存变长结构。

## 11. 前端如何获取评估进度和结果？

**回答：** 前端每 2 到 3 秒调用一次 `GET /api/voice-interview/sessions/{sessionId}/evaluation`。`PENDING` 显示排队中，`PROCESSING` 显示正在评估，`COMPLETED` 时停止轮询并加载 `VoiceEvaluationDetailDTO` 渲染报告，`FAILED` 时显示错误并提供重新评估按钮。

## 12. 手动重新评估如何保证幂等？

**回答：** 前端通过 `POST /evaluation` 手动触发时，Controller 先做三级状态判断：`COMPLETED` 直接返回已经保存的结果；`PENDING` 或 `PROCESSING` 只返回当前状态，不重复入队；只有 `FAILED` 或状态为 `null` 时才调用 `triggerEvaluation()` 创建新的评估任务，并返回 `PENDING`。

