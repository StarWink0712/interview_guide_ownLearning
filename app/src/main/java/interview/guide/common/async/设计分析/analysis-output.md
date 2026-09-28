# AbstractStreamConsumer 全面技术分析

> 分析时间：2026-05-16
> 目标文件：[AbstractStreamConsumer.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java)

---

## 一、代码结构分析

### 1.1 类定义

**完整类签名：**

```java
package interview.guide.common.async;

@Slf4j
public abstract class AbstractStreamConsumer<T> {
    // ...
}
```

位于 `interview.guide.common.async` 包，与 `AbstractStreamProducer` 并列，共同构成项目异步任务框架的核心模板层。

**泛型参数 `T` 的含义和作用：**

- `T` 代表**业务载荷类型**，即每条 Redis Stream 消息被解析后得到的领域对象。
- 典型实例：`VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizePayload>`，其中 `VectorizePayload` 是一个嵌套的 `record`，封装了 `taskId`、`kbId`、`provider` 等字段。
- 泛型使得模板基类可以统一处理"解析消息 → 标记状态 → 执行业务 → 重试/失败标记"的完整生命周期，而无需关心具体载荷结构。所有抽象方法（`parsePayload`、`markProcessing`、`processBusiness`、`retryMessage` 等）均以 `T` 为操作对象，子类通过覆写这些方法注入具体类型的处理逻辑。

---

### 1.2 类级注解

| 注解 | 作用 |
|------|------|
| `@Slf4j` | Lombok 注解，编译期自动生成 `private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AbstractStreamConsumer.class);`，使得类内可直接使用 `log.info(...)`、`log.error(...)` 等日志方法。 |

**为什么没有 `@Component` 或 `@Service`：**

此类是抽象模板基类，本身不能实例化。Spring 容器不会为抽象类创建 Bean。`@Component`（或 `@Service`）由各个具体子类分别标注。例如：

- `VectorizeStreamConsumer` → `@Component`（位于 `modules/knowledgebase/listener/`）
- `AnalyzeStreamConsumer` → `@Component`（位于 `modules/resume/listener/`）
- `EvaluateStreamConsumer` → `@Component`（位于 `modules/interview/listener/`）
- `VoiceEvaluateStreamConsumer` → `@Component`（位于 `modules/voiceinterview/listener/`）

这种设计遵循**模板方法模式**：基类定义骨架，子类通过 Spring 依赖注入获取各自所需的 Service/Repository，并标注 `@Component` 注册到容器。

---

### 1.3 继承关系

| 子类 | 位置（包路径） | 职责 | 消费的 Stream Key | Group Name |
|------|---------------|------|-------------------|------------|
| `VectorizeStreamConsumer` | `modules/knowledgebase/listener` | 知识库文档向量化：将上传的文档内容调用 Embedding API 生成向量，存入 pgvector | `knowledgebase:vectorize:stream` | `vectorize-group` |
| `AnalyzeStreamConsumer` | `modules/resume/listener` | 简历 AI 分析评分：对上传的简历调用 LLM 进行结构化分析和打分 | `resume:analyze:stream` | `analyze-group` |
| `EvaluateStreamConsumer` | `modules/interview/listener` | 模拟面试 AI 评估：对用户的答题内容调用 LLM 进行评估打分，生成面试报告 | `interview:evaluate:stream` | `evaluate-group` |
| `VoiceEvaluateStreamConsumer` | `modules/voiceinterview/listener` | 语音面试 AI 评估：对语音面试的多轮对话进行综合评估，生成语音面试报告 | `voice:evaluate:stream` | `voice-evaluate-group` |

每个子类的 Stream Key 和 Group Name 均定义在 [AsyncTaskStreamConstants](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/constant/AsyncTaskStreamConstants.java) 中，通过常量引用保证命名一致性。

---

### 1.4 架构定位

`AbstractStreamConsumer` 是项目**异步任务系统的消费者模板基类**，位于 `common/async` 通用基础设施层。其架构定位如下：

```
┌────────────────────────────────────────────────────────────┐
│                      业务层 (modules/)                       │
│  VectorizeStreamConsumer  AnalyzeStreamConsumer             │
│  EvaluateStreamConsumer   VoiceEvaluateStreamConsumer       │
│  每个子类标注 @Component，覆写 13 个抽象方法                    │
└──────────────────────────┬─────────────────────────────────┘
                           │ extends
┌──────────────────────────▼─────────────────────────────────┐
│              通用基础设施层 (common/async/)                   │
│  AbstractStreamConsumer<T>  ←  模板基类（骨架）               │
│  AbstractStreamProducer<T>  ←  生产者模板基类                 │
│  AsyncTaskCancelledException ←  取消异常                     │
└──────────────────────────┬─────────────────────────────────┘
                           │ 依赖
┌──────────────────────────▼─────────────────────────────────┐
│              Redis 基础设施层 (infrastructure/redis/)         │
│  RedisService（streamConsumeMessages / streamAck /           │
│               createStreamGroup / streamAutoClaim）          │
└────────────────────────────────────────────────────────────┘
```

**设计模式**：模板方法模式（Template Method Pattern）。基类封装不变部分——线程池管理、循环拉取、ACK 确认、重试逻辑——子类通过实现抽象方法注入变化的业务处理逻辑。

**核心价值**：
1. **消除重复代码**：4 个消费者共享同一套生命周期管理和异常处理逻辑
2. **统一行为契约**：所有消费者的初始化、运行、关闭、重试行为保持一致
3. **易于扩展**：新增异步任务只需新建子类实现 13 个抽象方法

---

## 二、方法详解

### 2.1 构造方法（两个重载）

**方法 1：单参数版本**

```java
protected AbstractStreamConsumer(RedisService redisService) {
    this(redisService, null);
}
```

- **访问修饰符**：`protected`，仅供子类通过 `super(redisService)` 调用
- **参数**：`RedisService redisService` —— Redis 操作服务，由 Spring 注入到子类再向上传递
- **逻辑**：委托给双参数版本，`metricsRecorder` 传 `null`（指标记录为可选依赖）

**方法 2：双参数版本**

```java
protected AbstractStreamConsumer(
    RedisService redisService,
    AsyncTaskMetricsRecorder metricsRecorder
) {
    this.redisService = redisService;
    this.metricsRecorder = metricsRecorder;
}
```

