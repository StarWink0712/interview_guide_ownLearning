# Redis Stream 消息队列面试题整理

> 适用项目：AI Interview Platform  
> 核心原则：回答时必须区分“当前已经实现”“Redis Stream 能提供”“生产级仍需改进”。

## 一、先用 1 分钟讲清项目方案

本项目把 Redis Stream 作为轻量消息队列，异步执行简历分析、知识库向量化、
文字面试评估、语音面试评估和面试提醒。每类任务使用独立的 Stream 和 Consumer Group，
消息只保存任务/业务 ID、`retryCount` 和可选 `traceId`，大文本仍保存在 PostgreSQL 或对象存储中。

生产者通过 `XADD` 投递消息；消费者使用 `XREADGROUP` 竞争消费新消息，业务成功后手动
`XACK`。消息被读取但未 ACK 时会进入 PEL。应用启动时以及之后每 60 秒，恢复服务会通过
`XAUTOCLAIM` 认领空闲超过 60 秒的 pending 消息。普通业务异常会重新 `XADD` 一条
`retryCount + 1` 的消息，最多重试 3 次；初始执行加 3 次重试，所以最多执行 4 次。

可靠性语义不是 exactly-once，而是 **at-least-once + 业务幂等**。数据库中的
`async_tasks` 表负责状态、错误、重试和人工运维，Redis Stream 只负责消息运输。

当前方案适合已有 Redis、任务量中等、希望降低组件复杂度的单体项目。它已经具备消费者组、
手动 ACK、pending 恢复和人工重试，但还没有实现真正的事务消息、延迟退避重试、独立死信
Stream、完整队列 lag 监控和严格顺序消费。

## 二、项目现状总表

| 能力 | 当前实现 | 能否直接宣称 |
|---|---|---|
| 业务管道隔离 | 5 个独立 Stream、5 个独立 Consumer Group | 已实现 |
| 负载均衡 | 同组消费者竞争读取；每个应用实例每条管道一个单线程消费者 | 已实现 |
| 手动 ACK | 成功、取消、重投或最终失败后 ACK 当前消息 | 已实现 |
| 崩溃恢复 | PEL + 启动/每 60 秒 `XAUTOCLAIM` | 已实现，但超时策略有风险 |
| 自动重试 | 立即重新 `XADD`，最多 3 次重试 | 已实现 |
| 人工重试 | `FAILED/CANCELLED/DEAD_LETTER -> PENDING` 后重新入队 | 已实现 |
| 防重复创建 | `taskType + businessId` 查询 + `activeKey` 唯一索引 | 已实现 |
| 重复消费幂等 | AI 任务主要使用“结果已存在则跳过” | 部分实现 |
| 严格顺序消费 | 仅单实例、单线程、无重试时近似保持读取顺序 | 未实现全局严格顺序 |
| 事务消息 | 事务提交后再发消息 | 仅有 after-commit，不是真正事务消息 |
| 死信队列 | 数据库任务状态 `DEAD_LETTER` | 有死信状态，无独立 DLQ Stream |
| 延迟/退避重试 | 失败后立即重投 | 未实现 |
| 消息持久性 | Redis 数据卷；Compose 未显式配置 AOF、主从或集群 | 不能宣称零丢失 |
| 堆积监控 | 生命周期 Counter，RedisService 可取 Stream 长度 | 尚无完整 lag/PEL age Gauge |

### 五条业务管道

| 场景 | Stream | Consumer Group |
|---|---|---|
| 知识库向量化 | `knowledgebase:vectorize:stream` | `vectorize-group` |
| 简历分析 | `resume:analyze:stream` | `analyze-group` |
| 文字面试评估 | `interview:evaluate:stream` | `evaluate-group` |
| 语音面试评估 | `voice:evaluate:stream` | `voice-evaluate-group` |
| 面试提醒 | `interview-schedule:reminder:stream` | `interview-reminder-group` |

通用参数：

- 每批最多读取 10 条。
- 阻塞读取超时 1 秒。
- 每个消费者 Bean 使用一个单线程执行消费循环。
- Stream 使用近似 `MAXLEN 1000` 裁剪。
- pending 空闲 60 秒后允许恢复，每条 Stream 每次最多认领 10 条。
- 每个消费者名为“业务前缀 + 8 位随机 UUID”，多实例之间名称不同。

## 三、负载均衡

### Q1：项目中的 Redis 消息如何负载均衡？

同一个 Stream 的多个消费者加入同一个 Consumer Group。各消费者都用
`XREADGROUP ... >` 拉取从未投递过的新消息，Redis 会把一条新消息交给组内某一个消费者，
因此组内是竞争消费，不会让每个消费者都收到同一条消息。

