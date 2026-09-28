# Micrometer 指标体系怎么讲

## 1. 一句话解释

这个项目没有在业务代码里直接依赖 Prometheus，而是通过 Micrometer 的 `MeterRegistry` 抽象来记录指标。

面试中可以这样说：

> 我把指标采集封装在几个 Recorder 里，业务模块只调用 `recordXxx()` 方法。Recorder 内部基于 Micrometer 的 `MeterRegistry` 写 Counter、Timer、Summary。这样业务代码不关心底层是 Prometheus、Datadog 还是其他监控系统，后续切换监控后端也更容易。

## 2. 为什么要封装 Recorder

如果每个业务 Service 都直接写：

```java
meterRegistry.counter("xxx", tags).increment();
meterRegistry.timer("yyy", tags).record(...);
```

会有几个问题：

- 指标命名不统一。
- 标签维度不统一。
- 空值保护容易漏。
- 业务代码被监控细节污染。
- 后续改指标名或标签要改很多地方。

所以项目按业务领域封装了几个 Recorder：

| Recorder | 领域 | 典型调用方 |
|---|---|---|
| `AiMetricsRecorder` | AI 结构化输出调用 | `StructuredOutputInvoker` |
| `RagMetricsRecorder` | 知识库 RAG 查询 | `KnowledgeBaseQueryService` |
| `AsyncTaskMetricsRecorder` | Redis Stream 异步任务 | `AbstractStreamProducer`、`AbstractStreamConsumer` |
| `VoiceMetricsRecorder` | 语音面试实时链路 | `VoiceInterviewWebSocketHandler` |

这样 Service 里只需要表达业务事件：

```java
ragMetricsRecorder.recordRetrieval(rewriteEnabled, topK, hitCount, success, startNanos);
```

而不是到处拼指标名和标签。

## 3. 指标类型怎么讲

### 3.1 Counter

Counter 是只增不减的计数器。

适合记录：

- 调用了多少次。
- 失败了多少次。
- 重试了多少次。
- 空结果出现了多少次。
- 异步任务创建了多少个。

项目里的例子：

- `app.ai.invocation.count`
- `app.ai.structured_output.retry.count`
- `app.ai.structured_output.failure.count`
- `app.rag.no_result.count`
- `app.async.task.created`
- `app.async.task.failed`
- `app.voice.interview.llm.calls`
- `app.voice.interview.errors`

面试回答：

> Counter 适合统计事件次数，不能用来表达当前值。例如“失败发生了多少次”适合 Counter，但“当前队列积压多少条”不适合 Counter，后者应该用 Gauge。

### 3.2 Timer

Timer 用来记录耗时分布。

适合记录：

- LLM 调用延迟。
- RAG query rewrite 耗时。
- RAG retrieval 耗时。
- RAG answer 耗时。
- 语音 LLM 首 token 延迟。
- TTS 合成耗时。
- 整轮语音面试耗时。

项目里的例子：

- `app.ai.invocation.latency`
- `app.rag.query_rewrite.latency`
- `app.rag.retrieval.latency`
- `app.rag.answer.latency`
- `app.voice.interview.llm.first_token_latency`
- `app.voice.interview.llm.duration`
- `app.voice.interview.tts.duration`
- `app.voice.interview.turn.duration`

面试回答：

> Timer 不是只看平均值，更重要的是看 P95、P99 这种尾延迟。AI 和语音链路尤其容易出现偶发慢请求，平均值可能看不出用户实际卡顿。

### 3.3 Summary

Summary 用来记录数值分布。

项目里最典型的是：

- `app.rag.retrieval.hit.count`

它记录每次检索命中了多少个文档块。

为什么不用 Counter？

因为 Counter 只能累加总命中数，而我们更关心每次请求的命中分布：

- 平均每次命中多少。
- 是否经常为 0。
- 是否 topK 调大后命中数变多。

面试回答：

> RAG 检索命中数是每次请求的一个观测值，不是一个单纯事件次数，所以我用 Summary 记录分布，再配合 `app.rag.no_result.count` 单独统计零命中。

## 4. 指标标签怎么讲

指标本身只有名字还不够，必须加标签才能定位问题。

### 4.1 AI 指标标签

`AiMetricsRecorder` 使用：

| 标签 | 含义 |
|---|---|
| `scenario` | 业务场景，比如简历评分、面试评估、知识库问答 |
| `provider` | LLM Provider，比如 dashscope、deepseek、openai |
| `model` | 模型名，比如 qwen、deepseek-chat |
| `status` | success 或 failure |

它能回答：

- 是所有 AI 调用都慢，还是某个场景慢？
- 是所有 Provider 都失败，还是某个 Provider 失败率高？
- 是某个模型版本响应慢，还是整体慢？

### 4.2 RAG 指标标签

`RagMetricsRecorder` 使用：

