# around 环绕通知执行流程怎么讲

## 1. around 方法是整个切面的入口

源码入口：

```java
@Around("@annotation(interview.guide.common.annotation.RateLimit) || "
        + "@annotation(interview.guide.common.annotation.RateLimit.Container)")
public Object around(ProceedingJoinPoint joinPoint) throws Throwable
```

面试定位：

> `around` 是限流切面的主流程，它在目标 Controller 方法执行前先做限流检查。只有全部限流规则通过，才调用 `joinPoint.proceed()` 进入真实业务。

## 2. 切点表达式为什么这么写

切点匹配两种情况：

```text
@annotation(RateLimit)
  方法上只有一个 @RateLimit

@annotation(RateLimit.Container)
  方法上有多个 @RateLimit，可重复注解底层使用容器注解
```

面试回答：

> 因为 `@RateLimit` 支持 `@Repeatable`。如果一个方法标多个 `@RateLimit`，编译器会把它们放到 `RateLimit.Container` 里。切点同时匹配单注解和容器注解，才能保证单维和多维限流都能被拦截。

## 3. 第一步：获取方法签名

源码：

```java
MethodSignature signature = (MethodSignature) joinPoint.getSignature();
Method method = signature.getMethod();
String className = method.getDeclaringClass().getSimpleName();
String methodName = method.getName();
```

为什么要拿类名和方法名：

```text
用于生成 Redis Key 的方法级命名空间
ratelimit:{ClassName:MethodName}:dimension
```

例如：

```text
ResumeController.uploadAndAnalyze
  -> ratelimit:{ResumeController:uploadAndAnalyze}:global
  -> ratelimit:{ResumeController:uploadAndAnalyze}:ip:127.0.0.1
```

面试表达：

> 类名和方法名决定限流作用域。不同 Controller 方法会生成不同限流 Key，避免互相影响。

## 4. 第二步：读取所有 @RateLimit 规则

源码：

```java
RateLimit[] rules = method.getAnnotationsByType(RateLimit.class);
```

为什么不用 `getAnnotation`：

```text
getAnnotation 只能拿单个注解
getAnnotationsByType 能展开 @Repeatable 容器
```

例子：

```java
@RateLimit(dimension = GLOBAL, count = 5)
@RateLimit(dimension = IP, count = 5)
public Result<?> uploadAndAnalyze(...) {}
```

`rules.length == 2`。

面试表达：

> 这里把单规则和多规则统一成数组处理，后面遍历即可，逻辑简单。

## 5. 第三步：生成 nowMs 和 requestId

源码：

```java
long nowMs = System.currentTimeMillis();
String requestId = UUID.randomUUID().toString();
```

### nowMs

传给 Lua 脚本，用作滑动窗口当前时间：

```text
过期边界 = nowMs - intervalMs
```

### requestId

传给 Lua 脚本，写入 Sorted Set member：

```text
requestId:permits
```

面试表达：

> nowMs 保证本次请求所有规则使用同一个时间点，requestId 用于让每次请求记录唯一，避免 Sorted Set member 冲突。

诚实边界：

> 这个 requestId 是切面内部每次新生成的 UUID，不能实现跨 HTTP 重试的严格幂等。如果需要外部重试去重，应该从请求头读取稳定的 Idempotency-Key。

## 6. 第四步：遍历规则

核心：

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

执行语义：

```text
规则 1 通过 -> 继续规则 2
规则 2 通过 -> 继续规则 3
任一规则失败 -> 立即 return，不再执行后续规则
全部通过 -> proceed
```

## 7. 为什么这里是短路逻辑

例如：

```java
@RateLimit(dimension = GLOBAL, count = 100)
@RateLimit(dimension = IP, count = 3)
```

如果某个 IP 已经超过 3 次：

```text
GLOBAL 可能仍有余量
IP 已经超限
```

请求仍然应该拒绝。

短路的好处：

- 减少无意义的 Redis 调用；
- 任一维度失败就立即保护系统；
- 语义清晰：所有规则必须全部通过。

## 8. 第五步：执行原方法

源码：

```java
return joinPoint.proceed();
```

只有所有规则通过才走到这里。

面试表达：

> `joinPoint.proceed()` 是 AOP 放行业务方法的关键点。如果限流失败，代码会提前 return fallback 或抛异常，业务方法完全不会执行。

## 9. around 方法里的一个注释边界

源码注释写：

```text
执行 Redis Lua 脚本，返回当前窗口剩余配额
```

但当前 Lua 脚本实际：

```lua
return 1 -- 通过
return 0 -- 拒绝
```

所以面试中要说：

> 当前 result 的实际语义是“是否通过”，不是剩余配额。注释有点早期设计遗留。如果要返回剩余配额，需要调整 Lua 的 return 值。

这个点很重要，它体现你读过真实代码，没有照着注释过度讲。

## 10. around 方法为什么没有直接捕获所有异常

当前 `around` 没有包一层 `catch Exception`，原因可以这样解释：

> 限流失败是预期业务异常，由 `handleRateLimitExceeded` 抛出 `RateLimitExceededException`。业务方法自己的异常应该继续按原有异常体系处理。Redis 非 NOSCRIPT 异常目前也会向上抛出，这是当前实现的边界，后续可以按策略做 fail-open 或 fail-closed。

## 11. 面试官可能追问

### 问：如果方法上没有 `@RateLimit`，会不会进入 around？

答：

> 不会。切点只匹配带 `@RateLimit` 或 `RateLimit.Container` 的方法。没有标注的方法不受这个切面影响。

### 问：如果 `rules` 是空数组怎么办？

答：

> 理论上切点已经保证方法有注解，所以正常不会为空。即使为空，for 循环不执行，也会直接 `proceed`。不过这种情况一般不会出现。

### 问：多条规则执行顺序重要吗？

答：

> Java 反射返回的注解顺序通常与声明顺序一致，但业务语义不应该依赖顺序，因为所有规则都是 AND。顺序只影响哪条规则先拒绝、日志里的 key 是哪一个。

### 问：为什么不把多个规则合成一个 Lua 脚本一次执行？

答：

> 现在的设计是单维脚本，切面逐条调用，代码简单、规则独立。缺点是多维规则会多次 Redis 调用。后续如果性能压力大，可以把多个 key 和参数一次传入 Lua，在脚本里批量判断并保证 all-or-nothing，但实现会更复杂。

## 12. 总结表达

> `around` 方法体现了这个限流组件的主干：识别被限流的方法，展开可重复注解，按维度生成 Key，调用 Redis Lua 判断，失败短路，成功放行业务。它把限流作为横切逻辑插入 Controller 执行前，实现了业务代码零侵入。

