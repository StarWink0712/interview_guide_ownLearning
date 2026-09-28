# common/observability 模块总览怎么讲

## 1. 一句话定位

`common/observability` 是这个项目的可观测性基础设施层。

它不直接完成简历解析、面试评估、知识库问答、语音通话这些业务动作，而是负责回答线上排查时最关键的几个问题：

- 这次请求有没有统一的 traceId 可以串起日志？
- AI 调用到底是哪个 Provider、哪个模型、哪个场景变慢了？
- RAG 是查询改写慢、向量检索空召回，还是答案生成慢？
- Redis Stream 异步任务是否创建、处理中、重试、失败？
- 语音面试一轮对话里，是 ASR、LLM、TTS，还是整轮耗时拖慢体验？

面试时可以这样讲：

> 我在项目里把可观测性抽成了一个 common 层模块。业务模块只需要在关键阶段调用 Recorder，底层统一用 Micrometer 的 `MeterRegistry` 写 Counter、Timer、Summary 指标；HTTP 请求通过 `TraceIdFilter` 写入 MDC，让日志带上同一个 `traceId`。这样 AI、RAG、异步任务、语音面试这些高不确定性的链路都能被观测，不只是功能能跑，还能知道慢在哪里、失败在哪里。

## 2. 这个目录下面有什么

| 文件 | 职责 | 面试里的讲法 |
|---|---|---|
| `TraceIdFilter.java` | HTTP 请求 traceId 生成、透传、写入 MDC、响应头回传 | 日志链路追踪入口 |
| `AiMetricsRecorder.java` | 记录结构化 AI 调用次数、延迟、重试、失败 | 观测 LLM Provider 和模型质量 |
| `RagMetricsRecorder.java` | 记录 RAG 查询改写、检索、答案生成三个阶段指标 | 定位 RAG 问答慢或空召回 |
| `AsyncTaskMetricsRecorder.java` | 记录 Redis Stream 异步任务创建、处理中、完成、失败、重试 | 观测异步任务吞吐和失败率 |
| `VoiceMetricsRecorder.java` | 提供语音面试通用 Timer/Counter 记录方法 | 观测 ASR、LLM、TTS、整轮对话延迟 |
| `链路追踪监控/aimetrice.md` | 已有的 AiMetricsRecorder 分析草稿 | 可作为旧版补充材料 |

这个目录的特点是：每个类都很小，但它们插在业务链路的关键节点上。

## 3. 它解决的业务问题

### 3.1 AI 面试平台为什么特别需要可观测性

普通 CRUD 系统出问题，通常可以从接口日志、SQL 日志、异常堆栈定位。

但这个项目的核心能力大量依赖外部和异步环节：

- LLM Provider 可能超时、返回格式不稳定、模型质量波动。
- RAG 可能 query rewrite 成功但检索不到，或者检索到了但答案生成慢。
- Redis Stream 异步任务可能生产成功但消费失败，也可能重试后成功。
- 语音面试是实时链路，ASR、LLM、TTS 任意一环慢，用户都能明显感知。

所以面试里不要只说“我用了 Micrometer”。更好的表达是：

> 我做可观测性的原因不是为了堆技术，而是因为 AI 面试平台存在很多不可控环节。用户只会说“AI 慢”“问答不准”“语音卡”，但后端必须把这句话拆成可定位的工程问题。所以我在 AI 调用、RAG 检索、异步任务、语音链路上都加了指标，并用 traceId 把日志串起来。

### 3.2 一个真实排查场景

假设用户反馈：“知识库问答经常答不上来。”

没有可观测性时，只能猜：

- 是文档没向量化？
- 是 query rewrite 改坏了？
- 是 topK 太小？
- 是 minScore 太高？
- 是 LLM 没正确使用上下文？

有了这个模块之后，可以分层排查：

1. 看 `traceId`，从接口日志定位这次请求。
2. 看 RAG 指标：
   - `app.rag.query_rewrite.latency`
   - `app.rag.retrieval.latency`
   - `app.rag.retrieval.hit.count`
   - `app.rag.no_result.count`
   - `app.rag.answer.latency`
3. 如果 `no_result.count` 高，优先看检索召回问题。
4. 如果检索命中数正常但答案差，继续查 AI 调用日志和 Prompt。
5. 如果只有某个 Provider 慢，看 AI 指标里的 `provider`、`model` 标签。

这个故事比单纯讲指标名更像真实工程经验。

## 4. 模块架构怎么讲

可以按“日志链路 + 指标链路 + 持久化观测日志”三层来讲。

