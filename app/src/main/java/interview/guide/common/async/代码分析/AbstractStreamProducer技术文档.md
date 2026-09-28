# AbstractStreamProducer 技术文档

---

## 1. 类概述

`AbstractStreamProducer<T>` 是一个基于 **Redis Stream** 的异步任务生产者模板基类（抽象类），使用 **模板方法模式** 定义了向 Redis Stream 发送消息的统一骨架。它负责将业务载荷对象转换为消息 Map、挂载链路追踪的 traceId、通过 `XADD` 命令写入 Redis Stream，并提供发送失败的异常回调和指标记录。

它和 [AbstractStreamConsumer](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java) 配对组成完整的异步任务管道——前者生产消息，后者消费消息，通过 Redis Stream 这个中间管道解耦。

子类只需实现 4 个抽象方法即可完成特定业务场景的生产者开发。当前有 4 个具体实现：

| 子类 | 用途 | 对应的 Consumer |
|------|------|----------------|
| `VectorizeStreamProducer` | 知识库文档向量化 | `VectorizeStreamConsumer` |
| `AnalyzeStreamProducer` | 简历 AI 分析评分 | `AnalyzeStreamConsumer` |
| `EvaluateStreamProducer` | 面试答题 AI 评估 | `EvaluateStreamConsumer` |
| `VoiceEvaluateStreamProducer` | 语音面试多轮评估 | `VoiceEvaluateStreamConsumer` |

类定义在 [AbstractStreamProducer.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/async/AbstractStreamProducer.java) 。

---

## 2. 依赖分析

### 2.1 导入的类和包

| 导入路径 | 用途 |
|----------|------|
| `interview.guide.common.constant.AsyncTaskStreamConstants` | 异步任务常量配置（Stream 最大长度等） |
| `interview.guide.common.observability.AsyncTaskMetricsRecorder` | 异步任务指标记录器（生产/失败计数） |
| `interview.guide.common.observability.TraceIdFilter` | TraceId 过滤器（从当前线程获取 traceId） |
| `interview.guide.infrastructure.redis.RedisService` | Redis 通用服务封装（Stream Add 操作） |
| `lombok.extern.slf4j.Slf4j` | SLF4J 日志注解 |
| `java.util.LinkedHashMap` | 保持消息字段顺序的 Map 实现 |
| `java.util.Map` | 消息键值对 |

### 2.2 外部库依赖

- **Lombok** — `@Slf4j` 注解自动生成日志对象
- **Redisson** — 底层 Stream `XADD` 命令执行

### 2.3 父类和接口

- 父类：`Object`（无显式继承）
- 泛型参数：`<T>` — 业务载荷类型（和对应的 Consumer 共享同一类型，如 `VectorizePayload`）

### 2.4 配置文件与资源

- 无直接配置文件依赖。配置通过 `AsyncTaskStreamConstants` 管理：
  - `STREAM_MAX_LEN = 1000` — Stream 最大长度（自动裁剪旧消息）
- 各具体生产者的 `streamKey` 也定义在此常量类中

---

## 3. 变量文档

| 变量名 | 类型 | 访问修饰符 | 说明 |
|--------|------|-----------|------|
| `redisService` | `RedisService` | `private final` | Redis 服务依赖，执行 `XADD` 写入操作。不可变，通过构造函数注入 |
| `metricsRecorder` | `AsyncTaskMetricsRecorder` | `private final` | 指标记录器，可为 null（不启用指标时）。记录生产成功/失败数量 |

**与 Consumer 的重要区别**：生产者没有线程池、没有运行状态标志、没有消费者名称。因为生产者不需要持续运行——它是被 Service 层主动调用的无状态 Bean。每次调用 `sendTask()` 就在当前 HTTP 请求线程上同步执行 `XADD`，写完了就返回。

---

## 4. 方法文档

### 4.1 构造函数

```java
protected AbstractStreamProducer(RedisService redisService)
protected AbstractStreamProducer(RedisService redisService, AsyncTaskMetricsRecorder metricsRecorder)
```

- **访问修饰符**：`protected`，仅供子类 `super()` 调用
- **参数 1**：`redisService` — Redis 服务依赖（必填）
- **参数 2**：`metricsRecorder` — 指标记录器（可选，传 null 则不启用指标）
- **逻辑**：单参数构造器委托调用双参数构造器，简单赋值