- **设计亮点**：`metricsRecorder` 可为 `null`，所有 `recordXxx()` 方法均以 `if (metricsRecorder != null)` 做空安全判断。这使得 Micrometer 依赖为可选——在没有 `MeterRegistry` Bean 的环境中消费者照常运行，仅跳过指标记录。

---

### 2.2 `@PostConstruct init()` —— 初始化方法

```java
@PostConstruct
public void init()
```

**核心逻辑分三步：**

**步骤 1：生成唯一消费者名称**
```java
this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
```
- `consumerPrefix()` 由子类提供，如 `"vectorize-consumer-"`、`"evaluate-consumer-"`
- 拼接 8 位随机 UUID，形成 `"vectorize-consumer-a1b2c3d4"` 格式
- **设计意图**：同一消费者组内可运行多个消费者实例（水平扩展），每个实例有唯一名称用于 Redis Stream 的消息分发和故障恢复

**步骤 2：创建单线程守护线程池**
```java
this.executorService = new ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS,
    new LinkedBlockingQueue<>(),
    r -> {
        Thread t = new Thread(r, threadName());
        t.setDaemon(true);
        return t;
    },
    new ThreadPoolExecutor.AbortPolicy()
);
```
- **核心线程数 = 最大线程数 = 1**：保证消息严格按入队顺序处理（FIFO），避免并发竞争
- **keepAliveTime = 0**：核心线程不需要回收超时
- **无界队列 `LinkedBlockingQueue<>()`**：配合 `AbortPolicy`，由于只有 1 个线程且通过 `submit()` 仅提交一次 `startConsumer` 任务，队列仅用作缓冲
- **`t.setDaemon(true)`**：线程设为守护线程。当 JVM 中只剩守护线程时，JVM 自动退出，不会阻塞应用关闭
- **拒绝策略 `AbortPolicy`**：理论上不会触发（只提交一次任务），但作为防御性编程保留

**步骤 3：启动消费循环**
```java
running.set(true);
executorService.submit(this::startConsumer);
```
- 先设置 `running = true`，再提交 `startConsumer` 任务
- 使用 `AtomicBoolean` 保证 `running` 的线程可见性

---

### 2.3 `@PreDestroy shutdown()` —— 关闭方法

```java
@PreDestroy
public void shutdown()
```

**优雅关闭流程：**

1. `running.set(false)` —— 标志位置 `false`，消费循环中 `while (running.get())` 检测到后退出
2. `executorService.shutdown()` —— 温和关闭线程池，不再接受新任务，等待当前任务完成
3. `log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName)` —— 记录关闭日志

**注意**：未使用 `shutdownNow()`（会中断正在执行的任务），因为消息处理中不应被暴力中断。当前消息处理完成后循环自然退出。

---

### 2.4 `startConsumer()` —— 启动消费流程

```java
private void startConsumer()
```

**逻辑分两步：**

**步骤 1：创建 Consumer Group**
```java
try {
    redisService.createStreamGroup(streamKey(), groupName());
    log.info("Redis Stream group is ready: {}", groupName());
} catch (Exception e) {
    log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
}
```
- 调用 `redisService.createStreamGroup(streamKey(), groupName())` 在 Redis 中创建消费者组
- **BUSYGROUP 容错**：如果组已存在（多实例启动或重启场景），Redis 返回 `BUSYGROUP Consumer Group name already exists` 错误。此处 `catch (Exception e)` 捕获后仅 `warn` 级别日志，不中断流程——这保证了幂等启动

**步骤 2：进入消费循环**
```java
consumeLoop();
```

---

### 2.5 `consumeLoop()` —— 主消费循环

```java
private void consumeLoop()
```

**核心结构**：

```java
while (running.get()) {
    try {
        redisService.streamConsumeMessages(
            streamKey(), groupName(), consumerName,
            AsyncTaskStreamConstants.BATCH_SIZE,      // 10
            AsyncTaskStreamConstants.POLL_INTERVAL_MS, // 1000ms
            this::processMessage
        );
    } catch (Exception e) {
        if (Thread.currentThread().isInterrupted()) {
            break;  // 线程被中断时优雅退出
        }
        log.error("Failed to consume message", e);  // 非中断异常记录后继续
    }
}
```

**关键设计细节：**

- **`running` 标志控制生命周期**：`shutdown()` 将 `running` 置为 `false` 后，循环自然退出
- **`BATCH_SIZE = 10`**：每次从 Stream 拉取最多 10 条消息，平衡吞吐与延迟
- **`POLL_INTERVAL_MS = 1000`**：当 Stream 中无新消息时，阻塞等待 1 秒后返回空结果
- **方法引用 `this::processMessage`**：每条消息的回调处理器，由 `RedisService.streamConsumeMessages()` 内部逐条调用
- **`Thread.interrupted()` 检测**：捕获异常后先判断线程中断状态，若是中断导致则 `break` 退出；否则仅记录错误日志后继续消费
- **无 `Thread.sleep()` 退避**：`running=false` 后下轮循环立即退出，无需等待

---

### 2.6 `processMessage()` —— 核心模板方法

```java
private void processMessage(StreamMessageId messageId, Map<String, String> data)
```

这是整个消费者框架中**最核心的方法**，实现了消息处理的标准模板流程。

**完整执行流程：**

```
┌─────────────────────────────────────────┐
│  1. 保存当前 MDC traceId                │
├─────────────────────────────────────────┤
│  2. 从消息中恢复 traceId → 设置 MDC     │
├─────────────────────────────────────────┤
│  3. parsePayload() 解析消息载荷          │
│     └── null? → ACK + return（跳过）    │
├─────────────────────────────────────────┤
│  4. parseRetryCount() 解析重试次数       │
├─────────────────────────────────────────┤
│  5. 进入 try-catch 处理块               │
│                                         │
│  ┌─ 正常路径 ─────────────────────────┐ │
│  │ markProcessing(payload)            │ │
│  │ processBusiness(payload)           │ │
│  │ markCompleted(payload)             │ │
│  │ ackMessage(messageId)              │ │
│  └────────────────────────────────────┘ │
│                                         │
│  ┌─ 取消路径 ─────────────────────────┐ │
│  │ catch AsyncTaskCancelledException  │ │
│  │   markCancelled(payload, reason)   │ │
│  │   ackMessage(messageId)            │ │
│  └────────────────────────────────────┘ │
│                                         │
│  ┌─ 异常路径 ─────────────────────────┐ │
│  │ catch Exception                    │ │
│  │   retryCount < MAX(3)?             │ │
│  │     YES → retryMessage(重试+1)     │ │
│  │     NO  → markFailed(payload)      │ │
│  │   ackMessage(messageId)            │ │
│  └────────────────────────────────────┘ │
├─────────────────────────────────────────┤
│  6. finally: 恢复 MDC traceId           │
└─────────────────────────────────────────┘
```

