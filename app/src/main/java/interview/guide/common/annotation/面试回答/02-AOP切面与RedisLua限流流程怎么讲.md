# AOP 切面与 Redis Lua 限流流程怎么讲

## 1. 总体流程

`@RateLimit` 只是规则声明，真正执行在 `RateLimitAspect`。

完整流程：

```text
请求进入 Controller
        |
        v
@Around 拦截带 @RateLimit 的方法
        |
        v
读取方法签名：className + methodName
        |
        v
getAnnotationsByType(RateLimit.class) 获取全部规则
        |
        v
为本次请求生成 requestId
        |
        v
逐条规则执行：
  1. interval/timeUnit 转毫秒
  2. 按 dimension 生成 Redis Key
  3. evalSha 执行 Lua 脚本
  4. 返回 1 放行，返回 0 拒绝
        |
        v
所有规则通过 -> joinPoint.proceed()
任一规则失败 -> fallback 或 RateLimitExceededException
```

面试表达：

> 这个切面是限流组件的执行器，它把注解里的静态配置变成运行时 Redis 限流判断。

## 2. 切点表达式怎么讲

源码：

```java
@Around("@annotation(interview.guide.common.annotation.RateLimit) || "
        + "@annotation(interview.guide.common.annotation.RateLimit.Container)")
```

为什么同时匹配两个：

```text
单个 @RateLimit
  方法上直接存在 RateLimit

多个 @RateLimit
  编译器会用 RateLimit.Container 包起来
```

面试回答：

> 因为 `@RateLimit` 支持可重复注解，方法上可能是单个 `RateLimit`，也可能是容器注解 `RateLimit.Container`。切点同时匹配两种情况，保证单规则和多规则都能被拦截。

## 3. 为什么用 method.getAnnotationsByType

源码：

```java
RateLimit[] rules = method.getAnnotationsByType(RateLimit.class);
```

作用：

> 不管方法上写的是一个 `@RateLimit`，还是多个 `@RateLimit` 被编译器放进 Container，这个 API 都能返回展开后的 `RateLimit[]`。

这让后续逻辑统一：

```java
for (RateLimit rule : rules) {
    ...
}
```

## 4. Redis Key 设计怎么讲

源码：

```java
String hashTag = "{" + className + ":" + methodName + "}";
String keyPrefix = "ratelimit:" + hashTag;

return switch (dimension) {
    case GLOBAL -> keyPrefix + ":global";
    case IP -> keyPrefix + ":ip:" + getClientIp();
    case USER -> keyPrefix + ":user:" + getCurrentUserId();
};
```

例子：

```text
ratelimit:{ResumeController:uploadAndAnalyze}:global
ratelimit:{ResumeController:uploadAndAnalyze}:ip:192.168.1.10
ratelimit:{ResumeController:uploadAndAnalyze}:user:10001
```

### 为什么要有 `{ClassName:MethodName}` Hash Tag

Redis Cluster 中，Lua 脚本操作多个 key 时，通常要求这些 key 在同一个 slot。

Hash Tag 的作用：

```text
ratelimit:{ResumeController:uploadAndAnalyze}:global
ratelimit:{ResumeController:uploadAndAnalyze}:ip:192.168.1.10
ratelimit:{ResumeController:uploadAndAnalyze}:user:10001

Redis Cluster 只对 {} 内的内容计算 slot
所以上面这些 key 会落到同一个 slot
```

虽然当前脚本每次只传一个 base key，但 Lua 内部会派生：

```text
key:value
key:permits
```

它们继承同一个 hash tag，因此也会落在同一个 slot。

面试表达：

> 这个 Key 设计不是随便拼字符串，而是考虑了 Redis Cluster。Hash Tag 能保证同一接口同一维度派生出来的 value 和 permits 两个键在同一个 slot，从而让 Lua 脚本可以原子执行。

## 5. Lua 脚本里的两个 Redis 键

脚本收到一个 base key：

```text
KEYS[1] = ratelimit:{ResumeController:uploadAndAnalyze}:ip:192.168.1.10
```

内部派生：

```lua
local value_key = key .. ":value"
local permits_key = key .. ":permits"
```

也就是：

```text
ratelimit:{ResumeController:uploadAndAnalyze}:ip:192.168.1.10:value
ratelimit:{ResumeController:uploadAndAnalyze}:ip:192.168.1.10:permits
```

| Key | Redis 类型 | 用途 |
|---|---|---|
| `:value` | String | 当前可用令牌数 |
| `:permits` | Sorted Set | 窗口内请求记录，score 是时间戳 |

## 6. Lua 滑动窗口算法怎么讲

脚本参数：

```text
KEYS[1]  限流 base key
ARGV[1]  当前时间戳 now_ms
ARGV[2]  本次申请令牌数 permits，当前固定为 1
ARGV[3]  窗口大小 interval，单位毫秒
ARGV[4]  最大令牌数 max_tokens
ARGV[5]  请求唯一标识 request_id
```

执行步骤：

### 6.1 获取当前令牌

```lua
local current_val = tonumber(redis.call("get", value_key)) or max_tokens
```

如果 Redis 里没有值，说明这个窗口第一次请求，令牌数按 `max_tokens` 初始化。

### 6.2 回收过期请求

```lua
local expired_values = redis.call("zrangebyscore", permits_key, 0, now_ms - interval)
```

含义：

