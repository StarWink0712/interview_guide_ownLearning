## `calculateAiSuccessRate` 方法详细分析

```java
private double calculateAiSuccessRate(List<AiInvocationLogEntity> aiLogs) {
    if (aiLogs.isEmpty()) {
      return 0;
    }
    long successCount = aiLogs.stream()
        .filter(log -> Boolean.TRUE.equals(log.getSuccess()))
        .count();
    return successCount / (double) aiLogs.size();
}
```

### 一句话描述

**计算 AI 调用成功率**——统计列表中 success=true 的记录占比。

---

### 逐行拆解

#### 第 1 步：空值保护

```java
if (aiLogs.isEmpty()) {
    return 0;
}
```

如果最近 24 小时没有任何 AI 调用记录，直接返回 0。如果不加这个判断，`return successCount / (double) aiLogs.size()` 会变成 `0 / 0 = NaN`（Not a Number），前端展示很不友好。

#### 第 2 步：统计成功次数

```java
long successCount = aiLogs.stream()
    .filter(log -> Boolean.TRUE.equals(log.getSuccess()))
    .count();
```

这里用 `Boolean.TRUE.equals(log.getSuccess())` 而不是 `log.getSuccess()`，**细节很关键**：

| 写法 | getSuccess() 为 true | getSuccess() 为 false | getSuccess() 为 null |
|------|:---:|:---:|:---:|
| `log.getSuccess()` | ✅ 算进去 | ❌ 没算进去 | ❌ 没算进去 |
| `Boolean.TRUE.equals(log.getSuccess())` | ✅ 算进去 | ❌ 没算进去 | **❌ 没算进去（但不抛 NPE）** |

`Boolean.TRUE.equals(null)` 返回 `false` 而不是 `NullPointerException`，所以这个方法既能过滤掉失败记录，又能防止 `getSuccess()` 字段为空指针导致整个接口报 500。

#### 第 3 步：计算比例

```java
return successCount / (double) aiLogs.size();
```

这里刻意把分子转成 `(double)`——如果不转 Java 会做整数除法：`3 / 10 = 0`，而不是期望的 `0.3`。

---

### 一个优化建议

如果你在面试中被问到"有什么可以改进的地方"，可以这样说：

> 当前方法是把全量数据加载到 Java 内存中，再用 stream filter 计算成功率。如果 24 小时有数万条记录，每次查询都会把整表数据拉到内存，有 OOM 风险。更好的做法是在数据库层直接聚合：
>
> ```java
> // 在 Repository 中加一个 @Query 方法，用 SQL 直接算好
> @Query("SELECT COUNT(e), SUM(CASE WHEN e.success = true THEN 1 ELSE 0 END) " +
>        "FROM AiInvocationLogEntity e WHERE e.createdAt >= :since")
> Object[] countInvocationStats(@Param("since") LocalDateTime since);
> ```
>
> 这样数据库只返回两个数字（总条数、成功条数），SQL 级别的聚合比 Java 内存的 stream 快得多。

不过在当前项目规模下，这个方法完全够用——因为 AiInvocationLog 每天的数据量不大，跑一次 stream 的性能开销可以忽略不计。