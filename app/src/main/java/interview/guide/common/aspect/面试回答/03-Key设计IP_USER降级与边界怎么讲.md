# Key 设计、IP/USER、降级与边界怎么讲

## 1. 这篇关注哪些私有方法

`RateLimitAspect` 后半部分主要是四类辅助能力：

```text
generateKey(...)
  生成 Redis 限流 Key

getClientIp()
  获取 IP 维度的具体值

getCurrentUserId()
  获取 USER 维度的具体值

handleRateLimitExceeded(...) / findFallbackMethod(...)
  限流触发后的降级和异常处理
```

面试官深挖时，很容易从这些方法问到 Redis Cluster、代理 IP、安全边界、fallback 反射和生产优化。

## 2. generateKey 怎么讲

源码逻辑：

```java
String hashTag = "{" + className + ":" + methodName + "}";
String keyPrefix = "ratelimit:" + hashTag;

return switch (dimension) {
    case GLOBAL -> keyPrefix + ":global";
    case IP -> keyPrefix + ":ip:" + getClientIp();
    case USER -> keyPrefix + ":user:" + getCurrentUserId();
};
```

生成结果：

```text
ratelimit:{ResumeController:uploadAndAnalyze}:global
ratelimit:{ResumeController:uploadAndAnalyze}:ip:192.168.1.10
ratelimit:{ResumeController:uploadAndAnalyze}:user:10001
```

面试表达：

> Key 由方法作用域和限流维度两部分组成。方法作用域保证不同接口互不影响，维度后缀保证 GLOBAL、IP、USER 独立计数。

## 3. Hash Tag 为什么重要

Redis Cluster 中，如果 Lua 脚本操作多个 key，这些 key 需要在同一个 slot。

当前 Lua 内部会派生：

```text
baseKey:value
baseKey:permits
```

如果 baseKey 是：

```text
ratelimit:{ResumeController:uploadAndAnalyze}:ip:192.168.1.10
```

派生 key：

```text
ratelimit:{ResumeController:uploadAndAnalyze}:ip:192.168.1.10:value
ratelimit:{ResumeController:uploadAndAnalyze}:ip:192.168.1.10:permits
```

它们都包含同一个 `{ResumeController:uploadAndAnalyze}`。

面试表达：

> Redis Cluster 只对 `{}` 里的内容算 slot，所以 `:value` 和 `:permits` 能落到同一 slot，Lua 脚本不会因为跨 slot 报错。这是 Key 设计里专门为集群部署考虑的点。

## 4. GLOBAL 维度怎么讲

Key：

```text
ratelimit:{ClassName:MethodName}:global
```

特点：

```text
不区分 IP
不区分用户
所有请求共享同一个配额池
```

适合：

- 保护整体系统资源；
- 控制外部 AI 服务调用总频率；
- 限制配置变更/连接测试接口。

面试表达：

> GLOBAL 是接口整体阈值，防止总流量超过后端承载能力。

## 5. IP 维度怎么讲

Key：

```text
ratelimit:{ClassName:MethodName}:ip:{clientIp}
```

`getClientIp()` 获取顺序：

```text
X-Forwarded-For
X-Real-IP
Proxy-Client-IP
WL-Proxy-Client-IP
request.getRemoteAddr()
```

为什么取 `X-Forwarded-For` 第一个：

```text
X-Forwarded-For: client, proxy1, proxy2
第一个通常是原始客户端 IP
```

面试表达：

> IP 维度主要防单个来源刷接口。代码兼容常见反向代理头，并对 `X-Forwarded-For` 取第一个 IP。

安全边界：

> 这些 Header 可以被伪造，所以生产环境要由 Nginx 或网关覆盖上游传来的 IP 头，后端只信任网关写入的真实 IP。

## 6. USER 维度怎么讲

Key：

```text
ratelimit:{ClassName:MethodName}:user:{userId}
```

`getCurrentUserId()` 获取顺序：

```text
request.getAttribute("userId")
request.getHeader("X-User-Id")
"anonymous"
```

面试表达：

> USER 维度适合登录系统，限制单个用户的资源使用。当前组件层已经支持，但项目里主要还用 GLOBAL 和 IP，因为 USER 要依赖认证模块提供可信身份。

安全边界：

> 最安全的方式是 JWT/Session 过滤器解析身份后写入 request attribute，切面只读 attribute；直接相信 `X-User-Id` Header 容易被伪造。

## 7. handleRateLimitExceeded 怎么讲

触发条件：

```text
Lua 返回 null 或 0
```

处理流程：

```text
1. 判断注解 fallback 是否非空
2. 如果配置了 fallback：
   - 查找同类中的 fallback 方法
   - 优先参数列表一致
   - 再找无参方法
   - 找到则反射调用
3. fallback 不存在或执行失败：
   - 记录日志
   - 抛 RateLimitExceededException
```

