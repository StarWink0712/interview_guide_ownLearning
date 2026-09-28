# 无会话同步/流式 RAG 查询完整调用链

## 1. 接口与当前状态

| 方法 | 接口 | 返回 | 当前前端调用 |
|---|---|---|---|
| `queryKnowledgeBase` | `POST /api/knowledgebase/query` | `Result<QueryResponse>` | API 已封装，页面未调用 |
| `queryKnowledgeBaseStream` | `POST /api/knowledgebase/query/stream` | `Flux<String>` SSE | API 已封装，页面未调用 |

当前 `KnowledgeBaseQueryPage` 使用的是 `RagChatController.sendMessageStream`，因为它需要会话和
历史消息持久化。本篇两个接口是无会话兼容入口。

## 2. 公共请求模型和限流

两个接口都接收：

```json
{
  "knowledgeBaseIds": [1, 2],
  "question": "问题"
}
```

`@Valid QueryRequest` 保证 ID 列表非空、问题非空白。

限流规则：

| 接口 | GLOBAL | IP | 默认窗口 |
|---|---:|---:|---|
| 同步 | 10 | 10 | 1 秒 |
| 流式 | 5 | 5 | 1 秒 |

所有规则由 `RateLimitAspect` 在 Controller 前执行。

## 3. 同步接口调用链

前端封装（当前无调用者）：

```text
knowledgeBaseApi.queryKnowledgeBase(req)
  → request.post('/api/knowledgebase/query', req, timeout=180s)
```

Controller：

```text
queryService.queryKnowledgeBase(request)
  → Result.success(QueryResponse)
```

Service 进入 `answerQuestionInternal(ids, question, sessionId=null)`。

## 4. 同步 RAG 公共主干

### 生成 trace 与基础校验

```text
retrievalLogService.createTraceId()
  → ids 为空或问题 trim 后为空：直接返回固定“未检索到”回答
```

正常请求先调用 `KnowledgeBaseCountService.updateQuestionCounts`：

1. ID 去重。
2. `findAllById` 验证全部知识库存在。
3. 单条 SQL 让每个参与知识库 `questionCount + 1`。

计数发生在检索和 LLM 之前，因此无召回或后续失败也已经计数。

### Query Rewrite 与动态检索参数

```text
buildQueryContext
  → normalizeQuestion(trim)
  → rewriteQuestion
      → 可选调用默认 ChatClient 改写问题
      → 失败时回退原问题
  → candidateQueries=[改写问题, 原问题] 去重
  → resolveSearchParams
      → 按问题长度选择 topK/minScore
```

### pgvector 召回

对候选查询依次调用：

```text
KnowledgeBaseVectorService.similaritySearch
  → SearchRequest(query, topK, minScore, kb_id filter)
  → VectorStore.similaritySearch
```

服务端过滤表达式失败时，会扩大 topK 做一次无过滤搜索，再根据 metadata.kb_id 在内存过滤。

每次检索都记录指标和 `RagRetrievalLogService.recordRetrieval`，把 traceId、原问题、改写问题、
候选查询、知识库 ID、topK、阈值、命中数和延迟关联起来。

### Prompt 和 LLM

无有效命中时不调用回答模型，直接返回固定无结果文案。有命中时：

```text
Document.text 用分隔线拼成 context
  → buildSystemPrompt
      → knowledgebase-query-system.st
      → 追加 ANTI_INJECTION_INSTRUCTION
  → buildUserPrompt(context, originalQuestion)
  → llmProviderRegistry.getDefaultChatClient
  → prompt.system(...).user(...).call().content()
  → normalizeAnswer
```

LLM 异常转换为 `BusinessException(KNOWLEDGE_BASE_QUERY_FAILED)`。

## 5. 同步响应组装

`queryKnowledgeBase` 继续执行：

```text
listService.getKnowledgeBaseNames(ids)
  → 对每个 ID 单独 findById（N 次查询）
  → 不存在时名称写“未知知识库”
  → 中文顿号拼接名称
  → 第一个 ID 作为兼容字段 knowledgeBaseId
```

返回 `QueryResponse(answer, primaryKbId, names, traceId)`。Axios 拦截器提取 data。

## 6. 无会话流式接口

前端封装（当前无调用者）使用原生 `fetch`，逐行解析 `data:`：

```text
POST /api/knowledgebase/query/stream
  → Controller queryService.answerQuestionStream(ids, question)
  → answerQuestionStreamWithTrace(sessionId=null, history=[])
  → Flux<String>
```

