# AI 与 RAG 可观测性怎么讲

## 1. 为什么要单独讲 AI 和 RAG

这个项目的核心卖点是 AI 面试和知识库问答。

但 AI/RAG 和普通业务代码不一样：

- LLM 可能慢。
- LLM 可能返回不符合格式的内容。
- LLM Provider 可能不稳定。
- RAG 可能检索不到文档。
- Query rewrite 可能把问题改坏。
- 检索命中了也不代表答案一定正确。

所以面试中要强调：

> AI/RAG 功能不能只证明“能调用模型”，还要证明我知道怎么观测它、怎么定位它、怎么优化它。

## 2. AI 结构化输出的观测链路

### 2.1 调用位置

AI 结构化输出的核心入口是 `StructuredOutputInvoker`。

它做了这些事情：

1. 记录开始时间 `startNanos`。
2. 从 `TraceIdFilter.currentTraceId()` 获取 traceId。
3. 给 system prompt 追加防注入指令。
4. 最多执行 `maxAttempts` 次 AI 调用。
5. 每次失败记录 attempt 指标。
6. 最终成功或失败记录 invocation 指标。
7. 委托 `AiMetricsRecorder` 记录业务级 AI 指标。
8. 委托 `AiInvocationLogService` 写数据库观测日志。

可以把它讲成四层：

```text
StructuredOutputInvoker
  |
  |-- recordAttempt(...)             每次尝试
  |-- recordInvocation(...)          最终结果
  |-- AiMetricsRecorder              provider/model/scenario 维度指标
  |-- AiInvocationLogService         持久化调用明细
```

## 3. `recordAttempt` 和 `recordInvocation` 的区别

这是面试官很可能追问的细节。

### 3.1 attempt

`recordAttempt(...)` 表示每一次实际尝试。

如果最多重试 2 次，可能出现：

```text
第 1 次失败 -> attempt failure +1
第 2 次成功 -> attempt success +1
最终 invocation success +1
```

attempt 能看出模型输出是否稳定。

例如：

- 最终成功率很高。
- 但 attempt failure 也很高。

这说明模型经常第一次输出不合格，只是靠重试修好了。

### 3.2 invocation

`recordInvocation(...)` 表示一次完整业务调用的最终结果。

无论中间重试多少次，最终只记录一次。

它更适合看用户视角的成功率：

- 用户最终拿到结果了吗？
- 这个接口最终失败了吗？
- 总耗时是多少？

### 3.3 面试回答

> 我把 AI 调用拆成 attempt 和 invocation 两个维度。attempt 记录每次模型调用尝试，用来观察结构化输出的稳定性；invocation 只记录最终结果，用来观察用户视角的成功率和总耗时。比如一个模型最终成功率高但 attempt failure 高，说明它需要频繁靠重试兜底，这对延迟和成本都有影响。

## 4. `AiMetricsRecorder` 记录了什么

核心方法：

```java
recordStructuredInvocation(
    scenario,
    provider,
    model,
    success,
    attemptCount,
    startNanos
)
```

它记录：

| 指标 | 含义 |
|---|---|
| `app.ai.invocation.count` | AI 调用最终成功或失败次数 |
| `app.ai.invocation.latency` | AI 调用整体耗时 |
| `app.ai.structured_output.retry.count` | 实际重试次数 |
| `app.ai.structured_output.failure.count` | 最终失败次数 |

标签：

| 标签 | 含义 |
|---|---|
| `scenario` | 业务场景 |
| `provider` | LLM Provider |
| `model` | 模型 |
| `status` | success/failure |

## 5. 重试指标为什么重要

代码里：

```java
int retryCount = Math.max(0, attemptCount - 1);
if (retryCount > 0) {
  meterRegistry.counter("app.ai.structured_output.retry.count", tags).increment(retryCount);
}
```

这里的关键点是：

```text
实际重试次数 = 总尝试次数 - 1
```

例子：

| 场景 | attemptCount | retryCount | 说明 |
|---|---:|---:|---|
| 第一次成功 | 1 | 0 | 没有重试 |
| 第二次成功 | 2 | 1 | 重试一次后成功 |
| 第三次成功 | 3 | 2 | 重试两次后成功 |
| 三次都失败 | 3 | 2 | 重试两次后最终失败 |

