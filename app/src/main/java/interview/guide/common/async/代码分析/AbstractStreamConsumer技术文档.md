# AbstractStreamConsumer 技术文档

---

## 1. 类概述

`AbstractStreamConsumer<T>` 是一个基于 **Redis Stream** 的异步任务消费者模板基类（抽象类），使用 **模板方法模式** 定义了异步消息消费的完整生命周期骨架。它负责从 Redis Stream 中持续拉取消息、解析为业务载荷对象、驱动子类执行业务逻辑，并提供 ACK 确认、失败重试（最多 3 次）、任务状态标记和链路追踪等通用能力。

子类只需实现 12 个抽象方法即可完成特定业务场景的异步消费者开发。当前有 4 个具体实现：

| 子类 | 用途 |
|------|------|
| `VectorizeStreamConsumer` | 知识库文档向量化 |
| `AnalyzeStreamConsumer` | 简历 AI 分析评分 |
| `EvaluateStreamConsumer` | 面试答题 AI 评估 |
| `VoiceEvaluateStreamConsumer` | 语音面试多轮评估 |

类定义在 [AbstractStreamConsumer.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java) 。

---

## 2. 依赖分析

### 2.1 导入的类和包

| 导入路径 | 用途 |
|----------|------|
| `interview.guide.common.constant.AsyncTaskStreamConstants` | 异步任务常量配置（批次大小、轮询间隔、最大重试次数、Stream Key 等） |
| `interview.guide.common.observability.AsyncTaskMetricsRecorder` | 异步任务指标记录器（处理中/完成/失败/重试计数） |
| `interview.guide.common.observability.TraceIdFilter` | TraceId 过滤器（提供追踪 Key 常量） |
| `interview.guide.infrastructure.redis.RedisService` | Redis 通用服务封装（Stream 创建、消息拉取、ACK 确认） |
| `jakarta.annotation.PostConstruct` | Spring 容器初始化回调 |
| `jakarta.annotation.PreDestroy` | Spring 容器销毁回调 |
| `org.slf4j.MDC` | SLF4J 的线程本地日志上下文 |
| `org.redisson.api.stream.StreamMessageId` | Redis Stream 消息 ID |
| `java.util.Map` | 消息数据键值对 |
| `java.util.UUID` | 生成唯一消费者名称 |
| `java.util.concurrent.ExecutorService` | 线程池接口 |
| `java.util.concurrent.LinkedBlockingQueue` | 无界阻塞队列 |
| `java.util.concurrent.ThreadPoolExecutor` | 线程池实现 |
| `java.util.concurrent.TimeUnit` | 时间单位枚举 |
| `java.util.concurrent.atomic.AtomicBoolean` | 线程安全的布尔标志 |

### 2.2 外部库依赖

- **Lombok** — `@Slf4j` 注解自动生成 SLF4J 日志对象
- **Redisson** — `StreamMessageId` 和底层 Stream 操作
- **SLF4J** — 结构化日志，MDC 链路追踪

### 2.3 父类和接口

- 父类：`Object`（无显式继承）
- 泛型参数：`<T>` — 业务载荷类型（由子类具体化，如 `KnowledgeBaseTask`、`ResumeAnalyzeTask`）

### 2.4 配置文件与资源

- 无直接配置文件依赖。配置通过 `AsyncTaskStreamConstants` 类集中管理，包括：
  - `MAX_RETRY_COUNT = 3` — 最大重试次数
  - `BATCH_SIZE = 10` — 每次拉取消息数
  - `POLL_INTERVAL_MS = 1000` — 轮询间隔（毫秒）
  - `PENDING_IDLE_TIMEOUT_MS = 60000` — Pending 消息空闲阈值
  - `PENDING_RECOVERY_BATCH_SIZE = 10` — 恢复批次大小
- 各具体消费者的 Stream Key、Group Name、Consumer Prefix 也定义在此常量类中

---

## 3. 变量文档