流式路径复用计数、改写、动态参数、召回、trace 日志和 Prompt。区别在 LLM 调用：

```text
ChatClient.prompt
  → system
  → user
  → stream().content()
  → normalizeStreamOutput
```

`normalizeStreamOutput` 先缓冲最多 120 字符，用于识别模型是否输出“没有相关信息”一类拒答：

- 命中无结果语义：输出统一固定文案并取消上游；
- 未命中：释放缓冲并继续实时透传；
- 客户端取消：dispose 上游订阅。

Spring 把 `Flux<String>` 按 `text/event-stream` 写成 SSE data 行，前端恢复正文并逐块回调。

## 7. 流式错误为何通常不是 HTTP 错误

Service 在两个阶段都把异常转换成普通内容：

- 建流前异常：返回 `Flux.just("【错误】知识库查询失败：...")`；
- LLM 流中异常：`onErrorResume` 返回固定错误文本。

因此 HTTP 常保持 200，前端 `onMessage` 收到以“【错误】”开头的正文，随后仍触发
`onComplete`，而不是 `onError`。

这个无会话流式 Controller 只返回 `.content()`，Service 生成的 traceId 没有通过 SSE 发给
客户端；只有同步 `QueryResponse` 和 RAG 会话 SSE 会暴露 traceId。

## 8. 与当前 RAG 会话链的区别

| 能力 | 无会话接口 | RagChatController |
|---|---|---|
| 保存会话/消息 | 否 | 是 |
| 多轮历史 | 空列表 | 最近已完成消息 |
| trace SSE 事件 | 否 | 是 |
| 页面当前使用 | 否 | 是 |
| 限流 | 有 | 当前没有 |

## 9. 当前实现边界

- 两个 API 当前没有页面调用，不能描述成主问答路径。
- 同步知识库名称查询存在按 ID 循环查库。
- questionCount 在无命中或 LLM 失败时也会增加。
- 流式业务异常被转成普通文本，HTTP 和 Flux 都可能“正常完成”。
- 无会话流式 traceId 在 Controller 层丢失。
- 同步/流式都使用默认 ChatClient，接口没有 Provider 参数。

## 流程总结

这两个无会话接口提供了不依赖 RAG 聊天会话的独立知识库问答能力，虽然当前前端未使用但构成了 RAG 引擎的核心逻辑。同步接口 `POST /api/knowledgebase/query` 走 `queryKnowledgeBase(request)` → `answerQuestionInternal` → `queryKnowledgeBase` 组装 `QueryResponse` 的完整链路；流式接口 `POST /api/knowledgebase/query/stream` 走 `answerQuestionStream(ids, question)` → `answerQuestionStreamWithTrace` 返回 `Flux<String>` 的 SSE 路径。两条路径共享同一段 RAG 核心逻辑：首先由 `KnowledgeBaseCountService.updateQuestionCounts` 批量增加参与知识库的提问计数，然后进入 `buildQueryContext` 构建检索上下文——将原始问题 trim 归一化后，如果启用了 Query Rewrite 则调用一次轻量 LLM 改写问题以提升检索精度（失败优雅降级回原问题），生成「改写问题, 原问题」的有序候选查询列表，再根据问题长度动态决定 topK 和 minScore（≤4字符用 topkShort/minScoreShort，≤12字符用 topkMedium/minScoreDefault，其余用 topkLong/minScoreDefault）。接着对候选查询逐一调用 `KnowledgeBaseVectorService.similaritySearch` 做 pgvector COSINE 相似度检索，通过 `kb_id in ['1','2']` 的 filterExpression 限定知识库范围，过滤失败时自动回退本地内存过滤。若所有候选查询均无命中，直接返回固定"未检索到相关信息"文本，不调用 LLM；有命中时则将检索到的文档片段用分隔线拼接为上下文文本，从 `classpath:prompts/knowledgebase-query-system.st` 和 `knowledgebase-query-user.st` 模板文件渲染 System/User Prompt（System Prompt 末尾追加防注入指令），最终通过 `LlmProviderRegistry.getDefaultChatClient()` 获取 ChatClient 调用 LLM 生成回答。两路径的区别在于 LLM 调用方式：同步用 `.call().content()` 一次性返回完整文本后经 `normalizeAnswer` 过滤无效回答；流式用 `.stream().content()` 逐 token 返回，并通过 `normalizeStreamOutput` 在前 120 字符构建探测缓冲区——若检测到"没有找到相关信息"等拒答语义则立即输出固定模板并取消上游订阅防止长篇空话，否则释放缓冲进入实时透传模式。