**分段详解：**

**（1）TraceId 上下文传递（可选能力）**
```java
String previousTraceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
String traceId = data.get(AsyncTaskStreamConstants.FIELD_TRACE_ID);
if (traceId != null && !traceId.isBlank()) {
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, traceId);
}
```
异步线程默认丢失 HTTP 请求的 `traceId`。生产者在发送消息时将当前 `traceId` 写入消息 `Map`（`AsyncTaskStreamConstants.FIELD_TRACE_ID = "traceId"`），消费者恢复后设置到 MDC。这样消费者日志中能关联到原始 HTTP 请求，实现**全链路日志追踪**。

**（2）载荷解析 — 防御性设计**
```java
T payload = parsePayload(messageId, data);
if (payload == null) {
    ackMessage(messageId);  // 格式错误的消息直接 ACK 丢弃
    return;
}
```
`parsePayload()` 返回 `null` 表示消息格式错误（如 ID 字段缺失、无法转为数字等），直接 ACK 确认跳过——防止毒消息阻塞队列。

**（3）正常处理路径**
```java
markProcessing(payload);    // 更新 DB 状态为 PROCESSING
recordProcessing();         // Micrometer 计数器 +1
processBusiness(payload);   // 执行业务（LLM 调用等）
markCompleted(payload);     // 更新 DB 状态为 COMPLETED
recordCompleted();          // Micrometer 计数器 +1
ackMessage(messageId);      // ACK 确认（从 PEL 移除）
```
- **状态机**：PENDING → PROCESSING → COMPLETED
- **指标记录**：通过 `metricsRecorder`（可能为 null）向 Micrometer 上报 `app.async.task.processing` 和 `app.async.task.completed` 计数器

**（4）取消路径**
```java
catch (AsyncTaskCancelledException e) {
    markCancelled(payload, truncateError(e.getMessage()));
    ackMessage(messageId);
}
```
- `AsyncTaskCancelledException` 是 `BusinessException` 的子类，携带 `ErrorCode.NOT_FOUND`
- 典型场景：消息处理前发现业务实体（如简历、面试会话）已被用户删除
- 处理：标记为 CANCELLED 状态，ACK 确认——**不重试**，因为实体已不存在

**（5）异常重试路径**
```java
catch (Exception e) {
    if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) { // < 3
        recordRetry();
        retryMessage(payload, retryCount + 1);  // 重新入队，retryCount+1
    } else {
        recordFailed();
        markFailed(payload, truncateError(
            taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage()
        ));
    }
    ackMessage(messageId);  // 无论重试还是失败，当前消息都 ACK
}
```
- **最大重试 3 次**：`MAX_RETRY_COUNT = 3`，即最多处理 4 次（初始 + 3 次重试）
- **重试机制**：调用 `retryMessage(payload, retryCount + 1)`，由子类实现如何将消息重新放入 Stream（通常通过对应的 Producer `sendTask`）
- **错误截断**：`markFailed` 时用 `truncateError()` 限制错误消息最长 500 字符，防止恶意或异常情况下超长错误信息导致存储问题
- **无论哪种路径都 ACK**：当前消息的处理已"终结"（重试会生成新消息），必须 ACK 避免 PEL 堆积

**（6）finally 块 — MDC 恢复**
```java
finally {
    if (previousTraceId == null) {
        MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
    } else {
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, previousTraceId);
    }
}
```
防止 traceId 泄漏到线程池中后续其他任务的处理。区分 `null` 和空字符串：原来没有则 remove，原来有则恢复原值。

---

### 2.7 `ackMessage()` —— ACK 确认

```java
private void ackMessage(StreamMessageId messageId)
```

```java
try {
    redisService.streamAck(streamKey(), groupName(), messageId);
} catch (Exception e) {
    log.error("Failed to ack stream message: messageId={}", messageId, e);
}
```

- ACK 失败仅记录错误日志，不抛异常
- **设计原因**：ACK 失败意味着该消息仍在 PEL（Pending Entries List）中，后续可通过 `recoverPendingMessages()` 方法找回重新处理。如果此处抛异常会导致 `processMessage` 中 finally 块异常，影响 traceId 恢复

---

### 2.8 `recoverPendingMessages()` —— 故障恢复

```java
public int recoverPendingMessages(long idleTimeoutMs, int count)
```

```java
Map<StreamMessageId, Map<String, String>> messages = redisService.streamAutoClaim(
    streamKey(), groupName(), consumerName,
    idleTimeoutMs, count
);
if (messages.isEmpty()) {
    return 0;
}
messages.forEach(this::processMessage);
return messages.size();
```

- **使用场景**：消费者实例崩溃或重启后，PEL 中存在未被确认的消息。通过 `XAUTOCLAIM` 命令将这些消息重新分配给当前消费者处理
- **`idleTimeoutMs`**：消息在 PEL 中空闲超过此时间才被认领（默认使用 `PENDING_IDLE_TIMEOUT_MS = 60_000`，即 60 秒）
- **`count`**：单次恢复数量上限（默认 `PENDING_RECOVERY_BATCH_SIZE = 10`）
- **`public` 访问级别**：对外暴露，可由定时任务（`@Scheduled`）周期性调用
- **返回值**：本次成功恢复并处理的消息数量，便于监控

---

### 2.9 `parseRetryCount()` —— 解析重试次数

```java
protected int parseRetryCount(Map<String, String> data)
```

```java
try {
    return Integer.parseInt(data.getOrDefault(
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
    ));
} catch (NumberFormatException e) {
    return 0;
}
```

- `protected`：允许子类覆写以支持不同字段名或格式
- **容错设计**：字段缺失或格式错误时返回 `0`（从第一次开始计数），避免因数据脏乱导致消息处理失败

---

### 2.10 `truncateError()` —— 错误消息截断

```java
protected String truncateError(String error)
```

```java
if (error == null) {
    return null;
}
return error.length() > 500 ? error.substring(0, 500) : error;
```

- `protected`：子类可根据需要调整截断长度
- **截断阈值 500 字符**：适配数据库 `VARCHAR(500)` 字段
- **null 安全**：入参 null 返回 null，避免 NPE

