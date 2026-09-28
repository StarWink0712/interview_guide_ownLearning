# StructuredOutputInvoker AI 调用指标记录详解

> 文件：`common/ai/StructuredOutputInvoker.java`
> 每次 `invoke()` 调用同时触发两条独立的可观测性路径，各自独立、互不影响。

***

## 一、可观测性架构概览

```
                    StructuredOutputInvoker.invoke()
                           │
              ┌────────────┼────────────┐
              ↓                         ↓
     recordBusinessAiMetrics()    recordAiInvocation()
              │                         │
              ↓                         ↓
       路径1: AiMetricsRecorder    路径2: AiInvocationLogService
       (Micrometer 内存)           (PostgreSQL 数据库)
              │                         │
              ↓                         ↓
       Prometheus 定期抓取          logRepository.save(entity)
              │                         │
              ↓                         ↓
       Grafana 监控大盘             REST API 查询排障
```

***

## 二、重试循环的控制流

`invoke()` 方法通过 `for` 循环 + `return` / `continue` / fall-through 三种控制流区分成功和失败。
**核心规则：路径1 和路径2 都遵循同一条原则——每次完整 invoke 只记录一次最终结果，中间重试不记录。**

```
┌─ for (attempt = 1; attempt <= maxAttempts; attempt++) ──────────────────┐
│   try:                                                                  │
│       第 1 次调用 LLM → JSON 解析成功（一次就成）                         │
│                                                                         │
│         → recordAttempt("success")          ← Micrometer, 每次尝试都记   │
│         → recordInvocation("success", t0)   ← Micrometer, 最终结果记一次 │
│         → recordBusinessAiMetrics(..., true)← 路径1, 最终结果记一次      │
│         → recordAiInvocation(..., true)     ← 路径2, 最终结果记一次      │
│         → return result  ← 方法结束，循环后续代码永不执行                │
│                                                                         │
│       或者：第 2 次调用才成功                                            │
│       attempt #1: 失败 → catch:                                         │
│         → recordAttempt("failure")  ← 唯一在中间失败时记录的指标         │
│         → continue                  ← 进入下一次循环重试                 │
│       attempt #2: 成功 → try:                                           │
│         → recordAttempt("success")                                      │
│         → recordInvocation("success", t0)   ← 含 attempt#1 的耗时       │
│         → recordBusinessAiMetrics(..., true, attemptCount=2)             │
│             → invocation.count(success)+1, retry.count+1                 │
│         → recordAiInvocation(..., true, attemptCount=2)                  │
│             → INSERT ai_invocation_logs(attempt_count=2, success=true)   │
│         → return result                                                 │
│                                                                         │
│       或者：第 3 次调用才成功                                            │
│       attempt #1: 失败 → recordAttempt("failure") → continue             │
│       attempt #2: 失败 → recordAttempt("failure") → continue             │
│       attempt #3: 成功 →                                                │
│         → recordAttempt("success")                                      │
│         → recordInvocation("success", t0)                               │
│         → recordBusinessAiMetrics(..., true, attemptCount=3)             │
│             → invocation.count(success)+1, retry.count+2                 │
│         → recordAiInvocation(..., true, attemptCount=3)                  │
│             → INSERT ai_invocation_logs(attempt_count=3, success=true)   │
│         → return result                                                 │
│                                                                         │
│   catch:                                                                │
│     失败（无论第几次）                                                   │
│       → 只记录 recordAttempt("failure")                                 │
│       → 不记 recordInvocation（等最终结果）                              │
│       → 不记 recordBusinessAiMetrics（等最终结果）                       │
│       → 不记 recordAiInvocation（等最终结果）                            │
│       → continue  ← 进入下一次循环重试                                   │
└─────────────────────────────────────────────────────────────────────────┘
  ↓ 循环结束（走到了这里 = 所有 maxAttempts 次尝试均失败，never return）
→ recordInvocation("failure", startNanos)
    → app.ai.structured_output.invocations{failure} +1, latency = 全部尝试的总耗时
→ recordBusinessAiMetrics(..., success=false, attemptCount=maxAttempts)
    → app.ai.invocation.count{failure} +1, retry.count +(maxAttempts-1), failure.count +1
→ recordAiInvocation(..., success=false, attemptCount=maxAttempts)
    → INSERT ai_invocation_logs(attempt_count=3, success=false, error_code="RESUME_ANALYSIS_FAILED")
→ throw BusinessException
```