面试讲法：

> 重试率可以反映模型输出稳定性。如果失败率高，说明用户直接受影响；如果失败率低但重试率高，说明系统靠重试兜住了，但会带来延迟和成本上升，也说明 Prompt 或模型选择需要优化。

## 6. AI 指标和 AI 调用日志的区别

`AiMetricsRecorder` 记录聚合指标。

`AiInvocationLogService` 写数据库明细。

| 维度 | AI Metrics | AI Invocation Log |
|---|---|---|
| 目标 | 趋势、告警、聚合 | 单次调用复盘 |
| 数据 | count、latency、retry、failure | traceId、prompt、provider、model、error |
| 查询方式 | 监控系统聚合 | 按 traceId 或最近记录查询 |
| 是否适合放 prompt | 不适合 | 适合，但要注意脱敏 |

面试回答：

> 指标和调用日志不是重复的。指标用于看整体趋势，比如过去 5 分钟某个 Provider 的失败率；调用日志用于复盘单次请求，比如这次 traceId 对应的 prompt 是什么、模型返回了什么错误。

## 7. RAG 可观测性总体设计

RAG 的链路可以拆成三个阶段：

```text
用户问题
  |
  v
Query Rewrite 查询改写
  |
  v
Retrieval 向量检索
  |
  v
Answer Generation 答案生成
```

`RagMetricsRecorder` 正好按这三个阶段记录指标：

- `recordRewrite(...)`
- `recordRetrieval(...)`
- `recordAnswer(...)`

这比只记录“RAG 接口总耗时”更有价值。

面试表达：

> 我没有只看 RAG 总耗时，而是把 RAG 拆成 query rewrite、retrieval、answer 三段。因为这三个阶段的问题完全不同：rewrite 慢通常是 LLM 调用问题，retrieval 空结果通常是召回或参数问题，answer 慢通常是上下文过长或模型响应问题。

## 8. Query Rewrite 指标怎么讲

`recordRewrite(boolean enabled, boolean success, long startNanos)` 记录：

- `app.rag.query_rewrite.latency`
- `app.rag.query_rewrite.count`

标签：

- `enabled`
- `status`

意义：

- 可以看 query rewrite 是否带来额外延迟。
- 可以看 rewrite 失败率。
- 可以对比开启和关闭 rewrite 的检索效果。

代码里如果 rewrite 失败，会回退到原问题继续检索：

```text
rewrite 失败
  -> 记录 failure
  -> log.warn
  -> 使用原问题继续检索
```

面试回答：

> Query rewrite 是增强召回的能力，但不能让它成为单点故障。所以失败时我记录指标和 warn 日志，然后回退到原问题继续检索。

## 9. Retrieval 指标怎么讲

`recordRetrieval(...)` 记录：

- `app.rag.retrieval.latency`
- `app.rag.retrieval.hit.count`
- `app.rag.no_result.count`

标签：

- `rewrite_enabled`
- `top_k`
- `status`

这里最关键的是 `hitCount` 和 `no_result`。

### 9.1 hit count

命中文档块数量可以反映召回情况。

如果命中数量长期偏低，可能是：

- 文档没有成功向量化。
- embedding 模型不匹配。
- minScore 太高。
- topK 太小。
- query rewrite 质量差。
- 用户问题和知识库内容无关。

### 9.2 no result

`hitCount <= 0` 时记录 `app.rag.no_result.count`。

这个指标可以做告警：

> 最近 10 分钟 RAG 零命中率突然升高，说明知识库检索可能出问题。

面试回答：

> RAG 里空召回是很关键的问题。如果只看接口成功率，接口可能返回“未找到相关内容”，从 HTTP 角度是成功的，但从业务角度是失败的。所以我单独记录了 no result 指标。

## 10. Answer 指标怎么讲

`recordAnswer(boolean streaming, boolean success, long startNanos)` 记录：

- `app.rag.answer.count`
- `app.rag.answer.latency`

标签：

- `streaming`
- `status`

它能回答：

- 流式输出和非流式输出耗时差异。
- 答案生成失败率。
- RAG 总体慢是否主要慢在答案生成阶段。

