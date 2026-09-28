# AI 实施计划：全链路可观测性与指标面板

## 一、执行前提

请先阅读：

- `StructuredOutputInvoker.java`
- `KnowledgeBaseQueryService.java`
- `AbstractStreamConsumer.java`
- `VoiceInterviewWebSocketHandler.java`
- `GlobalExceptionHandler.java`
- `application.yml`

不要一开始引入复杂监控栈。先用 Micrometer 记录业务指标。

## 二、第一阶段：新增 TraceIdFilter

新增：

```text
common/observability/TraceIdFilter.java
```

要求：

- 从请求头读取 `X-Trace-Id`。
- 如果不存在则生成 UUID。
- 放入 MDC。
- 响应头返回 `X-Trace-Id`。
- 请求结束后清理 MDC。

日志配置如果需要，可调整 `logback-spring.xml`，让日志包含 traceId。

## 三、第二阶段：新增指标记录器

新增：

- `AiMetricsRecorder`
- `RagMetricsRecorder`
- `AsyncTaskMetricsRecorder`
- `VoiceMetricsRecorder`

要求：

- 封装 `MeterRegistry`。
- 如果 MeterRegistry 不存在，不影响业务。
- 命名统一使用 `app.xxx.xxx`。

## 四、第三阶段：接入 AI 指标

改造：

- `StructuredOutputInvoker`
- `LlmProviderRegistry` 可选

记录：

- 调用次数。
- 成功/失败。
- 尝试次数。
- 耗时。
- 场景标签。

## 五、第四阶段：接入 RAG 指标

改造：

- `KnowledgeBaseQueryService`

记录：

- query rewrite 耗时。
- vector search 耗时。
- 命中文档数。
- no result 次数。
- stream answer 完成/失败次数。

## 六、第五阶段：接入异步任务指标

改造：

- `AbstractStreamProducer`
- `AbstractStreamConsumer`

记录：

- task created。
- task processing。
- task completed。
- task failed。
- retry count。

标签：

- `taskDisplayName`
- `streamKey`

## 七、第六阶段：业务摘要接口

新增：

```text
modules/monitoring/MonitoringController.java
modules/monitoring/service/BusinessMetricsQueryService.java
```

接口：

```text
GET /api/monitoring/business-summary
```

返回：

- 最近任务数。
- 失败任务数。
- 最近 AI 调用成功率。
- RAG 平均命中数。
- 语音面试平均 turn latency。

第一版可以从数据库日志表或内存 Meter 获取，优先简单可用。

## 八、第七阶段：OpenTelemetry 可选升级

如果项目已经稳定，再接入：

- OpenTelemetry Java Agent 或 Spring Boot Starter。
- OTLP exporter。
- Grafana Tempo / Jaeger。

不要在第一版强行引入完整链路追踪。

## 九、测试要求

新增：

- `TraceIdFilterTest`
- `AiMetricsRecorderTest`
- `RagMetricsRecorderTest`

验证：

- 请求无 traceId 时自动生成。
- 请求有 traceId 时沿用。
- MeterRegistry 缺失时业务不失败。

## 十、验收标准

完成后必须满足：

- 日志中能看到 traceId。
- AI 调用、RAG 检索、异步任务至少有基础指标。
- 业务摘要接口可访问。
- 失败路径也有指标记录。