```text
找出 score <= now_ms - interval 的请求记录
这些请求已经滑出当前窗口
```

然后：

```lua
redis.call("zremrangebyscore", permits_key, 0, now_ms - interval)
current_val = math.min(max_tokens, current_val + expired_count)
```

过期请求移除后，把它们消耗过的令牌还回来。

### 6.3 判断令牌是否足够

```lua
if current_val < permits then
    return 0
end
```

不够就拒绝。

### 6.4 扣减令牌并记录请求

```lua
local permit_record = request_id .. ":" .. permits
redis.call("zadd", permits_key, now_ms, permit_record)
redis.call("set", value_key, current_val - permits)
```

记录本次请求，扣掉一个令牌。

### 6.5 设置过期时间

```lua
local expire_time = math.ceil(interval * 2 / 1000)
if expire_time < 1 then expire_time = 1 end
redis.call("expire", value_key, expire_time)
redis.call("expire", permits_key, expire_time)
```

为什么是窗口的 2 倍：

> 避免短时间无请求后 key 立即消失，又能在长时间不用时自动清理 Redis 内存。2 倍窗口能覆盖滑动窗口回收需要的数据范围。

### 6.6 返回结果

```lua
return 1
```

当前脚本实际返回：

```text
1 -> 通过
0 -> 拒绝
```

注意：代码注释里有“返回剩余配额”的描述，但以当前 Lua 实现为准，语义是通过/拒绝，不是剩余令牌数。

## 7. 为什么 Redis Lua 是原子的

面试回答：

> Redis 执行 Lua 脚本时，脚本内部命令会作为一个整体执行，中间不会被其他客户端命令插入。所以“回收过期请求、判断令牌、扣减令牌、写请求记录”这几个步骤是原子的，避免并发下多个请求同时看到令牌充足而都放行。

## 8. evalSha 和 SHA1 怎么讲

切面初始化：

```java
luaScriptSha = rScript.scriptLoad(LUA_SCRIPT);
```

执行时：

```java
rScript.evalSha(..., luaScriptSha, ...)
```

为什么不用每次 eval 传完整脚本：

> Lua 脚本内容比较固定，启动时先 `scriptLoad` 到 Redis，后续只传 SHA1 摘要执行，减少网络传输和解析开销。

## 9. NOSCRIPT 容错怎么讲

Redis 重启或脚本缓存被清理后，`evalSha` 会报 `NOSCRIPT`。

代码处理：

```java
catch (RedisException e) {
    if (e.getMessage() contains "NOSCRIPT") {
        loadScript();
        evalSha again;
    }
    throw e;
}
```

面试表达：

> 这个是脚本缓存失效的容错。Redis 重启后 SHA1 不存在，切面会重新加载 Lua 脚本并重试一次，避免因为 Redis 脚本缓存丢失导致所有限流接口不可用。

## 10. 限流触发后的处理

```text
result == null 或 result == 0
        |
        v
handleRateLimitExceeded(...)
        |
        +-- fallback 不为空
        |     -> 找降级方法
        |     -> 参数一致则传原参数调用
        |     -> 无参则无参调用
        |
        +-- 没有 fallback / fallback 失败
              -> throw RateLimitExceededException("请求过于频繁，请稍后再试")
```

全局异常处理器：

```text
RateLimitExceededException extends BusinessException
ErrorCode.RATE_LIMIT_EXCEEDED = 8001
GlobalExceptionHandler 捕获 BusinessException
返回 HTTP 200 + Result.error(8001, message)
```

## 11. 当前 Redis 异常策略怎么讲

当前实现：

```text
NOSCRIPT
  -> 自动重载脚本并重试一次

其他 RedisException
  -> 直接向上抛出
```

面试中可以诚实说：

> 当前对 Redis 脚本缓存失效做了容错，但对 Redis 连接失败、超时等异常没有单独降级策略。后续可以根据业务选择 fail-open 或 fail-closed：如果更重视可用性，可以 Redis 故障时临时放行；如果更重视保护后端，可以直接拒绝并返回服务繁忙。

## 12. 高频追问

### 问：为什么不用 Spring Cloud Gateway 限流？

答：

> 网关限流适合统一入口保护，但业务接口有不同维度和不同阈值，比如简历上传、RAG 查询、Provider 测试的成本不同。项目里用注解可以把限流规则贴近业务接口，同时保留 Controller 级别的灵活配置。生产上也可以网关粗限流 + 应用内精细限流一起用。

### 问：为什么不用 Guava RateLimiter？

答：

> Guava RateLimiter 是单 JVM 内存限流，分布式多实例下每个实例各算各的，无法全局限流。这个项目后端可以多实例部署，所以用 Redis 做共享状态。

### 问：Lua 脚本操作两个 key，会不会 Redis Cluster 报错？

答：

> Key 中使用了 Hash Tag，例如 `{ResumeController:uploadAndAnalyze}`。脚本派生出的 `:value` 和 `:permits` 都包含同一个 Hash Tag，会落在同一个 slot，避免跨 slot 问题。

### 问：为什么要同时保存 value 和 permits？

答：

> `value` 快速表示当前可用令牌数，`permits` 保存每次请求发生时间，用于滑动窗口过期回收。如果只存计数，不知道哪些请求已经滑出窗口；如果只存 Sorted Set，每次都 count 也可以，但当前实现通过 value 做快速扣减和回收。