| 指标 | 标签 |
|---|---|
| query rewrite | `enabled`、`status` |
| retrieval | `rewrite_enabled`、`top_k`、`status` |
| answer | `streaming`、`status` |

它能回答：

- query rewrite 开启后检索命中是否改善？
- topK 不同取值下命中情况如何？
- 流式回答和非流式回答耗时有什么差异？

### 4.3 异步任务指标标签

`AsyncTaskMetricsRecorder` 使用：

| 标签 | 含义 |
|---|---|
| `task_type` | 任务类型可读名称 |
| `stream` | Redis Stream Key |

它能回答：

- 是简历分析任务失败多，还是面试评估任务失败多？
- 是哪个 Redis Stream 管道问题最多？

### 4.4 语音指标标签

`VoiceMetricsRecorder` 是通用工具类，调用方直接传标签：

```java
recordTimerSinceNanos("app.voice.interview.llm.duration", startNanos, "status", "success");
incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "true");
```

优点是灵活。

风险是标签约束弱，调用方必须控制标签数量和取值范围。

## 5. 标签空值保护

`AiMetricsRecorder` 和 `AsyncTaskMetricsRecorder` 都做了空值保护。

例如：

```java
private String safe(String value) {
  return value == null || value.isBlank() ? UNKNOWN : value;
}
```

意义：

- 防止标签值为 null 导致指标记录异常。
- 防止同一个含义被空字符串、null、缺失值拆成不同情况。
- 保证指标至少能落到 `unknown` 这一类。

面试可以说：

> 指标标签必须稳定，不能让 null 或空字符串进入监控系统。否则不仅可能报错，还会让聚合结果混乱。所以我在 Recorder 层做了统一的空值归一化。

## 6. 高基数问题怎么讲

这是监控面试里非常容易追问的点。

高基数标签是指标签取值非常多，例如：

- userId
- sessionId
- traceId
- prompt 全文
- question 全文
- fileName

这些字段不适合放到 Metrics 标签里。

原因：

- 每个不同标签组合都会生成一条时间序列。
- 高基数会让 Prometheus 或其他监控系统内存压力暴涨。
- 查询速度变慢，甚至影响监控系统稳定性。

这个项目的指标标签整体是相对克制的：

- AI 用 scenario/provider/model/status。
- RAG 用 enabled/topK/status/streaming。
- Async 用 task_type/stream。
- Voice 多数用 status、streaming、stage。

面试回答：

> traceId、sessionId 这种高基数字段我不会放进 Metrics 标签。它们适合放日志或数据库观测明细里。Metrics 负责看趋势和聚合，日志和明细表负责查单次链路。

## 7. `@Autowired(required = false)` 怎么讲

几个 Recorder 的构造函数都使用：

```java
public AiMetricsRecorder(@Autowired(required = false) MeterRegistry meterRegistry) {
  this.meterRegistry = meterRegistry;
}
```

含义是：如果 Spring 容器中没有 `MeterRegistry` Bean，应用也能启动。

每个记录方法里都有：

```java
if (meterRegistry == null) {
  return;
}
```

面试表达：

> 我把监控设计成可选增强能力。没有 MeterRegistry 时，Recorder 静默跳过，不影响业务主流程。因为监控系统故障不能反过来拖垮业务系统。

这也是测试里验证的点：

- `AiMetricsRecorderTest` 验证 MeterRegistry 缺失时不抛异常。
- `RagMetricsRecorderTest` 验证 MeterRegistry 缺失时不抛异常。

## 8. 当前指标清单

### 8.1 AI 指标

| 指标名 | 类型 | 含义 |
|---|---|---|
| `app.ai.invocation.count` | Counter | AI 调用最终成功或失败次数 |
| `app.ai.invocation.latency` | Timer | AI 调用整体延迟 |
| `app.ai.structured_output.retry.count` | Counter | 结构化输出实际重试次数 |
| `app.ai.structured_output.failure.count` | Counter | 结构化输出最终失败次数 |

此外 `StructuredOutputInvoker` 自己还记录：

- attempts 指标：每次尝试都记录。
- invocation 指标：最终结果记录一次。

### 8.2 RAG 指标

| 指标名 | 类型 | 含义 |
|---|---|---|
| `app.rag.query_rewrite.latency` | Timer | 查询改写耗时 |
| `app.rag.query_rewrite.count` | Counter | 查询改写次数 |
| `app.rag.retrieval.latency` | Timer | 向量检索耗时 |
| `app.rag.retrieval.hit.count` | Summary | 每次检索命中文档块数量 |
| `app.rag.no_result.count` | Counter | 检索零命中次数 |
| `app.rag.answer.count` | Counter | 答案生成次数 |
| `app.rag.answer.latency` | Timer | 答案生成耗时 |

### 8.3 异步任务指标

