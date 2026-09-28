# Java 并发、异步与虚拟线程

## 1. 面试官：并发、并行和异步有什么区别，项目分别有哪些例子？

我的回答：

并发是多个任务在时间上交错推进，并行是同一时刻在多个执行单元运行，异步是调用方提交后不阻塞等待最终结果。我的项目的 WebSocket 多连接和 Redis Consumer 属于并发；简历定制题与技能题使用两个虚拟线程同时请求模型，属于 I/O 并行；简历分析、向量化和最终评估通过 Redis Stream 异步执行，HTTP 先返回任务状态。概念不同决定工具不同：局部可控的 I/O 用虚拟线程，跨进程可靠任务用消息队列。当前不能用“异步”笼统描述所有线程切换，否则会忽略持久化与失败恢复差异。

代码依据：

- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java` — 并行出题
- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java` — 异步消费
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — 多连接并发

## 2. 面试官：Java 21 虚拟线程的原理是什么，适合哪些任务？

我的回答：

虚拟线程由 JVM 调度，阻塞 I/O 时可卸载底层 carrier thread，因此能用同步代码风格承载大量等待任务，创建和切换成本远低于平台线程。它适合 HTTP、数据库、S3、LLM 等 I/O 密集操作，不会让 CPU 计算更快，也不会扩大下游连接池和 API 配额。我的项目启用 Spring 虚拟线程，并在出题与语音管线中显式创建 per-task executor。当前模型调用多、等待长，选择合理；但若持有 `synchronized` 或本地调用发生 pinning，收益会下降。生产上要用 JFR 观察 pinned thread，并继续设置并发上限。

代码依据：

- `app/src/main/resources/application.yml` — `spring.threads.virtual.enabled`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java` — `newVirtualThreadPerTaskExecutor()`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — 虚拟线程执行器

## 3. 面试官：开启 Spring 虚拟线程是否意味着所有异步任务都自动安全扩容？

我的回答：

不开。Spring 配置只会让兼容的请求处理和任务执行使用虚拟线程，队列消费模型、数据库连接数、共享状态和下游配额不会自动变化。我的项目的 `AbstractStreamConsumer` 自己创建单线程 `ThreadPoolExecutor`，每个 Bean 只运行一个消费循环，因此即使全局开启虚拟线程，单管道仍近似串行；WebSocket TTS 虽用虚拟线程，也被 Semaphore 限制。虚拟线程减少“等待占线程”的成本，却可能让更多请求同时压向 LLM。生产上要把 Provider、数据库和语音连接作为真正的容量边界，用舱壁、限流和超时保护。

代码依据：

- `app/src/main/resources/application.yml` — 虚拟线程开关
- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java` — 单线程消费器
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — TTS 限制

## 4. 面试官：文字面试出题为什么并行执行，异常如何合并？

我的回答：

当两项外部 I/O 相互独立时，并行可以把总延迟从两次调用之和降到接近较慢的一次。我的项目有简历时，把约 60% 的简历定制题和剩余技能方向题提交到虚拟线程 executor，分别等待结果后合并、去重和补足数量；一个分支失败时记录异常并使用另一个分支，两个都失败才进入本地兜底题。这样并行不牺牲整体可用性。当前每次创建题目都可能同时放大 Provider 并发，没有全局 semaphore；生产上应按 providerId 限制在途请求，并为每个分支设置独立超时与取消。

代码依据：

- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java` — 并行生成问题
- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java` — 合并与兜底
- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java` — `@PreDestroy`

## 5. 面试官：语音管线为什么使用虚拟线程，线程生命周期如何管理？

我的回答：

WebSocket 回调线程不应被 LLM、TTS 或数据库阻塞，否则一个慢请求会影响连接读写。我的项目把开场问题、用户话语处理和多句 TTS 提交到 `Executors.newVirtualThreadPerTaskExecutor()`，Handler 只快速解析消息和更新状态；连接关闭时保存的 processingThread 会被 interrupt，Bean 销毁时执行器 shutdown。这样阻塞式 SDK 也能以清晰同步代码运行。边界是部分 SDK 未必响应中断，unbounded per-task 提交也可能产生大量在途请求。生产上需要任务 deadline、结构化并发和按会话/Provider 的并发预算。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `voicePipelineExecutor`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `processingThread`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `destroy()`

## 6. 面试官：并发 TTS 为什么使用 Future 按序收集，异常时怎么办？

我的回答：

多句 TTS 相互独立，可以并发合成，但播放必须遵循原文本顺序。我的项目为每句创建异步任务，Future 按句子索引存入列表，完成后仍按列表顺序取音频；`Semaphore` 控制同时合成数量，避免单会话瞬间打满 TTS 配额。某句返回空音频或抛异常时会记录并降级为文本或跳过，连接关闭则丢弃后续结果。当前按序等待会产生队头阻塞：第一句慢会挡住已完成的后续句。生产上可给每句超时，优先保证第一音频，并让客户端按 index 缓冲有限乱序结果。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — TTS Future 列表
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `Semaphore`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/QwenTtsService.java` — 空音频降级