本项目每个应用实例会为每条业务管道创建一个单线程消费者。如果部署 3 个后端实例，简历分析
组中就会有 3 个不同 consumerName，消息会分摊给这 3 个实例，实现水平扩展。

### Q2：Redis Stream 是轮询平均分配吗？

不能回答成严格 Round Robin。它是拉模式，消息通常交给当前发起读取且能够拿到新消息的消费者。
谁处理得快、谁更早再次阻塞读取，往往就会拿到更多消息。再加上本项目单次最多读取 10 条，
负载分配可能以批次为粒度，并不保证数量绝对平均。

更准确的说法是：**Consumer Group 提供竞争消费和自然的工作负载分摊，但不保证严格公平。**

### Q3：Consumer Group 是广播还是负载均衡？

- 同一个 Group 内：竞争消费，一条消息只分给组内一个消费者。
- 不同 Group 之间：每个 Group 都维护自己的消费进度，同一条消息可被每个 Group 分别消费一次。

本项目每条 Stream 目前只有一个业务 Group，所以使用的是任务队列语义，不是广播订阅语义。

### Q4：当前方案如何扩容？

最直接的方法是增加应用实例，同组中会出现更多消费者。每个实例、每条管道仍只有一个消费线程，
所以单管道理论并发度约等于应用实例数。

生产级还可以把每条管道的并发数配置化，或把向量化、LLM 评估、邮件提醒拆成独立 Worker，避免
为了扩容某一条慢管道而被迫扩容全部业务消费者。

### Q5：为什么不直接把单消费者改成很多线程？

增加线程能提高吞吐，但会带来外部 API 并发、数据库连接、内存和顺序性问题。知识库向量化与
LLM 评估还受 Provider QPS 限制，盲目加线程可能触发 429、超时和重试风暴。

合理做法是按任务类型分别配置并发度，并配合限流、隔离线程池、超时、熔断和队列 lag 监控。

## 四、重复消费与幂等

### Q1：Redis Stream 会重复消费吗？

会。消费者处理完业务但在 ACK 前崩溃，消息仍留在 PEL；恢复服务重新认领后会再次执行。
ACK 网络失败也会产生同样效果。因此当前系统是 at-least-once，而不是 exactly-once。

### Q2：项目如何防止重复提交和重复入队？

Producer 先调用 `createOrReusePendingTask()`：

1. 查询相同 `taskType + businessId` 是否已有 `PENDING/PROCESSING` 任务。
2. 有则复用，`created=false`，不再发送消息。
3. 没有则创建任务。
4. 数据库 `active_key` 有唯一索引，格式为 `taskType:businessId`，作为并发兜底。

需要诚实补充：当前代码没有显式捕获唯一索引冲突后回查已有任务。两个请求真正并发创建时，
数据库可以阻止双活任务，但其中一个请求仍可能收到唯一约束异常。生产级应捕获冲突并回查复用。

### Q3：项目如何防止重复执行产生重复结果？

消费者在调用昂贵业务前检查结果：

- 简历分析：状态为 `COMPLETED` 且最新分析结果存在时跳过。
- 知识库向量化：状态为 `COMPLETED` 且 `chunkCount > 0` 时跳过。
- 文字面试评估：会话已是 `EVALUATED` 且总分存在时跳过。
- 语音面试评估：该会话的评估记录存在时跳过，并且 `session_id` 有唯一约束。

这是“结果存在则跳过”的业务幂等，而不是按 messageId 建消费幂等表。

### Q4：当前幂等机制有没有竞态条件？

有。两个消费者可能同时通过“结果不存在”的检查，然后都调用 LLM 或 Embedding。检查与写结果并非
一个带条件的原子操作。尤其 pending 超时被错误认领时，容易产生并发重复执行。

改进可以采用：

1. `taskId` 或业务 ID 的幂等消费表，并用唯一键原子插入。
2. 状态条件更新，例如只有 `PENDING -> PROCESSING` 更新成功的消费者才能执行。
3. JPA `@Version` 乐观锁。
4. 最终结果表业务唯一键。
5. 保存结果和任务状态放进同一数据库事务。

### Q5：邮件提醒能保证不重复发送吗？

当前不能完全保证。SMTP 发送成功后，如果进程在 `markSent()` 或 ACK 之前崩溃，pending 恢复会再次
发送邮件。数据库中的提醒计划唯一键只能防止重复创建计划，不能证明外部邮件已经只发送一次。

生产级可以为每个提醒生成稳定的幂等键并让邮件服务支持去重；如果外部服务不支持幂等，则使用
“发送记录 + 状态机 + 对账”降低重复概率，但跨 SMTP 和数据库很难做到严格 exactly-once。

### Q6：如何回答 exactly-once？

不要说项目已经实现 exactly-once。更可靠的回答是：