| 变量名 | 类型 | 访问修饰符 | 说明 |
|--------|------|-----------|------|
| `redisService` | `RedisService` | `private final` | Redis 服务依赖，执行 Stream 创建、消息拉取、ACK 等操作。不可变，通过构造函数注入 |
| `running` | `AtomicBoolean` | `private final` | 消费者运行状态标志。`true` 时持续消费，`false` 时停止循环。线程安全 |
| `executorService` | `ExecutorService` | `private` | 单线程线程池，用于运行消费循环。`@PostConstruct` 中初始化，`@PreDestroy` 中关闭 |
| `consumerName` | `String` | `private` | 消费者唯一标识，格式为 `consumerPrefix + 8位UUID`。`@PostConstruct` 中赋值 |
| `metricsRecorder` | `AsyncTaskMetricsRecorder` | `private final` | 指标记录器，可为 null（不启用指标时）。记录处理中/完成/失败/重试数量 |

---

## 4. 方法文档

### 4.1 构造函数

```java
protected AbstractStreamConsumer(RedisService redisService)
protected AbstractStreamConsumer(RedisService redisService, AsyncTaskMetricsRecorder metricsRecorder)
```

- **访问修饰符**：`protected`，仅供子类 `super()` 调用
- **参数 1**：`redisService` — Redis 服务依赖（必填）
- **参数 2**：`metricsRecorder` — 指标记录器（可选，传 null 则不启用指标）
- **逻辑**：单参数构造器委托调用双参数构造器，简单赋值

---

### 4.2 `init()` — 初始化并启动消费者

```java
@PostConstruct
public void init()
```

- **注解**：`@PostConstruct`，Spring 容器完成依赖注入后自动调用
- **流程**：
  1. 生成唯一 `consumerName`（前缀 + 8 位随机 UUID）
  2. 创建单线程线程池（核心线程=1、最大线程=1、守护线程、`AbortPolicy` 拒绝策略）
  3. 设置 `running = true`
  4. 提交 `startConsumer()` 到线程池开始消费循环
- **线程池参数详解**：
  - `corePoolSize = 1` / `maximumPoolSize = 1` → 只有 1 个线程，保证消息顺序处理
  - `keepAliveTime = 0` → 因为 core = max，无多余线程可回收
  - `LinkedBlockingQueue<>()` → 无界队列，实际只提交一个任务
  - 线程工厂 → 守护线程，JVM 退出时不阻塞
  - `AbortPolicy` → 安全兜底，实际不会触发

---

### 4.3 `shutdown()` — 关闭消费者

```java
@PreDestroy
public void shutdown()
```

- **注解**：`@PreDestroy`，Spring 容器销毁 Bean 前自动调用
- **流程**：
  1. 设置 `running = false` → 消费循环在下一次 while 判断时退出
  2. 调用 `executorService.shutdown()` 关闭线程池

---

### 4.4 `startConsumer()` — 启动消费流程

```java
private void startConsumer()
```

- **调用链**：由 `init()` 提交到线程池执行
- **流程**：
  1. 尝试创建 Redis Stream 消费者组（`redisService.createStreamGroup(streamKey(), groupName())`）
  2. 组已存在时抛异常（`log.warn` 后继续，不阻断流程）
  3. 调用 `consumeLoop()` 进入主消费循环

---

### 4.5 `consumeLoop()` — 主消费循环

```java
private void consumeLoop()
```

- **循环控制**：`while (running.get())` — 每次迭代检查运行标志
- **每次迭代**：
  1. 调用 `redisService.streamConsumeMessages(...)` 从 Redis Stream 拉取消息
  2. 传入 `this::processMessage` 作为消息处理器回调
  3. 使用 `BATCH_SIZE`（10 条/次）和 `POLL_INTERVAL_MS`（1000 毫秒超时）
- **异常处理**：
  - `Thread.interrupted` → 优雅退出
  - 其他异常 → 记录日志，继续下一轮循环

---

### 4.6 `processMessage()` — 核心消息处理（模板方法）

```java
private void processMessage(StreamMessageId messageId, Map<String, String> data)
```

这是整个类的核心方法，实现五阶段处理流程（详见第 5 节）。

---

### 4.7 `parseRetryCount()` — 解析重试次数

```java
protected int parseRetryCount(Map<String, String> data)
```

- **逻辑**：从消息 Map 中读取 `retryCount` 字段并转为 int
- **容错**：字段缺失或格式错误时返回 0
- **访问修饰符**：`protected`，可供子类覆盖

