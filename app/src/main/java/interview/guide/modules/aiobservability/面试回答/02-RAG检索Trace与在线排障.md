# RAG 检索 Trace 与在线排障

## 1. 模块基础讲解

RAG 检索 Trace 由 `RagRetrievalLogService` 负责记录，核心目标是复盘一次 RAG 检索到底发生了什么。

它记录两类数据：

```text
rag_retrieval_logs：主记录
  traceId
  sessionId
  question
  rewrittenQuestion
  candidateQueriesJson
  knowledgeBaseIdsJson
  topK
  minScore
  hitCount
  latencyMs

rag_retrieved_chunk_logs：命中 chunk 明细
  retrievalLogId
  knowledgeBaseId
  chunkTextPreview
  score
  rank
  metadataJson
```

在真实调用链里，`KnowledgeBaseQueryService.retrieveRelevantDocs()` 完成向量检索后，会调用 `recordRetrievalLog()`，再委托 `RagRetrievalLogService.recordRetrieval()` 保存主记录和命中 chunk。

设计上只保存 chunk 前 500 字符预览，不保存完整文档内容，避免数据库膨胀和敏感信息扩散。

## 2. 基础面试问题

### Q1：RAG Trace 记录了哪些信息？

记录一次检索的完整上下文：用户原始问题、改写后问题、候选查询列表、知识库 ID 列表、topK、minScore、命中数量、检索耗时，以及每个命中 chunk 的知识库 ID、排序、相似度分数、文本预览和元数据。

这让我们能回答：为什么这个问题没检出相关内容，或者为什么检出了错误内容。

### Q2：RAG Trace 是在哪里写入的？

在 `KnowledgeBaseQueryService.retrieveRelevantDocs()` 的 `finally` 中写入。无论检索是否成功，都会记录 RAG 检索指标，并尝试写入检索日志。

写入失败时 `RagRetrievalLogService` 会返回 `Optional.empty()`，不会影响知识库问答主流程。

### Q3：为什么 chunk 只存预览？

完整 chunk 可能很长，也可能包含用户上传的资料内容。只存前 500 字符可以满足排查需要，比如确认命中文档是否相关，同时控制数据库体积和隐私风险。

### Q4：如何查询最近 RAG 检索？

Controller 提供：

```text
GET /api/ai-observability/rag-traces/recent
```

默认返回最近 20 条 RAG Trace，每条包含主记录和按 rank 排序的 chunk 明细。

## 3. 进阶面试问题

### Q1：query rewrite 在 Trace 里怎么体现？

`KnowledgeBaseQueryService` 会先 `normalizeQuestion()`，再尝试 `rewriteQuestion()`，然后构造候选查询：

```text
candidates = [rewrittenQuestion, normalizedQuestion]
```

Trace 里会保存 `question`、`rewrittenQuestion` 和 `candidateQueriesJson`。如果改写后的问题没有命中，系统还会尝试原问题。这样排查时可以判断 query rewrite 是提升了检索，还是把问题改偏了。

### Q2：Trace 中 topK 和 minScore 有什么排障价值？

`topK` 决定最多返回多少 chunk，`minScore` 决定最低相似度阈值。常见问题包括：

1. `minScore` 太高：hitCount 为 0。
2. `topK` 太小：正确 chunk 排在后面但没返回。
3. `minScore` 太低：弱相关 chunk 太多，污染上下文。
4. 短问题 topK 策略不合适：召回不足或噪声过多。

Trace 记录这些参数，才能把“回答不好”定位到检索参数层。

### Q3：metadataJson 有什么用？

Document 的 metadata 里通常包含 `kb_id`、来源文件、页码、chunk 信息等。Trace 记录 metadata 后，排查时可以看到命中的 chunk 来自哪个知识库、哪个文件或哪一段文本。

这也是未来实现用户可见引用来源的基础。

### Q4：当前 RAG Trace 和最终答案之间有什么缺口？

当前 Trace 重点记录检索阶段。答案生成阶段只通过 `RagMetricsRecorder.recordAnswer()` 记录指标，不一定把最终 Prompt、模型响应和答案内容落到 `AiInvocationLogService`。

