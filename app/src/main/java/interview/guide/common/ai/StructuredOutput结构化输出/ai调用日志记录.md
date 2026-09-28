## 第二条路径：AI 调用日志持久化到数据库

---

## 一、完整调用链

```
业务代码（ResumeGradingService / InterviewQuestionService 等）
  │
  └─→ StructuredOutputInvoker.invoke(chatClient, systemPrompt, userPrompt, ...)
        │
        ├── 重试循环（LLM 调用 + JSON 解析，最多 N 次）
        │
        ├── 成功时, invoke() 第 303 行:
        │     recordAiInvocation(traceId, context, attemptCount, true, startNanos,
        │                        null, null, systemPrompt, userPrompt, log)
        │
        └── 全部失败时, invoke() 第 338 行:
              recordAiInvocation(traceId, context, executedAttempts, false, startNanos,
                                 errorCode.name(), lastError, systemPrompt, userPrompt, log)
                    │
                    ├── resolveDefaultProviderId()       → "dashscope"
                    ├── resolveModel(providerId)          → "qwen-plus"
                    ├── TimeUnit.NANOSECONDS.toMillis(    → 纳秒→毫秒
                    │     System.nanoTime() - startNanos)
                    │
                    └── 构建 AiInvocationLogCreateRequest（14 个字段, record）
                         new AiInvocationLogCreateRequest(
                           traceId,      // 链路追踪 ID
                           logContext,   // 业务场景
                           providerId,   // LLM 厂商
                           model,        // 模型名
                           "structured-output-v1",  // Prompt 版本
                           false,        // streaming
                           true,         // structuredOutput
                           attemptCount, // 总尝试次数
                           success,      // 最终是否成功
                           latencyMs,    // 总耗时（毫秒）
                           errorCode,    // 失败错误码（成功为 null）
                           errorMsg,     // 异常信息（成功为 null）
                           systemPrompt, // System Prompt 原文
                           userPrompt    // User Prompt 原文
                         )
                              │
                              │  传递给 AiInvocationLogService
                              │
  ┌───────────────────────────┘
  │
  ▼
AiInvocationLogService.recordInvocation(request)
  │
  ├── 第 123-124 行: 拼接 prompt 源文本
  │     String promptSource = safeText(systemPrompt) + "\n" + safeText(userPrompt)
  │
  ├── 第 126-148 行: 构建 Entity → 字段加工
  │     AiInvocationLogEntity.builder()
  │       .traceId( nonBlankOr(traceId, UUID.randomUUID()) )  // null → 自动生成
  │       .scenario(request.scenario())
  │       .providerId(request.providerId())
  │       .model(request.model())
  │       .promptVersion(request.promptVersion())
  │       .promptHash( hash(promptSource) )       // ← 加工1: SHA-256 哈希
  │       .promptLength( promptSource.length() )   // ← 加工2: 只存长度
  │       .streaming(request.streaming())
  │       .structuredOutput(request.structuredOutput())
  │       .attemptCount(request.attemptCount())
  │       .success(request.success())
  │       .latencyMs(request.latencyMs())
  │       .errorCode(request.errorCode())
  │       .errorMessage( truncate(errorMsg, 1000) ) // ← 加工3: 截断
  │       .build()
  │
  ├── 第 149 行: 数据库写入
  │     logRepository.save(entity)
  │       │
  │       ▼
  │     AiInvocationLogRepository (Spring Data JPA)
  │       │
  │       ▼
  │     Hibernate → JDBC → PostgreSQL
  │       INSERT INTO ai_invocation_logs (
  │         trace_id, scenario, provider_id, model, prompt_version,
  │         prompt_hash, prompt_length, streaming, structured_output,
  │         attempt_count, success, latency_ms, error_code, error_message, created_at
  │       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  │
  └── 第 149 行: 返回 DTO
        toDTO(entity) → AiInvocationLogDTO
          → Optional.of(dto) 返回给 StructuredOutputInvoker
          
  ⚠️ 异常降级（第 150-154 行）:
     整个方法被 try-catch 包裹，任何数据库写入失败、
     hash 计算失败等都只记录 warn 日志 + 返回 Optional.empty()
     → StructuredOutputInvoker.recordAiInvocation 里也有 try-catch
     → 双重保护，绝不抛异常影响业务
```

---

## 二、各组件间的交互顺序

