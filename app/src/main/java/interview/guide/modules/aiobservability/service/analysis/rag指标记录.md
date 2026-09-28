<br />

## 一、两个指标记录的具体内容、数据来源与记录方式

这两个记录调用位于 [`retrieveRelevantDocs`](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java#L418-L461) 的 `finally` 块中（第446-460行），意味着**无论检索成功还是失败都会执行**。

***

### 1. `recordRagRetrieval` — Micrometer 实时指标

定义在 [`KnowledgeBaseQueryService#L535-L549`](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java#L535-L549)：

```java
private void recordRagRetrieval(
    QueryContext queryContext, int hitCount, boolean success, long startNanos) {
    if (ragMetricsRecorder != null) {
        ragMetricsRecorder.recordRetrieval(
            rewriteEnabled,                        // 是否启用改写
            queryContext.searchParams().topK(),    // 检索 Top-K 值
            hitCount,                              // 实际命中文档数
            success,                               // 检索是否成功（无异常）
            startNanos                             // 检索起始纳秒时间戳
        );
    }
}
```

最终落地在 [`RagMetricsRecorder.recordRetrieval()`](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/observability/RagMetricsRecorder.java#L141-L162)：

| Micrometer 指标                 | 类型          | 记录内容                                               | Tags                                 |
| ----------------------------- | ----------- | -------------------------------------------------- | ------------------------------------ |
| `app.rag.retrieval.latency`   | **Timer**   | `System.nanoTime() - startNanos` 纳秒级耗时             | `rewrite_enabled`, `top_k`, `status` |
| `app.rag.retrieval.hit.count` | **Summary** | `Math.max(0, hitCount)` 命中数分布（max/mean/percentile） | 同上                                   |
| `app.rag.no_result.count`     | **Counter** | `hitCount <= 0` 时 +1（零命中事件数）                       | 同上                                   |

- **数据来源**：`System.nanoTime()` 时间差、`queryContext.searchParams().topK()`、检出的 `docs.size()`
- **记录方式**：通过 Micrometer `MeterRegistry` 写入，最终由 Prometheus 采集，Grafana 展示。`meterRegistry == null` 时静默跳过（优雅降级）

***

### 2. `recordRetrievalLog` — 数据库持久化日志

定义在 [`KnowledgeBaseQueryService#L463-L484`](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java#L463-L484)：

```java
private void recordRetrievalLog(RagTraceContext traceContext, QueryContext queryContext,
    List<Long> knowledgeBaseIds, List<Document> docs, long latencyMs) {
    retrievalLogService.recordRetrieval(
        new RagRetrievalLogRequest(
            traceContext.traceId(),          // UUID 链路追踪 ID
            traceContext.sessionId(),        // 会话 ID（可为 null）
            queryContext.originalQuestion(), // 用户原始问题
            queryContext.rewrittenQuestion(),// LLM 改写后的问题
            queryContext.candidateQueries(), // 候选查询列表
            knowledgeBaseIds,                // 检索知识库 ID 列表
            queryContext.searchParams().topK(),
            queryContext.searchParams().minScore(),
            docs.size(),                     // 命中数量
            latencyMs                        // 检索耗时（毫秒）
        ),
        docs  // 检出的文档列表
    );
}
```

最终落地在 [`RagRetrievalLogService.recordRetrieval()`](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/aiobservability/service/RagRetrievalLogService.java#L138-L171)：

| 存储位置                               | 内容                                                                                  |
| ---------------------------------- | ----------------------------------------------------------------------------------- |
| `rag_retrieval_logs`（主表）           | traceId、sessionId、原始问题、改写问题、候选查询 JSON、知识库 ID JSON、topK、minScore、hitCount、latencyMs  |
| `rag_retrieved_chunk_logs`（子表 N 条） | retrievalLogId（外键）、knowledgeBaseId、chunkTextPreview（前500字符）、score、rank、metadataJson |

- **数据来源**：`RagTraceContext`（由上层 `answerQuestionInternal` / `answerQuestionStreamWithTrace` 创建）、`QueryContext`（由 `buildQueryContext` 构建）、`List<Document>`（pgvector 检索结果）
- **记录方式**：JPA `save()` + `saveAll()`，写入失败时降级为 `Optional.empty()` 并记录 warn 日志，不抛异常中断主流程

***

## 二、完整调用链梳理

```
┌─────────────────────────────────────────────────────────────────────┐
│ Controller 层                                                        │
│   KnowledgeBaseController.queryKnowledgeBase(QueryRequest)           │
│   KnowledgeBaseController.answerQuestionStream(...)                  │
│   RagChatSessionService.getStreamAnswer(...) ← RAG 聊天模式         │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│ KnowledgeBaseQueryService                                            │
│                                                                      │
│  queryKnowledgeBase(QueryRequest)                                    │
│    └─ answerQuestionInternal(kbIds, question, null)  [第162行]     │
│                                                                      │
│  answerQuestionStreamWithTrace(sessionId, kbIds, question, history)  │
│    └─ [第311行]                                                      │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                    ┌──────────┴──────────┐
                    ▼                     ▼
          ┌─────────────────┐   ┌──────────────────────┐
          │ 步骤1: 创建     │   │ 步骤2: 构建           │
          │ RagTraceContext │   │ QueryContext          │
          │                 │   │                      │
          │ traceId = UUID  │   │ originalQuestion     │
          │ sessionId       │   │ rewrittenQuestion ←LLM│
          │                 │   │ candidateQueries     │
          │                 │   │ searchParams         │
          │                 │   │   ├─ topK  ← 动态决策 │
          │                 │   │   └─ minScore← 动态决策│
          └─────────────────┘   └──────┬───────────────┘
                                       │
                    ┌──────────────────┘
                    ▼
          ┌──────────────────────────────────────────────────┐
          │ 步骤3: retrieveRelevantDocs(queryContext,        │
          │          knowledgeBaseIds, traceContext)         │
          │                                                 │
          │  遍历 candidateQueries:                         │
          │    vectorService.similaritySearch(              │
          │      candidateQuery, kbIds, topK, minScore)     │
          │    └─ pgvector COSINE 相似度检索                │
          │                                                 │
          │  finally {  ← 无论成功/失败都执行               │
          │    ┌───────────────────────────────────────┐    │
          │    │ recordRagRetrieval(...)               │    │
          │    │   → RagMetricsRecorder.recordRetrieval│    │
          │    │      (rewriteEnabled, topK, hitCount, │    │
          │    │       success, startNanos)            │    │
          │    │      → Micrometer Timer/Summary/Counter│   │
          │    │      → Prometheus → Grafana           │    │
          │    └───────────────────────────────────────┘    │
          │    ┌───────────────────────────────────────┐    │
          │    │ recordRetrievalLog(...)               │    │
          │    │   → RagRetrievalLogService            │    │
          │    │      .recordRetrieval(request, docs)   │    │
          │    │      → JPA save → rag_retrieval_logs  │    │
          │    │                → rag_retrieved_chunk_  │    │
          │    │                  logs                  │    │
          │    └───────────────────────────────────────┘    │
          │  }                                              │
          └──────────────────────┬───────────────────────────┘
                                 │
                                 ▼
          ┌──────────────────────────────────────────────────┐
          │ 步骤4: LLM 生成答案                              │
          │   getChatClient().prompt()                       │
          │     .system(systemPrompt)                        │
          │     .user(userPrompt)                            │
          │     .call() / .stream()                          │
          │                                                 │
          │ 步骤5: recordRagAnswer(streaming, success, start)│
          │   → RagMetricsRecorder.recordAnswer(...)        │
          │      → Timer(app.rag.answer.latency)            │
          │      → Counter(app.rag.answer.count)            │
          └──────────────────────────────────────────────────┘
```

### 关键参数流向

| 参数                  | 来源                                                             | 传递路径                                                                          |
| ------------------- | -------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| `traceId`           | `RagRetrievalLogService.createTraceId()` → `UUID.randomUUID()` | `RagTraceContext` → 传入 `RagRetrievalLogRequest` → 持久化到 `rag_retrieval_logs` 表 |
| `rewriteEnabled`    | `KnowledgeBaseQueryProperties.getRewrite().isEnabled()`        | 构造函数注入为字段 → 直接传入 `ragMetricsRecorder.recordRetrieval()`                       |
| `topK` / `minScore` | `resolveSearchParams(question)` 根据问题长度动态决策                     | 短问题用 `topkShort`，中等用 `topkMedium`，长问题用 `topkLong`                             |
| `rewrittenQuestion` | LLM 调用 `rewritePromptTemplate` 后返回                             | `buildQueryContext` → `QueryContext` → 写入日志                                   |
| `selectedDocs`      | `vectorService.similaritySearch()` → pgvector                  | 同时在 Metrics 中仅记录数量，在 Log 中记录详细内容（截断到500字符）                                    |

***

## 三、两个指标在系统中的具体作用

### `recordRagRetrieval`（Micrometer 指标）— 实时监控与告警

| 用途             | 说明                                                                              |
| -------------- | ------------------------------------------------------------------------------- |
| **检索性能监控**     | `app.rag.retrieval.latency` Timer 可查看 P50/P95/P99 检索延迟，识别 pgvector 索引退化或数据库负载问题 |
| **检索召回监控**     | `app.rag.retrieval.hit.count` Summary 展示命中数分布，若 P50 持续下降，说明向量检索效果变差             |
| **零命中告警**      | `app.rag.no_result.count` Counter 持续增长时可设置 Prometheus 告警规则，通知运维人员检查知识库内容或索引状态   |
| **改写效果对比**     | Timer/Summary 都带有 `rewrite_enabled=true/false` 标签，可对比启用查询改写前后的检索命中率和延迟变化        |
| **Top-K 策略调优** | `top_k` 标签允许按不同 K 值分析命中分布，辅助调整动态 Top-K 策略参数                                     |

### `recordRetrievalLog`（数据库日志）— 全链路追踪与问题排查

| 用途         | 说明                                                                                                                          |
| ---------- | --------------------------------------------------------------------------------------------------------------------------- |
| **全链路追踪**  | 通过 `traceId` 关联 `AiInvocationLogService` 的 LLM 调用日志，前端 `AiObservabilityController.getRagTrace(traceId)` 可查看一次 RAG 问答的完整检索详情 |
| **检索质量审计** | 保存了原始问题、改写问题、候选查询、检出文档块内容和相似度得分，支持检索质量问题回溯排查                                                                                |
| **用户行为分析** | 记录每次检索的 knowledgeBaseIds，可统计哪些知识库命中率低、哪些问题类型检索效果差                                                                           |
| **检索参数存档** | 保存每次检索时的 topK 和 minScore，事后可分析不同参数配置对检索效果的影响                                                                                |
| **消歧辅助**   | `candidateQueries` 列表保存了所有候选查询，可帮助判断改写逻辑是否正确分流了不同查询策略                                                                       |

### 两者配合关系

```
实时监控层（Micrometer）          离线分析层（数据库日志）
─────────────────────────        ─────────────────────────
app.rag.retrieval.latency ←──    rag_retrieval_logs.latency_ms
     ↓ 告警触发                        ↓ 按 traceId 查询
"P95 检索延迟 > 500ms"         →   具体哪次查询慢？查日志看候选查询是否合理
                                    
app.rag.no_result.count  ←──    rag_retrieval_logs.hit_count = 0
     ↓ 告警触发                        ↓ 按 traceId 查询
"零命中率 > 10%"              →   具体什么问题查不到？看原始问题和知识库范围
```

简而言之：Micrometer 指标负责**实时发现问题**，数据库日志负责**事后排查原因**。

toolName: todo\_write

status: success

Todos updated: 3 items

以上是对 [`retrieveRelevantDocs`](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java#L446-L460) 中两个指标记录调用的完整分析。核心结论：

- `recordRagRetrieval` → **Micrometer 实时指标**（Timer/Summary/Counter），对接 Prometheus + Grafana，用于性能监控和告警
- `recordRetrievalLog` → **数据库持久化日志**（主表 `rag_retrieval_logs` + 子表 `rag_retrieved_chunk_logs`），用于全链路追踪和事后排查

