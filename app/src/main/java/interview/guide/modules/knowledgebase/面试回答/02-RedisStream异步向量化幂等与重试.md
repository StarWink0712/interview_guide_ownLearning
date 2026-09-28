# Redis Stream 异步向量化、幂等与重试

## 1. 模块基础讲解

`VectorizeStreamProducer` 和 `VectorizeStreamConsumer` 继承公共 Stream 模板。生产者先通过
`AsyncTaskService.createOrReusePendingTask` 建立任务，再在事务提交后发送 `taskId/kbId/retryCount`；消费者组
读取消息，标记 PROCESSING，下载解析文件，执行向量化，最后标记 COMPLETED 并 ACK。失败最多重试三次，超过
阈值进入 DEAD_LETTER/FAILED；知识库被删除则抛 `AsyncTaskCancelledException` 并取消任务。

这体现了模板方法模式：公共父类统一消费循环、ACK、重试和指标，子类只实现 key、group、payload 解析与业务动作。
任务表提供可查询状态，Redis Stream 提供投递与消费者组，两者通过 taskId 关联。

## 2. 基础面试问题

**问题：Redis Stream 相比 List 有什么优势？**

回答：Stream 有消息 ID、消费者组、Pending Entries List、ACK 和历史读取，能够知道消息由谁领取、是否确认，并支持
故障消费者消息恢复；List 的 BLPOP 更像工作队列，不原生保存这些消费状态。

**问题：为什么消息不直接放全文？**

回答：消息只做事件通知，正文存对象存储，可避免 Redis 内存膨胀和大消息复制，也便于重试时重新获取源文件。
代价是消费者增加下载和解析，并依赖对象仍存在。

**问题：ACK 应该在什么时候执行？**

回答：业务处理和状态持久化成功后 ACK。先 ACK 再处理会在进程崩溃时丢任务；永不 ACK 会积压 PEL。重试重新入队
时还要确保旧消息被正确 ACK，避免同一次失败产生多个并行副本。

**问题：为什么消费语义通常是 at-least-once？**

回答：业务成功后、ACK 前进程可能崩溃，消息会再次投递。分布式系统很难让业务写入和 Redis ACK 原子提交，因此
可靠系统接受重复投递，并让业务处理幂等。

## 3. 进阶面试问题

**问题：当前幂等判断为什么实际无效？**

回答：消费者只在 `vectorStatus == COMPLETED && chunkCount > 0` 时跳过，但向量服务从未回写 chunkCount，字段
默认始终为 0。因此重复消息仍会先删旧向量再全部重建，这是明确的代码缺陷而非理论风险。

**问题：createOrReusePendingTask 能完全防重复吗？**

回答：只能减少活跃任务重复创建，不能消除 ACK 窗口重投、手动重试、超时抢占或历史消息重放。最终幂等必须以
`taskId + kbId + vectorVersion` 在业务数据层判定，而不是只依赖“入队时查一次”。

**问题：重试是否应该统一固定三次？**

回答：不应。429、网络超时适合指数退避加 jitter；格式损坏、空文本属于不可重试；数据库锁冲突可快速重试；
模型配额耗尽可能要延迟分钟级。当前统一重入 Stream 容易造成故障期间重试风暴。

**问题：消费者与删除并发有什么问题？**

回答：消费者只在开始时确认知识库存在。检查后若删除线程移除元数据和旧向量，消费者仍可能随后写入新向量，
形成没有知识库记录的孤儿向量。需要状态/version 条件写入或删除墓碑。

## 4. 技术延伸问题

**问题：Redis Stream 如何恢复卡在 PEL 的消息？**

回答：监控 pending idle time，使用 `XAUTOCLAIM` 或等价 Redisson API 把超过租约的消息转移给健康消费者；恢复前
检查任务 lease/version，避免原消费者仍在运行时双重处理。

**问题：Kafka 与 Redis Stream 如何选择？**

回答：Redis Stream 部署简单、低延迟，适合已有 Redis 和中等规模任务；Kafka 擅长高吞吐、长期保留、分区重放和
事件生态，但运维更重。此模块任务量通常不要求 Kafka，可靠性设计比中间件品牌更关键。

**问题：数据库任务表能否替代消息队列？**

回答：可以用 `SELECT ... FOR UPDATE SKIP LOCKED` 领取任务，天然与业务记录同事务，适合中低吞吐；缺点是轮询、
数据库压力和事件生态弱。也可用 DB outbox + Redis Stream 组合兼顾提交可靠性和消费效率。

## 5. 改进方案问题

**问题：如何实现真正幂等的向量化？**

回答：知识库增加 `desiredVectorVersion/currentVectorVersion`；任务固定携带目标版本；chunk 写入 metadata 含版本；
已完成同版本直接 ACK；旧版本任务检测到落后则 CANCEL；成功切换 currentVersion 后异步清理旧版本。

**问题：如何设计重试和死信？**

回答：错误分类为 RETRYABLE/NON_RETRYABLE；记录 nextRetryAt、attempt、lastErrorCode；指数退避；超过次数进入可查询
死信表；管理端支持修复配置后重放。重放必须创建新执行 attempt，但保留原 task correlation。

**问题：需要监控哪些指标？**

回答：Stream lag、PEL 数与最大 idle、任务排队/处理耗时、成功率、各错误码重试率、死信数、重复跳过数、每任务
chunk 数、Embedding 批次数和消费者心跳。只监控 FAILED 数无法识别队列完全不消费。

## 6. 故障处理问题

**问题：Stream 有消息但消费者不处理，如何排查？**

回答：检查消费者线程是否启动、group 是否存在、读取的是 `>` 还是 pending；查看 PEL owner/idle；确认 Redisson
连接池和 Stream key；验证 payload 字段和 Long 解析；再检查父类消费循环是否因未捕获异常退出。

**问题：同一 kbId 被反复向量化怎么办？**

回答：查 AsyncTask 是否存在多个活跃任务、Stream 是否重复消息、ACK 是否失败；随后检查 chunkCount 始终为 0 的
已知缺陷。先修复完成时原子回写 chunkCount/version，再清理重复任务与多余向量版本。

**问题：大量任务同时 429 怎么止损？**

回答：暂停或降低消费者并发，按 Provider 设置全局令牌桶，延长退避并加 jitter；切换可用 Embedding Provider 前先
验证维度和向量空间，不能直接把不同模型的向量混进同一索引。