```
时间线:

t0  业务: ResumeGradingService.analyzeResume() 调用 invoke()
t1  invoke(): 获取 traceId（HTTP header X-Trace-Id 或生成 UUID）
t2  invoke(): 循环调用 ChatClient + JSON 解析，直到成功或耗尽重试
    ...（中间失败时只记录 Micrometer 指标，不写数据库）
tn  invoke(): LLM 最终成功 → 调用 recordAiInvocation()
    │
    ├─→ resolveDefaultProviderId()  读 LlmProviderProperties 配置 Bean
    ├─→ resolveModel(providerId)    读 LlmProviderProperties.Providers Map
    ├─→ System.nanoTime() - startNanos → TimeUnit.NANOSECONDS.toMillis()
    │
    ├─→ new AiInvocationLogCreateRequest(...)   ← 不可变 record，14 个字段
    │
    └─→ aiInvocationLogService.recordInvocation(request)
          │
          ├─→ safeText() × 2         纯字符串拼接，无外部调用
          ├─→ hash(promptSource)     MessageDigest.getInstance("SHA-256") JVM 内置
          ├─→ truncate(errorMsg, 1000) 纯字符串操作
          ├─→ Entity.builder().build()  内存对象构建
          │
          └─→ logRepository.save(entity)
                │
                └─→ Hibernate Session → TransactionSynchronization
                      → DataSource.getConnection()
                      → PreparedStatement.executeUpdate()
                      → PostgreSQL WAL → 磁盘

tn+1 invoke(): recordBusinessAiMetrics() 再记录 Micrometer 指标（另一条路径）
tn+2 invoke(): return result ← 业务拿到解析后的 Java 对象
```

---

## 三、数据传递方式

| 传递节点 | 数据类型 | 传递方式 |
|---------|---------|---------|
| 业务 → invoke() | ChatClient, String, BeanOutputConverter | 方法参数（Java 对象引用） |
| invoke() 内部 | long startNanos, int attemptCount, boolean success | 局部变量和循环迭代 |
| invoke() → recordAiInvocation() | traceId, prompt 原文, error 等 9 个参数 | 方法参数（基本类型 + String） |
| recordAiInvocation() → AiInvocationLogCreateRequest | 14 个字段 | 不可变 Java `record` 对象 |
| AiInvocationLogCreateRequest → recordInvocation() | request 对象引用 | 方法参数 |
| recordInvocation() 内部 | promptSource, Entity, DTO | String / JPA Entity / DTO 对象 |
| recordInvocation() → Repository | AiInvocationLogEntity | JPA `save(entity)` 返回值 |
| Repository → PostgreSQL | 16 个字段值 | JDBC PreparedStatement 绑定参数 |
| invoke() 中 try-catch | Optional.empty() / warn 日志 | 降级返回，不抛异常 |

---

## 四、数据库记录的每个字段详解

### 自动生成字段

| 字段 | 生成方式 | 值示例 | 意义 |
|------|---------|--------|------|
| `id` | `@GeneratedValue(IDENTITY)` PostgreSQL 自增 | 1042 | 主键，唯一标识 |
| `created_at` | `@PrePersist` JPA 在 INSERT 前自动设置 | `2026-06-19 14:30:00` | 审计追踪，知道什么时候调用的 |

### 链路追踪字段

| 字段 | 来源 | 计算/生成方式 | 含义 |
|------|------|-------------|------|
| `trace_id` | `TraceIdFilter.currentTraceId()` → HTTP 请求头 `X-Trace-Id`，没有则 `UUID.randomUUID()` | 优先用 HTTP 传入的，空则 36 位 UUID | 将同一 HTTP 请求触发的多次 AI 调用串联起来 |

### 调用方信息字段

| 字段 | 来源 | 值示例 | 含义 |
|------|------|--------|------|
| `scenario` | `invoke()` 的参数 `logContext` | "简历评分"、"面试评估"、"问题生成" | 标识哪个业务场景触发了这次调用 |
| `provider_id` | `LlmProviderProperties.getDefaultProvider()` | "dashscope" | 用的是哪个 LLM 厂商 |
| `model` | `LlmProviderProperties.getProviders().get(id).getModel()` | "qwen-plus" | 用的是哪个模型 |
| `prompt_version` | 硬编码 `"structured-output-v1"` | "structured-output-v1" | Prompt 模板的版本，便于追踪模板变更影响 |

### Prompt 相关字段（不存原文）

| 字段 | 来源 | 计算方式 | 含义 |
|------|------|---------|------|
| `prompt_hash` | `systemPrompt + "\n" + userPrompt` | `MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8))` → `HexFormat.of().formatHex(bytes)` → 64 位十六进制字符串 | 相同 Prompt 产生相同哈希，可统计"某个 Prompt 被调用了多少次"。不存原文节省 TEXT 字段空间 |
| `prompt_length` | `(systemPrompt + userPrompt).length()` | 拼接后直接 `.length()` | Prompt 的总字符数，用于监控"是不是 Prompt 越写越长导致成本上升" |

### 调用特征字段

| 字段 | 来源 | 值 | 含义 |
|------|------|-----|------|
| `streaming` | 硬编码 `false` | false | 结构化输出不走流式 |
| `structured_output` | 硬编码 `true` | true | 这是结构化输出调用，区别于普通对话调用 |