---

### 2.11 `redisService()` —— 获取 Redis 服务引用

```java
protected RedisService redisService()
```

```java
return redisService;
```

- 简单的 getter，`protected` 可见性
- 供子类使用（例如在 `retryMessage()` 中需要调用 `redisService().streamAdd(...)` 将重试消息重新入队入 Stream）

---

### 2.12–2.15 指标记录方法

四个 `private` 方法，均通过 `metricsRecorder`（可能为 `null`）上报 Micrometer 指标：

| 方法 | 上报指标名 | 调用时机 |
|------|-----------|---------|
| `recordProcessing()` | `app.async.task.processing` | `markProcessing` 之后 |
| `recordCompleted()` | `app.async.task.completed` | `markCompleted` 之后 |
| `recordFailed()` | `app.async.task.failed` | 超过最大重试次数 |
| `recordRetry()` | `app.async.task.retry` | 进入重试流程 |

所有方法均以 `if (metricsRecorder != null)` 做空安全判断。

`AsyncTaskMetricsRecorder` 通过 Spring 注入 `micrometer.MeterRegistry`（`@Autowired(required = false)`），在无监控基础设施的环境中优雅降级。

每条指标的 Tags：
- `task_type`：任务显示名称（如 "简历分析"）
- `stream`：Stream Key（如 "resume:analyze:stream"）

---

### 2.16–2.28 抽象方法列表

以下是子类必须实现的 13 个抽象方法：

| 序号 | 抽象方法 | 返回类型 | 用途 |
|------|----------|----------|------|
| 1 | `taskDisplayName()` | `String` | 任务显示名称，用于日志和指标 Tag（如 "向量化"、"简历分析"、"评估"、"语音面试评估"） |
| 2 | `streamKey()` | `String` | Redis Stream Key，指定消费的目标 Stream |
| 3 | `groupName()` | `String` | Redis Stream 消费者组名称 |
| 4 | `consumerPrefix()` | `String` | 消费者实例名称前缀，拼接 8 位 UUID 构成唯一 consumerName |
| 5 | `threadName()` | `String` | 守护线程名称（如 "vectorize-consumer"、"analyze-consumer"） |
| 6 | `parsePayload(StreamMessageId, Map<String,String>)` | `T` | 将 Redis Stream 消息原始 Map 解析为业务载荷对象，格式错误返回 `null` |
| 7 | `payloadIdentifier(T)` | `String` | 获取载荷唯一标识，用于日志（如 "resumeId=123"、"sessionId=abc"） |
| 8 | `markProcessing(T)` | `void` | 标记任务状态为处理中（更新 DB 为 PROCESSING） |
| 9 | `processBusiness(T)` | `void` | 执行业务逻辑（调用 LLM、向量化、评估打分等耗时操作） |
| 10 | `markCompleted(T)` | `void` | 标记任务已完成（更新 DB 为 COMPLETED） |
| 11 | `markCancelled(T, String)` | `void` | 标记任务已取消（更新 DB 为 CANCELLED，记录取消原因） |
| 12 | `markFailed(T, String)` | `void` | 标记任务已失败（更新 DB 为 FAILED，记录错误原因） |
| 13 | `retryMessage(T, int)` | `void` | 重试消息入队（通过对应 Producer 重新发送到 Stream，retryCount+1） |

**抽象方法分类**：

- **配置类**（1–5）：提供静态配置，每次调用应返回相同值
- **解析类**（6–7）：将 Redis 消息转换为领域对象
- **业务类**（8–13）：状态管理和业务逻辑，构成消息处理的完整生命周期

---

## 三、方法调用关系

### 3.1 初始化调用链

```
Spring 容器启动
    ↓
子类 Bean 构造（RedisService、具体业务依赖注入）
    ↓
@PostConstruct init()
    ├→ consumerName = consumerPrefix() + UUID前8位
    ├→ new ThreadPoolExecutor(1, 1, 0L, ...)   // 单线程守护线程池
    ├→ running.set(true)
    └→ executorService.submit(this::startConsumer)
          ↓
       startConsumer() [守护线程中执行]
          ├→ redisService.createStreamGroup(streamKey(), groupName())
          │     ↓ (BUSYGROUP 异常 → warn 日志后继续)
          └→ consumeLoop()
                ↓
             while(running.get())
                └→ redisService.streamConsumeMessages(...)
                      ↓
                   processMessage(msgId, data)  // 逐条处理
```

**触发条件**：Spring 容器完成所有依赖注入后，检测到 @PostConstruct 注解自动调用 init()。

**关键设计**：init() 在主线程（Spring 启动线程）中执行，但 startConsumer() 通过 executorService.submit() 在独立守护线程中运行，不阻塞 Spring 启动流程。

### 3.2 消息处理调用链（核心）

```
consumeLoop() → redisService.streamConsumeMessages()
                    ↓
              processMessage(messageId, data)
                    ↓
            ┌───────┴──────────────────────────┐
            │ 1. 保存当前 MDC traceId          │
            │ 2. 从消息 data 提取 traceId 设置MDC│
            │ 3. parsePayload(messageId, data)  │ → 解析为泛型 T
            └───────┬──────────────────────────┘
                    │
            payload == null ?
            ├── 是 → ackMessage(messageId) → return（跳过无效消息）
            │
            └── 否 → parseRetryCount(data)
                    │
                    try {
                      ├→ markProcessing(payload)         // 状态→处理中
                      ├→ recordProcessing()              // 指标+1
                      ├→ processBusiness(payload)        // ★业务核心★
                      ├→ markCompleted(payload)          // 状态→已完成
                      ├→ recordCompleted()               // 指标+1
                      └→ ackMessage(messageId)           // ✓ ACK确认
                    }
                    catch (AsyncTaskCancelledException e) {
                      ├→ markCancelled(payload, reason)  // 状态→已取消
                      └→ ackMessage(messageId)           // ✓ ACK确认（不重试）
                    }
                    catch (Exception e) {
                      ├ retryCount < MAX_RETRY_COUNT(3)?
                      │   ├→ recordRetry()              // 指标+1
                      │   ├→ retryMessage(payload, retryCount+1) // 重新入队
                      │   └→ ackMessage(messageId)      // ✓ ACK确认(消息已入新队)
                      │
                      └ retryCount >= 3?
                          ├→ recordFailed()              // 指标+1
                          ├→ markFailed(payload, error)  // 状态→已失败
                          └→ ackMessage(messageId)       // ✓ ACK确认（不再重试）
                    }
                    finally {
                      恢复 MDC traceId
                    }
```