---

### 4.2 `sendTask()` — 核心方法：发送消息到 Redis Stream

```java
protected String sendTask(T payload)
```

这是整个类的核心方法。流程分三步：

#### 第一步：构建消息体（第 57-63 行）

调用 `buildMessageWithTrace(payload)` 将业务载荷对象转为消息 Map，同时挂载 traceId。

#### 第二步：写入 Redis Stream（第 58-67 行）

```java
String messageId = redisService.streamAdd(
    streamKey(),                          // 如 "resume:analyze:stream"
    buildMessageWithTrace(payload),       // 消息 Map
    AsyncTaskStreamConstants.STREAM_MAX_LEN  // MAXLEN = 1000，超出后裁剪旧消息
);
```

底层 Redis 命令：

```
XADD resume:analyze:stream MAXLEN ~ 1000 * taskId 789 resumeId 456 retryCount 0 traceId abc-123
```

- `*` — Redis 自动生成消息 ID
- `MAXLEN ~ 1000` — 近似裁剪，Stream 长度超过 1000 时删除最旧的消息（防止无限增长）

#### 第三步：结果处理（第 69-80 行）

- **成功**：记录指标（`recordCreated()`）、打印日志、返回 `messageId`
- **失败**：记录日志、记录指标（`recordFailed()`）、回调 `onSendFailed(payload, error)`、返回 `null`

**返回值**：成功时返回 Redis 自动生成的消息 ID（如 `"1747300000000-0"`），失败时返回 `null`。返回值可供调用方判断是否发送成功。

---

### 4.3 `buildMessageWithTrace()` — 构建带链路追踪的消息体

```java
private Map<String, String> buildMessageWithTrace(T payload)
```

```java
Map<String, String> message = new LinkedHashMap<>(buildMessage(payload));
TraceIdFilter.currentTraceId()
    .ifPresent(traceId -> message.put(AsyncTaskStreamConstants.FIELD_TRACE_ID, traceId));
return message;
```

执行逻辑：

1. 调用子类的 `buildMessage(payload)` 获取业务字段 Map，如 `{taskId=789, resumeId=456}`
2. 用 `LinkedHashMap` 包装以保持字段顺序
3. 从当前线程 MDC 中取出 traceId，存在则追加到 Map 中 → `{taskId=789, resumeId=456, traceId=abc-123}`
4. 返回完整消息

**traceId 的旅途**：

```
HTTP 请求进入 → Filter 生成 traceId 写入 MDC
                        │
              Service 调用 sendTask()
                        │
              buildMessageWithTrace() 从 MDC 取 traceId 写入消息
                        │
              XADD 写入 Redis Stream（消息体包含 traceId）
                        │
              消费者 processMessage() 从消息 Map 中取 traceId 写入自己的 MDC
                        │
              消费者处理全过程日志都带同一个 traceId
```

---

### 4.4 指标记录方法

| 方法 | 说明 |
|------|------|
| `recordCreated()` | 生产成功计数 +1 |
| `recordFailed()` | 生产失败计数 +1 |

两个方法都会先检查 `metricsRecorder != null`，为 null 时跳过。

---

### 4.5 `truncateError()` — 截断错误消息

```java
protected String truncateError(String error)
```

- **逻辑**：将错误消息截断为最多 500 字符，防止数据库字段溢出
- **边界**：`null` 返回 `null`
- 和 Consumer 中的同名方法完全一致

---

### 4.6 子类必须实现的抽象方法（共 4 个）

| 方法 | 返回类型 | 用途 |
|------|---------|------|
| `taskDisplayName()` | `String` | 任务显示名（如 "知识库向量化"） |
| `streamKey()` | `String` | Redis Stream Key（如 "knowledgebase:vectorize:stream"） |
| `buildMessage(payload)` | `Map<String, String>` | 将业务载荷转为消息 Map（子类决定哪些字段写入消息） |
| `payloadIdentifier(payload)` | `String` | 获取载荷标识（用于日志，如 "kbId=123"） |
| `onSendFailed(payload, error)` | `void` | 发送失败回调（更新数据库任务状态为 FAILED） |

---

## 5. 核心功能

### 5.1 核心方法

`sendTask()` 是唯一的核心方法。与 `AbstractStreamConsumer.processMessage()` 不同，它不是回调，而是由 Service 层主动调用。