所以当前可以很好地排查“检索有没有召回正确上下文”，但对“LLM 是否忠实使用上下文”还需要进一步补充答案级日志或评测。

## 4. 技术延伸问题

### Q1：如何把 RAG Trace 扩展成完整 RAG 调用链？

可以统一一个 traceId，贯穿：

```text
HTTP 请求
  -> query rewrite LLM 调用
  -> vector retrieval
  -> prompt assembly
  -> answer generation LLM 调用
  -> stream response
```

并把每个阶段都记录成事件：

```text
RAG_REWRITE
RAG_RETRIEVAL
RAG_PROMPT_BUILD
RAG_ANSWER_GENERATION
```

这样前端可以按时间线展示完整链路。

### Q2：RAG Trace 和用户可见引用有什么关系？

Trace 已经记录 chunk 的文本预览、rank、score、metadata。要做用户可见引用，需要在回答接口中把命中 chunk 的来源信息返回给前端，例如文件名、页码、片段 ID。当前后端 trace metadata 是基础，但还没有把引用来源作为回答响应的一部分稳定返回给用户。

### Q3：如何做 RAG 问题的线上回放？

可以基于 Trace 保存的 `question`、`knowledgeBaseIds`、`topK`、`minScore` 重跑检索，对比新旧结果：

```text
旧 trace：hitCount=0
调整 minScore 后回放：hitCount=3
调整 chunk 策略后回放：关键词命中
```

这对调参和回归测试很有价值。

## 5. 改进方案问题

### Q1：当前 RAG Trace 的优点是什么？

1. 保存检索主参数和命中 chunk 明细。
2. chunk 按 rank 排序，便于看相似度排序。
3. 保存 metadata，具备引用来源扩展基础。
4. 只存预览，不存全文，控制风险。
5. 写入失败不影响 RAG 主流程。
6. 已有测试验证 chunk 摘要保存和失败降级。

### Q2：当前可以怎么改进？

1. `findByTraceId` 如果 traceId 不唯一，可能只返回第一条；可以约束唯一或改为返回列表。
2. 最近 Trace 查询有 N+1 查询问题，每条主记录单独查 chunks，可以优化为批量查询。
3. Trace 查询缺少分页和过滤。
4. RAG answer generation 没有完整日志落库。
5. traceId 与 HTTP traceId、AI 调用日志 traceId 尚未完全统一。
6. 可以记录 query rewrite 是否启用、是否成功、失败原因。

### Q3：如何优化存储结构？

短期可以保留 JSON TEXT，简单直接。长期如果查询频繁，可以：

1. 把 `knowledgeBaseIds` 拆成关联表。
2. 给 `score`、`rank`、`knowledgeBaseId` 增加查询索引。
3. chunk metadata 用 JSONB（PostgreSQL）存储。
4. 对旧 Trace 做归档。

## 6. 故障处理问题

### Q1：RAG Trace 不存在怎么排查？

可能原因：

1. RAG 查询没有走 `KnowledgeBaseQueryService` 的记录链路。
2. 检索日志写入失败，但主流程降级继续了。
3. 前端拿到的是 HTTP traceId，而不是 RAG 检索 traceId。
4. 数据库记录被清理。

当前知识库回答返回的 `QueryResponse` 中包含 RAG traceId，应优先用这个 traceId 查询。

### Q2：Trace 显示 hitCount=0 怎么办？

排查：

1. 知识库 ID 是否传错。
2. 文档是否完成向量化。
3. minScore 是否太高。
4. query rewrite 是否把问题改偏。
5. 原问题作为候选查询是否也无命中。
6. 向量模型维度或 provider 是否和入库时一致。

### Q3：Trace 显示命中了 chunk，但答案仍然不好怎么办？

说明问题可能不在检索召回，而在答案生成：

1. Prompt 没有要求严格基于上下文回答。
2. 检索 chunk 太多或排序不佳，导致上下文噪声大。
3. chunk 内容虽然相关但不完整。
4. 模型没有正确遵循拒答/引用规则。

下一步应补答案生成日志和答案级评测。