---

### 4.8 `truncateError()` — 截断错误消息

```java
protected String truncateError(String error)
```

- **逻辑**：将错误消息截断为最多 500 字符，防止数据库字段溢出
- **边界**：`null` 返回 `null`

---

### 4.9 `ackMessage()` — ACK 确认

```java
private void ackMessage(StreamMessageId messageId)
```

- **逻辑**：调用 `redisService.streamAck()` 确认消息已消费
- **异常处理**：ACK 失败只记日志，不阻断后续流程

---

### 4.10 `recoverPendingMessages()` — 恢复 Pending 消息

```java
public int recoverPendingMessages(long idleTimeoutMs, int count)
```

- **用途**：处理消费者崩溃后遗留在 pending 队列中的消息
- **机制**：使用 Redis Stream 的 `XAUTOCLAIM` 命令，认领空闲超过 `idleTimeoutMs` 的消息
- **返回值**：本次恢复并处理的消息数量
- **由定时任务调度器周期性调用**

---

### 4.11 `redisService()` — 获取 RedisService

```java
protected RedisService redisService()
```

- **用途**：供子类获取 `redisService` 实例（因为 `redisService` 是 `private`）

---

### 4.12 指标记录方法

| 方法 | 说明 |
|------|------|
| `recordProcessing()` | 处理中计数 +1 |
| `recordCompleted()` | 完成计数 +1 |
| `recordFailed()` | 失败计数 +1 |
| `recordRetry()` | 重试计数 +1 |

这四个方法都会先检查 `metricsRecorder != null`，为 null 时跳过。

---

### 4.13 子类必须实现的抽象方法（共 12 个）

| 方法 | 返回类型 | 用途 |
|------|---------|------|
| `taskDisplayName()` | `String` | 任务显示名（如 "知识库向量化"） |
| `streamKey()` | `String` | Redis Stream Key |
| `groupName()` | `String` | Redis Stream 消费者组名称 |
| `consumerPrefix()` | `String` | 消费者名称前缀 |
| `threadName()` | `String` | 线程名 |
| `parsePayload(messageId, data)` | `T` | 将消息 Map 解析为业务载荷 |
| `payloadIdentifier(payload)` | `String` | 获取载荷标识（用于日志） |
| `markProcessing(payload)` | `void` | 标记任务为"处理中" |
| `processBusiness(payload)` | `void` | 执行业务逻辑 |
| `markCompleted(payload)` | `void` | 标记任务为"已完成" |
| `markCancelled(payload, reason)` | `void` | 标记任务为"已取消" |
| `markFailed(payload, error)` | `void` | 标记任务为"失败" |
| `retryMessage(payload, retryCount)` | `void` | 重新入队消息 |

---

## 5. 核心功能

### 5.1 核心方法

`processMessage()` 是唯一的核心方法，实现了**模板方法设计模式**——它定义了处理单条消息的完整骨架，而具体的解析、执行、状态标记等步骤全部委托给子类实现的抽象方法。

### 5.2 五阶段处理流程

```
消息入口：processMessage(messageId, data)
    │
    ▼
阶段 1: MDC TraceId 管理
    ├─ 备份当前 traceId
    ├─ 从消息取 traceId 写入 MDC
    │
    ▼
阶段 2: 解析载荷
    ├─ parsePayload() —— 子类解析
    ├─ null → ack + return（消息格式错，丢弃）
    │
    ▼
阶段 3: 读重试次数
    ├─ parseRetryCount()
    ├─ 打印开始处理日志
    │
    ▼
阶段 4: 执行业务（三条分支）
    │
    ├─ 成功: markProcessing → recordProcessing → processBusiness → markCompleted → recordCompleted → ack
    │
    ├─ 取消: catch AsyncTaskCancelledException → markCancelled → ack
    │
    └─ 失败: catch Exception
            ├─ retryCount < 3 → recordRetry → retryMessage(retryCount+1) → ack
            └─ retryCount ≥ 3 → recordFailed → markFailed → ack
    │
    ▼
阶段 5: 恢复 MDC
    ├─ 还原 previousTraceId（防止线程池复用污染）
```

### 5.3 关键设计决策

