# 定时扫描与 Stream 投递

## 1. `InterviewReminderScheduler` 如何启用和执行？

**回答：** Scheduler 是被 `@Component` 管理的 Bean，并通过 `@ConditionalOnProperty(name="app.interview-reminder.dispatch-enabled", havingValue="true")` 控制是否加载。`dispatchDueReminders()` 使用 `@Scheduled`，默认 Cron 为 `0 * * * * *`，即每分钟第 0 秒执行一次，可以通过 `app.interview-reminder.scan-cron` 覆盖，时区默认是 Asia/Shanghai。

## 2. 到期提醒是怎么查询和认领的？

**回答：** Scheduler 调用 `reminderService.claimDueReminderIds()`，在事务中查询 `status=PENDING AND remindAt<=NOW()` 的提醒，并按 remindAt 升序排列。查到后把它们批量更新为 QUEUED，再返回提醒 ID 集合给 Scheduler 投递。

即用条件查询。查询就是根据当前时间大于等于提醒时间，然后状态是未提醒，然后排列一下，按顺序封装成消息发送出出去。

## 3. 为什么使用悲观写锁？

**回答：** Repository 查询使用 `PESSIMISTIC_WRITE`，底层相当于 `SELECT ... FOR UPDATE`。这样多实例部署时，即使多个 Scheduler 同时扫描，也只有一个实例能够认领某条 PENDING 提醒，避免同一提醒被多个节点重复放入 Stream。

多实例部署时可以用分布式锁限制只有一个服务端可以去查询数据库做提醒，发送完消息后及时更新数据库中的状态，如果消息处理失败的话也达到了重试限制，即没有成功提醒，可以先回滚数据库并标记一下提醒失败，用降级方式再重新投递消息试一下，还不行可以人工介入。

## 4. 如何限制每次扫描数量并更新 QUEUED 状态？

**回答：** 查询使用 `PageRequest.of(0,batchSize)` 控制单次批量大小，batchSize 默认是 50。认领到记录后在同一事务中遍历设置 QUEUED，并通过 `saveAll` 批量写回数据库，最后只把 ID 列表返回给 Scheduler。

## 5. Scheduler 如何把提醒发送到 Redis Stream？

**回答：** Scheduler 遍历到期 ID，调用 `producer.sendReminder(reminderId)`。Producer 创建 `ReminderPayload(reminderId,retryCount=0)`，再由 `AbstractStreamProducer.sendTask()` 构造 Map 并调用 `redisService.streamAdd`，写入 `interview-schedule:reminder:stream`。

## 6. Stream 消息体包含哪些字段？

**回答：** 消息体包含 reminderId、businessId、retryCount 和 traceId。reminderId 用于定位提醒实体，businessId 供抽象 Producer 和 Consumer 统一表示业务对象，retryCount 记录当前重试次数，traceId 用于把调度、投递、消费和发送日志串成完整链路。

## 7. 为什么 `businessId` 和 `reminderId` 相同？

**回答：** `businessId` 是 AbstractStreamProducer 定义的通用字段，在知识库向量化中表示文档 ID，在简历分析中表示简历 ID，在面试评估中表示会话 ID。提醒场景需要处理的业务实体就是提醒本身，所以 businessId 和 reminderId 使用同一个值，父类模板便可以统一完成日志、重试和状态追踪。

## 8. TraceId、Stream 最大长度和发送失败分别如何处理？

**回答：** 父类从当前线程 MDC 中取得 traceId 并追加到消息 Map；调用 `streamAdd` 时传入全局 `STREAM_MAX_LEN`，限制 Stream 长度以避免 Redis 内存无限增长；写入成功后记录 messageId，发生 Redis 连接等发送异常时回调 `onSendFailed`，把数据库中的提醒状态标记为 FAILED。
