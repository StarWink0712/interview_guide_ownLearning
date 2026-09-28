# RateLimit 注解设计怎么讲

## 1. 先把注解定位讲清楚

`RateLimit.java` 本身不执行限流，它只负责声明规则。

面试中可以这样说：

> `@RateLimit` 是一个方法级元数据注解，用来描述某个 Controller 方法的限流策略。它本身不包含算法逻辑，真正执行由 `RateLimitAspect` 完成。这样的好处是 Controller 只声明“我要怎么限流”，不需要写任何 Redis 或计数逻辑。

这叫声明式编程：

```text
业务代码关注业务
注解声明规则
AOP 统一执行横切逻辑
```

## 2. 三个元注解怎么解释

源码：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RateLimit.Container.class)
public @interface RateLimit
```

### 2.1 @Target(ElementType.METHOD)

意思：

> 这个注解只能标在方法上。

为什么：

> 当前项目限流粒度是接口方法级别，比如上传简历、创建面试会话、知识库查询。类级限流或参数级限流暂时不需要，所以限制在方法上更明确。

### 2.2 @Retention(RetentionPolicy.RUNTIME)

意思：

> 注解在运行时仍然存在，可以被反射读取。

为什么：

> `RateLimitAspect` 在请求进来时要通过 `method.getAnnotationsByType(RateLimit.class)` 读取方法上的限流规则。如果 Retention 不是 RUNTIME，运行时切面就拿不到注解信息。

### 2.3 @Repeatable(RateLimit.Container.class)

意思：

> 同一个方法可以重复标注多个 `@RateLimit`。

为什么：

> 一个接口往往需要多维限流。例如简历上传既要全局限制，也要按 IP 限制。可重复注解可以让每条规则独立配置，表达更自然。

## 3. Container 是什么

源码：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface Container {
    RateLimit[] value();
}
```

面试解释：

> Java 的可重复注解底层需要一个容器注解。当一个方法上写多个 `@RateLimit` 时，编译器会把它们放到 `RateLimit.Container` 里。但业务代码不用直接操作 Container，切面用 `getAnnotationsByType` 可以统一拿到所有 `RateLimit`。

示例：

```java
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
@RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
public Result<?> uploadAndAnalyze(...) { ... }
```

运行时：

```java
RateLimit[] rules = method.getAnnotationsByType(RateLimit.class);
```

得到 2 条规则。

## 4. Dimension 设计怎么讲

源码：

```java
enum Dimension {
    GLOBAL,
    IP,
    USER
}
```

### GLOBAL：接口整体限流

适合保护整体资源：

```java
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
```

面试表达：

> GLOBAL 控制某个接口的整体吞吐，防止瞬间请求量过大拖垮服务。例如简历上传涉及文件解析和 AI 分析，所以要限制全局并发触发频率。

### IP：客户端来源限流

适合防刷：

```java
@RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
```

面试表达：

> IP 维度限制单个来源，防止一个用户或脚本反复刷新接口。比如知识库上传、流式查询、JD 解析都适合加 IP 限流。

### USER：用户维度限流

适合登录系统：

```java
@RateLimit(dimension = RateLimit.Dimension.USER, count = 20)
```

当前项目支持该维度，但主要 Controller 还没大量使用。原因是：

> USER 维度需要稳定可信的登录态或认证网关配合。当前代码从 request attribute `userId` 或请求头 `X-User-Id` 获取用户 ID，如果没有认证体系兜底，请求头可能被伪造，所以实际使用要谨慎。

## 5. count 为什么是 double

源码：

```java
double count();
```

面试可以这样讲：

> 当前项目每次请求固定消耗 1 个令牌，所以实际常用的是整数 count，比如 5、10、30。这里设计成 double 更偏扩展性，未来可以支持不同接口权重或半令牌等更细的限额表达。不过当前 Lua 脚本里每次 permits 固定传 1，所以不要过度声称已经实现复杂权重限流。

## 6. interval 和 timeUnit 怎么讲

源码：

```java
long interval() default 1;
TimeUnit timeUnit() default TimeUnit.SECONDS;
```