> Redis Stream 提供至少一次处理语义。严格 exactly-once 涉及 Redis、数据库和外部 LLM/SMTP，
> 很难靠消息中间件单独保证。本项目通过任务唯一键、结果存在检查和业务唯一约束实现效果幂等；
> 后续会用条件状态更新、幂等消费表和事务化结果写入进一步加强。

## 五、顺序消费

### Q1：Redis Stream 能保证消息顺序吗？

Stream 中的新消息按 Stream ID 排列，读取新消息时能获得写入顺序。但“读取有序”不等于“业务完成
有序”。本项目只有在单应用实例、单消费者线程、没有 pending 恢复和失败重试时，才能近似按读取顺序
串行完成。

### Q2：为什么项目不能保证全局严格顺序？

1. 多实例时，同组不同消费者可以并行处理不同消息。
2. 快任务可能先于早到的慢任务完成。
3. 失败任务会重新 `XADD` 到 Stream 尾部，后来的正常任务可能先完成。
4. `XAUTOCLAIM` 会把旧 pending 消息插入当前处理过程。
5. 五条不同 Stream 之间完全没有全局顺序。

所以当前保证的是单 Stream 的写入顺序和单线程局部处理顺序，不是全局完成顺序。

### Q3：本项目真的需要严格顺序吗？

大多数任务按不同 `resumeId/kbId/sessionId` 相互独立，不需要全局严格顺序。真正需要的是同一个业务
对象不要并发执行两个活跃任务，项目用 `activeKey` 处理了提交侧的一部分问题。

如果未来同一会话存在必须依次执行的事件，应保证同一业务键进入同一分区，并增加业务序号校验。

### Q4：如何设计“同一业务 ID 有序，不同 ID 并行”？

可以对 `businessId` 做一致性哈希，将消息路由到固定分片 Stream，例如
`interview:evaluate:stream:{0..N-1}`。每个分片由一个串行消费者处理，相同 businessId 始终进入
同一分片，不同分片可并行。

同时在业务表保存 `sequence/version`，消费者只接受期望版本，防止重试、恢复或人工补发破坏顺序。

## 六、事务消息与数据一致性

### Q1：项目如何处理“数据库提交”和“消息发送”的顺序？

四类 AI Producer 使用 `TransactionAfterCommitExecutor`。如果当前有数据库事务，就在事务提交成功后
执行 `XADD`；事务回滚时不会发消息。这样避免业务记录尚未提交，消费者已经拿到 ID 却查不到数据。

面试提醒的流程不同：调度器先在数据库事务中把到期提醒认领为 `QUEUED`，再逐条发送到 Stream。

### Q2：afterCommit 是事务消息吗？

不是。它只保证时序，不保证 PostgreSQL 与 Redis 原子提交。

典型故障窗口是：

```text
数据库提交成功
  -> JVM 在 afterCommit 执行 XADD 前崩溃
  -> 数据库中留下 PENDING 任务，但 Redis 没有消息
```

另外，`XADD` 成功后到 `markEnqueued()` 写回 messageId 之间也存在崩溃窗口，可能出现 Redis 有消息、
任务表没有 messageId。

### Q3：当前实现做了哪些补偿？

- `XADD` 正常抛异常时，Producer 的 `onSendFailed()` 会把业务状态和任务状态标为失败。
- 任务表保留 `streamKey`、`payloadJson`、`businessId` 和错误，可通过人工重试重新投递。
- 消费者侧使用幂等降低重复投递的副作用。

但进程突然退出时不会执行异常回调，因此仍有任务卡在 `PENDING + messageId=null` 的可能。

### Q4：真正的事务消息应该怎么做？

推荐 Transactional Outbox：

```text
同一个 PostgreSQL 事务：
  保存业务数据
  保存 async_task
  保存 outbox_event(NEW)
提交
  -> 后台 Publisher 扫描 NEW 事件
  -> XADD Redis Stream
  -> 标记 outbox_event=PUBLISHED
```

Publisher 应使用 `SELECT ... FOR UPDATE SKIP LOCKED` 支持多实例并发，投递失败时带退避重试。若 Publisher
在 `XADD` 成功、标记 PUBLISHED 前崩溃，仍可能重复投递，因此消费者幂等依然必要。

Redis Lua 或 Redis 事务只能保证 Redis 内部命令原子性，不能和 PostgreSQL 做跨资源原子事务。

### Q5：手动重试方法有什么事务边界问题？

当前 `AsyncTaskService.retryTask()` 是 `@Transactional` 方法，但内部直接调用 Redis `XADD`。如果 Redis
写入成功而数据库事务随后回滚，就会出现孤儿消息；同时外部 IO 位于数据库事务中，也会延长连接占用。