**为什么 ACK 在 finally 之前执行？**
ACK 不是无条件执行的——在成功/取消/失败三条分支中各自调用。消息确认和状态变更是原子搭配：成功→ACK 移除消息；失败→ACK 当前消息后重新生产新消息。如果失败不 ACK，消息会永远留在 pending 中重复消费。

**为什么重试时先 ACK 再重新生产？**
因为 Redis Stream 的消息 ID 不可变，同一消息不能改 retryCount 后重新消费。所以采用"ACK 旧消息 + 重新生产新消息"的模式：新消息继承原 taskId，retryCount + 1。

**为什么 MDC 需要备份恢复？**
消费者线程是线程池复用的。当前消息 traceId = "abc"，下一条消息 traceId = "xyz"。如果不先备份清空，下一条消息的日志会带上前一条的 traceId，导致链路追踪串扰。finally 块保证了原子恢复。

---

## 6. 代码流程分析

### 6.1 完整生命周期

```
Spring 容器启动
    │
    ▼
依赖注入（redisService、metricsRecorder）
    │
    ▼
@PostConstruct → init()
    │
    ├── consumerName = prefix + UUID(8位)
    ├── 创建单线程守护线程池
    ├── running = true
    └── executorService.submit(this::startConsumer)
            │
            ▼
        startConsumer()
            │
            ├── redisService.createStreamGroup(streamKey, groupName)
            │     └─ 失败 → log.warn，继续
            │
            └── consumeLoop()
                    │
                    ▼
                while (running) {
                    redisService.streamConsumeMessages(
                        streamKey, groupName, consumerName,
                        BATCH_SIZE, POLL_INTERVAL_MS,
                        this::processMessage     ← 回调入口
                    )
                }
                    │
            消息到达 → processMessage(messageId, data)
                    │
                    └── [五阶段处理，见第5节]
                    │
            线程中断 / running=false → 退出循环
    │
    ▼
Spring 容器关闭
    │
    ▼
@PreDestroy → shutdown()
    │
    ├── running = false（while 循环退出）
    └── executorService.shutdown()
```

### 6.2 方法调用关系图

```
init()                                ← @PostConstruct
  ├── consumerPrefix()                ← 子类提供
  ├── UUID.randomUUID()
  ├── ThreadPoolExecutor 创建
  │     └── threadName()              ← 子类提供
  └── submit(startConsumer)
        └── startConsumer()
              ├── redisService.createStreamGroup(streamKey(), groupName())
              └── consumeLoop()
                    └── while(running)
                          └── redisService.streamConsumeMessages(
                                streamKey(), groupName(), consumerName,
                                BATCH_SIZE, POLL_INTERVAL_MS,
                                this::processMessage
                              )
                                └── processMessage(messageId, data)
                                      ├── MDC.get / MDC.put
                                      ├── parsePayload()           ← 子类
                                      ├── parseRetryCount()
                                      ├── markProcessing()         ← 子类
                                      ├── processBusiness()        ← 子类
                                      ├── markCompleted()          ← 子类
                                      ├── markCancelled()          ← 子类（取消分支）
                                      ├── markFailed()             ← 子类（失败分支）
                                      ├── retryMessage()           ← 子类（重试分支）
                                      ├── ackMessage()
                                      │     └── redisService.streamAck()
                                      ├── recordProcessing/Completed/Failed/Retry()
                                      │     └── metricsRecorder.recordXxx()
                                      └── finally: MDC 恢复

shutdown()                            ← @PreDestroy
  ├── running.set(false)
  └── executorService.shutdown()

recoverPendingMessages()              ← 外部定时调度
  ├── redisService.streamAutoClaim()
  └── processMessage() 遍历
```

---

## 7. 外部交互

### 7.1 依赖的外部类

| 外部类 | 交互方式 | 数据方向 |
|--------|---------|---------|
| `RedisService` | 方法调用 | 双向：消费者 → Redis（创建组、拉取消息、ACK），Redis → 消费者（消息数据） |
| `AsyncTaskStreamConstants` | 读取常量 | 单向：常量值流入消费者配置 |
| `AsyncTaskMetricsRecorder` | 方法调用 | 单向：消费者 → 指标记录器（计数） |
| `TraceIdFilter.TRACE_ID_MDC_KEY` | 读取常量 | 单向：常量值用于 MDC Key |
| `StreamMessageId` | 参数传递 | 单向：Redis → 消费者（消息 ID） |

