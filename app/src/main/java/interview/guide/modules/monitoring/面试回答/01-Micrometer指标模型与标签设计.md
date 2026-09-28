# Micrometer 指标模型与标签设计

## 1. 模块基础讲解

Micrometer 是 Java 监控指标的门面，业务代码面向统一 API 记录 Meter，具体 Registry 决定导出到
Prometheus、Datadog 等后端。项目使用三种主要 Meter：

- `Counter`：只增不减的事件累计数，如任务创建、AI 调用、RAG 零结果。
- `Timer`：记录事件次数和耗时总量，可派生平均值，并可配置 max、histogram 和 percentile。
- `DistributionSummary`：记录非时间数值分布，如每次 RAG 命中文档块数量。

同一指标名称加不同 Tags 会产生不同时间序列。例如：

```text
app.ai.invocation.count{
  scenario="简历评分",
  provider="dashscope",
  model="qwen",
  status="success"
}
```

项目 Recorder 使用可选 `MeterRegistry`：Registry 不存在时直接返回，遵循“监控不能拖垮业务”的
fail-open 思路。但静默 no-op 也意味着监控链路失效不易被发现，应额外暴露 telemetry health。

Micrometer 指标名在代码中使用点号；Prometheus 导出时会规范化为下划线，Actuator `/metrics` 查询
仍使用代码中的原名。

## 2. 基础面试问题

**问题：Counter 和 Gauge 有什么区别？**

回答：Counter 表示累计发生次数，只能递增，查询端通过 rate/increase 得到窗口速率；Gauge 表示当前
瞬时值，可以升降，例如队列长度和活跃连接数。用 Counter 表达当前积压量是错误的。

**问题：Timer 为什么不只记录一个平均值？**

回答：平均值会掩盖长尾。Timer 至少记录 count 和 total time，还可配置 max、histogram 和 P95/P99。
生产告警通常关注错误率、吞吐和高分位延迟，而不是单一 mean。

**问题：DistributionSummary 适合什么？**

回答：适合记录没有时间单位的数值分布，例如响应大小、批次大小、RAG hitCount。它与 Timer 类似，
但不做时间单位换算。

**问题：Tag 的作用是什么？**

回答：Tag 让同一指标按 provider、status、scenario 等低基数维度切片。查询时既能看全局，也能定位
某模型或某阶段异常。

## 3. 进阶面试问题

**问题：为什么不能把 userId、sessionId、traceId 放入指标标签？**

回答：每个唯一标签组合都会创建新时间序列和 Meter，无界值会导致 JVM 内存、Prometheus 存储和查询
成本爆炸。高基数信息应进入 Trace 或日志。VoiceMetricsRecorder 注释中的 `session_id` 示例不应照搬。

**问题：`timer.mean()` 表示哪个窗口？**

回答：取决于 Registry 实现。基本 Timer 的 count/total 通常从进程或 step 起累计，mean 不是业务代码
声明的“最近 24 小时”；max 和 percentile 可能是时间窗口统计。要精确最近窗口，应在 Prometheus 用
`rate(sum)/rate(count)` 或查询时序后端。

**问题：默认 Summary 会自动提供 P95 吗？**

回答：不会。代码注释说 hitCount Summary 可看 percentile，但只有显式配置 publishPercentiles 或
percentileHistogram/SLO buckets 后才会发布相关统计。默认通常只有 count、total、max 等基础值。

**问题：`top_k` 适合作为 Tag 吗？**

回答：若取值来自少量固定配置，可以；若用户可传任意整数，会增加基数。更稳妥是限制合法集合或
分桶为 small/medium/large，把精确值放日志。

**问题：为什么指标记录应使用单调时钟？**

回答：耗时计算应基于 `System.nanoTime()` 或 Registry Clock，不受系统时间校准影响。当前 Recorder
大多这样做；业务时间窗口仍使用 wall clock，两者用途不同。

## 4. 技术延伸问题

**问题：如何设计 RED 指标？**

回答：对每个服务/操作记录 Rate、Errors、Duration。比如 AI 调用量用 Counter，错误通过 status，
Duration 用 Timer histogram；再按受控 provider/scenario 切片。资源层可补 USE：利用率、饱和度、错误。

**问题：客户端 percentile 和服务端 histogram 怎么选？**

回答：客户端 percentile 不能跨实例可靠聚合；Prometheus histogram bucket 可以跨实例求和再计算
P95/P99，通常更适合集群。代价是 bucket 时间序列增加，需要按实际 SLO 配置边界。

**问题：如何限制标签爆炸？**

回答：代码层使用枚举/白名单；Registry 配 `MeterFilter.maximumAllowableTags` 和最大 Meter 数；监控
高基数检测；把 URI 使用模板而不是实际 ID；定期审计时间序列数量和成本。

## 5. 改进方案问题

**问题：Recorder API 应如何改进？**

回答：VoiceMetricsRecorder 不应允许任意 metricName 和裸 `String... tags`。可定义指标常量、标签枚举
和专用方法，编译期约束标签数和值域，防止奇数参数、拼写漂移和高基数标签。

**问题：如何配置语音延迟 SLO？**

回答：用 `Timer.builder(...).serviceLevelObjectives(...)` 或 MeterFilter 配 500ms、1s、2s、5s 等业务
边界，发布 histogram；Dashboard 展示 P50/P95/P99 和超 SLO 比例，而不是生命周期 mean。

**问题：如何处理监控不可用？**

回答：业务记录仍 fail-open，但增加 `telemetry.enabled`、Registry 类型、最近 scrape、meter 数量等自监控；
启动生产环境时若要求监控却无 Registry，应 fail-fast，而不是永远静默。

**问题：指标命名如何统一？**

回答：采用稳定域前缀和单位无关命名，如 `app.voice.interview.turn` 由 Timer 自带 seconds 单位；状态
使用 Tag，避免为 success/failure 建重复名称。维护指标目录、owner、描述、标签和 SLO。

## 6. 故障处理问题

**问题：同名指标读取结果不稳定，怎么查？**

回答：列出该名称下所有 Meter ID 和 Tags。当前语音 turn Timer 有 status=success/failure 两条序列，
`find(name).timer()` 只取一条而非自动聚合，返回哪条不应作为全局均值。应按标签选择或在后端聚合。

**问题：JVM 内存持续增长且 Meter 数量很多，怎么查？**

回答：按指标名统计 tag value 数，寻找 sessionId、traceId、动态错误消息或任意 topK；立即用 MeterFilter
限制，再修调用代码。已经注册的高基数 Meter 可能需要重启才能释放。

**问题：Timer count 有值但 P95 没有，为什么？**

回答：没有启用 percentile/histogram，或后端不支持；检查 Meter 配置和导出序列。Timer 基础 count
和 total 存在不代表自动生成高分位。

**问题：Prometheus 中找不到点号指标名，为什么？**

回答：Prometheus 命名约定会把点号转换为下划线，并为 Timer 生成 `_seconds_count/_sum/_max` 等序列。
Actuator 查询仍使用原始点号名，两个入口不要混淆。