### 5.2 消息生产过程

```
Service 调用 producer.sendTask(payload)
    │
    ▼
buildMessageWithTrace(payload)
    │
    ├── buildMessage(payload)          ← 子类实现，payload → Map
    │     例: {taskId:789, kbId:456}
    │
    └── 追加 traceId
    │     从 TraceIdFilter.currentTraceId() 获取
    │     → {taskId:789, kbId:456, traceId:abc-123}
    │
    ▼
redisService.streamAdd(streamKey, message, MAXLEN=1000)
    │
    │  XADD resume:analyze:stream MAXLEN ~ 1000 *
    │      taskId 789 kbId 456 retryCount 0 traceId abc-123
    │
    │  Redis 返回: "1747300000000-0"
    │
    ▼
返回 messageId / 失败时 onSendFailed
```

### 5.3 关键设计决策

**为什么消息中要写入 retryCount？**
首次发送时 retryCount = 0。消费者失败重试时，会以 retryCount + 1 重新发送。consumer 通过这个字段判断是否需要继续重试，超过 3 次则标记失败。

**为什么 STREAM_MAX_LEN 设为 1000？**
防止 Redis 内存无限增长。`~` 是近似裁剪，Redis 会尽量接近 1000 条而非严格精确（避免性能开销）。对于低频异步任务来说，1000 条足够宽松。

**为什么 Producer 不需要线程池？**
Producer 是被动调用的——Service 层在 HTTP 请求线程中直接调用 `sendTask()`，`XADD` 通常只需 1 毫秒，不需要额外线程。Consumer 需要持续轮询拉消息，才需要独立线程。

**为什么用 LinkedHashMap？**
保持消息字段的插入顺序。方便调试时在 Redis 中看到一致的字段排列，也方便测试验证。

---

## 6. 代码流程分析

### 6.1 完整调用链

```
HTTP 请求
    │
    ▼
Controller
    │
    ▼
Service
    │
    ├── 1. 写数据库（创建异步任务记录，status = PENDING）
    │
    └── 2. 调用 producer.sendTask(payload)
              │
              ├── buildMessageWithTrace(payload)
              │     ├── buildMessage(payload)    ← 子类：{taskId:789, resumeId:456}
              │     └── 追加 traceId             ← {taskId, resumeId, traceId}
              │
              ├── redisService.streamAdd(streamKey, message, MAXLEN)
              │     底层: XADD streamKey MAXLEN ~ 1000 * field1 val1 ...
              │     Redis 返回 messageId
              │
              ├── 成功 → log.info + return messageId
              │
              └── 失败 → log.error + onSendFailed + return null
    │
    ▼
Controller 返回响应给客户端
（不等待消费者处理完成）
```

### 6.2 方法调用关系图

```
sendTask(payload)                           ← Service 层调用
  ├── buildMessageWithTrace(payload)
  │     ├── buildMessage(payload)            ← 子类
  │     └── TraceIdFilter.currentTraceId()
  │           └── MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)
  ├── redisService.streamAdd(streamKey(), message, STREAM_MAX_LEN)
  │     底层: XADD
  ├── recordCreated()                        ← 指标（成功路径）
  │     └── metricsRecorder.recordCreated()
  ├── recordFailed()                         ← 指标（失败路径）
  │     └── metricsRecorder.recordFailed()
  └── onSendFailed(payload, error)           ← 子类（失败路径）
```

---

## 7. 外部交互

### 7.1 依赖的外部类

| 外部类 | 交互方式 | 数据方向 |
|--------|---------|---------|
| `RedisService` | 方法调用 | 单向：生产者 → Redis（XADD 写入） |
| `AsyncTaskStreamConstants` | 读取常量 | 单向：STREAM_MAX_LEN 流入生产者 |
| `AsyncTaskMetricsRecorder` | 方法调用 | 单向：生产者 → 指标记录器（计数） |
| `TraceIdFilter` | 静态方法调用 | 单向：从 MDC 读取 traceId |

### 7.2 与 Service 层的交互

```
Service 层
    │
    ├── 调用 sendTask(payload)
    │      └── 返回 messageId（成功）/ null（失败）
    │
    └── 不等待消费者处理结果（异步解耦）
```

Producer 是 Service 层的工具，被调用的时机通常是在写数据库之后：

