# 文本分块、Embedding 与 PgVector 索引

## 1. 模块基础讲解

`KnowledgeBaseVectorService` 使用 Spring AI `TokenTextSplitter.builder().build()` 把全文转为 `Document` chunk，
为每块写入字符串类型 `kb_id`，再以最多 10 块一批调用 `VectorStore.add`。查询使用 `SearchRequest` 的 query、
topK、similarityThreshold 和 metadata filter，底层配置为 PostgreSQL pgvector、1024 维、COSINE_DISTANCE、HNSW。

默认 splitter 的目标块大小是 800 token、最小块字符数 350、最小嵌入长度 5、最大 10000 块，基于
CL100K_BASE 编码并尽量在标点处截断。当前代码注释称“无重叠”，代码也确实没有额外 overlap 实现；同时没有显式
加入中文标点、页码、来源、chunkIndex、parserVersion 或 embeddingVersion metadata。

Spring AI 官方文档确认 pgvector 可把通用 metadata filter 转为 PostgreSQL JSON path，并提供结构化
`FilterExpressionBuilder`；HNSW 查询召回/速度较好，但构建更慢、内存高于 IVFFlat。

## 2. 基础面试问题

**问题：为什么要分块，不能整篇生成向量吗？**

回答：整篇文档可能超过 Embedding 模型上限，且一个向量会稀释局部主题。分块能让查询命中具体段落，降低注入
LLM 的上下文长度。块太小会丢语义，太大又会降低定位精度并增加 token 成本。

**问题：Embedding 是什么？**

回答：Embedding 把文本映射到固定维度的稠密向量，语义相近文本在向量空间距离更近。查询时对问题也生成向量，
再用余弦距离等度量找近邻。它不是关键词索引，也不能保证事实正确。

**问题：为什么使用余弦距离？**

回答：余弦关注向量方向而非绝对长度，常用于文本语义相似度。必须与模型训练和向量库 operator class 一致；若
模型输出已归一化，内积或欧氏距离也可能更高效，但不能随意切换。

**问题：HNSW 与 IVFFlat 有何区别？**

回答：HNSW 是多层近邻图，通常查询速度和召回权衡更好、无需训练，但建索引慢且占内存；IVFFlat 将向量聚类到
多个 list，构建快、内存较省，但需合适训练数据和 probes 参数。小数据可用精确扫描作为基线。

## 3. 进阶面试问题

**问题：当前“先删后写”有什么风险？**

回答：重向量化先删除全部旧向量，再分批调用外部 Embedding 和数据库写入。任一批失败会丢失旧可用版本并留下
0 到 N 批新数据。它避免大事务占连接，但牺牲了原子可见性。

**问题：为什么 `@Transactional` 不能包住整个向量化？**

回答：`VectorStore.add` 内含外部 Embedding API，事务期间等待网络会长期占用连接；而且外部调用无法随 DB 回滚。
正确做法是 staging/version：外部计算和分批写新版本，校验完整后短事务切换指针。

**问题：Embedding 模型变更为何危险？**

回答：即便维度同为 1024，不同模型的坐标空间也不兼容；新查询向量与旧文档向量比较没有语义保证。当前 metadata
没有 model/provider/version，动态切换默认 Embedding Provider 后可能静默混合向量空间。

**问题：回退检索为什么会漏召回？**

回答：过滤表达式失败后，代码只全局取 `topK * 3`，再在 JVM 过滤选定 kbId。若全局前 3K 被其他知识库占满，
目标知识库的相关块根本不会返回，产生假零命中；数据量越大、知识库越多越严重。

## 4. 技术延伸问题

**问题：如何设计更好的 chunk？**

回答：按标题、段落、表格、代码块和页结构先语义切分，再做 token 上限；加入 10% 到 20% overlap；为中文配置
`。？！；`；保留 source/page/section/chunkIndex；按文档类型调参，并通过 Recall@K 和答案指标验证。

**问题：什么是混合检索？**

回答：将向量语义召回与 BM25/全文检索结合，再用 RRF 或加权融合。专有名词、错误码、类名和数字往往更适合词法
检索；自然语言改写更适合向量召回。之后可用 cross-encoder/LLM reranker 精排。

**问题：如何做多租户隔离？**

回答：每个 chunk metadata 至少含 tenantId，并在数据库层强制过滤；高隔离场景使用独立 schema/table/index；查询
服务不能接受客户端任意 kbId 后直接搜索，还要验证知识库归属和权限。

参考：[Spring AI PgVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)、
[Spring AI ETL/TokenTextSplitter](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html)。

## 5. 改进方案问题

**问题：如何实现零停机重建索引？**

回答：生成 `vectorVersion`，新 chunk 全部写 staging 版本；核对实际 chunk 数、模型版本和校验和；短事务把知识库
activeVersion 指向新版本；查询 filter 带 activeVersion；延迟删除旧版本。失败只清理 staging，不影响线上检索。

**问题：如何修复 chunkCount？**

回答：`vectorizeAndStore` 返回 `VectorizationResult(chunkCount, modelVersion, contentHash)`；消费者在确认所有批次写入后，
用条件更新一次性写 COMPLETED、chunkCount 和 activeVersion。若更新失败则新版本保持不可见并进入补偿。

**问题：如何优化批量性能？**

回答：把批大小配置化并遵守 Provider 限制；控制并发和速率；复用已计算的内容哈希与 Embedding 缓存；避免一次持有
超大全文和所有向量；记录每批延迟与 token；数据库侧调 HNSW 参数、VACUUM 和连接池。

## 6. 故障处理问题

**问题：出现向量维度不匹配怎么处理？**

回答：核对配置 dimensions、实际模型输出和表 `vector(n)`；停止写入，不能截断或补零；按新模型建立新表/版本并
全量重嵌入。生产关闭自动 schema 变更，发布前做启动校验。

**问题：检索很慢如何定位？**

回答：区分 Embedding API 延迟和 SQL 延迟；用 EXPLAIN ANALYZE 确认 HNSW 索引、过滤选择性和 operator class；
查看表膨胀、缓存命中、并发与 topK；对比精确搜索召回，避免只靠降低 ef_search 换速度。

**问题：重复 chunk 或部分 chunk 怎么修复？**

回答：按 kbId/version 统计实际行数，与元数据 chunkCount 对账；异常版本重建而非原地补丁；为 chunk 使用稳定 ID
或 `(kbId, version, chunkIndex)` 唯一键，批量写入采用 upsert 并保留任务审计。