### 3.3 关闭调用链

```
Spring 容器关闭信号（Ctrl+C / SIGTERM / kill -15）
    ↓
@PreDestroy shutdown()    [主线程中执行]
    ├→ running.set(false)    // ① AtomicBoolean CAS 操作
    ↓                         //    效果：consumeLoop 的 while(running.get()) 返回 false
consumeLoop() 检测到 running==false
    ↓
   退出 while 循环 → 守护线程自然结束
    ↓
executorService.shutdown()  // ② 温和关闭线程池
    ↓                         //    效果：拒绝新任务，等当前任务跑完
JVM 退出
```

**双重保护**：
- 正常路径：running.set(false) → consumeLoop 退出 → 线程池 shutdown
- 异常兜底：如果 @PreDestroy 因故未执行，守护线程由 JVM 暴力终止（至少不卡进程）

### 3.4 Pending 消息恢复调用链

```
PendingMessageRecoveryService
    ├→ @EventListener(ApplicationReadyEvent)  // 启动后立即执行一次
    └→ @Scheduled(fixedDelay=60_000)           // 每 60 秒定时执行
            ↓
       recoverPendingMessages(PENDING_IDLE_TIMEOUT_MS=60000, PENDING_RECOVERY_BATCH_SIZE=10)
            ↓
       redisService.streamAutoClaim(streamKey, groupName, consumerName, idleTimeoutMs, count)
            ↓  (Xautoclaim 命令：认领 PEL 中空闲超过 60 秒的消息)
       Map<StreamMessageId, Map<String,String>> messages
            ↓
       messages.forEach(this::processMessage)  // 逐条重新处理
            ↓
       return messages.size()  // 返回恢复数量用于日志统计
```

**触发场景**：
1. 消费者异常崩溃（processMessage 中途退出，消息未 ACK）
2. 消费处理时间过长（超过 60 秒，消息被其他消费者认领）
3. 应用重启（PEL 中残留未完成的消息）

---

## 四、变量与常量分析

### 4.1 成员变量（5 个）

| 变量名 | 类型 | 访问修饰符 | final | 初始值/初始化方式 | 含义 |
|--------|------|-----------|-------|-----------------|------|
| redisService | RedisService | private final | 是 | 构造注入 | Redis 通用服务，用于 Stream 创建、消息拉取、ACK 确认等操作 |
| running | AtomicBoolean | private final | 是 | new AtomicBoolean(false) | 消费者运行状态标志，false 时停止消费循环 |
| executorService | ExecutorService | private | 否 | init() 中创建 | 单线程消费者线程池，保证消息顺序处理 |
| consumerName | String | private | 否 | init() 中生成 | 消费者唯一名称（consumerPrefix + 8 位随机 UUID） |
| metricsRecorder | AsyncTaskMetricsRecorder | private final | 是 | 构造注入（可为 null） | 指标记录器，用于 Micrometer 指标上报 |

**各变量的生命周期**：

| 变量 | 创建时机 | 销毁时机 | 线程安全性 |
|------|---------|---------|-----------|
| redisService | 子类构造时 super() 传入 | Bean 销毁时随对象被 GC | 由 Redisson 客户端保证线程安全 |
| running | 类加载时 new AtomicBoolean(false) | Bean 销毁时 | AtomicBoolean 通过 CAS 保证多线程可见性 |
| executorService | @PostConstruct init() 中创建 | @PreDestroy shutdown() 中关闭 | 由 ThreadPoolExecutor 内部保证 |
| consumerName | @PostConstruct init() 中生成 | 不可变字符串，无需安全保障 | 生成后只读，天然线程安全 |
| metricsRecorder | 子类构造时 super() 传入 | Bean 销毁时 | 由 Micrometer MeterRegistry 保证线程安全（若不为 null） |

**设计意图解析**：

- **redisService (final)**：核心依赖，不可变引用避免运行时被替换导致不可预期的行为。
- **running (AtomicBoolean)**：volatile 语义 + CAS 操作，确保主线程的 `shutdown()` 和守护线程的 `consumeLoop()` 之间的可见性。不需要锁即可实现线程间通信。
- **executorService (非 final)**：init() 中延迟创建，因为必须在 @PostConstruct 阶段获取子类实现的 `threadName()` 才能构造线程工厂。非 final 因为它在运行时才初始化。
- **consumerName (非 final)**：同 executorService，依赖子类实现的 `consumerPrefix()` 才能生成。
- **metricsRecorder (final, 可为 null)**：可选依赖，构造时通过 `@Autowired(required = false)` 注入。null 安全检查在执行指标记录时进行。

### 4.2 引用的常量（来自 AsyncTaskStreamConstants）

| 常量名 | 值 | 使用位置 | 作用 |
|--------|-----|---------|------|
| BATCH_SIZE | 10 | consumeLoop() | 每次拉取的消息批次大小 |
| POLL_INTERVAL_MS | 1000 | consumeLoop() | 消费者轮询阻塞超时（毫秒） |
| MAX_RETRY_COUNT | 3 | processMessage() | 最大重试次数 |
| FIELD_RETRY_COUNT | "retryCount" | parseRetryCount() | 消息中重试次数字段名 |
| FIELD_TRACE_ID | "traceId" | processMessage() | 消息中链路追踪 ID 字段名 |
| STREAM_MAX_LEN | 1000 | retryMessage()（子类调用） | Stream 最大长度，自动裁剪旧消息 |
| PENDING_IDLE_TIMEOUT_MS | 60000 | recoverPendingMessages（调用方传入） | Pending 消息空闲超时阈值 |
| PENDING_RECOVERY_BATCH_SIZE | 10 | recoverPendingMessages（调用方传入） | 单次恢复消息数上限 |

**常量设计分析**：

- **BATCH_SIZE=10**：平衡批量拉取效率与单线程处理能力。一次拉 10 条消息逐条处理，处理完毕后再拉下一批。不会因为批量过大导致单次处理时间过长。
- **POLL_INTERVAL_MS=1000**：使用阻塞拉取模式（XREADGROUP BLOCK 1000），当 Stream 无消息时消费者线程阻塞等待最多 1 秒。好处：无忙轮询 CPU 浪费，有消息时立即返回。
- **MAX_RETRY_COUNT=3**：3 次重试是业界常用的合理的重试上限平衡点。少于 3 次可能因网络抖动导致不必要失败，多于 3 次可能浪费资源且大概率说明是持久性故障。
- **STREAM_MAX_LEN=1000**：Redis Stream 自动裁剪到约 1000 条，防止 Stream 无限增长占用内存。生产环境可根据消息大小和 Redis 内存预算调整。