伪代码：

```java
if (fallback configured) {
    Method fallbackMethod = findFallbackMethod(...);
    if (fallbackMethod != null) {
        if (fallbackMethod.getParameterCount() > 0) {
            return fallbackMethod.invoke(target, args);
        }
        return fallbackMethod.invoke(target);
    }
}
throw new RateLimitExceededException("请求过于频繁，请稍后再试");
```

## 8. findFallbackMethod 怎么讲

查找顺序：

```text
targetClass.getDeclaredMethod(fallbackName, parameterTypes)
  -> 找原方法同参数降级

targetClass.getDeclaredMethod(fallbackName)
  -> 找无参降级

都找不到
  -> log.warn
  -> return null
```

为什么要 `setAccessible(true)`：

> 允许 fallback 方法不是 public，例如 private fallback 也能被调用。

面试表达：

> fallback 方法必须在同一个 Controller 类里，支持两种签名：和原方法参数一致，或者无参。这样既能拿到原请求参数做降级，也能写简单通用降级。

## 9. fallback 适合什么场景

适合：

```text
读接口
  返回缓存

推荐/列表接口
  返回默认结果

非核心查询
  返回友好提示
```

不适合：

```text
上传文件
创建资源
删除资源
修改 Provider 配置
提交面试答案
```

面试表达：

> fallback 不是越多越好。写接口如果降级返回成功，会造成数据状态不一致。当前项目多数高成本写接口更适合直接拒绝，让用户稍后重试。

## 10. RateLimitExceededException 怎么进入统一响应

`RateLimitExceededException` 继承：

```text
BusinessException
```

错误码：

```text
ErrorCode.RATE_LIMIT_EXCEEDED = 8001
```

全局异常：

```text
GlobalExceptionHandler.handleBusinessException(...)
  -> Result.error(e.getCode(), e.getMessage())
```

面试表达：

> 限流失败不会散落成各种 HTTP 响应，而是走项目统一异常体系，前端看到的是统一 Result 格式和错误码 8001。

## 11. 这里有哪些工程边界

### 11.1 fallback 失败后原始异常会被日志记录，但最终抛限流异常

当前代码：

```java
catch (Exception e) {
    log.error("降级方法执行失败: {}", rateLimit.fallback(), e);
}
throw new RateLimitExceededException(...)
```

面试说法：

> fallback 失败不会继续放行业务方法，而是记录错误后仍然按限流拒绝处理。这样更安全，但如果需要排查 fallback 错误，要看日志。

### 11.2 IP 可能被伪造

解决方向：

```text
Nginx/Gateway 清洗并覆盖代理头
后端只信任内网网关
```

### 11.3 USER 需要认证体系

解决方向：

```text
认证过滤器解析 token
request.setAttribute("userId", userId)
切面只读 attribute
```

### 11.4 Key 粒度目前是方法级

当前：

```text
ClassName + MethodName + dimension
```

这意味着：

```text
同一个接口下不同 knowledgeBaseId / resumeId 不分开限流
```

后续可以扩展：

```text
@RateLimit(key = "#request.knowledgeBaseId")
或 SpEL 支持业务参数维度
```

## 12. 高频追问

### 问：fallback 为什么要求同一个类？

答：

> 当前实现用 `joinPoint.getTarget().getClass()` 反射查找，简单直接。降级逻辑和接口放在同一个 Controller 里，方便理解和维护。后续如果降级逻辑复杂，可以抽成专门服务或支持 Bean 名称。

### 问：如果 fallback 返回值类型和原方法不一致怎么办？

答：

> 当前代码没有显式校验返回类型，反射调用后由 AOP 返回给 Spring MVC。如果类型不兼容，运行时可能出错。所以实际开发要保证 fallback 返回值和原方法兼容，后续可以在启动时做校验。

### 问：为什么 Key 不包含请求参数？

答：

> 当前限流主要按接口、IP、用户维度保护资源，足够覆盖高成本接口防刷。参数级限流更精细，但也更复杂，需要 SpEL 或自定义 key 解析，后续可以扩展。

### 问：IP 为 unknown 会怎样？

答：

> 如果非 Web 环境或无法获取 IP，会使用 `unknown`，所有未知来源共享同一个 IP 维度 key。这在测试或异常场景下能兜底，但生产应保证网关正确传递真实 IP。

## 13. 总结表达

> `generateKey` 决定限流粒度，`getClientIp` 和 `getCurrentUserId` 决定维度值，`handleRateLimitExceeded` 决定限流后的行为。这个切面不只是执行 Redis 脚本，还把限流的命名空间、集群兼容、安全边界和降级策略都集中管理起来。