更合理的方式是先在短事务中写 Outbox/重试请求，事务外由 Publisher 投递。

## 七、消息丢失

### Q1：消息可能在哪些阶段丢失？

应按链路分阶段回答：

| 阶段 | 风险 | 当前处理 |
|---|---|---|
| DB 提交后、XADD 前 | JVM 崩溃，afterCommit 未执行 | 无自动扫描补发，需 Outbox |
| XADD 时 | Redis 不可用 | 捕获异常并标 `FAILED`，可人工重试 |
| XADD 后、写 messageId 前 | Redis 有消息，DB 记录不完整 | 消费仍可能进行，但可观测性不一致 |
| Redis 落盘前 | Redis 进程/宿主机故障 | Compose 未显式启用强持久化/副本，不能保证不丢 |
| Stream 裁剪 | 堆积超过近似 1000 条，旧消息可能被删除 | 当前有真实风险 |
| 消费处理中 | 消费者崩溃 | 未 ACK 消息进入 PEL，定时 XAUTOCLAIM |
| ACK 失败 | 消息继续留在 PEL | 可能重复，不是丢失；依赖幂等 |
| 脏消息 | 缺少字段时直接 ACK | 有意丢弃，但没有隔离队列 |
| 重试切换 | 新消息重投与旧消息 ACK 非原子 | 可能重复，也可能只留下死信状态 |

### Q2：为什么 `MAXLEN 1000` 可能导致丢消息？

`XADD` 每次都使用近似长度裁剪。Redis 会从 Stream 头部删除旧 entry，而不会因为它还未被某个
Consumer Group 成功处理就自动保留。高峰时只要生产速度远高于消费速度，未消费或 pending 的旧消息
都可能失去消息体。

Redis 7 的 `XAUTOCLAIM` 遇到“PEL 仍引用、Stream entry 已被裁剪”的记录时会清理这类 PEL 引用，
但已经无法恢复消息内容。

生产级应按峰值吞吐、最长故障恢复时间和消息保留目标计算容量；重要任务不应只用固定 1000 条保护。
可以配置更大长度、按时间保留、只裁剪所有 Group 已确认的消息（取决于 Redis 版本和策略），并用数据库
Outbox 作为可重放真源。

### Q3：ACK 后消息是否从 Stream 删除？

不会。`XACK` 只会从该 Consumer Group 的 PEL 移除 pending 引用，Stream entry 本身仍然存在，直到被
`XTRIM/XDEL` 删除。因此 `XLEN` 是保留 entry 数，不等于未消费积压数。

### Q4：Redis 持久化怎么回答？

当前 Compose 挂载了 `/data` 数据卷，但没有显式配置 AOF、主从复制或 Redis Cluster。数据卷不等于每条
消息都已经持久化；具体耐久性取决于 Redis 的 RDB/AOF 配置和故障类型。

生产级可按业务 RPO 选择 AOF `everysec/always`、主从复制、Sentinel/Cluster 和跨可用区备份。但即使
启用 AOF everysec，也不能宣称绝对零丢失；Outbox 才能提供从数据库重新投递的业务兜底。

### Q5：如何发现 DB 有任务但 Redis 没消息？

增加 Reconciliation Job，定时扫描：

- `status=PENDING` 且 `messageId is null` 且创建时间超过阈值；
- `PENDING/PROCESSING` 持续超过任务超时；
- Outbox 长时间处于 `NEW/RETRY`；
- 数据库任务数量与 Redis Group lag/PEL 长期不一致。

确认任务仍应执行后再补发，并使用 taskId 幂等，不能盲目创建新任务。

## 八、消费确认、Pending 和恢复

### Q1：PEL 是什么？

消费者使用 `XREADGROUP` 读取消息后，Redis 会把消息 ID 记录到该 Group 的 Pending Entries List。
PEL 会记录消息所有者、空闲时间和投递次数。成功执行 `XACK` 后，该 Group 的 PEL 引用被删除。

### Q2：为什么主循环只读取 never-delivered 消息？

主循环对应 `XREADGROUP ... >`，只负责新消息；已投递但未 ACK 的消息留在 PEL，由专门的恢复服务
处理。这样普通消费和故障恢复职责清晰。

### Q3：项目如何恢复消费者宕机留下的消息？

`PendingMessageRecoveryService` 在应用启动完成后执行一次，以后每 60 秒执行一次。它遍历所有
`AbstractStreamConsumer` Bean，调用 `XAUTOCLAIM` 将空闲超过 60 秒的消息认领到当前 consumerName，
然后直接重新走 `processMessage()`。

### Q4：当前 pending 恢复最大的风险是什么？

**60 秒 idle 并不代表消费者死亡，只代表这条 pending 消息在 60 秒内没有重新投递或 claim。**

