# Redis 缓存、限流与 Stream 任务

## 1. 面试官：项目的滑动窗口限流是怎样实现的，为什么用 Lua？

我的回答：

我的项目用可重复 `@RateLimit` 描述 GLOBAL、IP、USER 三种维度，AOP 在 Controller 方法前逐条计算 key。Redis Lua 用 ZSET 保存窗口内请求时间，先删除过期成员、统计数量、判断额度，再写入当前请求并设置过期；脚本通过 EVALSHA 执行，遇到 NOSCRIPT 会重新加载。Lua 保证单个维度的清理、判断和写入原子，避免 Java 多条命令并发超卖。多个注解却是依次执行，不是一个跨维度原子事务，因此全局额度可能先被消耗、随后 IP 维度拒绝。若该误差不可接受，我会把多维 key 放入一段 Lua 一次判断。

代码依据：

- `app/src/main/java/interview/guide/common/annotation/RateLimit.java`
- `app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java`
- `app/src/main/resources/scripts/rate_limit_single.lua`
- `app/src/test/java/interview/guide/common/aspect/RateLimitScriptTest.java`

## 2. 面试官：Redis 限流 key 为什么使用 Hash Tag，IP 和 USER 维度是否可信？

我的回答：

我这里的 key 形如 `ratelimit:{ClassName:MethodName}:dimension`，花括号内容作为 Redis Cluster Hash Tag，使同一接口的不同维度落到同一 slot，为将来多 key Lua 留条件。GLOBAL 固定，IP 优先取 `X-Forwarded-For` 第一个值，USER 优先取 request attribute，再取 `X-User-Id`，缺失则退化为匿名。当前项目没有认证过滤器给 attribute 注入可信身份，因此 USER 维度并不安全；如果反向代理未覆盖客户端头，IP 也可伪造。生产上应只信任受控代理写入的真实 IP，并从已认证 Principal 获取用户 ID。

代码依据：

- `app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java`
- `app/src/main/java/interview/guide/common/annotation/RateLimit.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/KnowledgeBaseController.java`

## 3. 面试官：公共 Redis Stream 消费模板的消息生命周期是什么？

我的回答：

我的项目的生产者先确保 Stream 和消费者组存在，再 XADD 包含业务字段、retryCount、traceId 的消息并限制近似最大长度。消费者使用单线程执行器持续批量读取，收到消息后恢复 MDC traceId，校验取消状态、标记任务 PROCESSING，调用子类 `processMessage`；成功标记 COMPLETED 并 ACK，实体已删除等取消异常也直接 ACK。普通异常若未达上限，则以 retryCount+1 重新 XADD、把任务标为 RETRYING，再 ACK 旧消息；超过上限进入 dead-letter 状态。模板统一了三条核心 AI 管道和提醒管道的可靠性语义。

代码依据：

- `app/src/main/java/interview/guide/common/async/AbstractStreamProducer.java`
- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java`
- `app/src/main/java/interview/guide/common/constant/AsyncTaskStreamConstants.java`
- `app/src/main/java/interview/guide/common/async/AsyncTaskCancelledException.java`

## 4. 面试官：消费者进程在处理消息时崩溃，项目如何恢复 Pending 消息？

我的回答：

我的项目不只读取新消息。`PendingMessageRecoveryService` 在应用就绪后以及每 60 秒执行一次，对知识库向量化、简历分析、文字评估、语音评估和提醒等消费者组调用 XAUTOCLAIM，把空闲超过 60 秒的 Pending 消息重新分配给当前消费者，单次最多处理 10 条。这样能覆盖“已读但未 ACK 时进程退出”的情况，消息随后重新进入公共消费流程，业务幂等检查避免重复结果。它不能找回根本没有成功 XADD 的数据库任务，也没有基于处理时长区分合法长任务；慢于 60 秒时可能被提前认领，后续应按任务类型设置 idle 阈值和分布式执行租约。

代码依据：

- `app/src/main/java/interview/guide/modules/asynctask/service/PendingMessageRecoveryService.java`
- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java`
- `app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamConsumer.java`
- `app/src/test/java/interview/guide/modules/asynctask/service/PendingMessageRecoveryServiceTest.java`

## 5. 面试官：为什么有 Redis Stream 还要建 AsyncTask 数据表？

我的回答：

我把 Stream 视为传输机制，把 AsyncTask 表视为用户可查询的业务状态。表中保存任务类型、业务 ID、PENDING/QUEUED/PROCESSING/RETRYING/COMPLETED/FAILED/CANCELLED/DEAD_LETTER、重试次数、消息 ID 和错误摘要，前端管理页可以查询、取消或人工重试；`createOrReusePendingTask` 还会复用同一业务对象的活动任务，避免重复排队。Redis 消息过期或被 ACK 后不适合做长期审计，因此两者职责不同。当前二者没有事务 outbox 强一致，状态可能与 Stream 漂移，后续需要巡检“长期 QUEUED 无消息”的任务并补投或告警。

代码依据：

- `app/src/main/java/interview/guide/modules/asynctask/model/AsyncTaskEntity.java`
- `app/src/main/java/interview/guide/modules/asynctask/service/AsyncTaskService.java`
- `app/src/main/java/interview/guide/modules/asynctask/AsyncTaskController.java`
- `frontend/src/pages/AsyncTaskPage.tsx`

## 6. 面试官：流量增大后，当前 Stream 消费模型如何扩容？

我的回答：

我的项目已使用 Consumer Group，因此同一 Stream 的多个应用实例理论上可以分摊消息；业务消息又只含 ID，适合横向扩消费者。但每个 `AbstractStreamConsumer` 实例当前只提交一个长期消费循环，使用单线程 `ThreadPoolExecutor`，所以单实例每条管道基本串行处理；增加虚拟线程配置不会自动提高这个循环的业务并发。直接提高并发还会撞上 Provider 配额、同一实体重复处理和数据库连接限制。我的扩容顺序是先监控 backlog/pending/处理时长，再按任务类型设置消费者并发与舱壁，对 businessId 做互斥或幂等版本，并水平增加实例。

代码依据：

- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java`
- `app/src/main/java/interview/guide/infrastructure/redis/RedisService.java`
- `app/src/main/java/interview/guide/common/observability/AsyncTaskMetricsRecorder.java`
- `app/src/main/resources/application.yml`

# 本章总结

Redis 在项目中承担三种不同职责：面试会话缓存、滑动窗口限流和可靠异步传输。Lua、Consumer Group、任务状态表与 Pending 恢复共同提高了并发正确性和可运维性。

当前仍存在多维限流非原子、可信身份缺失、入队空窗和单实例串行消费等边界，扩容前必须同时考虑幂等与下游容量。

# 核心主线

用 Redis 提升实时访问与异步吞吐，但以数据库任务状态和业务幂等约束其最终语义。
