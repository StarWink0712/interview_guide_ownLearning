# Redis Stream 异步向量化

## 1. 向量化消费者是怎么启动和工作的？

**回答：** Spring Boot 启动后，消费者通过 `@PostConstruct` 创建守护线程，进入持续阻塞式拉取循环，从 Redis Stream 实时获取向量化消息。消费者继承异步任务父类，由父类模板方法统一编排状态更新、业务处理、ACK 和失败重试等流程。

## 2. 消费者拿到消息后的标准处理流程是什么？

**回答：** 收到消息后先调用 `markProcessing()`，把 `async_tasks` 中的任务状态和知识库实体的 `vectorStatus` 都改为 `PROCESSING`；然后进入 `processBusiness()`，按 `kbId` 查询知识库、执行实体存在性和幂等检查、从 RustFS 下载并解析文件，最后调用 `vectorService.vectorizeAndStore()` 完成删除旧向量、文本分块、批量 Embedding 和 pgvector 写入。

## 3. 消费时发现知识库实体已经被删除怎么办？

**回答：** 消费者处理前先用 `kbId` 查询知识库实体。如果实体已经不存在，说明用户可能在任务排队期间删除了知识库，此时抛出 `AsyncTaskCancelledException` 取消任务，不再下载文件或生成向量，避免为已经不存在的业务数据继续消耗资源。

## 4. 向量化任务如何保证幂等？

**回答：** 任务开始时检查 `vectorStatus` 和 `chunkCount`。只有状态已经是 `COMPLETED` 且 `chunkCount > 0` 时，才认为向量数据确实生成成功并直接跳过；否则继续重新处理。这样重复消息不会重复向量化，同时也能处理进程异常导致状态与实际向量数据不一致的情况。

<br />

向量化前先检查数据库中这个文档的状态啊，数据库中设置的有知识库文档表，根据文档id查询向量化状态字段。如果是向量化中就停止。

## 5. 为什么幂等检查不能只判断 vectorStatus=COMPLETED？

**回答：** 极端情况下，进程可能在状态已经被标记为完成、但向量数据尚未真正写入或写入失败时崩溃。如果只判断状态，就会永久跳过这条实际没有向量数据的知识库。因此还要验证 `chunkCount > 0`，用实际分块数量辅助确认处理结果。

## 6. vectorizeAndStore 为什么先删除旧向量？

**回答：** 重新向量化前，方法通过原生 SQL `deleteByKnowledgeBaseId()` 删除该知识库已有的全部向量，避免新旧分块混在一起，造成重复召回或已失效内容仍然被检索。删除后再基于当前原文件重新生成完整向量集合。

## 7. 文本是怎么进行分块的？

**回答：** 系统使用 Spring AI 的 `TokenTextSplitter` 把全文转换成多个 `Document`。默认每块约 800 tokens，最小字符数 350，最小嵌入长度 5，最多 10000 块，基于 `CL100K_BASE` 编码，并尽量在标点边界截断，当前策略不做分块重叠。

文本分块也是有讲究的，有多种方法，项目中只是用的最简单的固定长度，但这种可能会导致语义分割较严重。

<br />

## 8. 向量化中的 Document 包含什么？

**回答：** Spring AI 的 `Document` 主要包含两个部分：`content` 保存一个文本分块，`metadata` 保存键值形式的元数据。系统会给每个分块写入所属知识库的 `kb_id`，之后向量检索可以根据知识库 ID 对候选文档进行过滤。

document+文本的向量存到数据库中，文档id可以用来做过滤，只对需要的文档进行向量匹配。

## 9. 为什么 metadata 中的 kb\_id 要统一存成 String？

**回答：** 每个分块都将 `kb_id` 统一转换为 String 后写入 metadata，后续执行多知识库过滤查询时也使用相同类型。统一类型可以避免同一个 ID 在写入和过滤阶段分别以 Long、Integer 或 String 表示，导致过滤条件无法匹配。

## 10. 为什么 Embedding 要每 10 个 chunk 分一批？

**回答：** 阿里云 DashScope Embedding API 限制每批最多提交 10 个 chunk，因此系统根据分块总数向上取整计算批次数，再循环截取每批最多 10 个 `Document` 交给 `vectorStore.add()`。Spring AI 内部自动调用 Embedding API，并把向量和 metadata 一起写入 pgvector。

<br />

批量向量化，要不然速度会很慢

## 11. 向量在 pgvector 中是如何生成和保存的？

**回答：** `vectorStore.add(batch)` 接收一批 Spring AI `Document`，内部调用 DashScope Embedding API 把文本转换成 1024 维向量，再把向量、原文和 metadata 写入 PostgreSQL 的 pgvector 存储。后续查询使用余弦相似度，并根据 metadata 中的 `kb_id` 限定知识库范围。

## 12. 向量化失败后的重试机制是什么？

**回答：** 父类最多允许重试 3 次。每次失败后把 `retryCount` 加一，再将任务重新 XADD 到 Redis Stream 尾部；超过最大次数后把任务和知识库向量状态标记为 `FAILED`，作为死信保留，用户可以在前端手动点击“重新向量化”重新入队。

## 13. 向量化成功后会更新哪些状态？

**回答：** 处理成功后调用 `markCompleted()`，将异步任务表状态更新为 `COMPLETED`，同时把知识库实体的 `vectorStatus` 更新为完成，并保存实际 `chunkCount`。前端轮询到完成状态后，才可以在 RAG 对话中选择该知识库进行语义检索。