LLM、文件解析和大文档向量化完全可能正常执行超过 60 秒。此时定时任务会把仍在执行的消息再次
claim 并直接处理。即使只有一个应用实例，恢复方法也运行在调度线程上，可能与原来的单线程消费循环
并发执行同一任务；多实例时风险更明显。

改进优先级很高：

1. pending 超时必须大于该任务类型的合理 P99 执行时间，不同管道分别配置。
2. 长任务增加租约/心跳，或拆成较短步骤。
3. `markProcessing` 使用原子状态条件/租约令牌，只有成功取得执行权的 Worker 才执行。
4. 当前封装每次都从最小 ID 开始，并只返回消息、丢弃 `XAUTOCLAIM` 的下一页游标；PEL 很大时，
   前面的未到期记录可能让后面的过期记录长期扫描不到，应保存并续传游标直到返回 `0-0`。
5. 当前恢复方法在调度线程中直接执行业务，单个慢任务会阻塞其他 Stream 的恢复；应把认领和执行解耦。
6. 恢复调度可加分布式协调，但不能代替业务幂等。
7. 记录并检查 PEL delivery count，超过阈值进入隔离流程。

### Q5：为什么 ACK 放在业务完成后？

如果读取后立即 ACK，消费者在业务处理中崩溃时 Redis 已经认为消息成功，无法通过 PEL 恢复。
业务完成后 ACK 才能提供至少一次处理。

### Q6：ACK 失败怎么办？

当前 `ackMessage()` 捕获异常并记录日志，消息留在 PEL，后续会被恢复并重复处理。因此 ACK 失败主要
带来重复执行，不是消息丢失，最终依赖消费者幂等。

生产级还应监控 ACK 失败数、最老 PEL age 和重复跳过数，必要时对持续失败的 ACK 告警。

## 九、消费重试与死信

### Q1：项目的自动重试流程是什么？

```text
初始消息 retryCount=0
  -> 业务异常
  -> XADD 新消息 retryCount=1
  -> ACK 旧消息
  -> 再失败则 retryCount=2
  -> 再失败则 retryCount=3
  -> retryCount=3 的执行仍失败
  -> 标记 DEAD_LETTER/FAILED
  -> ACK 当前消息
```

所以“最多重试 3 次”意味着初次执行 1 次，加重试 3 次，最多共 4 次。

### Q2：为什么业务失败时重新 XADD，而不是一直保留旧 pending？

当前设计把两类故障分开：

- 可预期业务失败：重新 XADD，显式携带新的 retryCount。
- 消费者突然崩溃：旧消息保留在 PEL，由 XAUTOCLAIM 恢复。

这样重试次数和任务状态更容易管理，但“新 XADD + 数据库 markRetrying + 旧 XACK”跨 Redis 与
PostgreSQL，不是原子操作。

### Q3：重投与 ACK 非原子会发生什么？

- 新消息已 XADD，但旧消息 ACK 失败：旧 pending 和新消息都可能执行，造成重复。
- 新消息已 XADD，但 `markRetrying` 失败：DB 状态可能被标死信，新消息却仍会被消费。
- 新消息 XADD 失败：四类 AI Consumer 会捕获并把任务标为 `DEAD_LETTER`，父模板随后 ACK 旧消息；
  自动链路停止，但任务台账仍在，可人工重试。
- 面试提醒的 `retryMessage()` 没有内部吞掉异常；XADD 失败会使旧消息未 ACK，后续由 PEL 恢复。

这说明当前链路追求的是可恢复与可运维，不是原子切换。

### Q4：当前有没有真正的死信队列？

没有独立的 Redis DLQ Stream。四类统一异步任务在自动重试耗尽或重投失败时，把数据库状态设为
`DEAD_LETTER`；业务实体通常显示 `FAILED`。面试提醒直接使用 `FAILED`。

所以准确说法是“实现了死信状态和人工重试入口”，不是“实现了完整死信队列”。

### Q5：为什么需要独立 DLQ？

独立 DLQ 可以保留原消息、错误类型、堆栈摘要、首次/最后失败时间、投递次数和版本，支持审计、告警、
批量修复与安全回放。当前数据库任务表已具备一部分控制面信息，但没有完整的每次重试历史。

### Q6：当前重试策略有什么问题？

失败后立即重投，没有指数退避、随机抖动和错误分类。如果 LLM Provider 或 Redis 下游持续故障，大量
任务会快速执行 4 次，形成重试风暴。

建议：

1. 区分可重试异常：超时、429、临时 5xx。
2. 不可重试异常直接失败：参数错误、文件损坏、权限错误。
3. 使用指数退避，例如 10 秒、30 秒、2 分钟，并加 jitter。
4. 利用已有但尚未实际使用的 `nextRetryAt` 字段。
5. 用 ZSET 延迟队列、Outbox 调度器或专业 MQ 的延迟机制到期后再写 Stream。
6. 配合 Provider 限流和熔断，避免重试放大故障。