### 为什么中间失败只记 recordAttempt？

| 记录方法                           | 中间失败时 | 最终成功时 | 最终失败时 | 原因                                     |
| ------------------------------ | :---: | :---: | :---: | -------------------------------------- |
| `recordAttempt`                |   记录  |   记录  |   记录  | 每次 LLM 尝试都记，统计 "JSON 解析失败比例"           |
| `recordInvocation`             |   不记  |  记一次  |  记一次  | 等最终结果，含总延迟                             |
| `recordBusinessAiMetrics`（路径1） |   不记  |  记一次  |  记一次  | 每条代表一次完整 invoke，中间失败记了会干扰成功率统计         |
| `recordAiInvocation`（路径2）      |   不记  |  记一次  |  记一次  | 同上，且数据库存中间失败的 attempt 无实际价值（用户只关心最终结果） |

如果中间失败也记后三条，在"第 1 次失败→第 2 次成功"的场景中：

- Prometheus 会同时看到 `success=true` 和 `success=false` 各一条 → 成功率 50%（实际是 100%，第 1 次的失败已在第 2 次挽回）
- 数据库会同时有两条记录 → 用户查日志时会困惑 "到底成功还是失败？"

***

## 三、路径1：AiMetricsRecorder → Micrometer → Prometheus → Grafana

### 调用链

```
recordBusinessAiMetrics(logContext, executedAttempts, true/false, startNanos)
  │
  ├── resolveDefaultProviderId()        → "dashscope"
  ├── resolveModel(providerId)          → "qwen-plus"
  │
  └── aiMetricsRecorder.recordStructuredInvocation(
        "简历评分",      // scenario — 业务场景
        "dashscope",     // provider — LLM 厂商
        "qwen-plus",     // model    — 模型名
        true/false,      // success
        3,               // attemptCount
        startNanos       // 用于计算总延迟
      )
        │
        ├── 构建 Tags: scenario + provider + model + status
        │
        ├── 指标 1: meterRegistry.counter("app.ai.invocation.count", tags).increment()
        │     Counter 自增 1，区分 success/failure
        │
        ├── 指标 2: meterRegistry.timer("app.ai.invocation.latency", tags)
        │           .record(System.nanoTime() - startNanos, NANOSECONDS)
        │     直方图桶追加一个时间戳
        │
        ├── 指标 3: if (attemptCount > 1) meterRegistry.counter("app.ai.structured_output.retry.count", tags)
        │           .increment(attemptCount - 1)
        │     首次成功 → 不记录；重试2次成功 → +2
        │
        └── 指标 4: if (!success) meterRegistry.counter("app.ai.structured_output.failure.count", tags)
                    .increment()
              仅失败时 +1，简化告警规则
```

### 记录的 4 个指标

| 指标名                                      | 类型      | 计算方式                                 | 业务意义                                         |
| ---------------------------------------- | ------- | ------------------------------------ | -------------------------------------------- |
| `app.ai.invocation.count`                | Counter | 每完成一次完整 invoke（含重试），+1               | 按 scenario/provider/model/status 切片统计吞吐量和成功率 |
| `app.ai.invocation.latency`              | Timer   | `(now - startNanos) / 1e9` 秒，含所有重试耗时 | 同时输出 count/sum/bucket，可算 P50/P90/P99 延迟分布    |
| `app.ai.structured_output.retry.count`   | Counter | `attemptCount - 1`，仅 >0 时记录          | 监控 JSON 解析质量，重试多说明 LLM 输出格式不稳定               |
| `app.ai.structured_output.failure.count` | Counter | 仅 `success=false` 时 +1               | 独立告警指标，不依赖 label 过滤，简化 PromQL                |

### 存储方案

| 层级         | 存储位置                | 数据格式                                    | 保留周期        | 访问方式                   |
| ---------- | ------------------- | --------------------------------------- | ----------- | ---------------------- |
| Micrometer | JVM 堆内存             | ConcurrentHashMap + AtomicDouble/直方图桶数组 | JVM 进程生命周期  | `/actuator/metrics` 端点 |
| Prometheus | 本地 TSDB 磁盘文件        | 压缩时序数据（\~1.3字节/样本）                      | 默认 15 天（可配） | PromQL 查询 / HTTP API   |
| Grafana    | Dashboard JSON（展示层） | —                                       | 永久          | 图表面板                   |

