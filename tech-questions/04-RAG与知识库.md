# RAG 与知识库

## 1. 面试官：什么是 RAG，为什么这个项目需要它？

我的回答：

RAG 是先从外部知识库检索证据，再把证据作为上下文交给大模型生成答案，用来弥补模型知识过时、私有资料不可见和事实不可追溯的问题。我的项目允许用户上传技术文档，切块后写入 pgvector；查询时按选定知识库召回相关 chunk，再把上下文和安全指令交给 ChatClient。它比把所有资料直接塞进 Prompt 更节省上下文，也能随文档更新。当前回答还没有强制逐句引用来源，模型仍可能脱离证据发挥；生产化会增加 chunk 引用、answerable 门控和忠实度评测。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java` — `query()`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java` — `similaritySearch()`
- `app/src/main/java/interview/guide/modules/knowledgebase/KnowledgeBaseController.java` — 查询接口

## 2. 面试官：原始文档和向量数据库分别存什么，为什么不能只存向量？

我的回答：

原始文档是可重新解析的事实源，向量库保存的是切块文本、embedding 和用于过滤的 metadata。我的项目把 PDF、Word、TXT 原文件保存到 S3 兼容对象存储，PostgreSQL 保存文件名、hash、storageKey、vectorStatus 和 chunkCount；Spring AI 的 `vector_store` 表保存 chunk 内容、1024 维向量及 `kb_id` 等元数据。只存向量会丢失原始版式和重建能力，模型或切分策略升级后无法重新生成索引。当前重向量化会重新下载原文件，设计正确；生产上还应给解析文本、切分策略和 embedding 模型增加版本。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/model/KnowledgeBaseEntity.java` — 文档元数据
- `app/src/main/java/interview/guide/infrastructure/file/FileStorageService.java` — 原文件存储
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java` — 向量写入
- `app/src/main/java/interview/guide/modules/knowledgebase/repository/VectorRepository.java` — `vector_store`

## 3. 面试官：PDF、Word 等文档如何解析，解析质量为什么会影响 RAG？

我的回答：

RAG 的上限首先受输入文本质量限制，乱码、页眉重复或顺序错乱会直接污染切块与召回。我的项目使用 Apache Tika `AutoDetectParser` 解析 PDF、DOC、DOCX 和 TXT，结合实际 MIME 检测，不只信任扩展名；PDF 采用按位置排序，关闭嵌入文档提取，并限制最大文本量。解析后由 `TextCleaningService` 去除控制字符、图片文件名、URL、HTML 和异常空行。当前没有 OCR，所以扫描版 PDF 可能得到空文本；复杂表格和双栏排版也可能顺序失真。生产上应增加 OCR、版面分析与解析质量检测。

代码依据：

- `app/src/main/java/interview/guide/infrastructure/file/DocumentParseService.java` — `parseDocument()`
- `app/src/main/java/interview/guide/infrastructure/file/ContentTypeDetectionService.java` — MIME 检测
- `app/src/main/java/interview/guide/infrastructure/file/TextCleaningService.java` — `cleanText()`
- `app/src/main/java/interview/guide/infrastructure/file/NoOpEmbeddedDocumentExtractor.java` — 禁用嵌入提取

## 4. 面试官：项目当前如何切分文档，Chunk 中保存哪些元数据？

我的回答：

