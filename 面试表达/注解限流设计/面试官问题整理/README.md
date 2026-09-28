# 注解限流设计面试问题索引

本目录根据上一级目录中的两篇注解限流笔记整理。原始笔记保持不变；分类文件按照面试问题逐题整理答案，答案只重组原笔记中的信息。

## 一、限流需求与整体方案

- 为什么项目需要限流？
- 哪些接口需要重点限流？
- 为什么使用注解实现限流？
- `@RateLimit` 支持哪些限流维度？
- 注解中可以配置哪些参数？
- 多条限流规则是什么关系？
- 请求被限流后如何降级？

对应文件：[01-限流需求与整体方案.md](01-限流需求与整体方案.md)

## 二、RateLimitAspect 执行链

- `RateLimitAspect` 的完整执行流程是什么？
- `@Repeatable` 在限流设计中有什么作用？
- Redis 限流 Key 是怎么设计的？
- 为什么 Key 中使用 Redis Cluster Hash Tag？
- 多维限流如何做到全部通过才执行原方法？
- 限流失败后如何调用 fallback 或返回错误码？

对应文件：[02-RateLimitAspect执行链.md](02-RateLimitAspect执行链.md)

## 三、Redis Lua 滑动窗口

- 为什么使用 Redis Lua 实现限流？
- Lua 脚本内部使用了什么数据结构？
- 执行脚本需要传入哪些参数？
- 滑动窗口算法的完整执行过程是什么？
- Lua 如何保证判断和扣减的原子性？
- 为什么预加载 Lua 脚本并使用 SHA1 调用？
- 为什么要给限流 Key 设置过期时间？

对应文件：[03-Redis-Lua滑动窗口.md](03-Redis-Lua滑动窗口.md)

## 四、异常、降级与高可用

- 什么是 NOSCRIPT 异常？
- NOSCRIPT 为什么可以自动恢复？
- 非 NOSCRIPT Redis 异常怎么处理？
- Redis 宕机时限流应该 fail-open 还是 fail-closed？
- 哪些接口应该 fail-closed？
- 哪些接口可以 fail-open？
- Redis 异常时如何做本地兜底限流？
- 为什么要设置短超时并记录监控指标？
- Sentinel 和 Cluster 分别适合什么限流场景？

对应文件：[04-异常降级与高可用.md](04-异常降级与高可用.md)
