# Redis 缓存、限流与分布式控制

## 1. 面试官：Redis 在项目中承担哪些职责，为什么要区分这些职责？

我的回答：

Redis 是内存数据平台，不同用法的可靠性和容量要求不同。我的项目中它承担四类职责：缓存文字面试会话与简历到未完成会话的映射；用 ZSET 和 Lua 实现滑动窗口限流；用 Stream 与 Consumer Group 传递异步任务；通过 Redisson 暴露分布式锁、原子计数和通用数据结构。缓存可丢后重建，限流允许短期状态，消息则要求 ACK 和 Pending 恢复，不能用同一套“Redis 很快”概括。当前所有职责共享单实例和数据库 0，故障域与热点互相影响；生产上应至少按容量和可靠性拆分实例或集群。

代码依据：

- `app/src/main/java/interview/guide/infrastructure/redis/RedisService.java` — 通用 Redis 能力
- `app/src/main/java/interview/guide/infrastructure/redis/InterviewSessionCache.java` — 会话缓存
- `app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java` — 限流
- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java` — Stream 消费

## 2. 面试官：项目的缓存 Key 和数据结构如何设计？

我的回答：

Key 应体现业务域、实体和用途，并控制长度、TTL 与集群 slot。我的项目的面试会话使用 `interview:session:{sessionId}` 保存序列化对象，另有 resumeId 到未完成 sessionId 的映射；限流 key 形如 `ratelimit:{ClassName:MethodName}:dimension`，花括号是 Redis Cluster Hash Tag；Stream 使用固定业务 key。`RedisService` 还封装 String、Hash、List、AtomicLong 和 Lock。当前缓存对象包含问题 JSON，随着题目增多可能变成大 value；按 pattern 扫描和删除在大 keyspace 也有风险。生产上会建立 Key 规范、大小阈值与扫描监控。

代码依据：

- `app/src/main/java/interview/guide/infrastructure/redis/InterviewSessionCache.java` — Key 常量与 `saveSession()`
- `app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java` — 限流 Key
- `app/src/main/java/interview/guide/infrastructure/redis/RedisService.java` — 数据结构封装
- `app/src/main/java/interview/guide/common/constant/AsyncTaskStreamConstants.java` — Stream Key

## 3. 面试官：面试会话缓存采用什么读写策略，缓存未命中怎么办？

我的回答：

我的项目采用接近 Cache-Aside 的策略：答题时优先从 `InterviewSessionCache` 读取，命中可快速获得题目、当前索引和状态；未命中则查询 PostgreSQL 的会话实体，反序列化 questions JSON 并重建缓存。更新题目、索引和状态时，业务同时写数据库和 Redis；删除会话时也清理缓存与映射。这样 Redis 不是唯一事实源，过期或重启后可以恢复。当前双写顺序不是严格原子，创建时数据库失败还可能留下缓存会话，提交时并发读改写也可能覆盖。生产上应以数据库为准，事务提交后失效或刷新缓存，并加入版本号。

代码依据：

- `app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java` — 会话读取与更新
- `app/src/main/java/interview/guide/infrastructure/redis/InterviewSessionCache.java` — `getSession()`、`updateCurrentIndex()`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java` — 数据库事实

## 4. 面试官：TTL 有什么作用，项目如何设置和刷新过期时间？

我的回答：

TTL 用于自动淘汰不再需要的临时状态，防止内存无限增长，也定义缓存能容忍的陈旧时间。我的项目为文字面试会话和 resume 映射设置 24 小时 TTL，保存或显式刷新时同时续期；限流 ZSET 的过期时间由窗口长度决定，旧请求成员又会在 Lua 中主动删除。这样长时间不用的会话会自动释放，而数据库仍可恢复。当前固定 24 小时没有根据会话状态区分，活跃会话若没有触发刷新也可能过期，不过业务会回源。生产上会对进行中、完成和映射采用不同 TTL，并监控 TTL 缺失与大量同时过期。

代码依据：

- `app/src/main/java/interview/guide/infrastructure/redis/InterviewSessionCache.java` — `SESSION_TTL`、`refreshSessionTTL()`
- `app/src/main/java/interview/guide/infrastructure/redis/RedisService.java` — `set()`、`expire()`
- `app/src/main/resources/scripts/rate_limit_single.lua` — 窗口过期

## 5. 面试官：缓存穿透、击穿和雪崩在项目中可能怎样发生？

我的回答：

穿透是大量查询不存在 key 并持续打数据库；击穿是热点 key 过期时并发回源；雪崩是大量 key 同时过期或 Redis 故障。我的项目会话缓存未命中就查数据库，没有布隆过滤器、空值缓存或 single-flight，同一热门 session 的缓存失效可能产生并发回源；固定 TTL 也可能造成同批会话集中到期。当前业务规模较小，尚未完整治理。生产上我会先校验 sessionId 格式和权限，对不存在结果短 TTL 缓存；热点重建用互斥或逻辑过期，TTL 加随机抖动，并对数据库回源设置限流和熔断。

代码依据：

- `app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java` — 缓存未命中回源
- `app/src/main/java/interview/guide/infrastructure/redis/InterviewSessionCache.java` — 固定 TTL
- `app/src/main/java/interview/guide/infrastructure/redis/RedisService.java` — `getOrLoad()`

## 6. 面试官：缓存和数据库不一致有哪些典型窗口，项目应该怎样改进？

我的回答：