切分是把长文档变成可嵌入和可召回的最小上下文单元。我的项目使用 Spring AI `TokenTextSplitter.builder().build()` 的默认配置，代码注释说明每个 chunk 约 800 token、无重叠，并尽量按标点边界切分。每个 `Document` 还携带 knowledgeBaseId、文件名和 chunk 序号等 metadata，后续检索可按 `kb_id` 限定文档集合，观测日志也能显示来源。当前没有保存页码、章节路径和父文档 ID，回答引用与上下文扩展能力有限；后续会在解析阶段保留结构元数据。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java` — `TokenTextSplitter`
- `app/src/main/java/interview/guide/modules/knowledgebase/model/KnowledgeBaseEntity.java` — 文档标识
- `app/src/main/java/interview/guide/modules/knowledgebase/repository/VectorRepository.java` — metadata 查询

## 5. 面试官：为什么选择约 800 token 的 Chunk，太大或太小分别有什么影响？

我的回答：

Chunk 大小是在语义完整、召回精度、上下文成本和 embedding 表达之间折中。我的项目沿用 `TokenTextSplitter` 约 800 token 默认值，适合一般技术文档：足以容纳一段完整解释，又不会让单个命中占满 Prompt。Chunk 太大会混入多个主题，相似度被稀释，召回后 token 成本高；太小会割裂定义与条件，产生大量向量并失去上下文。当前 800 是经验值，不是由项目数据验证的最优参数。生产上应在固定评测集上比较不同大小的 Recall@K、MRR、答案忠实度、延迟和成本。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java` — 默认 splitter
- `app/src/main/java/interview/guide/modules/aiobservability/service/RagEvaluationService.java` — 检索评测
- `app/src/main/resources/application.yml` — RAG 检索参数

## 6. 面试官：如何避免语义被 Chunk 边界切断，还有哪些切分方案？

我的回答：

避免语义切断的核心是让边界尊重文档结构，并在检索后补足邻近上下文。我的项目当前依赖 TokenTextSplitter 的标点边界，没有 overlap、标题层级、父子 chunk 或相邻 chunk 扩展，因此跨段定义和表格可能被拆散。可选方案包括按 Markdown 标题或 PDF 章节切分、固定窗口加 overlap、基于 embedding 变化的语义切分，以及“小块召回、父块生成”的父子索引。我的生产方案会先保留 sectionPath、page 和 chunkIndex，再按文档类型选择切分器；命中后可合并前后邻块，同时用最大 token 预算防止上下文膨胀。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java` — 当前切分实现
- `app/src/main/java/interview/guide/infrastructure/file/DocumentParseService.java` — 当前解析输出
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java` — 上下文拼接

## 7. 面试官：Embedding 模型如何选择，模型切换时要注意什么？

我的回答：

Embedding 模型应根据语言、领域、维度、最大输入、成本和检索质量选择，同一索引中的向量必须维度和语义空间一致。我的项目默认使用 DashScope `text-embedding-v3`、1024 维，Provider 配置声明是否支持 embedding，`LlmProviderRegistry` 单独解析默认 embedding Provider，pgvector 表也按 1024 维初始化。不能只改配置就让新模型查询旧向量，否则即使维度相同，空间也不兼容。生产切换时我会建立 indexVersion，后台全量重建新索引，用离线评测比较，再原子切换读流量并保留回滚窗口。

代码依据：

- `app/src/main/resources/application.yml` — embedding 模型与维度
- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java` — `getDefaultEmbeddingModel()`
- `app/src/main/java/interview/guide/common/config/LlmEmbeddingConfig.java` — VectorStore 配置
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java` — 向量化

## 8. 面试官：向量检索和关键词检索有什么区别，项目为什么还没有做混合检索？

我的回答：

