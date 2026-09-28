     
## 数据库里的数据长什么样

### `ai_invocation_logs` 表（AI 调用日志）

```sql
id | trace_id                           | scenario   | provider_id | model     | success | latency_ms | created_at
---|------------------------------------|------------|-------------|-----------|---------|------------|------------------------
1  | abc-def-456                        | 面试评估    | deepseek    | deepseek-chat | true | 3200 | 2026-05-21 10:30:00
2  | abc-def-456                        | 面试评估    | deepseek    | deepseek-chat | true | 4100 | 2026-05-21 10:30:05
3  | ghi-jkl-789                        | RAG 检索   | openai      | gpt-4o    | true | 1500 | 2026-05-21 11:00:00
4  | mno-pqr-012                        | RAG 检索   | openai      | gpt-4o    | false| 30000| 2026-05-21 11:30:00
```

一条记录 = 一次 LLM 调用。

### `rag_retrieval_logs` 表（RAG 检索日志）

```sql
id | trace_id      | question                        | hit_count | latency_ms | created_at
---|---------------|---------------------------------|-----------|------------|------------------------
1  | ghi-jkl-789   | "Spring 的 IoC 是什么？"         | 3         | 200        | 2026-05-21 11:00:00
2  | mno-pqr-012   | "AOP 有哪些实现方式？"            | 5         | 180        | 2026-05-21 11:30:00
3  | stu-vwx-901   | "MySQL 索引优化"                 | null      | 0          | 2026-05-21 12:00:00
```

一条记录 = 一次 RAG 向量检索。

---

## 这两行代码到底干了什么？

```java
// 1. 向数据库发 SQL：SELECT * FROM ai_invocation_logs WHERE created_at >= '2026-05-20 15:30:00'
List<AiInvocationLogEntity> aiLogs = aiInvocationLogRepository.findByCreatedAtAfter(since);

// 2. 数据库返回结果 → 变成 Java 内存中的 List
//    aiLogs = [LogEntity{traceId="abc-def-456", success=true, latencyMs=3200}, 
//              LogEntity{traceId="abc-def-456", success=true, latencyMs=4100}, 
//              LogEntity{traceId="ghi-jkl-789", success=true, latencyMs=1500},
//              LogEntity{traceId="mno-pqr-012", success=false, latencyMs=30000}]
```

**数据库中的数据 → 通过网络传输 → 变成 Java 内存中的一个 List**。这就是"把数据拿到内存里"。

---

## 什么是"内存计算"？

内存计算就是：**数据已经从数据库拿到 Java 内存里了，剩下的计算（统计、过滤、求平均）在 Java 代码里完成，不再问数据库**。

### 对比两种做法

**方案 A（当前的做法——内存计算）：**

```java
// SQL 查出来
List<AiInvocationLogEntity> aiLogs = repository.findByCreatedAtAfter(since);

// 内存里计算
long total = aiLogs.size();                 // 4 条
long success = aiLogs.stream()
    .filter(log -> Boolean.TRUE.equals(log.getSuccess()))
    .count();                               // 3 条
double rate = success / (double) total;     // 75%
```

```
数据库                          Java 内存
┌─────────────┐  send 4 rows  ┌──────────┐
│  4 条记录    │ ───────────→  │  4 条记录 │ → stream.filter().count() → 75%
└─────────────┘               └──────────┘
                                ↑ 这就是"内存计算"
```

**方案 B（更好的做法——数据库端计算）：**

```java
// SQL 里直接算好：SELECT COUNT(*), SUM(CASE WHEN success=true THEN 1 ELSE 0 END) FROM ...
// 数据库只返回两个数字
Object[] stats = repository.countInvocationStats(since);
// stats = [4, 3]
```

```
数据库                          Java 内存
┌─────────────┐  send 2 nums ┌──────────┐
│  4 条记录    │ ───────────→  │ [4, 3]    │ → 直接拿来算
│  SQL 聚合计算 │              └──────────┘
└─────────────┘
    ↑ 数据库帮你算好了
```

### 为什么说"如果数据量增长要考虑改造"？

当前示例中 24 小时只有 4 条记录，数据量很小。但如果项目上线后，每天产生 10 万条 AI 调用记录：

```
方案 A（内存计算）：
  查 10 万条 → 网络传输 → Java 内存存 10 万个对象 → stream 遍历计算
  → 内存占用：10万 × 约500字节 = 50MB
  → 如果同时有 100 个人访问监控页面 → 压力 × 100

方案 B（数据库端聚合）：
  查 2 个数字 → 网络传输 → Java 直接拿结果
  → 内存占用：忽略不计
```

**结论**：当前数据量小的时候，方案 A 写起来简单直接，没问题。哪天日数据量到万级以上，改成方案 B 就行。这就是注释里写"如果数据量增长到数万条/天，应考虑改为数据库端聚合"的原因。