### Q7：手动重试和自动重试有什么区别？

| 维度 | 自动重试 | 手动重试 |
|---|---|---|
| 触发方 | Consumer | 管理 API/人工 |
| 允许状态 | 正在处理且未达到上限 | `FAILED/CANCELLED/DEAD_LETTER` |
| retryCount | 每次加 1 | 重置为 0 |
| 目的 | 恢复临时故障 | 故障修复后的人工恢复 |

人工重试前应先按 taskType 和 lastError 聚合定位根因，不能在依赖仍故障时全量重试。

## 十、消息堆积

### Q1：如何判断 Stream 是否堆积？

不能只看 `XLEN`，因为 ACK 后的 entry 仍留在 Stream。至少要组合观察：

- `XINFO GROUPS <stream>`：Group 的 lag、last-delivered-id。
- `XPENDING <stream> <group>`：pending 数和消费者分布。
- `XPENDING ... IDLE ...`：最老 pending 的 idle time。
- `XINFO CONSUMERS`：消费者 pending、idle/inactive。
- 数据库 `PENDING/PROCESSING` 数量与最老任务年龄。
- 每分钟 created/completed/failed/retry 速率。
- 单任务处理 P95/P99、LLM 429/超时率和消费者存活状态。

当前项目只有 created、processing、completed、failed、retry Counter；`processing` 是累计开始次数，
不是当前并发数。完整的 Stream lag、PEL age 和处理耗时监控仍需补充。

### Q2：消息为什么会堆积？

1. 生产速度长期大于消费速度。
2. 单消费者单线程吞吐有限。
3. LLM、Embedding、对象存储或数据库变慢。
4. Redis/网络异常导致消费者循环失败。
5. 毒消息反复重试。
6. Provider 限流导致 429 和重试风暴。
7. 大文档任务独占消费者，产生队头阻塞。

### Q3：发生堆积后如何处理？

先保护系统，再扩容和恢复：

1. 确认消费者是否存活，Redis Group/PEL 是否正常。
2. 按错误类型判断是消费能力不足还是下游故障。
3. 下游故障时先限流/暂停非核心生产，避免继续放大。
4. 修复依赖后按管道增加 Worker，并控制 LLM/Embedding QPS。
5. 隔离毒消息，不让其持续立即重试。
6. 大小任务拆流，避免大文档阻塞小任务。
7. 评估并立即提高或关闭 `MAXLEN 1000` 裁剪，防止积压还没处理就被删。
8. 恢复期间持续观察 lag、oldest age、成功率和下游饱和度，不能一次性无限扩容。

### Q4：如何估算需要多少消费者？

近似公式：

```text
单消费者吞吐 = 1 / 平均处理时间
所需并发度 >= 峰值生产速率 × 平均处理时间 × 安全系数
```

例如平均每个任务 10 秒、峰值每秒 2 个任务，理论上至少需要 20 个并发，再考虑长尾、重试和下游
限流增加安全系数。但 AI 任务应使用 P95/P99 与 Provider QPS 校准，不能只看平均值。

### Q5：`BATCH_SIZE=10` 是否代表并行处理 10 条？

不是。当前 `streamConsumeMessages()` 取回最多 10 条后，通过 `for` 循环逐条调用处理器；每个消费者
又是单线程，所以同一批仍串行处理。批量读取减少 Redis 往返，但不会自动提高业务并行度。

## 十一、故障场景追问

### Q1：消费者业务成功后、ACK 前宕机怎么办？

消息留在 PEL，超过恢复阈值后被 XAUTOCLAIM 再次执行。系统依靠业务幂等跳过已经存在的结果。

### Q2：消费者刚读到消息就宕机怎么办？

同样进入 PEL；恢复服务认领后重新处理。这正是 Stream 相比简单 Redis List 的主要优势之一。

### Q3：Redis 整体宕机怎么办？

生产者 XADD 失败会把任务标成失败；消费者读取和 pending 恢复会记录异常并继续循环。Redis 恢复后
可以人工重试失败任务。若 Redis 丢失了已经成功 XADD 的数据，仅靠当前任务表无法自动重建全部消息，
需要 Outbox/Reconciliation Job。

### Q4：消息格式错误怎么办？

必要字段缺失时，`parsePayload()` 返回 null，父模板直接 ACK 丢弃。提醒消费者会捕获非法 reminderId；
其他消费者中的 `Long.parseLong()` 仍可能抛 `NumberFormatException`，异常发生在内部业务 try-catch 之前，
当前消息不会被 ACK，会留在 PEL 并被重复恢复，可能形成毒消息。

