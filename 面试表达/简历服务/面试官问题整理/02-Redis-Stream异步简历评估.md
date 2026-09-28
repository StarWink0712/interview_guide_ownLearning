# Redis Stream 异步简历评估

## 1. 简历分析任务是如何投递的？

**回答：** 文件和 `ResumeEntity` 保存完成后，`AnalyzeStreamProducer.sendAnalyzeTask()` 先创建异步任务记录，再把包含 taskId 和 resumeId 的轻量消息写入 `resume:analyze:stream`。上传请求随后返回 PENDING，模型分析由消费者在后台执行。

通过redisson服务端

## 2. AnalyzeStreamConsumer 如何运行？

**回答：** `AnalyzeStreamConsumer` 继承 `AbstractStreamConsumer`，启动后使用虚拟线程持续监听 Redis Stream。父类负责拉取消息、状态流转、ACK、失败重试和终态处理，子类只实现简历分析所需的 `processBusiness()`。

## 3. 消费者收到任务后做什么？

**回答：** 消费者解析 resumeId，从数据库加载 `ResumeEntity`，取得可用的简历正文，调用 `ResumeGradingService.analyzeResume()` 完成结构化 LLM 评分，再由 `ResumePersistenceService.saveAnalysis()` 保存结果，并将简历与异步任务状态更新为 COMPLETED。

消费者从redis stream中监测到消息后会一直启动消费循环，用虚拟线程执行业务，执行完之后会把简历和异步任务的状态更新为完成，之后再ack消息，然后将处于redis pel列表中的消息移除，如果中间出现故障就会补货异常判断一下当前重试次数有没有超过最大重试次数，如果没有就把旧消息清除重写发送新消息到消息队列中，到达最大次数的话就只能在数据库中做一下标记然后人工处理。消费者是一直处于阻塞等待超时重新阻塞等待的循环中

## 4. 分析期间简历被删除了怎么办？

**回答：** 消费者处理前会按 resumeId 查询实体。如果用户在排队或重试期间已经删除简历，系统抛出 `AsyncTaskCancelledException`，由异步任务框架优雅取消，不继续调用 LLM，也不把这种业务删除当成普通失败反复重试。

## 5. 消费者如何获取简历文本？

**回答：** `resolveResumeText()` 优先读取 `ResumeEntity.resumeText`，这是上传时已经解析并缓存到数据库的正文。只有数据库文本为空时，才根据 storageKey 从 RustFS 下载原文件，再通过 Tika 重新解析，保证历史数据或异常情况下仍能继续分析。

直接用简历id去数据库中查即可，如果数据库为空就去rustfs下载原件再用tika解析再存数据库。

## 6. ResumeGradingService 如何构建 Prompt？

**回答：** 系统 Prompt 和用户 Prompt 从 `resources/prompts/` 下的 StringTemplate 模板加载，把简历全文作为变量注入。模板规定评分维度、结果字段和输出规则，再通过 LLM 注册中心取得 ChatClient，交给 `StructuredOutputInvoker` 调用模型。

## 7. 为什么简历评分要使用 StructuredOutputInvoker？

**回答：** 后端需要稳定获得可持久化和展示的 `ResumeAnalysisResponse`，而不是一段自由文本。结构化输出器要求模型返回 JSON，处理 JSON 修复和最多 3 次重试，再反序列化成确定 DTO，降低代码块、解释文字或格式错误导致的失败。

简历评估用结构化输出器，内部通过beanOutputConvert转换器获取到对应对象的json格式，然后用convertWithRepair转换器转换成对应的java对象。

## 8. 简历分析包含哪五个评分维度？

**回答：** 五个维度是项目经验 40 分、技能匹配度 20 分、内容完整性 15 分、结构清晰度 15 分和表达专业性 10 分，总分为 100。除此之外，模型还生成综合总结、优势列表和改进建议。主要是五个字段作为分数，若干个字段作为综合优势，优势关键词和一些改进建议。

## 9. ResumeAnalysisResponse 包含什么？

**回答：** 响应对象包含综合分数、五个维度的分项得分、总体总结、优势亮点和改进建议。它是 StructuredOutputInvoker 最终反序列化的业务对象，也是前端分析详情和 PDF 报告的数据来源。

## 10. 分析结果如何持久化？

**回答：** `ResumePersistenceService` 将结果保存到 `ResumeAnalysisEntity`，包括 overallScore、scoreDetail JSON、strengths JSON 和 suggestions JSON，并通过 resumeId 关联原始简历。分析完成后同时把 `ResumeEntity.analyzeStatus` 和异步任务状态改为 COMPLETED。

## 11. 为什么简历和分析结果要分表保存？

**回答：** `ResumeEntity` 保存文件、正文和当前状态，`ResumeAnalysisEntity` 保存每次 AI 评分结果。同一份简历可能重新分析并保留多条历史记录，因此两者是一对多关系；评分详情、优势和建议数量不固定，使用 JSON 字段更灵活。

简历结果与分析结果分开保存一是因为分析结果字段太多，并且一个简历也可以进行多次分析，然后简历上传时查重时可以选择再次进行评估也可以直接返回之前评估过的结果。两个表通过简历id关联起来即可。

## 12. 简历分析失败如何重试？

**回答：** 消费链路最多重试 3 次。可恢复异常会由父类重新投递并增加重试次数；超过上限后任务与 `analyzeStatus` 标记为 FAILED。结构化输出器内部还会对单次 LLM JSON 输出执行自己的修复和重试，两层机制分别处理输出格式问题和异步任务级失败。消费链路失败抛出异常的画可以补货异常由消费者守护线程再次投递出去。

## 13. 前端如何得到最终分析结果？

**回答：** 上传成功先显示 PENDING，并轮询简历详情或分析状态。状态变成 COMPLETED 后读取 `ResumeAnalysisEntity` 对应的评分、优势和建议；FAILED 时显示失败状态并允许后续重新分析。上传接口本身不长时间等待模型。