### 4.3 MDC 相关引用

**TraceIdFilter.TRACE_ID_MDC_KEY** 是项目中定义的 MDC 键名常量，在 `processMessage()` 中用于全链路追踪：

```java
// 进入处理前：保存当前线程的 MDC traceId
String previousTraceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);

// 从消息提取 traceId 设置到消费线程
String traceId = data.get(AsyncTaskStreamConstants.FIELD_TRACE_ID);
MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, traceId);

// finally 块中恢复
if (previousTraceId == null) {
    MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
} else {
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, previousTraceId);
}
```

**设计意义**：MDC 是 ThreadLocal 实现，跨线程不传递。但异步消费中生产者线程和消费者线程是不同的线程，需要通过 Redis Stream 消息字段作为中间载体传递 traceId，实现业务日志的全链路追踪。finally 块中的恢复逻辑确保不影响线程池复用时的下一次消费。

---

## 五、代码联动机制

### 5.1 与 RedisService 的依赖关系

AbstractStreamConsumer 通过构造函数接收 RedisService，不直接操作 RedissonClient，而是通过封装层执行 Redis Stream 操作。

| 方法调用 | RedisService 方法 | 底层 Redis 命令 | 作用 | 调用位置 |
|----------|-------------------|-----------------|------|---------|
| createStreamGroup | streamCreateGroup(streamKey, groupName) | XGROUP CREATE ... MKSTREAM | 创建消费者组 | startConsumer() |
| streamConsumeMessages | streamConsumeMessages(streamKey, groupName, consumerName, batchSize, pollMs, callback) | XREADGROUP | 拉取新消息 | consumeLoop() |
| streamAck | streamAck(streamKey, groupName, messageId) | XACK | 确认消息处理完成 | ackMessage() |
| streamAutoClaim | streamAutoClaim(streamKey, groupName, consumerName, idleMs, count) | XAUTOCLAIM | 认领 PEL 超时消息 | recoverPendingMessages() |

**设计优势**：通过 RedisService 封装层隔离 RedissonClient 底层细节，方便单元测试 Mock，也方便未来切换 Redis 客户端库。

### 5.2 与 AsyncTaskMetricsRecorder 的可选依赖

```java
// metricsRecorder 可以为 null（无 Micrometer 时优雅降级）
private void recordProcessing() {
    if (metricsRecorder != null) {          // null 安全检查
        metricsRecorder.recordProcessing(taskDisplayName(), streamKey());
    }
}
```

| 指标名称 | 触发时机 | 标签 |
|----------|---------|------|
| app.async.task.processing | 开始处理消息 | task_type, stream |
| app.async.task.completed | 消息处理成功 | task_type, stream |
| app.async.task.failed | 消息最终失败 | task_type, stream |
| app.async.task.retry | 消息进入重试 | task_type, stream |

**设计优势**：
- 使用 Micrometer MeterRegistry 上报，兼容 Prometheus / Grafana 监控体系
- @Autowired(required = false) 确保未引入 Micrometer 时也不报错
- 4 个 private 方法确保了 null 安全检查不散落在业务代码中

### 5.3 与 AbstractStreamProducer 的生产-消费配对

```
AbstractStreamProducer<T>              AbstractStreamConsumer<T>
─────────────────────                ──────────────────────
sendTask(payload)                       consumeLoop()
  ↓                                       ↓
buildMessage(payload)                   streamConsumeMessages()
  ↓ (Map<String,String>)                  ↓ (Map<String,String>)
redisService.streamAdd()                parsePayload()
  ↓ (消息进入Redis Stream)                 ↓ (泛型 T)
返回 messageId                          processBusiness()
                                          ↓
                                        ackMessage()
```

**配对关系**：

| 生产者 | 消费者 | 共享 Stream Key | 共享常量 |
|--------|--------|-----------------|---------|
| VectorizeStreamProducer | VectorizeStreamConsumer | KB_VECTORIZE_STREAM_KEY | FIELD_KB_ID, FIELD_TASK_ID |
| AnalyzeStreamProducer | AnalyzeStreamConsumer | RESUME_ANALYZE_STREAM_KEY | FIELD_RESUME_ID, FIELD_TASK_ID |
| EvaluateStreamProducer | EvaluateStreamConsumer | INTERVIEW_EVALUATE_STREAM_KEY | FIELD_SESSION_ID, FIELD_TASK_ID |
| VoiceEvaluateStreamProducer | VoiceEvaluateStreamConsumer | VOICE_EVALUATE_STREAM_KEY | FIELD_VOICE_SESSION_ID, FIELD_TASK_ID |

**数据流路径**：Service 层 → Producer.sendTask(payload) → Redis Stream → Consumer.consumeLoop() → Consumer.processMessage()

### 5.4 与 PendingMessageRecoveryService 的定时恢复机制

```java
// PendingMessageRecoveryService 通过 Spring 注入收集所有 AbstractStreamConsumer Bean
@RequiredArgsConstructor
public class PendingMessageRecoveryService {
    private final List<AbstractStreamConsumer<?>> consumers;
    // Spring 自动收集所有 AbstractStreamConsumer 子类 Bean 注入到该 List
}
```

**双触发机制**：
- `@EventListener(ApplicationReadyEvent.class)`：应用启动后立即执行一次
- `@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)`：启动 60 秒后开始每 60 秒执行一次

### 5.5 与 AsyncTaskCancelledException 的异常协作

```java
// AsyncTaskCancelledException 继承 BusinessException(ErrorCode.NOT_FOUND)
// processMessage 中通过 catch (AsyncTaskCancelledException e) 精确捕获
// 效果：区分"任务需取消"和"任务需重试"两种情况
```

**设计意图**：
- 业务实体被删除 → 子类 processBusiness 抛出 AsyncTaskCancelledException → 标记取消 + ACK，不重试
- 其他异常（LLM 超时、网络错误等）→ 重试或最终失败

### 5.6 TraceId 全链路追踪传递机制