应统一做协议版本和完整格式校验，将永久无效消息写入 quarantine/DLQ 后 ACK，而不是无限恢复。

### Q5：任务被手动取消后还会继续执行吗？

如果取消发生在 `markProcessing()` 之前，任务服务会检测 `CANCELLED` 并停止。如果外部 LLM 已经开始，
当前实现无法强制中断，请求仍可能完成，且保存结果前也未统一再次检查取消状态。

生产级应在外部调用前、调用后、保存结果前和长循环批次之间增加协作式取消检查，并使用条件更新避免
`CANCELLED` 被后续 `COMPLETED` 覆盖。

### Q6：为什么实体删除后要 ACK？

业务实体已删除属于不可恢复错误，重复执行不会成功。消费者抛 `AsyncTaskCancelledException`，父模板
标记取消后 ACK，避免无效消息长期占据 PEL 并不断重试。

### Q7：消费者组创建失败还继续消费合理吗？

`createStreamGroup()` 对 BUSYGROUP 视为组已存在，其余异常记录警告；主循环仍会启动并持续尝试消费。
短暂 Redis 故障恢复后有机会继续，但如果组确实没有创建成功，读取会持续报错。生产级应增加就绪探针、
重试退避和告警，而不是只依赖日志。

## 十二、方案选型

### Q1：为什么项目选择 Redis Stream，而不是 `@Async`？

`@Async` 是进程内线程池，应用重启后内存任务无法恢复，也缺少 Consumer Group、ACK、PEL 和跨实例
工作分摊。Redis Stream 更适合需要任务状态、失败恢复和水平扩展的后台 AI 任务。

### Q2：为什么没有直接上 Kafka 或 RabbitMQ？

当前是单体项目，已经依赖 Redis，任务吞吐中等，主要需求是解耦慢任务、消费者组、ACK 和恢复。
Redis Stream 的接入与运维成本更低。

如果未来需要复杂路由、延迟消息、成熟 DLQ 和细粒度重试，RabbitMQ 更合适；如果需要超高吞吐、
分区顺序、事件回放和多下游订阅，Kafka 更合适。迁移时数据库任务控制面可以保留，只替换消息运输层。

### Q3：Redis Stream 和 Kafka 的顺序语义怎么比较？

Kafka 在单 Partition 内提供有序日志，可以通过 key 保证同一业务键进入同一 Partition；多个 Partition
之间无全局顺序。Redis Stream 有有序 entry，但 Consumer Group 没有直接等价的 key 分区路由，想实现
“同 key 有序、不同 key 并行”通常要自己做多个 Stream 分片和路由。

## 十三、面试官高压追问速答

### 1. 你们项目能保证消息不丢吗？

不能承诺绝对不丢。消费阶段有 PEL 和 XAUTOCLAIM；发送异常会落失败状态；但 DB 提交到 XADD 之间、
Redis 持久化以及 `MAXLEN 1000` 裁剪仍有丢失窗口。生产级会用 Outbox、AOF/副本、容量化保留策略和
对账补发形成闭环。

### 2. 你们项目能保证不重复吗？

不能保证消息只投递一次，语义是至少一次。通过 activeKey 防重复活跃任务，并在消费者中检查结果是否
已存在，实现业务效果幂等。邮件这种外部副作用仍需幂等键或发送对账加强。

### 3. 你们项目能保证顺序吗？

单实例单线程且无失败时能保持局部处理顺序；多实例、重试和 pending 恢复都会破坏完成顺序。当前业务
任务大多相互独立，不依赖全局顺序。如果同一业务键必须有序，会做哈希分片和业务序号校验。

### 4. 你们的事务消息怎么实现？

当前只是事务提交后投递，解决“消费者先于数据提交”的时序问题，不是原子事务消息。严格方案是同库
Transactional Outbox，由可靠 Publisher 投递，并保留消费者幂等。

### 5. 一条消息到底执行几次？

正常 1 次；失败时最多再重试 3 次，总共最多 4 次。若出现 ACK 失败或 pending 恢复，还可能额外重复，
所以不能把 4 次理解成系统层面的绝对上限。

### 6. 为什么重试后要 ACK 旧消息？

因为已经产生了携带新 retryCount 的新消息，旧消息继续留在 PEL 会造成双重重试。缺点是重投与 ACK
不是原子操作，因此仍需幂等和任务台账兜底。

### 7. `XLEN` 能表示积压吗？

不能。ACK 不删除 Stream entry，`XLEN` 包含已处理历史。应看 Consumer Group lag、PEL pending、最老
pending age，并结合数据库 PENDING 任务年龄。

### 8. 为什么 60 秒后 XAUTOCLAIM 有问题？