### Prometheus 输出示例

```
# HELP app_ai_invocation_count_total  AI 调用总次数
# TYPE app_ai_invocation_count_total counter
app_ai_invocation_count_total{scenario="简历评分",provider="dashscope",model="qwen-plus",status="success"} 142.0
app_ai_invocation_count_total{scenario="简历评分",provider="dashscope",model="qwen-plus",status="failure"} 8.0

# HELP app_ai_invocation_latency_seconds  AI 调用延迟
# TYPE app_ai_invocation_latency_seconds histogram
app_ai_invocation_latency_seconds_count{scenario="简历评分",...} 142.0
app_ai_invocation_latency_seconds_sum{scenario="简历评分",...} 623.5
app_ai_invocation_latency_seconds_bucket{scenario="简历评分",...,le="0.5"} 5.0
app_ai_invocation_latency_seconds_bucket{scenario="简历评分",...,le="1.0"} 38.0
...

# HELP app_ai_structured_output_retry_count_total  结构化输出重试次数
# TYPE app_ai_structured_output_retry_count_total counter
app_ai_structured_output_retry_count_total{scenario="简历评分",...} 35.0

# HELP app_ai_structured_output_failure_count_total  结构化输出失败次数
# TYPE app_ai_structured_output_failure_count_total counter
app_ai_structured_output_failure_count_total{scenario="简历评分",...,status="failure"} 8.0
```

### 典型 PromQL 查询

```promql
# 最近 5 分钟每秒调用量（QPS）
sum(rate(app_ai_invocation_count_total[5m]))

# 各场景成功率
sum(rate(app_ai_invocation_count_total{status="success"}[5m])) by (scenario)
/ sum(rate(app_ai_invocation_count_total[5m])) by (scenario)

# P99 延迟（秒）
histogram_quantile(0.99, rate(app_ai_invocation_latency_seconds_bucket[5m]))

# 每分钟重试次数
rate(app_ai_structured_output_retry_count_total[1m])
```

***

## 四、路径2：AiInvocationLogService → PostgreSQL

### 调用链

```
recordAiInvocation(traceId, logContext, executedAttempts, true/false,
                   startNanos, errorCode/null, error/null, systemPrompt, userPrompt, log)
  │
  ├── resolveDefaultProviderId()        → "dashscope"
  ├── resolveModel(providerId)          → "qwen-plus"
  ├── System.nanoTime() - startNanos    → latencyMs
  │
  └── aiInvocationLogService.recordInvocation(new AiInvocationLogCreateRequest(
        traceId,           // HTTP header X-Trace-Id 或 UUID
        logContext,        // "简历评分" / "面试评估"
        "dashscope",       // provider
        "qwen-plus",       // model
        "structured-output-v1", // promptVersion
        false,             // streaming
        true,              // structuredOutput
        attemptCount,      // 成功:N  失败:maxAttempts
        success,           // 最终是否成功
        latencyMs,         // 总耗时毫秒
        errorCode,         // 失败错误码（成功=null）
        errorMsg,          // 异常信息（成功=null）
        systemPrompt,      // System Prompt 原文
        userPrompt         // User Prompt 原文
      ))
        │
        ▼
  AiInvocationLogService.recordInvocation():
        │
        ├── 加工1: promptSource = systemPrompt + "\n" + userPrompt
        │
        ├── 加工2: promptHash = SHA-256(promptSource) → 64 位 hex
        │    不存原文！只有 hash + length
        │
        ├── 加工3: errorMessage = truncate(errorMsg, 1000)
        │    防止异常堆栈撑爆 TEXT 字段
        │
        ├── 加工4: traceId 为空时 → UUID.randomUUID()
        │
        ├── 构建 Entity → AiInvocationLogEntity.builder()
        │     .traceId / .scenario / .providerId / .model
        │     .promptVersion / .promptHash / .promptLength
        │     .streaming / .structuredOutput
        │     .attemptCount / .success / .latencyMs
        │     .errorCode / .errorMessage
        │     .build()
        │
        ├── logRepository.save(entity)  → JPA → JDBC → PostgreSQL
        │     INSERT INTO ai_invocation_logs (...)
        │
        └── 双重 try-catch: 此方法 + AiInvocationLogService 各一层
              写入失败只打 warn 日志，不抛异常
```

