<br />

RateLimit 限流注解系统 - 完整分析

这个限流系统由 **三个核心部分** 组成：

### 一、整体架构

```
@RateLimit 注解（定义规则）
    ↓ 标注在方法上
RateLimitAspect 切面（拦截执行）
    ↓ 执行Lua脚本
Redis 限流脚本（滑动时间窗口）
    ↓ 返回 0 或 1
触发限流 → 降级方法 / RateLimitExceededException
```

### 二、注解详解 (RateLimit.java)

#### **可重复注解设计**

```java
@Target(ElementType.METHOD)      // 只能标注在方法上
@Retention(RetentionPolicy.RUNTIME)  // 运行时保留
@Repeatable(RateLimit.Container.class) // 支持重复标注
public @interface RateLimit {
```

**为什么要可重复**？因为一个方法可能需要同时受多个维度的限流：

```java
@RateLimit(dimension = Dimension.GLOBAL, count = 100)  // 全局：每秒100次
@RateLimit(dimension = Dimension.IP, count = 5)         // IP维度：每秒5次
public Result query() { ... }
```

两个维度**都必须通过**才能放行，任一维度触发就拒绝。

#### **三个限流维度**

```java
enum Dimension {
    GLOBAL,  // 全局限流：所有用户共享配额
    IP,      // IP限流：按客户端IP单独计算
    USER     // 用户限流：按用户ID单独计算
}
```

| 维度     | 场景举例       | Redis Key                                         |
| ------ | ---------- | ------------------------------------------------- |
| GLOBAL | 防止接口被刷     | `ratelimit:{UserController:query}:global`         |
| IP     | 防止单个IP恶意请求 | `ratelimit:{UserController:query}:ip:192.168.1.1` |
| USER   | 防止单个用户滥用   | `ratelimit:{UserController:query}:user:123`       |

#### **核心参数**

```java
double count();                    // 时间窗口内允许的请求数（如 10）
long interval() default 1;         // 时间窗口大小（默认 1）
TimeUnit timeUnit() default SECONDS; // 时间单位（默认秒）
```

**配置示例**：

```java
@RateLimit(count = 10, interval = 1, timeUnit = TimeUnit.MINUTES)
// 每分钟最多10次请求
```

#### **降级机制**

```java
String fallback() default "";  // 限流触发时的降级方法名
```

```java
@RateLimit(count = 5, fallback = "queryFallback")
public Result query() { ... }

// 同一个类中的降级方法
public Result queryFallback() {
    return Result.error("系统繁忙，请稍后重试");
}
```

***

### 三、AOP 切面详解 (RateLimitAspect.java)

#### **注解拦截**

```java
@Around("@annotation(interview.guide.common.annotation.RateLimit) || " +
        "@annotation(interview.guide.common.annotation.RateLimit.Container)")
public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
```

拦截所有标注了 `@RateLimit` 或 `@RateLimit.Container` 的方法。

#### **核心执行流程**

```java
// 1. 获取方法上所有 @RateLimit 注解
RateLimit[] rules = method.getAnnotationsByType(RateLimit.class);

// 2. 逐条执行限流规则
for (RateLimit rule : rules) {
    long intervalMs = calculateIntervalMs(rule.interval(), rule.timeUnit());
    String key = generateKey(className, methodName, rule.dimension());
    Long result = executeRateLimitScript(key, nowMs, requestId, intervalMs, rule.count());
    
    if (result == null || result == 0) {
        // 任一规则不通过，触发限流
        return handleRateLimitExceeded(joinPoint, rule, key);
    }
}

// 3. 所有规则通过，执行原方法
return joinPoint.proceed();
```

#### **Redis Key 设计**

```java
private String generateKey(String className, String methodName, RateLimit.Dimension dimension) {
    String hashTag = "{" + className + ":" + methodName + "}";  // Hash Tag 分组
    String keyPrefix = "ratelimit:" + hashTag;
    
    return switch (dimension) {
        case GLOBAL -> keyPrefix + ":global";
        case IP -> keyPrefix + ":ip:" + getClientIp();     // 包含客户端IP
        case USER -> keyPrefix + ":user:" + getCurrentUserId();  // 包含用户ID
    };
}
```

**Hash Tag** `{}` 的作用：确保同一个方法的多个限流维度的 Key 落在同一个 Redis 槽上，提高性能。

**Key 示例**：

```
ratelimit:{ResumeController:uploadAndAnalyze}:global
ratelimit:{ResumeController:uploadAndAnalyze}:ip:192.168.1.1
ratelimit:{ResumeController:uploadAndAnalyze}:user:123
```

#### **Lua 脚本执行与 NOSCRIPT 处理**

```java
private Long executeRateLimitScript(String key, long nowMs, String requestId, long intervalMs, double count) {
    try {
        // 使用SHA1执行已加载的脚本
        Object resultObj = rScript.evalSha(RScript.Mode.READ_WRITE, luaScriptSha, ...);
        return convertToLong(resultObj);
    } catch (RedisException e) {
        // Redis 重启后脚本缓存丢失，自动重新加载
        if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
            loadScript();  // 重新加载脚本
            // 重试执行
            return convertToLong(rScript.evalSha(...));
        }
        throw e;
    }
}
```

**为什么 Redis 重启后会出现 NOSCRIPT？**

- 第一次执行时用 `script load` 加载脚本，获得 SHA1
- 之后用 `evalsha SHA1` 执行，节省网络带宽
- Redis 重启后脚本缓存丢失，需要重新加载

#### **IP 地址获取**

