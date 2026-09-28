# 简历面试环节面试问题索引

本目录根据上一级目录中的两篇简历面试笔记整理。原始笔记保持不变；分类文件按照面试官可能追问的问题逐题组织答案，答案只重组原笔记中的信息。

## 一、会话创建与未完成会话恢复

- 面试 Controller 为什么依赖三个不同的 Service？
- 创建面试会话接口如何限流？
- CreateInterviewRequest 包含哪些字段？
- 创建面试时简历和 skillId 分别是不是必填？
- 为什么创建新会话前要先查未完成会话？
- Redis 中为什么同时缓存会话本身和 resumeId 到 sessionId 的映射？
- Redis 没找到未完成会话时怎么恢复？
- forceCreate 字段有什么作用？
- 新会话的 sessionId 和默认参数怎么生成？
- 系统如何查询历史上已经考过的问题？
- 历史问题如何用于避免 LLM 重复出题？

对应文件：[01-会话创建与未完成会话恢复.md](01-会话创建与未完成会话恢复.md)

## 二、简历与方向个性化出题

- generateQuestionsBySkill 的主要职责是什么？
- Skill 定义在出题时提供哪些信息？
- 难度参数如何转换成模型能理解的描述？
- 结构化出题为什么使用 Plain ChatClient？
- 没有简历时如何生成方向题？
- 方向题的分类配额如何生成？
- Skill 参考资料和 JD 如何注入 Prompt？
- 无简历模式的系统 Prompt 如何构建？
- 有简历时题目比例如何分配？
- 为什么简历题和方向题要并行生成？
- generateResumeQuestions 和 generateDirectionOnly 分别关注什么？
- 出题时系统提示词和用户提示词如何分工？
- 模型返回的 QuestionListDTO 结构是什么？
- QuestionDTO 如何转换成 InterviewQuestionDTO？
- LLM 多生成题目时如何保证主问题数量准确？

对应文件：[02-简历与方向个性化出题.md](02-简历与方向个性化出题.md)

## 三、结构化输出、并发与会话持久化

- StructuredOutputInvoker 如何调用 LLM 生成题目？
- 为什么还要额外强调禁止 Markdown 和解释文字？
- LLM 返回的 JSON 不合法时如何处理？
- 结构化出题全部重试失败后怎么办？
- 本地兜底题是怎么生成的？
- 简历题生成失败时如何降级？
- 方向题生成失败时如何处理？
- 两路题目成功后如何合并索引？
- questionExecutor 为什么使用虚拟线程？
- 虚拟线程执行器与传统线程池有什么区别？
- questionExecutor 如何在应用关闭时释放？
- 新会话生成题目后为什么先写 Redis？
- 会话数据库中保存哪些字段？
- 数据库保存会话失败为什么只记录 warn？
- 创建会话最终返回哪些信息？

对应文件：[03-结构化输出并发与会话持久化.md](03-结构化输出并发与会话持久化.md)

## 四、答题提交与 Redis、数据库双写

- 用户提交答案时调用什么接口？
- submitAnswer 如何取得当前会话？
- 如何校验前端提交的 questionIndex？
- 为什么使用 question.withAnswer(answer) 更新题目？
- 系统如何判断是否还有下一题？
- 提交答案时为什么先更新 Redis？
- Redis 更新后，数据库会写入哪些内容？
- interview_answers 表为什么是每道题一行？
- saveAnswer 如何保证重复提交的幂等性？
- 为什么数据库写入失败不阻断答题响应？
- 会话表和答案表分别保存什么自适应数据？

对应文件：[04-答题提交与Redis数据库双写.md](04-答题提交与Redis数据库双写.md)

## 五、单题即时评估与自适应决策

- 为什么每提交一题都要做即时评估？
- evaluateAndDecideSafely 为什么要带 Safely？
- 单题评估前需要从会话表加载哪些信息？
- 自适应评估的三步编排是什么？
- TurnEvaluationDTO 包含哪些内容？
- 能力画像的加权移动平均如何计算？
- 能力画像的置信度如何更新？
- 薄弱知识点如何合并？
- AbilityProfileDTO 最终包含什么？
- AdaptiveDecisionDTO 包含哪些决策字段？
- 自适应决策的本地降级规则是什么？
- AdaptiveInterviewResultDTO 为什么要组合三部分数据？
- 单题评估结果如何持久化？
- SubmitAnswerResponse 返回哪些自适应信息？

对应文件：[05-单题即时评估与自适应决策.md](05-单题即时评估与自适应决策.md)

## 六、Redis Stream 综合评估与报告

- 综合评估在什么时候触发？
- sessionStatus 和 evaluateStatus 为什么要分开？
- Producer 如何避免同一会话重复入队？
- 为什么评估消息也要在事务提交后发送？
- EvaluateStreamConsumer 的标准消费流程是什么？
- 消费者如何构建完整问答数据集？
- AnswerEvaluationService 如何准备统一评估输入？
- 综合评估的第一阶段“分批评估”是什么？
- 第二阶段如何合并多个批次？
- 为什么还要进行第三阶段二次汇总？
- InterviewReportDTO 包含哪些内容？
- 综合报告如何持久化？
- 消费失败后的重试和终态是什么？
- 前端如何获取最终报告？

对应文件：[06-Redis-Stream综合评估与报告.md](06-Redis-Stream综合评估与报告.md)
