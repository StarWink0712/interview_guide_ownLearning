# RateLimitAspect 限流切面总览怎么讲

## 1. 面试中先讲它的职责

`RateLimitAspect` 是限流组件真正执行逻辑的地方。面试里不要只说“这是一个 AOP 切面”，而要先讲业务价值：

> 项目里有很多高成本接口，比如简历上传会触发文件解析和 AI 分析，知识库问答会触发向量检索和大模型生成，Provider 连通性测试会请求外部模型服务。如果这些接口被刷，会浪费模型调用成本，也会压垮后端资源。所以我用 `@RateLimit` 做声明式配置，再由 `RateLimitAspect` 统一拦截 Controller 方法，通过 Redis Lua 实现分布式滑动窗口限流。

一句话定位：

```text
RateLimitAspect = @RateLimit 注解的运行时执行器。
```

它做的不是业务逻辑，而是横切能力：

```text
读取限流规则
生成 Redis Key
执行 Lua 脚本
判断是否放行
触发降级或抛限流异常
```

## 2. 它和其他组件的关系

完整链路：

```text
Controller 方法
  @RateLimit(...)
        |
        v
RateLimitAspect.around(...)
        |
        v
executeRateLimitScript(...)
        |
        v
resources/scripts/rate_limit_single.lua
        |
        v
Redis String + Sorted Set
        |
        v
通过：joinPoint.proceed()
拒绝：fallback 或 RateLimitExceededException
        |
        v
GlobalExceptionHandler -> Result.error(8001, message)
```

组件分工：

| 组件 | 职责 |
|---|---|
| `@RateLimit` | 声明规则：维度、次数、窗口、降级方法 |
| `RateLimitAspect` | AOP 拦截并执行规则 |
| `rate_limit_single.lua` | Redis 原子滑动窗口算法 |
| `RateLimitExceededException` | 限流拒绝异常，错误码 8001 |
| `GlobalExceptionHandler` | 统一返回 `Result.error` |

面试表达：

> `@RateLimit` 本身只是元数据，真正实现零侵入限流的是切面。切面在 Controller 方法执行前先做 Redis 限流判断，所有规则都通过才让业务方法执行。

## 3. RateLimitAspect 的核心字段怎么讲

源码中有几个关键字段：

```java
private final RedissonClient redissonClient;
private static final String LUA_SCRIPT;
private String luaScriptSha;
private RScript rScript;
```

### 3.1 RedissonClient

> 用于访问 Redis，并通过 `getScript(StringCodec.INSTANCE)` 获取脚本执行接口。

### 3.2 LUA_SCRIPT

> 静态加载 `resources/scripts/rate_limit_single.lua`。脚本作为 classpath 资源随应用打包，启动时读入内存。

### 3.3 luaScriptSha

> Lua 脚本通过 `scriptLoad` 加载到 Redis 后会返回 SHA1 摘要。后续执行时使用 `evalSha`，不用每次传完整脚本。

### 3.4 RScript

> Redisson 提供的脚本执行接口，负责 `scriptLoad` 和 `evalSha`。

## 4. 启动初始化流程

静态块：

```text
应用加载 RateLimitAspect 类
  -> 从 classpath 读取 scripts/rate_limit_single.lua
  -> 保存到 LUA_SCRIPT
  -> 读取失败则抛 IllegalStateException
```

`@PostConstruct init()`：

```text
Spring 创建 RateLimitAspect Bean
  -> redissonClient.getScript(StringCodec.INSTANCE)
  -> loadScript()
       -> rScript.scriptLoad(LUA_SCRIPT)
       -> 保存 luaScriptSha
```

面试表达：

> 脚本不是每次请求临时读取，而是在类加载和 Bean 初始化时完成加载。运行时只拿 SHA1 执行，性能更好。

## 5. 核心运行流程

`around` 方法做了 6 步：

```text
1. 从 joinPoint 获取 MethodSignature
2. 取类名和方法名，用于 Key 分组
3. 通过 getAnnotationsByType 获取所有 @RateLimit 规则
4. 生成当前时间 nowMs 和 requestId
5. 遍历每条规则：
   - 时间窗口转毫秒
   - 按维度生成 Redis Key
   - 执行 Lua
   - result 为 0/null 则限流
6. 所有规则通过，执行原方法
```

伪代码：

```java
for (RateLimit rule : rules) {
    long intervalMs = calculateIntervalMs(rule.interval(), rule.timeUnit());
    String key = generateKey(className, methodName, rule.dimension());
    Long result = executeRateLimitScript(key, nowMs, requestId, intervalMs, rule.count());

    if (result == null || result == 0) {
        return handleRateLimitExceeded(joinPoint, rule, key);
    }
}

return joinPoint.proceed();
```