### 数据库存储结构

表：`ai_invocation_logs`

| 字段                  | 类型                   | 说明                |
| ------------------- | -------------------- | ----------------- |
| `id`                | BIGSERIAL PK         | 自增主键              |
| `trace_id`          | VARCHAR(64) NOT NULL | 链路追踪 ID，索引        |
| `scenario`          | VARCHAR(100)         | 业务场景，索引           |
| `provider_id`       | VARCHAR(80)          | LLM 厂商            |
| `model`             | VARCHAR(120)         | 模型名               |
| `prompt_version`    | VARCHAR(80)          | Prompt 版本         |
| `prompt_hash`       | VARCHAR(64)          | SHA-256 哈希（不存原文！） |
| `prompt_length`     | INTEGER              | Prompt 总字符数       |
| `streaming`         | BOOLEAN              | 是否流式              |
| `structured_output` | BOOLEAN              | 是否结构化输出           |
| `attempt_count`     | INTEGER              | 总尝试次数（含重试）        |
| `success`           | BOOLEAN              | 最终是否成功            |
| `latency_ms`        | BIGINT               | 总耗时毫秒             |
| `error_code`        | VARCHAR(80)          | 失败错误码             |
| `error_message`     | TEXT                 | 失败原因（截断到 1000 字符） |
| `created_at`        | TIMESTAMP NOT NULL   | 创建时间，索引           |

### 查询方式

| 查询        | SQL                                                                           | 对应 API                                              |
| --------- | ----------------------------------------------------------------------------- | --------------------------------------------------- |
| 最近 50 条   | `SELECT * FROM ai_invocation_logs ORDER BY created_at DESC LIMIT 50`          | `GET /api/ai-observability/invocations`             |
| 按 traceId | `SELECT * FROM ai_invocation_logs WHERE trace_id = ? ORDER BY created_at ASC` | `GET /api/ai-observability/invocations?traceId=xxx` |
| 单条详情      | `findById(id)`                                                                | `GET /api/ai-observability/invocations/{id}`        |

***

## 五、invoke() 内部附加的粗粒度指标（仅 Micrometer 内存）

除了两条主路径外，`invoke()` 内部还有两组直接写 Micrometer 的指标，维度只有 `context + status`（比路径1/2 更粗）：

### recordAttempt

```java
// 每次 LLM 尝试都记录，含重试
recordAttempt(contextTag, "success" / "failure")
  → app.ai.structured_output.attempts{context="jian_li_ping_fen", status="success"}

// 一次 invoke 重试 3 次最终成功 → 2 次 failure + 1 次 success
```

用于统计"每次尝试的成功率"、"JSON 解析失败比例"、"第几次重试最容易失败"。

### recordInvocation

```java
// 每次完整 invoke 的最终结果，只记一次
recordInvocation(contextTag, "success" / "failure", startNanos)
  → app.ai.structured_output.invocations{context="jian_li_ping_fen", status="success"}
  → app.ai.structured_output.latency{context="jian_li_ping_fen", status="success"}
```

与路径1 的 `AiMetricsRecorder` 功能重叠，但维度只有 `context + status`，缺少 `provider` 和 `model`。

***

## 六、四条记录路径对比总结

| 路径             | 方法                        | 存储                                | 维度                                   | 记录频率             | 用途                 |
| -------------- | ------------------------- | --------------------------------- | ------------------------------------ | ---------------- | ------------------ |
| 粗粒度 attempt    | `recordAttempt`           | Micrometer 内存                     | context + status                     | 每次 LLM 尝试（含重试）   | 统计重试率和 JSON 解析失败比例 |
| 粗粒度 invocation | `recordInvocation`        | Micrometer 内存                     | context + status                     | 每次完整 invoke 最终结果 | 与路径1 重叠但维度更少       |
| **路径1** 细粒度指标  | `recordBusinessAiMetrics` | Micrometer → Prometheus → Grafana | scenario + provider + model + status | 每次完整 invoke 最终结果 | 监控大盘、告警、SLO        |
| **路径2** 调用日志   | `recordAiInvocation`      | PostgreSQL `ai_invocation_logs`   | 16 个字段                               | 每次完整 invoke 最终结果 | 事后排障、审计追踪          |

### 实际数据示例

一次简历评分，第 1 次 LLM 返回非法 JSON 失败，第 2 次成功：