```text
HTTP 请求
  |
  v
TraceIdFilter
  |-- 读取或生成 X-Trace-Id
  |-- 写入 SLF4J MDC: traceId
  |-- 写入响应头: X-Trace-Id
  |
  v
Controller / Service / Async Producer / Consumer / AI / RAG / Voice
  |
  |-- log.info(...) 自动带 traceId
  |-- XxxMetricsRecorder 写 Micrometer 指标
  |-- aiobservability 模块写数据库明细日志
  v
排查手段
  |-- 日志: 追踪单次请求
  |-- Metrics: 看趋势、延迟、失败率、空召回率
  |-- DB logs: 按 traceId 复盘 AI 和 RAG 明细
```

注意这里要诚实：当前 `observability` 包主要实现的是轻量 traceId 和 Micrometer 埋点，不是完整的 OpenTelemetry 分布式 Trace。

## 5. 和其他模块的联动

### 5.1 和 `common/ai`

`StructuredOutputInvoker` 会在结构化输出调用过程中记录三类数据：

- `recordAttempt(...)`：每一次尝试都会记录，包含重试过程。
- `recordInvocation(...)`：一次完整调用的最终成功或失败。
- `recordBusinessAiMetrics(...)`：委托 `AiMetricsRecorder` 记录带 provider/model/scenario 的业务指标。
- `recordAiInvocation(...)`：委托 `AiInvocationLogService` 把调用明细持久化到数据库。

面试时可以这样说：

> 我把 AI 调用观测分成两层：Micrometer 指标用于看总体趋势，比如失败率、P99 延迟、重试率；数据库调用日志用于按 traceId 复盘单次请求，比如看当时的 prompt、provider、model、错误原因。两者不是互相替代，而是分别服务于告警和问题复盘。

### 5.2 和 `modules/knowledgebase`

`KnowledgeBaseQueryService` 会在 RAG 三个阶段调用 `RagMetricsRecorder`：

- 查询改写：`recordRewrite(...)`
- 向量检索：`recordRetrieval(...)`
- 答案生成：`recordAnswer(...)`

同时还会通过 `RagRetrievalLogService` 记录检索明细，包含：

- traceId
- 原问题
- 改写后的问题
- candidate queries
- knowledgeBaseIds
- topK
- minScore
- hitCount
- latencyMs
- 命中文档块

这说明 RAG 不是只做“问答功能”，而是具备可复盘能力。

### 5.3 和 `common/async`

`AbstractStreamProducer` 和 `AbstractStreamConsumer` 会接入 `AsyncTaskMetricsRecorder`。

生产者：

- 发送成功：`recordCreated(...)`
- 发送失败：`recordFailed(...)`
- 入队时从 `TraceIdFilter.currentTraceId()` 获取 traceId，写入 Redis Stream 消息。

消费者：

- 开始处理：`recordProcessing(...)`
- 成功完成：`recordCompleted(...)`
- 进入重试：`recordRetry(...)`
- 最终失败：`recordFailed(...)`
- 消费时把消息里的 traceId 写回 MDC，处理结束后恢复 MDC。

这个设计回答了一个很常见的面试追问：

> 异步任务已经不在原来的 HTTP 线程里了，你怎么把用户请求和后台任务日志串起来？

回答就是：HTTP 线程写 MDC，Producer 把 traceId 放进消息，Consumer 取出 traceId 写回 MDC。

### 5.4 和 `modules/voiceinterview`

`VoiceInterviewWebSocketHandler` 使用 `VoiceMetricsRecorder` 记录：

- `app.voice.interview.asr.final_segments`
- `app.voice.interview.asr.merge_wait`
- `app.voice.interview.llm.first_token_latency`
- `app.voice.interview.llm.duration`
- `app.voice.interview.llm.calls`
- `app.voice.interview.tts.duration`
- `app.voice.interview.tts.empty_audio`
- `app.voice.interview.turn.duration`
- `app.voice.interview.turn.completed`
- `app.voice.interview.errors`

这些指标不是为了好看，而是因为语音面试对实时性非常敏感。

如果用户觉得“语音卡”，你可以拆成：

- 是 ASR 最终片段迟迟不到？
- 是 LLM 首 token 慢？
- 是 LLM 总生成慢？
- 是 TTS 合成慢？
- 是整个 turn duration 超标？

这就是可观测性给业务排查带来的价值。

## 6. 面试讲解主线

建议用这套顺序讲：