| 指标名 | 类型 | 含义 |
|---|---|---|
| `app.async.task.created` | Counter | 任务创建 |
| `app.async.task.processing` | Counter | 任务开始处理 |
| `app.async.task.completed` | Counter | 任务成功完成 |
| `app.async.task.failed` | Counter | 任务最终失败 |
| `app.async.task.retry` | Counter | 任务重试 |

### 8.4 语音面试指标

| 指标名 | 类型 | 含义 |
|---|---|---|
| `app.voice.interview.asr.final_segments` | Counter | ASR 最终片段收到次数 |
| `app.voice.interview.asr.merge_wait` | Timer | ASR 片段合并等待耗时 |
| `app.voice.interview.llm.first_token_latency` | Timer | LLM 首 token 延迟 |
| `app.voice.interview.llm.duration` | Timer | LLM 回复总耗时 |
| `app.voice.interview.llm.calls` | Counter | LLM 调用次数 |
| `app.voice.interview.tts.duration` | Timer | TTS 合成耗时 |
| `app.voice.interview.tts.empty_audio` | Counter | TTS 返回空音频次数 |
| `app.voice.interview.turn.duration` | Timer | 一轮语音问答总耗时 |
| `app.voice.interview.turn.completed` | Counter | 一轮语音问答完成次数 |
| `app.voice.interview.errors` | Counter | 语音面试错误次数 |

## 9. 和持久化日志的区别

项目里除了 Micrometer 指标，还有 `aiobservability` 模块做数据库持久化日志。

区别如下：

| 能力 | Micrometer Metrics | 数据库观测日志 |
|---|---|---|
| 主要用途 | 看趋势、告警、聚合分析 | 按 traceId 复盘单次请求 |
| 数据形态 | 时间序列聚合指标 | 明细记录 |
| 适合字段 | 低基数标签，如 status/provider/model | traceId、prompt、question、命中文档 |
| 查询问题 | 最近 5 分钟失败率高不高 | 某次请求为什么失败 |
| 保存内容 | 计数、耗时、分布 | prompt、error、latency、hit docs |

面试关键句：

> Metrics 负责“发生了多少、慢不慢、失败率高不高”，数据库观测日志负责“这一次具体发生了什么”。两者是互补关系。

## 10. 当前项目的真实边界

### 10.1 已经实现的是 Micrometer 埋点

代码里已经使用 `MeterRegistry` 写指标，测试也用 `SimpleMeterRegistry` 验证了核心行为。

### 10.2 还没有显式完成 Prometheus 暴露

当前依赖里没有显式看到：

- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`

也没有看到 `management.endpoints.web.exposure.include=prometheus` 这类配置。

所以要诚实讲：

> 我完成了应用内指标埋点和 Recorder 封装，但要真正接 Prometheus/Grafana，还需要补 Actuator、Prometheus registry、management endpoint 配置和部署侧 scrape 配置。

### 10.3 还缺少部分运行态 Gauge

当前异步任务更多是生命周期 Counter。

如果要更完整，还可以补：

- Redis Stream 当前长度。
- Pending entries 数量。
- Consumer lag。
- 当前 WebSocket 活跃连接数。
- 正在处理的异步任务数。

这些更适合 Gauge。

## 11. 生产化补全路线

如果面试官问“你后续怎么完善”，可以这样答：

1. 引入 `spring-boot-starter-actuator` 和 `micrometer-registry-prometheus`。
2. 在 `application.yml` 暴露 `/actuator/prometheus`。
3. 用 Prometheus scrape 后端服务。
4. Grafana 建四类 dashboard：
   - AI 调用质量面板。
   - RAG 检索质量面板。
   - 异步任务吞吐和失败面板。
   - 语音实时链路延迟面板。
5. 配置告警：
   - AI 失败率过高。
   - RAG 零命中率过高。
   - 异步任务失败或重试激增。
   - 语音 turn duration P95 超过阈值。
6. 补 Gauge：
   - Redis Stream backlog。
   - Pending 消息数量。
   - WebSocket 活跃连接数。
7. 如果系统拆成多个服务，再接 OpenTelemetry。

## 12. 30 秒版本回答

> 这个模块的指标体系是基于 Micrometer 做的。我没有让业务代码直接依赖 Prometheus，而是封装了 AI、RAG、异步任务、语音面试四类 Recorder。AI 记录调用次数、延迟、重试和失败，并按场景、Provider、模型、状态打标签；RAG 按 query rewrite、retrieval、answer 三个阶段记录耗时、命中数和空结果；异步任务记录 created、processing、completed、failed、retry；语音面试记录 ASR、LLM、TTS 和整轮耗时。指标标签避免使用 traceId、sessionId 这类高基数字段，单次问题复盘交给日志和数据库观测明细。当前代码完成了 Micrometer 埋点，生产接 Prometheus 还需要补 Actuator 和 registry 配置。