```
生产者端（同步）                        消费者端（异步）
────────────────                    ────────────────
TraceIdFilter 设置 MDC               
    ↓                               
AbstractStreamProducer.sendTask()    
    ↓                               
buildMessageWithTrace(payload)       
    ├→ TraceIdFilter.currentTraceId()  从 MDC 获取当前 traceId
    ├→ 放入消息 Map: traceId → "abc123"
    └→ redisService.streamAdd()        消息写入 Stream（traceId 随消息持久化）
                                       ↓
                                  AbstractStreamConsumer.processMessage()
                                       ├→ data.get(FIELD_TRACE_ID)  从消息中提取
                                       ├→ MDC.put(traceId)          设置到消费线程 MDC
                                       ├→ 业务日志自动关联 traceId
                                       └→ finally 恢复原 MDC（null 则 remove）
```

**关键设计**：跨线程（生产线程→消费线程）的链路追踪通过 Redis Stream 消息字段作为中间载体实现。

---

## 六、执行流程解析

### 6.1 正常消费流程

```
Service层调用 Producer.sendTask(payload)
    ↓
buildMessageWithTrace(payload)
    ↓ 将 payload 字段 + traceId 封装为 Map<String,String>
redisService.streamAdd(streamKey, message, STREAM_MAX_LEN=1000)
    ↓ XADD streamKey MAXLEN ~1000 * field1 val1 field2 val2 ...
消息进入 Redis Stream
    ↓
[Consumer 消费循环]
consumeLoop() → while(running.get())
    ↓
redisService.streamConsumeMessages(streamKey, groupName, consumerName, BATCH_SIZE=10, POLL_INTERVAL_MS=1000, callback)
    ↓ XREADGROUP GROUP groupName consumerName BLOCK 1000 COUNT 10 STREAMS streamKey >
逐条回调 processMessage(messageId, data)
    ↓
parsePayload(messageId, data) → T payload
    ↓
markProcessing(payload)     → 数据库状态: PENDING → PROCESSING
    ↓
processBusiness(payload)    → 调用LLM / 解析文件 / 向量化存储等
    ↓
markCompleted(payload)      → 数据库状态: PROCESSING → COMPLETED
    ↓
ackMessage(messageId)       → XACK streamKey groupName messageId
    ↓                         消息从PEL移除，真正"消费完成"
返回，继续拉取下一条消息
```

**关键特征**：每条消息独立处理（单线程顺序），消息处理成功才会 ACK，未 ACK 的消息保留在 PEL 中。

### 6.2 消息取消流程（实体已删除）

```
processMessage(messageId, data)
    ↓
parsePayload → payload 正常
    ↓
markProcessing(payload)      → PROCESSING
    ↓
processBusiness(payload)
    ↓ 子类执行：resumeRepository.findById(resumeId)
    ↓           → orElseThrow(AsyncTaskCancelledException(...))
    ↓
catch (AsyncTaskCancelledException e)
    ├→ markCancelled(payload, error)     → 数据库状态: CANCELLED 
    └→ ackMessage(messageId)             → ACK，不重试
```

**触发条件**：用户在上传过程中删除简历/知识库，异步任务处理时发现实体不存在。这是正常业务场景，不是系统故障，因此标记 CANCELLED 而非 FAILED。

### 6.3 失败重试流程（retryCount < 3）

```
processMessage(messageId, data)
    ↓
parsePayload → payload 正常
    ↓ retryCount = 0（首次消费）
markProcessing(payload)      → PROCESSING
    ↓
processBusiness(payload)
    ↓ LLM调用超时 / 网络异常 / 其他运行时异常
    ↓
catch (Exception e)  且 retryCount(0) < MAX_RETRY_COUNT(3)
    ├→ recordRetry()                    → 指标: app.async.task.retry +1
    ├→ retryMessage(payload, 1)         → 子类实现：重新 XADD 到同一个 Stream
    │      ↓ 新消息包含 retryCount=1
    └→ ackMessage(messageId)            → ACK 原消息
                                          
新消息重新进入 Stream → 再次被 consumer 拉取 → 从头执行
```

**为什么 ACK 原消息？** 因为子类 retryMessage 已经将任务重新入队（新消息），原消息必须 ACK 否则会残留 PEL 导致重复处理。

### 6.4 最终失败流程（retryCount >= 3）

```
processMessage(messageId, data)
    ↓
parsePayload → parseRetryCount → retryCount = 3
markProcessing(payload)      → PROCESSING
    ↓
processBusiness(payload)     → 仍然失败
    ↓
catch (Exception e)  且 retryCount(3) >= MAX_RETRY_COUNT(3)
    ├→ recordFailed()                   → 指标: app.async.task.failed +1
    ├→ markFailed(payload, truncatedError)
    │      ↓ 子类更新数据库状态为 FAILED，记录错误信息（截断至500字符）
    └→ ackMessage(messageId)            → ACK（不再重试，人工介入）
```

### 6.5 Pending 消息恢复流程

```
场景：消费者处理到一半崩溃（JVM宕机 / OOMKilled / kill -9）

此时状态：
  - 消息已通过 XREADGROUP 拉取，进入 PEL
  - processMessage 未执行到 ackMessage
  - 数据库状态可能为 PROCESSING（markProcessing 已执行）

恢复流程：
  ↓
应用重启 / 定时任务触发
  ↓
PendingMessageRecoveryService.recoverPendingMessages()
  ↓
遍历所有 AbstractStreamConsumer Bean
  ↓
consumer.recoverPendingMessages(
    PENDING_IDLE_TIMEOUT_MS=60000,    // 只认领空闲超过60秒的消息
    PENDING_RECOVERY_BATCH_SIZE=10     // 每次最多10条
)
  ↓
redisService.streamAutoClaim(streamKey, groupName, consumerName, 60000, 10)
  ↓ XAUTOCLAIM streamKey groupName consumerName 60000 COUNT 10
返回 Map<StreamMessageId, Map<String,String>>
  ↓
messages.forEach(this::processMessage)
  ↓ 逐条重新处理（复用正常流程）
  ├→ 幂等保护：子类 processBusiness 中检查是否已 COMPLETED
  └→ ACK 确认 → 从 PEL 移除
```

### 6.6 应用关闭流程

