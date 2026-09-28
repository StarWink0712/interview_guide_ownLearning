# RateLimitAspect 执行链

## 1. `RateLimitAspect` 的完整执行流程是什么？

**回答：** 请求进入后，`@Around` 通知通过 `MethodSignature` 获取被拦截的类名、方法名和方法上的所有 `@RateLimit` 注解，再生成本次请求的 UUID。之后遍历全部限流规则，为每条规则构造 Redis Key，通过 Redisson 的 `evalSha` 执行预加载 Lua 脚本；只有全部规则都通过，才调用 `joinPoint.proceed()` 执行原方法。

<br />

请求进入后。aspect切面会拦截请求，创建执行请求类的代理对象，然后通过around通知中的方法，生成本次请求的请求id，然后构造rediskey，再用redisson的evalsha方法执行lua脚本，全部规则都通过后才用joinpoint.proceed执行原方法。

## 2. `@Repeatable` 在限流设计中有什么作用？

**回答：** `@RateLimit` 是运行时保留的方法级注解，通过 `@Repeatable` 允许同一个接口同时声明 GLOBAL、IP、USER 等多条规则。切面使用 `method.getAnnotationsByType(RateLimit.class)` 一次取得所有规则，再逐条执行限流判断。

<br />

切画使用方法即获取注解方法获取全部注解中设置的规则。这个和注解的底层原理有关。

## 3. Redis 限流 Key 是怎么设计的？

**回答：** Key 会包含 Controller、方法名、限流维度以及维度值。例如全局 Key 是 `ratelimit:{ResumeController:uploadAndAnalyze}:global`，IP Key 是 `ratelimit:{ResumeController:uploadAndAnalyze}:ip:1.2.3.4`，USER Key 是 `ratelimit:{ResumeController:uploadAndAnalyze}:user:10001`。这样可以让不同接口、IP 和用户拥有独立额度。

<br />

key以请求路径即controller请求路径加限流标识如ipglobal等，

## 4. 为什么 Key 中使用 Redis Cluster Hash Tag？

**回答：** Key 中使用 `{ResumeController:uploadAndAnalyze}` 这样的 Redis Cluster Hash Tag，可以保证同一方法派生出的多个限流 Key 落到同一个 Slot。这样在 Redis Cluster 环境下执行 Lua 时，脚本访问的相关 Key 不会跨 Slot，便于保持原子执行。

<br />

<br />

## 5. 多维限流如何做到全部通过才执行原方法？

**回答：** 切面会遍历方法上的全部 RateLimit Rule，对每个维度分别生成 Key 并执行 Lua。只要有一条规则返回 0 或 null，就停止正常业务流程并进入限流处理；所有 GLOBAL、IP、USER 规则都返回通过时，才执行原方法，因此多维规则天然是 AND 关系。

<br />

for循环遍历即可，有一个不通过for循环就过不了。

## 6. 限流失败后如何调用 fallback 或返回错误码？

**回答：** 规则不通过后进入 `handleRateLimitExceeded`，如果注解配置了降级方法就优先调用 fallback；没有降级方法时抛出 `RateLimitExceededException`，再由全局异常处理器包装成统一响应并返回限流错误码 8001。