因为 idle 不是执行心跳。AI/向量任务正常运行超过 60 秒也可能被再次认领，产生并发重复。应该按任务
P99 设置租约，增加心跳或执行令牌，并强化条件状态更新。

### 9. 你们有死信队列吗？

当前有数据库 `DEAD_LETTER` 状态和人工重试入口，但没有独立 DLQ Stream。不能把死信状态说成完整
死信队列。

### 10. 你认为当前最优先的三个改进是什么？

1. 引入 Transactional Outbox 和对账补发，关闭 DB 到 Redis 的丢失窗口。
2. 按任务 P99 重做 pending 租约/心跳，避免 60 秒误认领导致并发重复。
3. 将固定 `MAXLEN 1000`、重试间隔和消费者并发配置化，补齐 lag、PEL age、处理耗时和死信告警。

## 十四、推荐的完整改进路线

### P0：先消除明显可靠性风险

1. 将 `STREAM_MAX_LEN`、pending timeout、恢复批次和消费者并发配置化。
2. pending timeout 按任务类型设置，并高于合理 P99；增加执行租约或心跳。
3. 修复 payload 数值解析，将毒消息隔离后 ACK。
4. 补 Redis Group lag、pending count、oldest pending age 和任务处理耗时监控。
5. 为邮件提醒增加外部副作用幂等策略。

### P1：建立可靠投递闭环

1. 引入 Transactional Outbox。
2. Publisher 支持退避、并发认领、幂等投递和告警。
3. 增加 Reconciliation Job，扫描孤立 PENDING/PROCESSING 任务。
4. 建立独立 DLQ/quarantine，记录原消息和失败历史。
5. 对 Redis 开启符合 RPO 的持久化、高可用和备份策略。

### P2：提升吞吐和治理能力

1. 按业务管道独立配置 Worker 并发与限流。
2. 大小任务分流，减少队头阻塞。
3. 可重试异常分类、指数退避、jitter、熔断。
4. 结果保存使用业务唯一键、条件状态更新和乐观锁。
5. 有严格顺序需求时按 businessId 哈希分片并校验 sequence/version。

## 十五、排障命令速查

```bash
# 查看 Stream 长度；注意它不是准确积压量
redis-cli XLEN resume:analyze:stream

# 查看消费者组的 last-delivered-id、pending 和 lag
redis-cli XINFO GROUPS resume:analyze:stream

# 查看某组消费者状态
redis-cli XINFO CONSUMERS resume:analyze:stream analyze-group

# 查看 PEL 摘要
redis-cli XPENDING resume:analyze:stream analyze-group

# 查看详细 pending：消息 ID、owner、idle、delivery count
redis-cli XPENDING resume:analyze:stream analyze-group - + 20

# 查看消息内容
redis-cli XRANGE resume:analyze:stream - + COUNT 20
```

排查顺序：

```text
查 async_tasks 状态/messageId/lastError
  -> 查 Stream 与 Group 是否存在
  -> 查 lag 和最老未投递消息
  -> 查 PEL owner/idle/delivery count
  -> 查消费者线程和应用实例是否存活
  -> 查 LLM、Embedding、对象存储、数据库依赖
  -> 修复后小批量重试并观察指标
```

## 十六、代码证据索引

- 通用消费模板：`app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java`
- 通用生产模板：`app/src/main/java/interview/guide/common/async/AbstractStreamProducer.java`
- Redis Stream 封装：`app/src/main/java/interview/guide/infrastructure/redis/RedisService.java`
- Stream 常量：`app/src/main/java/interview/guide/common/constant/AsyncTaskStreamConstants.java`
- Pending 恢复：`app/src/main/java/interview/guide/modules/asynctask/service/PendingMessageRecoveryService.java`
- 任务状态机：`app/src/main/java/interview/guide/modules/asynctask/service/AsyncTaskService.java`
- 任务唯一索引：`app/src/main/java/interview/guide/modules/asynctask/model/AsyncTaskEntity.java`
- 事务后投递：`app/src/main/java/interview/guide/common/transaction/TransactionAfterCommitExecutor.java`
- 各业务生产/消费：`app/src/main/java/interview/guide/modules/*/listener/`
- 面试提醒生产/消费：`app/src/main/java/interview/guide/modules/interviewschedule/reminder/listener/`
- Redis 容器配置：`docker-compose.yml`

## 十七、Redis 官方资料

- XREADGROUP：https://redis.io/docs/latest/commands/xreadgroup/
- XPENDING：https://redis.io/docs/latest/commands/xpending/
- XAUTOCLAIM：https://redis.io/docs/latest/commands/xautoclaim/
- XTRIM：https://redis.io/docs/latest/commands/xtrim/
