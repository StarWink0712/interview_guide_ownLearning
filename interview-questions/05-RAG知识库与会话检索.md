# RAG 与知识库

## 1. 面试官：知识库文档从上传到可检索经历哪些步骤？

我的回答：

我的项目对知识库文件先做 50MB 限制、真实 MIME 检测和 SHA-256 去重，再用 Tika 解析清洗、上传 S3 兼容存储并以 PENDING 状态保存元数据。事务提交后，`VectorizeStreamProducer` 只投递 taskId 和 knowledgeBaseId；消费者确认实体仍存在且未完成，重新下载原文件解析，调用 `KnowledgeBaseVectorService` 切块、批量生成 embedding 并写 pgvector，最后更新 chunkCount 和 COMPLETED。失败由公共 Stream 模板最多重试三次并记录任务状态。删除中的实体会直接 ACK 取消，避免异步任务把已删除知识库重新写回。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseUploadService.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamProducer.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamConsumer.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java`

## 2. 面试官：项目如何切块和批量写向量，为什么没有把整个向量化过程放进事务？

我的回答：

我这里使用 Spring AI 的 `TokenTextSplitter` 把清洗文本切成约 800 token 的片段，并在 metadata 写入知识库 ID、文件名和 chunk 序号。由于 DashScope embedding 批量上限，代码按最多 10 个 Document 一批调用 `VectorStore.add`。重新向量化前先用短事务删除旧向量，随后在事务外执行切块和远程 embedding，避免长时间占用数据库连接或让网络超时拖住事务。代价是删除成功、重新写入失败时会暂时没有向量；而删除异常当前被记录后可能继续写入，还可能产生重复，生产上应采用版本化索引、构建完成后原子切换。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/repository/VectorRepository.java`
- `app/src/test/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorServiceTransactionTest.java`

## 3. 面试官：一次 RAG 查询的完整调用链是什么？

我的回答：

我的项目先校验知识库 ID 和问题并生成 traceId；如果开启 query rewrite，就把近期上下文交给 LLM 生成更适合检索的问题，失败则保留原问题。检索会先试改写问题、再试原问题，按问题长度选择 topK 和最低相似度，并用 `kb_id` filter 限定知识库；若 Provider 不支持过滤表达式，就扩大候选集后本地过滤。命中文档被拼成受安全边界包裹的上下文，再交给默认 ChatClient生成答案，同时记录检索耗时、分数和片段预览。无有效命中时返回统一提示，不让模型凭空回答。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryProperties.java`
- `app/src/main/java/interview/guide/modules/aiobservability/service/RagRetrievalLogService.java`

## 4. 面试官：为什么短问题和长问题使用不同 topK 与阈值？当前参数有什么值得复核的地方？

我的回答：

我根据问题长度动态调整召回：很短的问题信息少、歧义大，因此用更高 topK 和更低阈值扩大候选；中等问题提高阈值；较长问题语义更充分，当前配置使用较小 topK，控制上下文与成本。参数集中在 `KnowledgeBaseQueryProperties`，可用环境变量调整，而不是散落硬编码。这是合理启发式，但不是经过数据集验证的最优值，而且代码注释中对长查询 topK 的描述与实际 `topk-long: 8` 存在不一致。我的改进是用项目已有 RAG 评测集做网格实验，以命中率、延迟和 token 成本共同选择参数。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryProperties.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java`
- `app/src/main/resources/application.yml`
- `app/src/main/java/interview/guide/modules/aiobservability/service/RagEvaluationService.java`

## 5. 面试官：RAG 流式回答怎样处理“模型其实回答不了”的情况？

我的回答：

我的项目同步接口可以在生成前根据检索结果直接返回无结果提示；流式接口更复杂，因为响应已经开始发送。当前实现先探测模型输出的前约 120 个字符，识别“根据资料无法回答”等模式，如果命中就取消后续流并替换为统一无结果文案，否则再把探测缓冲和后续 token 继续推送。这样减少前端先看到半句幻觉再被纠正的问题。它仍是文本规则，不保证覆盖所有表达，也可能误判。更稳妥的方案是在生成前基于最高分、重排分数和证据覆盖率做门控，或让模型先返回结构化 `answerable` 再开始 SSE。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/KnowledgeBaseController.java`
- `frontend/src/api/knowledgebase.ts`

## 6. 面试官：RAG 多轮会话怎样保存历史，流式中断会留下什么状态？

我的回答：

我的项目用关系表保存会话与消息，并由会话关联多个知识库。发送消息时先在短事务中保存 USER 消息和一个 `completed=false` 的 ASSISTANT 占位消息，计算 messageOrder；随后事务外执行检索和 SSE，正常结束再回写完整答案与完成状态，异常则记录错误。构造上下文时只取最近已完成消息并按时间恢复顺序，避免把当前问题和残缺回答重复注入。这样即使流式调用很长也不占数据库事务，但应用崩溃会留下未完成占位记录；当前也没有同一会话并发轮次锁，后续需要超时清理、恢复标记和顺序唯一约束。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/RagChatSessionService.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/RagChatController.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/model/RagChatMessageEntity.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/repository/RagChatMessageRepository.java`

## 7. 面试官：项目的 RAG 评测已经做到什么程度，哪些能力还没有实现？

我的回答：

我的项目已经有评测数据集、用例、运行批次和逐用例结果表。运行时同步遍历用例，按指定知识库执行真实 `similaritySearch`，统计是否召回文档、匹配了多少期望关键词、延迟和预览，再汇总命中率等结果。这可以回归切块、embedding、topK 和阈值变化对“检索”的影响，也有对应单元测试。但它还不是完整 RAGAS 或 LLM-as-Judge：没有评估最终生成答案的忠实度、相关性与引用正确性，关键词命中也可能漏掉同义表达。后续我会保留确定性指标，再增加人工标注集和语义评审。

代码依据：

- `app/src/main/java/interview/guide/modules/aiobservability/service/RagEvaluationService.java`
- `app/src/main/java/interview/guide/modules/aiobservability/model/RagEvalDatasetEntity.java`
- `app/src/main/java/interview/guide/modules/aiobservability/model/RagEvalRunCaseResultEntity.java`
- `app/src/test/java/interview/guide/modules/aiobservability/service/RagEvaluationServiceTest.java`

# 本章总结

知识库链路通过对象存储保存原文、Redis Stream 异步向量化、pgvector 负责召回，再以查询改写、动态参数、安全上下文和流式生成完成 RAG。会话与评测模块让检索不仅能用，还能追踪和回归。

当前重点边界是重建索引的原子性、流式残缺消息、并发会话顺序以及评测只覆盖检索层。

# 核心主线

把文档可靠转成带业务隔离元数据的向量，再以可观测、可回退的检索结果约束模型回答。