### 结果字段

| 字段 | 来源 | 计算方式 | 含义 |
|------|------|---------|------|
| `attempt_count` | `invoke()` 重试循环的迭代次数 | 成功: 第 N 次尝试 = N; 失败: = maxAttempts | 知道一次成功花了多少次尝试。attemptCount=1 说明第一次就成功；=3 说明前两次 JSON 解析失败 |
| `success` | `invoke()` 中有无 return result | 正常 return → true; 所有重试耗尽 → false | 最终是否成功 |
| `latency_ms` | `invoke()` 开头的 `System.nanoTime()` | `TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)` → 毫秒值 | 从调用开始到返回结果的总耗时，含所有重试 |
| `error_code` | `errorCode.name()` (BusinessException 的 ErrorCode) | "RESUME_ANALYSIS_FAILED" | 失败时的业务错误码，便于分类统计"哪种失败最多" |
| `error_message` | `lastError.getMessage()` | `"JSON解析失败: Unexpected character..."` | 失败原因，截断到 1000 字符防止撑爆 TEXT 字段 |

---

## 五、存储方案

### 存储位置

```
PostgreSQL 数据库
  └── ai_invocation_logs 表
        ├── 主键索引: id (B-Tree, 自动)
        ├── idx_ai_invocation_trace: trace_id (B-Tree, 用于 findByTraceId)
        ├── idx_ai_invocation_scenario: scenario (B-Tree, 用于按场景过滤)
        └── idx_ai_invocation_created_at: created_at (B-Tree, 用于时间排序)
```

### 数据格式

每条记录是一行关系型数据，JPA Entity 到数据库列的映射：

```
列名               类型           约束
id                BIGSERIAL      PRIMARY KEY
trace_id          VARCHAR(64)    NOT NULL
scenario          VARCHAR(100)
provider_id       VARCHAR(80)
model             VARCHAR(120)
prompt_version    VARCHAR(80)
prompt_hash       VARCHAR(64)
prompt_length     INTEGER
streaming         BOOLEAN
structured_output BOOLEAN
attempt_count     INTEGER
success           BOOLEAN
latency_ms        BIGINT
error_code        VARCHAR(80)
error_message     TEXT
created_at        TIMESTAMP      NOT NULL
```

### 写入方式

JPA `repository.save(entity)` → Hibernate 脏检查 → 新实体执行 `EntityManager.persist()` → JDBC `PreparedStatement.executeUpdate()` → 直写 PostgreSQL。

**无异步队列、无缓存。同步写入，但失败不抛异常。**

### 存储周期

代码中没有自动清理逻辑（没有 `@Scheduled` 任务、没有 TTL 配置）。数据默认永久保留，需要运维手动清理或配置定时任务。

### 访问机制

三条查询路径，都在 `AiInvocationLogRepository` 中：

| 查询方法 | SQL | 使用场景 |
|---------|-----|---------|
| `findTop50ByOrderByCreatedAtDesc()` | `SELECT * FROM ai_invocation_logs ORDER BY created_at DESC LIMIT 50` | 管理后台"最近调用"列表 |
| `findByTraceIdOrderByCreatedAtAsc(traceId)` | `SELECT * FROM ai_invocation_logs WHERE trace_id = ? ORDER BY created_at ASC` | 输入 traceId 查看一次请求的所有 AI 调用链 |
| `findByCreatedAtAfter(startTime)` | `SELECT * FROM ai_invocation_logs WHERE created_at > ?` | 监控系统按时间窗口聚合统计 |

前端通过 `AiObservabilityController` 的 REST API 访问：
- `GET /api/ai-observability/invocations` → 最近 50 条
- `GET /api/ai-observability/invocations?traceId=xxx` → 按 traceId 查
- `GET /api/ai-observability/invocations/{id}` → 单条详情

---

## 六、与第一条路径的对比总结

| 维度 | 第一条路径 (AiMetricsRecorder) | 第二条路径 (AiInvocationLogService) |
|------|------|------|
| **存储位置** | JVM 内存 → Prometheus TSDB | PostgreSQL `ai_invocation_logs` 表 |
| **存什么** | 聚合后的时序指标（Counter/Timer） | 每次调用的完整上下文（16 个字段） |
| **粒度** | 按分钟/小时聚合 | 每次调用一条记录 |
| **查询方式** | PromQL (`rate()`, `histogram_quantile()`) | SQL / JPA Repository |
| **典型问题** | "过去 1 小时 P99 延迟是多少？" | "昨天 14:30 那次简历评分为什么失败？" |
| **保留周期** | Prometheus 配置决定（通常 15 天） | 永久保留（无自动清理） |
| **写入失败影响** | 静默跳过（Micrometer 内存操作几乎不失败） | 静默跳过（日志 warn + Optional.empty） |