```
外部信号（Ctrl+C / kill -15 / Kubernetes SIGTERM）
    ↓
Spring Boot 收到关闭信号
    ↓
触发所有 Bean 的 @PreDestroy 回调
    ↓
AbstractStreamConsumer.shutdown()
    ├─ ① running.set(false)
    │      ↓
    │   consumeLoop() 检测 running == false
    │      ↓ while 循环退出
    │   守护线程自然结束（当前正在处理的消息会正常完成并 ACK）
    │
    └─ ② executorService.shutdown()
           ↓ 温和关闭：等当前任务跑完
           ↓ 不再接受新任务
    ↓
JVM 退出
    ↓
如果 consumeLoop 卡死（处理中阻塞超过 shutdown 等待时间）：
    └─ 守护线程 → JVM 暴力终止
         ↓ 未 ACK 的消息留在 PEL
         ↓ 下次启动 → recoverPendingMessages() 恢复
```

---

## 七、代码总结与评估

### 7.1 设计模式应用

| 设计模式 | 体现位置 | 作用 |
|----------|---------|------|
| **模板方法模式** | processMessage() 骨架 + 13个抽象方法 | 定义消息处理固定流程，子类只实现差异化业务 |
| **生命周期管理模式** | @PostConstruct init() + @PreDestroy shutdown() | Spring 容器统一管理消费线程的启动与停止 |
| **生产者-消费者模式** | AbstractStreamProducer ↔ AbstractStreamConsumer | 解耦任务提交与任务执行，削峰填谷 |
| **防御性设计** | running AtomicBoolean + shutdown + 守护线程 | 多重保护确保 JVM 可正常退出 |
| **策略模式（变体）** | 异常分派：AsyncTaskCancelledException vs Exception | 取消和失败走不同处理路径 |

### 7.2 性能设计评估

| 维度 | 设计决策 | 评估 |
|------|---------|------|
| **线程模型** | 单线程消费（corePoolSize=1, maxPoolSize=1） | ✅ 消除锁竞争，保证消息顺序；⚠️ 单点吞吐瓶颈 |
| **批量拉取** | BATCH_SIZE=10，每次拉取最多10条 | ✅ 减少网络往返；滑动窗口控制不被撑爆 |
| **阻塞轮询** | POLL_INTERVAL_MS=1000ms 阻塞等待 | ✅ 无消息时 CPU 空转为零 |
| **连接复用** | 单 RedissonClient 通过 RedisService 共享 | ✅ 连接池复用，避免频繁建连 |
| **水平扩展** | 同一消费者组可部署多个实例 | ✅ 灵活扩展消费能力 |
| **Stream 裁剪** | STREAM_MAX_LEN=1000 自动裁剪旧消息 | ✅ 防止 Stream 无限增长 |

### 7.3 可靠性设计评估

| 机制 | 说明 | 评价 |
|------|------|------|
| **ACK 确认** | 每条消息必须 XACK 才真正移除 | ✅ 确保 "至少一次" 投递语义 |
| **PEL 待处理列表** | 未 ACK 的消息永久保留在 PEL 中 | ✅ 消费者崩溃不丢消息 |
| **AUTO CLAIM 恢复** | 启动时 + 每 60 秒定时 reclaim 超时消息 | ✅ 自动故障恢复 |
| **重试上限** | MAX_RETRY_COUNT=3，超过后 FAILED | ✅ 避免无限重试浪费资源 |
| **守护线程兜底** | @PreDestroy 失效时 JVM 仍可退出 | ✅ 防止僵尸进程 |
| **幂等保护** | 子类 processBusiness 检查是否已 COMPLETED | ✅ 重复消息不会导致数据异常 |

### 7.4 潜在问题

| 问题 | 严重程度 | 位置 | 说明 |
|------|---------|------|------|
| **shutdown() 无超时** | 🟡 中等 | shutdown() | executorService.shutdown() 没有 awaitTermination 超时，若消费处理阻塞则关闭流程可能卡死 |
| **ACK 失败静默丢失** | 🟡 中等 | ackMessage() | ACK 失败仅日志记录，消息残留在 PEL 中，依赖后续 AUTO CLAIM 恢复，但恢复有 60 秒延迟 |
| **无界队列风险** | 🟢 轻微 | init() | LinkedBlockingQueue 无界，但单线程只 submit 一次，实际不会积压 |
| **调试困难** | 🟢 轻微 | consumeLoop() | 单线程没有线程名包含任务标识，仅知道是哪个消费者但不知道正在处理哪条消息 |
| **守护线程暴力终止** | 🟢 轻微 | init() | 守护线程被 JVM 杀死时正在处理的消息未 ACK，虽然 PEL 可恢复但处理进度丢失 |

### 7.5 改进建议

| 建议 | 优先级 | 实现方式 |
|------|--------|---------|
| **shutdown 添加超时** | 高 | executorService.shutdown() + awaitTermination(30, SECONDS) + shutdownNow() |
| **ACK 失败重试** | 中 | ACK 失败时记录到"ACK 失败队列"，由独立定时任务重试 XACK |
| **线程名增强** | 低 | 在 processMessage 入口处 Thread.currentThread().setName(threadName + ":" + messageId) |
| **增加限流熔断** | 中 | 连续失败 N 次时暂停消费一段时间，避免雪崩 |
| **死信队列** | 中 | retryCount >= 3 的消息写入独立死信 Stream，而非仅标记数据库 FAILED |

### 7.6 项目架构中的核心作用

```
                          AbstractStreamConsumer
                                  │
        ┌─────────────────────────┼─────────────────────────┐
        │                         │                         │
  向量化管道                  简历分析管道                面试评估管道
  VectorizeStreamConsumer  AnalyzeStreamConsumer     EvaluateStreamConsumer
        │                         │                         │
   知识库上传               简历上传                    面试完成
   → 异步向量化             → 异步AI评分               → 异步AI评估
   → pgvector 存储          → 分析报告入库             → 评估报告生成
```

AbstractStreamConsumer 是整个 AI 面试平台异步任务系统的**骨架基类**，它定义了：

1. **统一的消息消费基础设施**：4 条管道共用同一套消费循环、ACK 确认、失败重试、Pending 恢复机制
2. **标准化的任务生命周期**：PENDING → PROCESSING → COMPLETED / CANCELLED / FAILED
3. **可观测性基础**：Micrometer 指标 + TraceId 全链路追踪
4. **高可用保障**：优雅关闭 + 守护线程兜底 + PEL 自动恢复

**重要性评估**：🔴 核心基础设施类。如果该类出现故障，项目全部 4 条异步管道（知识库、简历、面试、语音面试）同时瘫痪。因此代码质量和可靠性设计至关重要。