## 6. 为什么切面里规则是逐条执行

因为 `@RateLimit` 支持多条规则：

```java
@RateLimit(dimension = GLOBAL, count = 5)
@RateLimit(dimension = IP, count = 5)
```

切面逐条执行，语义是：

```text
GLOBAL 通过
  -> 再检查 IP

IP 通过
  -> 执行业务

任一维度不通过
  -> 立即拒绝
```

面试表达：

> 多维限流不是取最大值或最小值，而是 AND 关系。只要全局、IP、USER 中任意一个维度超限，就说明当前请求不应该继续进入业务方法。

## 7. 为什么用 AOP 而不是拦截器

可以这样回答：

> 拦截器也能做限流，但它更靠近 HTTP 路径层，拿到的是 URL 和 Handler。AOP 可以直接围绕方法执行，天然能读取方法上的注解、方法参数、目标类和 fallback 方法，和 `@RateLimit` 这种声明式方法级配置更匹配。

补充：

> 如果要做全站统一网关限流，拦截器或 Gateway 更合适；如果要做业务方法级、可重复、多维、带 fallback 的限流，AOP 更自然。

## 8. 为什么用 Redis Lua 而不是 Java 锁

Java 锁只能锁当前 JVM：

```text
应用部署 3 个实例
实例 A 锁住了
实例 B/C 不知道
```

Redis Lua：

```text
所有实例共享 Redis
Lua 脚本在 Redis 内原子执行
天然适合分布式限流
```

面试表达：

> 限流组件要考虑分布式部署，所以不能只靠本地锁或内存计数。Redis 是共享状态，Lua 保证过期回收、判断和扣减在 Redis 端原子执行。

## 9. 这段代码适合讲的亮点

### 9.1 声明式、零业务侵入

Controller 只写：

```java
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
```

业务方法不感知 Redis。

### 9.2 支持可重复注解

`method.getAnnotationsByType(RateLimit.class)` 能同时支持单规则和多规则。

### 9.3 支持 Redis Cluster

Key 使用 `{ClassName:MethodName}` Hash Tag，派生 key 落同一 slot。

### 9.4 支持脚本缓存容错

`evalSha` 遇到 `NOSCRIPT` 自动 `scriptLoad` 后重试一次。

### 9.5 支持 fallback

限流触发时可以反射调用同类中的降级方法。

## 10. 当前实现的边界

面试里要诚实讲：

| 点 | 当前实现 | 下一步 |
|---|---|---|
| Lua 返回值 | 实际返回 `1/0`，不是剩余配额 | 注释可修正，或 Lua 返回剩余数 |
| `timeout` | 注解字段存在，但切面没实现等待 | 实现短等待重试或删除字段 |
| Redis 故障 | 只处理 `NOSCRIPT`，连接异常直接抛出 | 增加 fail-open/fail-closed 配置 |
| IP 来源 | 读取代理头，可能被伪造 | 生产由网关覆盖可信头 |
| USER 来源 | 支持 attribute/header | 接入认证过滤器，减少 header 依赖 |
| requestId 去重 | 每次请求生成 UUID | 若要跨重试幂等，应读取外部 requestId |

## 11. 2 分钟面试回答

> `RateLimitAspect` 是我项目里限流注解的执行层。Controller 通过 `@RateLimit` 声明限流规则，比如简历上传接口同时配置 GLOBAL 和 IP 限流。请求进入时，Aspect 的 `@Around` 通知会先拦截方法，通过 `MethodSignature` 拿到类名、方法名和方法上的所有 `@RateLimit` 注解。
>
> 每条规则都会生成一个 Redis Key，格式类似 `ratelimit:{ResumeController:uploadAndAnalyze}:ip:1.2.3.4`。这里用了 Redis Cluster Hash Tag，保证同一方法派生出的 key 落在同一个 slot。然后切面通过 Redisson 的 `evalSha` 执行预加载的 Lua 脚本，Lua 内部用 String 保存当前令牌数，用 Sorted Set 保存窗口内请求记录，实现滑动时间窗口。
>
> 规则之间是 AND 关系，所有规则都通过才执行 `joinPoint.proceed()`。任意一条返回 0 或 null，就进入 `handleRateLimitExceeded`，优先调用 fallback，没有 fallback 就抛 `RateLimitExceededException`，最后由全局异常处理器统一返回错误码 8001。
>
> 这个设计的核心是业务零侵入、分布式原子限流，并且把 Redis 脚本缓存、NOSCRIPT 重载、IP/USER 多维 Key 和降级处理都集中在切面里维护。