```
attempt #1 失败:
  recordAttempt(context, "failure")         → attempt{failure} +1
  (不记其他3条)

attempt #2 成功:
  recordAttempt(context, "success")         → attempt{success} +1
  recordInvocation(context, "success", t0)  → invocation{success} +1, latency = 3.2s
  recordBusinessAiMetrics("简历评分", "dashscope", "qwen-plus", success=true, attemptCount=2)
    → invocation.count(success) +1, latency 3.2s, retry.count +1
  recordAiInvocation(..., success=true, attemptCount=2)
    → ai_invocation_logs INSERT 一条记录, attempt_count=2, success=true
```

失败同理——3 次全部失败：

```
attempt #1~3 各失败:
  recordAttempt(context, "failure") × 3    → attempt{failure} +3

循环耗尽后:
  recordInvocation(context, "failure", t0)  → invocation{failure} +1, latency=12.8s
  recordBusinessAiMetrics(..., success=false, attemptCount=3)
    → invocation.count(failure) +1, latency 12.8s, retry.count +2, failure.count +1
  recordAiInvocation(..., success=false, attemptCount=3)
    → ai_invocation_logs INSERT 一条记录, attempt_count=3, success=false, error_code="RESUME_ANALYSIS_FAILED"
```

<br />

<br />

让我用最简单的方式说清楚。

## 一句话区别

同一个 `invoke()` 结束时，同时做了两件事：

- **路径1**：给计数器 +1 → 记到内存 → 被 Prometheus 抓走 → 画成 Grafana 折线图
- **路径2**：写一条数据库记录 → 可以按 traceId 搜索 → 在管理后台查看详情

## 打个比方

就像你去医院体检：

| <br /> | 路径1（指标）              | 路径2（日志）                |
| :----- | :------------------- | :--------------------- |
| 记什么    | 血压 120、心率 72         | 姓名、年龄、症状、诊断结果全文        |
| 存哪     | 护士手里的记录本（汇总统计）       | 你的病历档案（可随时调阅）          |
| 怎么查    | "今天体检的 100 人平均心率多少？" | "张三昨天 14:30 的体检结果是什么？" |
| 能查细节吗  | 不能，只有数字              | 能，看得到完整的诊断描述           |

## 用代码说话

同一次 `invoke()` 的同一时刻，两段代码先后执行：

```java
// invoke() 内部，LLM 返回成功解析后：

// 路径1 — 只记了 4 个数字到内存
recordBusinessAiMetrics("简历评分", 2, true, startNanos);
// → aiMetricsRecorder 内部:
//     counter("invocation.count", {scenario="简历评分", status="success"}) += 1
//     timer("invocation.latency", {scenario="简历评分", status="success"}).record(3.2秒)
//     counter("retry.count", {scenario="简历评分", status="success"}) += 1

// 路径2 — 写了一整条数据库记录
recordAiInvocation(traceId, "简历评分", 2, true, startNanos,
                   null, null, systemPrompt, userPrompt, log);
// → aiInvocationLogService.recordInvocation():
//     INSERT INTO ai_invocation_logs(trace_id, scenario, model, prompt_hash,
//       attempt_count, success, latency_ms, error_code, error_message, created_at)
//     VALUES ('abc-123', '简历评分', 'qwen-plus', 'e3b0c4...', 2, true, 3200, null, null, NOW())
```

## 具体能做什么

**路径1（Prometheus/Grafana 问的问题）：**

- 过去 1 小时简历评分场景调了多少次 LLM？
- P99 延迟超过 30 秒了吗？要不要告警？
- 结构化输出的重试率是不是在升高？（说明 LLM JSON 格式不稳定的趋势）

**路径2（数据库/管理后台问的问题）：**

- traceId `abc-123` 那次调用到底发生了什么？为什么 attemptCount=2？
- 昨天 14:30\~15:00 之间有多少次失败？都是什么错误码？
- 某个 Prompt 模板 `structured-output-v1` 调了多少次？（用 promptHash 去重统计）

## 为什么不能合并成一个

如果只用数据库存所有调用，Prometheus 没法做实时聚合和告警（每秒 QPS、P99 延迟这些需要在内存里快速算）。如果只用 Micrometer 内存指标，丢了 traceId 和错误详情，出问题找不到具体是那次调用。两者互补，各干各的。