### 7.2 与 Redis Stream 的交互

- **生产者**（[AbstractStreamProducer.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/async/AbstractStreamProducer.java)）负责写入消息
- **消费者**（本类）负责消费消息
- 双方通过消费者组实现负载均衡：多个消费者实例竞争消费同一 Stream

### 7.3 与子类的交互

通过 13 个抽象方法（5 个配置 + 8 个业务）定义契约。子类必须全部实现，基类控制流程但不关心具体业务。

---

## 8. 类的定位与角色

`AbstractStreamConsumer` 在系统架构中充当初以下几个角色：

1. **消息消费者引擎**：将 Redis Stream 的底层操作（XREADGROUP、XACK、XAUTOCLAIM）封装为高层业务语义

2. **模板方法骨架**：定义了"解析 → 执行 → 标记 → ACK"的标准流程，子类零流程代码

3. **可靠性保证层**：内置重试机制（最大 3 次）、pending 消息恢复、ACK 确认，确保消息不丢失

4. **可观测性基础设施**：集成 MDC 链路追踪（traceId 跨服务传递）和指标计数器（处理量、失败率、重试率）

5. **生命周期管理**：自动初始化（`@PostConstruct`）和优雅关闭（`@PreDestroy`）

**与 AbstractStreamProducer 的配合**：

```
用户请求 → Controller → Service → Producer 发送消息到 Redis Stream
                                          │
                                          ▼
                                   Redis Stream
                                          │
                                          ▼
                              Consumer（本类）拉取并处理
                                          │
                                          ▼
                              更新数据库状态 / 返回结果
```

---

## 9. 完整流程总结

Spring 容器完成依赖注入后，通过 `@PostConstruct` 触发 `init()` 方法：生成一个由消费者前缀加 8 位随机 UUID 组成的唯一消费者名称，创建一个核心线程数和最大线程数均为 1 的单线程守护线程池（使用 `LinkedBlockingQueue` 无界队列和 `AbortPolicy` 拒绝策略），设置运行标志为 true 后向线程池提交 `startConsumer()` 任务。该任务首先尝试创建 Redis Stream 消费者组（组已存在则忽略告警），随后进入 `consumeLoop()` 无限循环，不断调用 `RedisService.streamConsumeMessages()` 以每次最多 10 条、每轮 1 秒超时的策略从 Redis Stream 中拉取消息，每条消息到达时触发 `processMessage()` 回调。该回调作为模板方法的骨架分五阶段执行：先备份当前线程 MDC 中的 TraceId 并将消息携带的 traceId 写入 MDC 以保证整条处理链路日志可追踪，再调用子类的 `parsePayload()` 将原始 Map 数据解析为业务载荷对象（解析失败则直接 ACK 丢弃），读取消息中的 retryCount 字段获取当前重试次数后进入业务执行阶段——依次调用子类的 `markProcessing`（标记处理中）、`processBusiness`（执行真正的业务逻辑，如调用 LLM、向量化、文件解析）、`markCompleted`（标记已完成）并最终 ACK 确认；若业务执行中抛出 `AsyncTaskCancelledException`（如关联实体已被删除），则调用 `markCancelled` 标记取消后 ACK；若抛其他异常且重试次数未达 3 次上限则调用 `retryMessage` 将任务重新生产到 Redis Stream（retryCount + 1），已达上限则调用 `markFailed` 标记最终失败，无论重试与否都 ACK 当前消息。最后在 finally 块中将线程 MDC 恢复为处理前的原始状态，避免线程池复用时串扰后续消息的日志追踪。当 Spring 容器销毁时，`@PreDestroy` 触发的 `shutdown()` 将 running 标志置为 false 使消费循环退出，并关闭线程池完成优雅停机。此外，外部定时调度器可调用 `recoverPendingMessages()` 方法通过 Redis Stream 的 XAUTOCLAIM 机制恢复消费者崩溃后遗留在 pending 队列中的消息，保证消息不丢失。