双写不一致通常来自一个写成功、另一个失败，或并发请求以旧值覆盖新值。我的项目创建文字会话时先写缓存再写数据库，数据库异常仍可能返回缓存数据；提交答案涉及答案表、会话表和 Redis 索引更新，也不是跨资源原子事务。读取以缓存优先，因此陈旧值会直接影响下一题。当前可以靠数据库回源修复缓存丢失，却不能自动发现陈旧缓存。我的改进是数据库事务先提交，再删除或更新缓存；缓存对象携带 version，写入使用 compare-and-set；定期对账未完成会话，关键状态变更通过 outbox 驱动缓存失效。

代码依据：

- `app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java` — `createSession()`、`submitAnswer()`
- `app/src/main/java/interview/guide/infrastructure/redis/InterviewSessionCache.java` — 缓存写入
- `app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java` — 事务写入

## 7. 面试官：Lua 脚本为什么能保证原子性，项目如何处理脚本缓存失效？

我的回答：

Redis 在执行一段 Lua 时不会穿插其他命令，因此脚本内的多步读改写对同一 Redis 实例是原子的。我的项目限流脚本在一次执行中删除窗口外 ZSET 成员、统计当前请求、判断额度、写入唯一成员并设置过期，避免 Java 分多条命令产生并发超卖。应用启动时加载脚本取得 SHA，正常用 EVALSHA；Redis 重启或脚本缓存清空出现 NOSCRIPT 时，切面会重新加载再执行。原子性只覆盖脚本涉及的 key 与单次 Redis 执行，不等于跨数据库事务，也不能解决 Redis 故障后的状态丢失。

代码依据：

- `app/src/main/resources/scripts/rate_limit_single.lua` — 原子滑动窗口
- `app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java` — `init()`、脚本执行
- `app/src/test/java/interview/guide/common/aspect/RateLimitScriptTest.java` — Lua 行为测试

## 8. 面试官：滑动窗口限流怎样工作，和固定窗口、令牌桶相比有什么差异？

我的回答：

滑动窗口保留最近一段时间的请求时间，任意时刻统计都较平滑；固定窗口只计自然时间段，边界两侧可能突发双倍流量；令牌桶允许可控突发并按速率恢复。我的项目用 ZSET score 保存毫秒时间，Lua 删除 `now-window` 之前成员，数量达到 count 就拒绝，否则加入当前请求。`@RateLimit` 可重复配置 GLOBAL、IP、USER，每个维度独立 count、interval 和 timeUnit。优点是精确直观，缺点是每次请求要维护成员，热点接口内存和 CPU 较高。高 QPS 下可考虑令牌桶或网关级限流。

代码依据：

- `app/src/main/java/interview/guide/common/annotation/RateLimit.java` — 限流参数与维度
- `app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java` — `around()`
- `app/src/main/resources/scripts/rate_limit_single.lua` — ZSET 计算

## 9. 面试官：项目的多维限流和客户端身份识别有什么边界？

我的回答：

我的项目逐条执行多个 `@RateLimit`，单个维度由 Lua 原子保证，但多个维度不是一个原子决策：GLOBAL 额度可能已消耗，随后 IP 维度才拒绝。IP 优先取 `X-Forwarded-For` 第一个值，USER 优先取 request attribute，再取 `X-User-Id`；项目没有认证过滤器注入可信用户，因此 USER 头可伪造，代理未覆盖转发头时 IP 也不可信。当前适合开发期流量保护，不是安全配额系统。生产上应从受控代理和 Principal 取身份，并把同 slot 的多维判断合并到一段 Lua。

代码依据：

- `app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java` — `getClientIp()`、`getUserId()`
- `app/src/main/java/interview/guide/common/annotation/RateLimit.java` — Repeatable 维度
- `app/src/main/java/interview/guide/modules/knowledgebase/KnowledgeBaseController.java` — 多维注解使用

## 10. 面试官：项目是否真正使用了分布式锁，Redis 宕机和集群化如何处理？

我的回答：

我的项目的 `RedisService` 已封装 Redisson `RLock`、`tryLock`、`unlock` 和 `executeWithLock`，包含等待时间与租约，但核心面试、提醒和消费流程没有普遍使用它，因此不能说分布式锁已经形成业务闭环。提醒认领实际采用数据库悲观锁，语音并发主要是 JVM 内 CAS。Redis 当前是 Compose 单节点，宕机会同时影响缓存、限流和 Stream，部分接口可能直接失败。生产上我会部署 Redis Sentinel 或 Cluster，按用途拆实例；锁只用于确需互斥的短操作，并配 fencing token，不能仅依赖租约防止旧持有者写入。

代码依据：

- `app/src/main/java/interview/guide/infrastructure/redis/RedisService.java` — `executeWithLock()`
- `app/src/main/java/interview/guide/modules/interviewschedule/reminder/repository/InterviewReminderRepository.java` — 数据库悲观锁
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — JVM CAS
- `docker-compose.yml` — 单 Redis 实例

# 本章总结

Redis 在项目中同时服务于缓存、滑动窗口限流、Stream 和分布式原语。会话缓存可回源，限流由 Lua 保证单维原子，锁能力虽已封装但没有广泛落地。

生产化需要解决双写一致性、穿透击穿雪崩、可信身份、热点与大 key，以及单实例故障域和集群部署。

# 核心主线

按缓存、控制和消息三种语义使用 Redis，并为每种语义分别设计过期、一致性和故障策略。