向量检索按语义相似度匹配，能处理同义表达；关键词检索按词项和统计相关性匹配，对错误码、类名、精确术语更稳定。我的项目当前只通过 Spring AI VectorStore 做余弦相似度检索，RAG 评测中的关键词仅用于判断结果，不参与在线召回，因此精确标识和冷门专有名词可能漏检。混合检索可以把 pgvector 与 PostgreSQL 全文搜索或 Elasticsearch BM25 的候选合并，再用 RRF 或 reranker 排序。当前规模下单一向量链路简单，但生产上我会先用失败用例证明收益，再引入混合召回，避免无依据增加系统复杂度。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java` — `similaritySearch()`
- `app/src/main/java/interview/guide/modules/aiobservability/service/RagEvaluationService.java` — 关键词命中仅用于评测
- `app/src/main/resources/application.yml` — COSINE_DISTANCE

## 9. 面试官：Query Rewrite 解决什么问题，项目如何避免改写失败影响查询？

我的回答：

多轮对话中的“它怎么配置”缺少独立语义，Query Rewrite 会结合近期历史改写为可检索的完整问题；它也能补充同义词，但可能改变用户原意。我的项目在配置开启时用 LLM 生成 rewritten query，失败就保留原问题；检索先尝试改写问题，再尝试原始问题，取第一组有效结果，所以改写不是单点故障。RAG Chat 只加载最近已完成历史，避免残缺消息污染改写。当前没有评估改写忠实度，也没有同时融合两组候选。生产上会记录原问、改写和命中差异，对短指代问题启用，对完整问题可跳过。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java` — Query Rewrite
- `app/src/main/java/interview/guide/modules/knowledgebase/service/RagChatSessionService.java` — 历史上下文
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryProperties.java` — rewrite 开关

## 10. 面试官：动态 topK、相似度阈值、metadata filter 和 Rerank 如何配合？

我的回答：

topK 控制候选数量，阈值过滤低相关内容，metadata filter 保证业务隔离，rerank 则用更精确模型重排候选。我的项目按问题长度选择 topK：短问题歧义大，用 20 和较低阈值；中等问题 12；长问题 8，并用 `kb_id in [...]` 限定知识库。若 Provider 不支持预过滤，就扩大到 topK×3 后本地过滤。当前没有真正的 cross-encoder 或 LLM reranker，候选主要按向量分数直接使用；代码注释对长问题 topK 还有表述不一致。生产上会召回较多候选、过滤后 rerank，再截取上下文。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryProperties.java` — 动态参数
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java` — filter 与回退
- `app/src/main/resources/application.yml` — topK 与 minScore

## 11. 面试官：项目如何降低 RAG 幻觉和无资料回答？

我的回答：

降低 RAG 幻觉要同时控制检索质量和生成行为。我的项目在检索为空或分数不足时直接返回统一无结果提示，不让模型自由回答；有结果时只拼接选中 chunk，并追加防注入指令，要求依据资料。流式场景无法在发送后改 HTTP 状态，因此先缓存模型前约 120 个字符，若识别出“资料不足”表达就取消后续流并替换标准文案。当前判断仍是文本规则，也没有引用核验。生产上会在生成前计算证据覆盖率，用结构化 answerable 判定，并在答案后做 claim-to-context faithfulness 检查。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java` — 无结果与流式探测
- `app/src/main/java/interview/guide/common/ai/PromptSecurityConstants.java` — RAG 安全指令
- `app/src/main/java/interview/guide/modules/knowledgebase/KnowledgeBaseController.java` — SSE 查询

## 12. 面试官：知识库文档更新后如何更新向量，当前实现是增量更新吗？

我的回答：

索引更新必须避免新旧向量混杂，并允许失败恢复。我的项目提供 revectorize：先把文档状态改为待处理并投递 Stream，消费者重新下载原文件、解析全部文本；`KnowledgeBaseVectorService` 删除该 kbId 的旧向量，再分批写入全部新 chunk。因此它是按文档全量重建，不是只更新变化 chunk。外部 embedding 不放在长事务中，避免占用连接，但删除成功后写入失败会产生临时空索引；删除异常后继续写还可能重复。生产上我会用文档版本或 staging 索引，新版本完整构建校验后再原子切换。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseUploadService.java` — `revectorize()`
- `app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamConsumer.java` — 重建流程
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java` — 删除与批量写入
- `app/src/main/java/interview/guide/modules/knowledgebase/repository/VectorRepository.java` — `deleteByKnowledgeBaseId()`

## 13. 面试官：Recall@K、Precision@K、MRR 和 NDCG 分别衡量什么，项目实现了哪些？