时间单位：

```java
MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS
```

切面转换：

```java
private long calculateIntervalMs(long interval, RateLimit.TimeUnit unit) {
    return switch (unit) {
        case MILLISECONDS -> interval;
        case SECONDS -> interval * 1000;
        case MINUTES -> interval * 60 * 1000;
        case HOURS -> interval * 3600 * 1000;
        case DAYS -> interval * 86400 * 1000;
    };
}
```

示例：

```java
@RateLimit(count = 10)
```

等价于：

```text
每 1 秒最多 10 次
```

```java
@RateLimit(count = 100, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
```

表示：

```text
每 1 分钟最多 100 次
```

## 7. timeout 字段怎么诚实讲

源码：

```java
long timeout() default 0;
```

注释里说：

```text
等待令牌的超时时间
如果为 0，不等待
如果大于 0，尝试等待指定时间
```

但当前 `RateLimitAspect` 没有使用这个字段。

面试中必须诚实：

> `timeout` 是预留扩展字段，目前切面没有实现等待令牌逻辑，当前策略是请求到来后立即执行 Lua 脚本，配额不足就拒绝或降级。后续如果要实现 timeout，可以在切面层循环尝试获取令牌，或者改成令牌桶队列/异步排队，但要注意不要阻塞请求线程太久。

## 8. fallback 字段怎么讲

源码：

```java
String fallback() default "";
```

用途：

> 限流触发时，如果配置了 fallback，就调用同一个类中的降级方法；没配置就抛 `RateLimitExceededException`。

支持两种 fallback：

```text
1. 参数列表与原方法完全一致
2. 无参方法
```

查找顺序：

```text
先找同参数方法
再找无参方法
找不到 -> 记录 warn -> 抛限流异常
```

示例：

```java
@RateLimit(count = 10, fallback = "busy")
public Result<?> query(QueryRequest request) {
    ...
}

private Result<?> busy(QueryRequest request) {
    return Result.error(8001, "当前请求较多，请稍后再试");
}
```

面试表达：

> fallback 适合读接口或可以降级的接口，比如返回缓存或友好提示。但像上传、配置修改这类写接口，通常直接拒绝更安全。

## 9. 注解和切面的边界

面试官可能问：“为什么不把逻辑写在注解里？”

回答：

> Java 注解本质上是元数据，不能直接拦截请求，也不应该持有业务逻辑。执行逻辑放在 AOP 切面里，注解只负责声明限流参数。这样业务方法保持干净，限流逻辑集中维护。

## 10. 注解测试怎么讲

项目里有 `RateLimitScriptTest`，主要验证：

- `RetentionPolicy.RUNTIME`；
- `Target METHOD`；
- Dimension 枚举数量；
- TimeUnit 枚举数量；
- `@Repeatable` 存在；
- 默认值；
- 自定义值；
- 多条注解能通过 `getAnnotationsByType` 获取。

面试表达：

> 对注解本身，我没有测 Redis，而是测元配置和反射行为，确保 AOP 运行时能拿到注解，并且多条规则可以被正确展开。

## 11. 高频追问

### 问：为什么 `@RateLimit` 放在 Controller 层？

答：

> 限流本质上是接口入口保护，越靠近入口越能节省后端资源。如果放到 Service 层，Controller 参数校验、文件解析等可能已经发生了。当前项目主要保护外部 HTTP 接口，所以标在 Controller 方法上最直接。

### 问：如果同一个方法有两条限流规则，它们是什么关系？

答：

> AND 关系。切面逐条执行所有规则，全部通过才放行。任一规则不通过就触发 fallback 或抛限流异常。

### 问：为什么不写成 `@GlobalRateLimit`、`@IpRateLimit` 两个注解？

答：

> 会增加注解类型数量，也会让切面处理更复杂。用一个 `@RateLimit` 加 `dimension` 枚举，表达统一，扩展 USER 或其他维度也更自然。

### 问：`timeout` 字段当前有效吗？

答：

> 当前无效，是预留字段。当前策略是配额不足立即拒绝。这个点我会如实说明，不会说已经实现等待。

