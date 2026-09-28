你可以按这个结构讲，先用一句话概括，再拆开说。

## 开场一句话

> 每次 LLM 调用会同时走两条独立的可观测性路径：一条记聚合指标到 Prometheus 做监控告警，一条写详细日志到数据库做排查审计。两条路径失败都不影响业务。

## 然后分两点展开

### 路径1：指标（Metrics）

> 通过 Micrometer 把每次调用的结果（成功/失败、耗时、重试次数）存到 JVM 内存的 Counter 和 Timer 里。Prometheus 每 15 秒抓一次 `/actuator/prometheus`，时序数据进 TSDB，Grafana 出图。用 histogram_quantile 算 P99 延迟，用 Counter 的 rate 算失败率，设置告警规则"失败率连续 5 分钟大于 1% 就通知"。存在内存的好处是零 IO，百万 QPS 也不怕。

### 路径2：日志（Structured Logging）

> 每次调用在 `ai_invocation_logs` 表 INSERT 一条，存 traceId、模型名、promptHash、attemptCount、errorMessage 等 16 个字段。prompt 原文不存数据库，只存 SHA-256 哈希节省空间。查询时按 traceId 把一个 HTTP 请求触发的多次 AI 调用串起来看全链路，或者按场景+时间范围查失败分布。存在数据库的好处是可以做关联查询，比如"同一个 Prompt 模版的成功率变化"。

## 最后收尾

> 为什么用两条路径而不是合并？因为指标和日志的读写模式完全不同——指标需要高频低延迟的聚合计算（P99、rate），适合内存；日志需要持久化和按任意维度关联查询，适合数据库。架构上没有互相依赖，任何一条路径故障都不影响另一条正常工作，也不影响业务 LLM 调用。