1. 先讲业务痛点：AI 面试平台链路长、外部依赖多、实时性要求高，必须能定位慢和失败。
2. 再讲设计目标：不让监控侵入业务核心，用 Recorder 做轻量封装。
3. 再讲三类观测：
   - TraceId/MDC 串日志。
   - Micrometer 指标看趋势和告警。
   - 持久化调用日志按 traceId 复盘。
4. 再讲落点：
   - AI：provider/model/scenario/status/retry/latency。
   - RAG：rewrite/retrieval/answer/hit count/no result。
   - Async：created/processing/completed/failed/retry。
   - Voice：ASR/LLM/TTS/turn duration。
5. 最后讲边界和改进：
   - 目前是轻量 traceId，不是完整 OpenTelemetry。
   - 目前代码有 Micrometer 埋点，但 `build.gradle` 未显式引入 Actuator 和 Prometheus registry。
   - 指标更多是运行质量指标，不直接等价于 AI 答案质量评测。
   - 后续可以补 dashboard、alert rules、队列积压 Gauge、OpenTelemetry spans。

## 7. 当前代码里已经做到的

- HTTP 请求 traceId 生成与响应头回传。
- 日志格式里配置了 `%X{traceId:-}`，能把 MDC traceId 打到日志。
- AI 结构化输出调用有尝试次数、最终调用、业务级 provider/model 维度指标。
- RAG 查询有 query rewrite、retrieval、answer 三阶段指标。
- 检索空结果有单独的 `app.rag.no_result.count`。
- 异步任务有生命周期 Counter。
- 异步消息能传递 traceId。
- 语音面试有 LLM 首 token、LLM 总耗时、TTS 耗时、整轮耗时等指标。
- `AiMetricsRecorderTest` 和 `RagMetricsRecorderTest` 验证了部分核心指标和无 MeterRegistry 时的降级。

## 8. 当前代码里要诚实说明的边界

### 8.1 没有完整接入 Prometheus 暴露端点

代码注释里多次提到 Prometheus/Grafana，这是合理的目标架构。

但从当前 `app/build.gradle` 和 `gradle/libs.versions.toml` 看，没有显式引入：

- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`

所以面试中更准确的说法是：

> 当前代码层已经按 Micrometer 的方式完成了指标埋点，具备对接 Prometheus/Grafana 的基础。要在生产环境真正暴露 `/actuator/prometheus`，还需要补 Actuator、Prometheus registry 依赖和 management endpoint 配置。

不要说成“我已经完整部署了 Prometheus/Grafana”，除非你后面确实补上了部署和配置。

### 8.2 TraceId 不是完整分布式 Trace

`TraceIdFilter` 做的是轻量链路追踪：

- 一个请求一个 traceId。
- 日志带 traceId。
- 响应头返回 traceId。
- 异步消息传递 traceId。

但它没有 spanId、parentSpanId、采样、跨服务 span 拓扑、耗时瀑布图。

更准确的表达：

> 这是轻量级日志链路追踪方案，适合单体项目和异步任务排查。如果系统拆成多个微服务，下一步可以接 OpenTelemetry，把 traceId/spanId 体系补完整。

### 8.3 指标不等于语义质量评估

`RagMetricsRecorder` 可以告诉你：

- 检索是否空结果。
- 检索耗时多少。
- 答案生成是否成功。
- 答案生成耗时多少。

但它不能直接告诉你：

- 答案是否事实正确。
- 是否存在幻觉。
- 引用是否充分。
- 用户是否满意。

所以面试中可以说：

> 这个模块解决的是运行态可观测性，不是离线语义评测。RAG 的答案质量还需要结合测试集、人工标注、命中片段相关性、答案 faithfulness 等评估机制。

## 9. 30 秒版本回答

> `common/observability` 是项目的可观测性基础模块。我用 `TraceIdFilter` 给每个 HTTP 请求生成或透传 `X-Trace-Id`，写入 SLF4J MDC，并通过 logback 输出到日志；异步任务入队时把 traceId 写进 Redis Stream，消费时恢复到 MDC。指标方面，我封装了几个 Recorder，基于 Micrometer 记录 AI、RAG、异步任务、语音面试的关键指标。AI 侧记录 provider、model、场景、成功失败、重试和延迟；RAG 侧按 query rewrite、retrieval、answer 三阶段记录延迟、命中数和空结果；异步任务记录 created、processing、completed、failed、retry；语音链路记录 ASR、LLM、TTS 和整轮耗时。它的价值是把“AI 慢了、RAG 答不上来、语音卡顿”拆成可定位、可告警、可复盘的工程问题。