```java
// Service 层伪代码
public void submitAnalyzeTask(Long resumeId) {
    // 1. 先持久化
    AnalyzeTaskEntity task = saveTask(resumeId, PENDING);
    // 2. 再发消息
    AnalyzePayload payload = new AnalyzePayload(task.getId(), resumeId);
    producer.sendTask(payload);  // 这一行返回不代表任务已处理完成
    // 3. 返回给前端（消费者在后台异步处理）
}
```

### 7.3 与 Redis Stream 的交互

唯一的交互是将消息写入 Stream。与 Consumer 不同，Producer 不关心消费者组、不关心 PEL、不关心 ACK——它只负责"扔消息进管道"。

```
Producer                              Redis Stream
    │                                      │
    │── XADD streamKey MAXLEN ~ 1000 * ... │
    │                                      │
    │   ←── messageId: "1747300000000-0" ──│
    │                                      │
    │  之后 Producer 不关心消息命运           │
    │                                      │
    │                              Consumer 自己来 XREADGROUP 拉取
```

---

## 8. 类的定位与角色

`AbstractStreamProducer` 在系统架构中充当初以下几个角色：

1. **消息生产者引擎**：将 Redis Stream 的底层 `XADD` 操作封装为高层业务语义

2. **模板方法骨架**：定义了"构建消息 → 挂载 traceId → 写入 Stream → 记录指标"的标准流程

3. **链路追踪传递者**：从 HTTP 请求线程的 MDC 中取出 traceId，写入消息体，使消费者也能拿到同一个 traceId，实现全链路追踪

4. **失败兜底处理**：写入 Stream 失败时，通过 `onSendFailed` 回调通知子类更新数据库状态

**与 AbstractStreamConsumer 的配合**：

```
Service 层
    │
    ├── 1. 写数据库 (PENDING)
    └── 2. Producer.sendTask()  ← 当前 HTTP 线程同步执行
              │
              ▼
         Redis Stream
              │
              ▼
         Consumer 线程异步拉取并处理
              │
              └── 3. 更新数据库 (PROCESSING → COMPLETED/FAILED)
```

**关键时间线**：

```
t0: 用户请求到达
t1: Service 写数据库，状态 = PENDING
t2: Producer.sendTask()，XADD 写入 Stream（1ms）
t3: 返回响应给用户（"任务已提交"）
    ↑ 到这里 HTTP 请求结束，用户线程释放
t4~t30: Consumer 线程异步处理（调 LLM、向量化、评分）
t30: 更新数据库状态 = COMPLETED
```

---

## 9. 完整流程总结

当 Service 层需要提交一个异步任务时，调用 `AbstractStreamProducer` 子类的 `sendTask(payload)` 方法。该方法首先调用子类的 `buildMessage(payload)` 将业务载荷对象（如 `VectorizePayload(taskId, kbId)`）转换为 `Map<String, String>` 格式的消息体（如 `{taskId=789, kbId=456}`），然后通过 `TraceIdFilter.currentTraceId()` 从当前 HTTP 请求线程的 MDC 上下文中取出链路追踪 ID（traceId），追加到消息 Map 中形成完整消息体（`{taskId=789, kbId=456, traceId=abc-123}`）。随后调用 `redisService.streamAdd(streamKey, message, STREAM_MAX_LEN)` 执行 Redis 的 `XADD` 命令，将消息写入对应的 Stream（如 `resume:analyze:stream`），同时指定近似最大长度 1000 以防止消息无限堆积。Redis 成功写入后返回自动生成的消息 ID（如 `1747300000000-0`），生产者记录成功指标并返回该消息 ID；若写入过程中发生异常（如 Redis 连接中断），则记录错误日志和失败指标，调用子类的 `onSendFailed(payload, error)` 回调方法（由子类负责更新数据库中的任务状态为 FAILED），并返回 null 表示发送失败。整个 `sendTask` 过程在调用方线程（通常是 HTTP 请求线程）上同步执行，XADD 操作通常在 1 毫秒内完成，不阻塞请求。生产者发送完消息后即完成职责——消息后续由 `AbstractStreamConsumer` 的消费者线程通过 `XREADGROUP` 异步拉取、解析为相同的业务载荷类型、执行业务逻辑、最终 ACK 确认。生产者与消费者通过 Redis Stream 完全解耦，对方是否在线、处理快慢互不影响。
