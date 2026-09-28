# 答题提交与 Redis、数据库双写

## 1. 用户提交答案时调用什么接口？

**回答：** 前端调用 `POST /api/interview/sessions/{sessionId}/answers`，请求体包含 `questionIndex` 和 `answer`。Controller 将 URL 中的 sessionId 与请求体字段组合成 `SubmitAnswerRequest` record，再调用 `InterviewSessionService.submitAnswer()`。

## 2. submitAnswer 如何取得当前会话？

**回答：** 方法先调用 `getOrRestoreSession()`，优先从 Redis 读取 `CachedSession`；缓存未命中时，从 `interview_sessions` 查询实体、反序列化 `questionsJson` 并重新写回缓存，同时刷新 TTL。两边都找不到时抛出 `BusinessException(INTERVIEW_SESSION_NOT_FOUND)`。

<br />

先从redis中拿到会话信息，然后验证当前题目的index是不是在问题列表中的，然后就将用户的答案写入到问题列表中再存回redis中，然后再用trycatch更新数据库，同时同步调用llm评估已经提交的这个题目，提交到最后一题时会把全部的问题合并评估。

有一个问题就是，如果提交了一个题目，就发现数据库断开了连接，那可以先在本地或在redis中记录一下，只要redis中有数据，事后可以通过人工或定时扫描对比数据库和redis进行同步。单体是同步评估的，答完之后总的评估是异步的。因为已经回答完了不需要急着等待结果。等异步评估完即可。

<br />

## 3. 如何校验前端提交的 questionIndex？

**回答：** 系统从缓存反序列化出 `List<InterviewQuestionDTO>`，读取请求中的 index。如果 `index < 0` 或 `index >= questions.size()`，就抛出 `BusinessException(INTERVIEW_QUESTION_NOT_FOUND)`；只有合法索引才能继续写答案，避免越界或给不存在的题目提交内容。

## 4. 为什么使用 question.withAnswer(answer) 更新题目？

**回答：** `InterviewQuestionDTO` 是不可变数据对象，不能直接修改字段。`withAnswer(answer)` 创建一个题目信息不变、答案已填充的新副本，再替换回问题列表，既保持不可变对象的安全性，也得到包含最新答案的完整列表用于写回 Redis。

## 5. 系统如何判断是否还有下一题？

**回答：** 当前答案写入后计算 `newIndex = index + 1`，通过 `newIndex < questions.size()` 判断 `hasNextQuestion`。有下一题时取出对应 DTO；已经到末尾时 `nextQuestion=null`，并将会话状态更新为 COMPLETED，随后触发综合评估。

<br />

## 6. 提交答案时为什么先更新 Redis？

**回答：** 实时答题过程主要从 Redis 读取会话，系统先通过 `updateQuestions` 保存包含最新答案的完整题目列表，再通过 `updateCurrentIndex` 更新进度，最后一题时还通过 `updateSessionStatus` 写入 COMPLETED。先保证缓存主状态可用，可以让前端快速推进到下一题。

<br />

## 7. Redis 更新后，数据库会写入哪些内容？

**回答：** 数据库持久化包括三部分：`saveAnswer()` 保存当前题目、分类、用户答案、初始分数和反馈；`updateCurrentQuestionIndex()` 更新会话当前进度；`updateSessionStatus()` 更新 IN\_PROGRESS 或 COMPLETED 状态。进入 COMPLETED 或 EVALUATED 时会自动记录 `completedAt`。

数据库更新也是更新当前题目的答案，以及llm给出的评估

## 8. interview\_answers 表为什么是每道题一行？

**回答：** 所有会话共用 `interview_answers` 表，每条记录通过 sessionId 外键关联会话，并保存 questionIndex、question、userAnswer、score 和 feedback。一个会话对应多道答案，按题拆行便于单题 upsert、即时评分回填和最终报告逐题更新。

## 9. saveAnswer 如何保证重复提交的幂等性？

**回答：** 方法按 `(sessionId, questionIndex)` 查询现有记录，存在就覆盖题目和答案，不存在才创建 `InterviewAnswerEntity` 并关联会话。数据库在 `(session_id, question_index)` 上还有联合唯一约束，防止并发情况下同一道题插入多行。

## 10. 为什么数据库写入失败不阻断答题响应？

**回答：** 数据库写入被 try-catch 包裹，异常只记录 warn，Redis 中已经保存的答案、索引和状态仍可支持当前答题会话继续。这个设计优先保证实时交互可用性，但数据库是最终报告的数据来源，因此持久化失败需要依赖日志和监控及时发现。

## 11. 会话表和答案表分别保存什么自适应数据？

**回答：** `interview_answers` 保存每道题的用户答案、即时 score 和 feedback；`interview_sessions` 保存全局 `abilityProfileJson` 和自适应决策追踪链 JSON。单题结果按行落在答案表，跨多轮累积的能力画像和决策历史放在会话表。