注意：

流式输出的“用户感知速度”不只看总耗时，还应该看首 token 延迟。当前 RAG Recorder 记录的是 answer 阶段总延迟，没有专门记录首 token。

这是可以主动说的改进点。

## 11. RAG 持久化检索日志

`KnowledgeBaseQueryService` 在检索 finally 中不仅记录 Micrometer 指标，还调用：

```java
recordRetrievalLog(...)
```

写入内容包括：

- traceId
- sessionId
- originalQuestion
- rewrittenQuestion
- candidateQueries
- knowledgeBaseIds
- topK
- minScore
- hitCount
- latencyMs
- docs

这非常适合面试时展开。

> 指标能告诉我“零命中率升高了”，但不能告诉我“某次问题为什么没命中”。所以我还把 RAG 检索明细按 traceId 持久化，包括原问题、改写问题、topK、minScore、命中文档块。这样可以从宏观指标下钻到单次链路。

## 12. 如何用这些指标定位问题

### 场景一：用户说知识库问答很慢

排查顺序：

1. 看 `app.rag.query_rewrite.latency` 是否升高。
2. 看 `app.rag.retrieval.latency` 是否升高。
3. 看 `app.rag.answer.latency` 是否升高。
4. 如果 answer 慢，再看 AI provider/model 延迟。
5. 按 traceId 查 RAG 明细和 AI 调用日志。

### 场景二：用户说知识库经常答不上来

排查顺序：

1. 看 `app.rag.no_result.count` 是否升高。
2. 看 `app.rag.retrieval.hit.count` 均值是否下降。
3. 按 topK、rewrite_enabled 分组看差异。
4. 查具体 traceId，看原问题、改写问题、minScore、命中文档块。
5. 检查向量化任务是否失败或知识库文档是否为空。

### 场景三：AI 结构化输出经常失败

排查顺序：

1. 看 `app.ai.structured_output.failure.count`。
2. 看 `app.ai.structured_output.retry.count`。
3. 按 provider/model/scenario 分组。
4. 查 `AiInvocationLogService` 持久化日志里的 errorMessage。
5. 看 prompt 是否过长、格式约束是否不足、模型是否不适合结构化输出。

## 13. 当前边界和下一步

### 13.1 当前能观测运行质量，不直接评估答案质量

RAG 指标能看到：

- 延迟。
- 成功失败。
- 命中数量。
- 空结果。

但不能直接判断：

- 答案是否事实正确。
- 是否引用了正确文档。
- 是否有幻觉。
- 是否满足用户意图。

下一步可以做：

- 构建 RAG 离线评测集。
- 记录命中文档与标准答案的相关性。
- 对答案做 faithfulness 检查。
- 引入用户反馈评分。

### 13.2 当前 RAG 没有单独首 token 指标

语音面试里记录了 `llm.first_token_latency`。

RAG 流式回答当前主要记录 answer 总耗时。

如果要优化用户感知，可以补：

- RAG 首 token 延迟。
- SSE 首包时间。
- 流式输出完成耗时。

### 13.3 指标暴露还需要生产配置

当前代码已经具备 Micrometer 埋点，但要完整接 Prometheus 需要补：

- Actuator。
- Prometheus registry。
- management endpoint 配置。
- Dashboard 和 alert rules。

## 14. 面试 1 分钟回答

> AI 和 RAG 是这个项目最需要观测的部分，因为它们依赖外部模型和检索质量，不能只看接口是否 200。我在结构化输出里把观测拆成 attempt、invocation、业务级 AI 指标和持久化调用日志。attempt 看每次重试，invocation 看最终结果，`AiMetricsRecorder` 按 scenario、provider、model、status 记录延迟、失败和重试，调用日志按 traceId 保存 prompt、错误和耗时，方便单次复盘。RAG 这边我按 query rewrite、retrieval、answer 三段记录指标，尤其是检索阶段记录 hit count 和 no result，因为 HTTP 成功不代表业务上召回成功。这样用户说“AI 慢”或“知识库答不上来”时，我可以定位是模型慢、改写慢、检索空召回，还是答案生成慢。