```java
private String getClientIp() {
    // 逐级获取真实IP（考虑代理场景）
    String ip = request.getHeader("X-Forwarded-For");  // 有代理时
    if (ip == null) ip = request.getHeader("X-Real-IP");
    if (ip == null) ip = request.getHeader("Proxy-Client-IP");
    if (ip == null) ip = request.getHeader("WL-Proxy-Client-IP");
    if (ip == null) ip = request.getRemoteAddr();      // 直连
    
    // 处理多级代理：取第一个IP
    if (ip != null && ip.contains(",")) {
        ip = ip.split(",")[0].trim();
    }
    return ip;
}
```

支持 Nginx 反向代理场景下的真实 IP 获取。

***

### 四、Lua 脚本核心逻辑 - 滑动时间窗口算法

#### **为什么用 Lua 脚本？**

- **原子性**：Redis 保证整个脚本的原子执行
- **减少网络往返**：一次脚本调用=多次 Redis 命令
- **避免竞态条件**：并发环境下不会出现超卖

#### **脚本核心逻辑解析**

```lua
-- 1. 获取当前可用令牌
local current_val = tonumber(redis.call("get", value_key)) or max_tokens

-- 2. 回收已过期的令牌（滑动窗口）
local expired_values = redis.call("zrangebyscore", permits_key, 0, now_ms - interval)
if #expired_values > 0 then
    -- 计算过期令牌总数
    for _, v in ipairs(expired_values) do
        local p = tonumber(string.match(v, ":(%d+)$"))
        expired_count = expired_count + p
    end
    
    redis.call("zremrangebyscore", permits_key, 0, now_ms - interval)
    
    -- 回收令牌（不超过最大容量）
    if expired_count > 0 then
        current_val = math.min(max_tokens, current_val + expired_count)
    end
end

-- 3. 检查是否有足够令牌
if current_val < permits then
    return 0  -- 拒绝请求
end

-- 4. 扣减令牌并记录
redis.call("zadd", permits_key, now_ms, request_id .. ":" .. permits)
redis.call("set", value_key, current_val - permits)

-- 5. 设置过期时间释放内存
redis.call("expire", value_key, interval * 2 / 1000)
```

#### **数据结构**

| Redis Key       | 类型         | 作用             |
| --------------- | ---------- | -------------- |
| `{key}:value`   | String     | 当前可用令牌数        |
| `{key}:permits` | Sorted Set | 记录所有请求的时间戳和消耗量 |

**Sorted Set 的作用**：按时间排序，方便滑动窗口回收过期请求。

#### **运行示例**

假设配置：`count=3, interval=1秒`

```
时间点    请求    ZSET内容                    可用令牌    结果
00:00    req1    [00:00:1]                      2→1      通过
00:01    req2    [00:00:1, 00:01:1]             1→0      通过
00:02    req3    [00:00:1, 00:01:1, 00:02:1]    0→-1     通过
00:03    req4    [00:01:1, 00:02:1, 00:03:1]    0→-1     拒绝 ❌
00:04    req5    回收[00:01:1]                  1→0      通过
```

***

### 五、实际应用场景

#### **在高频接口上设置较宽松的限流**

```java
@GetMapping("/api/resumes")
@RateLimit(dimension = GLOBAL, count = 30, timeUnit = SECONDS)
public Result<List<ResumeListItemDTO>> getAllResumes() { ... }
```

#### **在高风险接口上设置多维度限流**

```java
@PostMapping("/upload")
@RateLimit(dimension = GLOBAL, count = 5)  // 全局：每秒5次
@RateLimit(dimension = IP, count = 2)      // 单IP：每秒2次
public Result<Map<String, Object>> uploadAndAnalyze(@RequestParam("file") MultipartFile file) {
    // 上传接口需要更严格的限流
}
```

#### **提供优雅的降级体验**

```java
@RateLimit(count = 10, fallback = "fallbackMethod")
public Result query() { ... }

public Result fallbackMethod() {
    return Result.error(429, "请求过于频繁，请稍后再试");
}
```

***

### 六、限流系统工作流程图

```
客户端请求
    ↓
Controller 方法（标注 @RateLimit）
    ↓
RateLimitAspect 拦截
    ↓
获取所有 @RateLimit 规则
    ↓
┌─────────────────────────────────────────┐
│  for each 规则:                         │
│    ├─ 生成 Redis Key                    │
│    ├─ 执行 Lua 脚本（滑动窗口检查）      │
│    └─ 结果是否为 0？                    │
│         ├─ 是 → 触发限流                │
│         │    ├─ 有降级方法？ → 执行降级  │
│         │    └─ 无降级方法 → 抛异常      │
│         └─ 否 → 继续下一个规则           │
└─────────────────────────────────────────┘
    ↓
所有规则通过 → 执行原方法
    ↓
返回正常结果
```

***

### 七、技术亮点总结

| 特性         | 实现方式                      | 优势            |
| ---------- | ------------------------- | ------------- |
| **多维度限流**  | `@Repeatable` 可重复注解       | 灵活组合，按需配置     |
| **滑动窗口算法** | Redis Lua 脚本 + Sorted Set | 精确限流，无突发流量    |
| **原子操作**   | Lua 脚本一次性执行               | 无竞争条件，线程安全    |
| **自动修复**   | NOSCRIPT 异常自动重载           | Redis 重启不丢失功能 |
| **降级机制**   | fallback 方法调用             | 优雅降级，而非直接报错   |
| **性能优化**   | Hash Tag 分组 + EVALSHA     | 减少网络开销        |
| **IP 穿透**  | 多级 Header 获取              | 支持反向代理场景      |

这个限流系统是项目的 **重要安全屏障**，结合了 Spring AOP、Redis Lua 脚本和滑动窗口算法的优势，既保证了安全性，又提供了良好的用户体验。