## 7. 面试官：AtomicBoolean、CAS 和 Semaphore 在项目中分别解决什么问题？

我的回答：

CAS 用无锁原子比较更新解决轻量状态竞争，Semaphore 控制同时进入临界区的任务数量。我的项目为每个语音 SessionState 保存 `AtomicBoolean processing`，只有 `compareAndSet(false,true)` 成功的触发者能启动 LLM 管线，防止 ASR final、VAD 和手动 submit 重复回答；`aiSpeaking` 表示回声抑制状态。TTS 则用 Semaphore 限制单会话并发合成数。它们都是 JVM 内控制，不能跨实例，也不能保证业务落库幂等。生产多实例时需要 session owner、分布式租约或数据库版本，同时保留本地快速保护。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `SessionState.processing`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `aiSpeaking`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — TTS `Semaphore`

## 8. 面试官：定时任务、防抖和共享 Map 如何保证线程安全？

我的回答：

我的项目的 WebSocket 连接、状态、开场音频和活动时间使用 ConcurrentHashMap，支持回调线程、虚拟线程和定时扫描并发访问；话语防抖使用 ScheduledExecutorService，新的 final 片段会取消或替换旧定时任务，延迟后合并触发；空闲检查由 `@Scheduled` 每 30 秒遍历连接。单字段用 volatile 或 AtomicBoolean，但一个会话的多字段转换并非整体原子，关闭、重连和处理完成可能交错。生产上应把会话生命周期建模为显式状态机，在单会话串行执行器中处理事件，或用版本/锁保护复合更新，减少散落 Map 的竞态。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — ConcurrentHashMap
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — utterance scheduler
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `@Scheduled` 空闲扫描

## 9. 面试官：ThreadPoolExecutor 为什么强调有界队列，项目当前实现如何评价？

我的回答：

线程池的线程数和队列共同决定过载行为；无界队列会把压力转成内存增长，直到延迟失控或 OOM，有界队列才能触发拒绝、降级或反压。我的项目的 Stream Consumer 显式创建单核心、单最大线程的 `ThreadPoolExecutor`，但使用无界 `LinkedBlockingQueue`；实际只提交一个长期 consumeLoop，所以队列目前几乎不积累普通业务任务，风险有限。若未来改成每条消息 submit，这个配置会成为隐患。生产上会使用有界队列、命名线程、拒绝策略和队列指标，虚拟线程任务也要在提交前限流。

代码依据：

- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java` — `ThreadPoolExecutor`
- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java` — `LinkedBlockingQueue`
- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java` — `startConsumer()`

## 10. 面试官：并发任务中的取消、异常和上下文传播如何处理？

我的回答：

并发任务不能只启动，还要定义谁取消、错误如何汇总、日志上下文怎样传递。我的项目在 WebSocket 断开时 interrupt processingThread、关闭 ASR 并清理状态；出题 executor 在 Bean 销毁时 shutdownNow；Redis Stream 消息携带 traceId，消费者恢复 MDC 后在 finally 清理。业务异常会落任务状态并重试，异步增强失败不一定阻断核心流程。当前虚拟线程和 Reactor 流的 ThreadLocal 上下文仍需验证，部分 SDK 不响应 interrupt，TTS Future 也缺统一 deadline。生产上应使用结构化并发、显式 CancellationToken、超时预算和上下文快照。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — 断线取消
- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java` — executor 销毁
- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java` — MDC 恢复与清理
- `app/src/main/java/interview/guide/common/observability/TraceIdFilter.java` — trace 来源

# 本章总结

项目按问题类型选择并发工具：虚拟线程处理大量阻塞 I/O，CAS 与 Semaphore 控制单会话竞争，ScheduledExecutor 做防抖，Redis Stream 承担跨进程可靠异步。

虚拟线程并不等于无限并发；真正的生产边界仍是 Provider 配额、连接池、任务队列、复合状态原子性和取消传播。

# 核心主线

用轻量线程表达阻塞流程，用原子状态和并发配额约束共享资源，再把需要可靠恢复的任务交给消息队列。
