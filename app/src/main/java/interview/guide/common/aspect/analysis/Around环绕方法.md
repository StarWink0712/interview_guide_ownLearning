这是切面的核心方法，逐行分析。

### 切入点声明（第 102 行）

```java
@Around("@annotation(interview.guide.common.annotation.RateLimit) || "
        + "@annotation(interview.guide.common.annotation.RateLimit.Container)")
```

这里用 `||` 匹配两种情况：

- **`@annotation(RateLimit)`** — 方法上只有一个 `@RateLimit` 注解时，直接匹配它
- **`@annotation(RateLimit.Container)`** — 方法上有多个 `@RateLimit` 注解时，编译器会自动套一层 `@Container`，真正的 `@RateLimit` 注解被包在 `@Container.value()` 里

如果只写前者，多注解时切面不会被触发。`||` 保证无论单注解还是多注解都能拦截。

---

### 提取方法信息（第 105-108 行）

```java
MethodSignature signature = (MethodSignature) joinPoint.getSignature();
Method method = signature.getMethod();
String className = method.getDeclaringClass().getSimpleName();  // 如 "ResumeController"
String methodName = method.getName();                            // 如 "uploadResume"
```

`joinPoint.getSignature()` 返回被拦截方法的签名，强转为 `MethodSignature` 后用 `getMethod()` 拿到反射对象。类名和方法名用于构造 Redis Key 的 Hash Tag 部分（`{ResumeController:uploadResume}`）。

---

### 获取所有限流规则（第 111 行）

```java
RateLimit[] rules = method.getAnnotationsByType(RateLimit.class);
```

`getAnnotationsByType` 是 Java 8 配合 `@Repeatable` 的 API，不管方法上写了几个 `@RateLimit`，都会返回一个数组。如果只有一条，数组长度为 1；三条，长度为 3。这一步统一了单注解和多注解的处理。

---

### 准备请求级数据（第 112-115 行）

```java
long nowMs = System.currentTimeMillis();          // 当前毫秒时间戳，作为 Lua 脚本的"现在"
String requestId = UUID.randomUUID().toString();   // 唯一请求 ID，每次请求生成一个新的
```

`nowMs` 和 `requestId` 对所有规则共用一份。一轮请求算一个时间点，防止遍历规则过程中时间流逝导致前后规则判断标准不一致。

`requestId` 传入 Lua 脚本后作为 member 的一部分（`uuid:1`），网络重试时同一 requestId 的 ZADD 只更新 score 不新增记录，实现去重。

---

### 逐条遍历规则（第 118-131 行）

```java
for (RateLimit rule : rules) {
    long intervalMs = calculateIntervalMs(rule.interval(), rule.timeUnit());
    String key = generateKey(className, methodName, rule.dimension());
    Long result = executeRateLimitScript(key, nowMs, requestId, intervalMs, rule.count());

    if (result == null || result == 0) {
        return handleRateLimitExceeded(joinPoint, rule, key);
    }
}
```

每条规则独立执行三步：

| 步骤 | 方法 | 输入 | 输出 |
|------|------|------|------|
| 1. 换算窗口 | `calculateIntervalMs(interval, timeUnit)` | 注解的 `interval` + `timeUnit` | 毫秒数（如 1 分钟 → 60000） |
| 2. 生成 Key | `generateKey(className, methodName, dimension)` | 类名、方法名、维度 | `ratelimit:{ResumeController:uploadResume}:ip:1.2.3.4` |
| 3. 执行脚本 | `executeRateLimitScript(key, nowMs, requestId, intervalMs, count)` | 上述所有参数 | Long：通过返回正数，拒绝返回 null/0 |

**AND 逻辑**：一旦某条规则返回 null 或 0，`return handleRateLimitExceeded(...)` 立即终止循环，不会执行后续规则，也不会执行原方法。

---

### 放行（第 134 行）

```java
return joinPoint.proceed();
```

所有规则遍历完成且全部通过后，调用 `proceed()` 执行原始的 Controller 方法，返回值原样返回给调用方。

---

### 完整执行时序

以一个标注了双重限流的方法为例：

```java
@RateLimit(dimension = GLOBAL, count = 10, interval = 1, timeUnit = MINUTES)
@RateLimit(dimension = IP, count = 5, interval = 1, timeUnit = SECONDS)
public Result<UploadResponse> upload(Request req) { ... }
```

```
请求到达 upload()
    │
    ▼
@Around 拦截
    │
    ├─ 提取类名 "ResumeController"、方法名 "upload"
    ├─ 获取 2 条 @RateLimit 规则
    ├─ nowMs = 当前时间, requestId = UUID
    │
    ├─ 第 1 条规则：GLOBAL, 10次/分钟
    │   ├─ intervalMs = 60000
    │   ├─ key = ratelimit:{ResumeController:upload}:global
    │   ├─ executeRateLimitScript() → Lua 返回 6（还剩 6 次）
    │   └─ 6 > 0，通过 → 继续
    │
    ├─ 第 2 条规则：IP, 5次/秒
    │   ├─ intervalMs = 1000
    │   ├─ key = ratelimit:{ResumeController:upload}:ip:1.2.3.4
    │   ├─ executeRateLimitScript() → Lua 返回 0（该 IP 已用光）
    │   └─ 0，拒绝 → handleRateLimitExceeded()
    │       └─ 有 fallback？→ 调降级方法
    │       └─ 无 fallback？→ 抛 RateLimitExceededException
    │
    └─ 不会走到 proceed()
```