我的回答：

Recall@K 衡量前 K 个结果覆盖了多少相关文档，Precision@K 衡量其中相关结果比例；MRR 关注第一个相关结果的排名倒数，NDCG 同时考虑多级相关性和位置折损。我的项目当前 RAG 评测会对每个用例执行真实向量检索，用 expectedKeywords 判断是否命中，汇总 matchedCases、平均延迟和结果预览，本质更接近粗粒度 Hit Rate，不是严格的四项排名指标，因为没有逐 chunk 相关性标注。生产上应给问题标注相关 chunk 集及等级，才能计算 Recall、Precision、MRR 和 NDCG 并比较检索策略。

代码依据：

- `app/src/main/java/interview/guide/modules/aiobservability/service/RagEvaluationService.java` — `runDataset()`
- `app/src/main/java/interview/guide/modules/aiobservability/model/RagEvalCaseEntity.java` — 评测用例
- `app/src/main/java/interview/guide/modules/aiobservability/model/RagEvalRunCaseResultEntity.java` — 当前结果字段
- `app/src/test/java/interview/guide/modules/aiobservability/service/RagEvaluationServiceTest.java` — 评测测试

## 14. 面试官：Context Relevance、Faithfulness、Answer Relevance 和 LLM-as-a-Judge 如何用于 RAG 评测？

我的回答：

Context Relevance 判断召回上下文是否针对问题，Faithfulness 判断答案陈述是否能由上下文支持，Answer Relevance 判断答案是否真正回应问题；LLM-as-a-Judge 可按 rubric 自动评分，但会受评审模型偏差影响。我的项目当前只评估检索是否命中关键词，没有对最终生成答案计算这三项，也没有 Judge 流程，所以我会明确说尚未完整实现。生产上我会保留确定性的排名指标，再让固定版本 Judge 输出带证据的结构化评分，并抽样人工复核；对关键用例使用双评审或一致性检查，避免把 Judge 分数当绝对真值。

代码依据：

- `app/src/main/java/interview/guide/modules/aiobservability/service/RagEvaluationService.java` — 当前仅检索评测
- `app/src/main/java/interview/guide/modules/aiobservability/model/RagEvalRunEntity.java` — 当前汇总指标
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java` — 待评估生成链路

## 15. 面试官：线上答案不好时，如何区分是检索问题还是生成问题？

我的回答：

排障必须把 rewrite、retrieval 和 generation 分段观察。我的项目为每次 RAG 查询保存 traceId、原问题、改写结果、知识库 ID、topK、阈值、命中数量、分数、chunk 预览和阶段耗时，前端还能通过 trace 事件跳转。如果相关 chunk 根本没出现，是解析、切分、embedding、filter 或召回参数问题；相关 chunk 已在前列但答案错误，则重点检查 Prompt、上下文截断和模型忠实度；上下文相关但答案答非所问，则是生成相关性问题。当前预览会截断，生产上应在受控权限下关联完整 chunk，并用失败标签回流评测集。

代码依据：

- `app/src/main/java/interview/guide/modules/aiobservability/service/RagRetrievalLogService.java` — 检索日志
- `app/src/main/java/interview/guide/modules/aiobservability/model/RagRetrievedChunkLogEntity.java` — chunk 明细
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java` — 分阶段记录
- `frontend/src/api/ragChat.ts` — trace SSE 事件

# 本章总结

项目的 RAG 已实现原文件保存、解析清洗、Token 切分、Embedding、pgvector 过滤检索、Query Rewrite、动态参数、流式回答和检索评测。

当前主要边界是切分策略单一、没有混合检索与 rerank、索引重建非原子，以及评测停留在关键词命中，尚缺严格排名指标和答案忠实度评测。

# 核心主线

以可重建的文档索引提供受业务范围约束的证据，再用分阶段日志和评测区分召回质量与生成质量。
