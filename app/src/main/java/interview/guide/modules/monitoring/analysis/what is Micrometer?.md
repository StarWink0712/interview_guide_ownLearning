## Micrometer 是什么？

### 一句话解释

**Micrometer 是 Java 生态里的"SLF4J 版本"做指标监控的。** 

就像 SLF4J 统一了各种日志框架（Logback、Log4j 等）的 API 一样，Micrometer 统一了各种监控系统（Prometheus、Datadog、New Relic 等）的 API。

---

### 类似 SLF4J 的类比

| 对比项 | SLF4J（日志） | Micrometer（指标） |
|--------|-------------|-------------------|
| **统一 API** | `logger.info("xxx")` | `meterRegistry.counter("xxx")` |
| **底层实现** | Logback / Log4j | Micrometer Prometheus Registry / Datadog Registry |
| **输出** | 写入文件或控制台 | 暴露为 `/actuator/prometheus` 端点 |
| **最终消费** | 开发者看日志 | Prometheus 采集 → Grafana 展示 |

```java
// 你的代码只需要面向 Micrometer 统一 API 写
meterRegistry.counter("app.ai.invocation.count", ...).increment();

// 底层配置决定数据去哪：
// 引入 micrometer-registry-prometheus → 暴露 Prometheus 格式的 /actuator/prometheus 端点
// 引入 micrometer-registry-datadog   → 直接推送到 Datadog 平台
// 不引入任何 Registry → meterRegistry 为 null，指标记录静默跳过
```

---

### 在本项目中，Micrometer 生了三种"蛋"

你的代码里有四种 Recorder，它们用的是 Micrometer 的三种核心指标类型：

| Micrometer 类型 | 作用 | 项目中的使用 |
|----------------|------|------------|
| **Counter** | 只增不减的计数器 | `invocation.count`、`retry.count`、`failed`、`created` |
| **Timer** | 记录耗时分布 | `latency`（P50/P99/P999 延迟） |
| **Summary** | 记录值的大小分布 | `hit.count`（命中文档数的分布情况） |

每种类型都可以附带**标签（Tags）** 进行切片分析，比如按 `provider`、`scenario`、`model` 拆分统计。

---

### 为什么用 Micrometer 而不是直接用 Prometheus？

```java
// 不用 Micrometer，直接写死 Prometheus：
CollectorRegistry.defaultRegistry.counter("xxx").inc();

// 用 Micrometer：
meterRegistry.counter("xxx").increment();
```

区别在于：如果某天公司决定从 Prometheus 迁移到 Datadog，**直接写死 Prometheus** 的代码要全部重写；**用 Micrometer** 的只需要改一行配置依赖（从 `micrometer-registry-prometheus` 换成 `micrometer-registry-datadog`），代码一行不动。

---

### 本项目中 Micrometer 数据的流转路径

```
你的 Recorder（如 AiMetricsRecorder）
    ↓ meterRegistry.counter("app.ai.invocation.count", ...).increment()
Micrometer 内存计数器
    ↓ 通过 /actuator/prometheus 端点暴露
Prometheus 每 15 秒拉取一次
    ↓ 存储在 TSDB 时序数据库
Grafana 查询 Prometheus 数据
    ↓ 渲染成折线图、仪表盘
你看到的监控面板
```

所以你在 Grafana 上看到的每个"AI 调用延迟 P99"图表，底层就是一个 `Timer.record()` 不断写入数据，Prometheus 定期拉取，Grafana 查询展示的过程。