这是 Redis Stream 的核心—— **同一份数据，多个消费者组各自独立消费**。逐层拆解：

---

### 第一层：Stream 本身

```
resume:analyze:stream      ← Stream Key，就是消息的容器
    │
    ├── 消息1: 1747300000000-0  {taskId:789, resumeId:456}
    ├── 消息2: 1747300001000-0  {taskId:790, resumeId:457}
    ├── 消息3: 1747300002000-0  {taskId:791, resumeId:456}  ← PEL 中
    ├── 消息4: 1747300003000-0  {taskId:792, resumeId:458}  ← PEL 中
    └── ...（消息永久保留，直到被 MAXLEN 裁剪）
```

Stream 里的消息**不会因为被消费就删除**，这点和 Kafka 一样——消息是持久化的，删不删由 `MAXLEN` 策略决定。

---

### 第二层：消费者组（Consumer Group）

一个 Stream 可以有多个消费者组，**每个组维护自己独立的消费进度**：

```
resume:analyze:stream
    │
    ├── 消费者组: analyze-group        ← 简历分析业务的消费组
    │       last_delivered_id: 1747300001000-0  ← 该组消费到哪了
    │       ...
    │
    └── 消费者组: voice-evaluate-group ← 语音评估的消费组
            last_delivered_id: 1747300000000-0  ← 独立进度，互不干扰！
```

**同一份消息可以被两个组各自消费一次**，类似 Kafka 的不同 consumer group。

---

### 第三层：`last_delivered_id`

```
last_delivered_id: "1747300001000-0"
```

这个值的意思是：analyze-group 这个组**最后一条投递给消费者的消息是 `1747300001000-0`**。下次有消费者来拉消息（`XREADGROUP`），Redis 就从这个 ID 之后开始投：

```
已投递过的：
  1747300000000-0  ← 投过了
  1747300001000-0  ← 最后投的（= last_delivered_id）

待投递的：
  1747300002000-0  ← 下次拉取时投这条
  1747300003000-0
```

---

### 第四层：PEL（Pending Entries List）

先理解一个场景：消息已经投给消费者了，但消费者挂了没来得及 ACK。这时消息去哪了？

**就在 PEL 里。**

```
PEL (Pending Entries List)
    │
    ├── 1747300002000-0 → consumer: analyze-consumer-a3f2b01c
    │     投递时间: 1747300002500
    │     投递次数: 1
    │     ↑ 含义：这条消息已投给 a3f2b01c 这个消费者，但还没收到 ACK
    │
    └── 1747300003000-0 → consumer: analyze-consumer-a3f2b01c
          投递时间: 1747300003500
          投递次数: 1
```

**PEL 就是"已投递但等待确认"的消息清单**。每条记录存四个信息：

| 信息 | 含义 |
|------|------|
| 消息 ID | 哪条消息 |
| 归属消费者 | 投给了谁 |
| 投递时间 | 什么时候投的 |
| 投递次数 | 投了几次（消费者挂了重投时会累加） |

**ACK 做了什么？** `XACK resume:analyze:stream analyze-group 1747300002000-0` → Redis 把这条消息从 PEL 里删掉，就算消费完成了。

**不 ACK 会怎样？** 消息永远留在 PEL，不会被重复投递给其他消费者。你需要通过 `XAUTOCLAIM`（即代码里的 `recoverPendingMessages`）主动认领回来。

---

### 第五层：消费者（Consumer）

```
consumers:
    └── analyze-consumer-a3f2b01c     ← 你的项目启动时 init() 里生成的
            ├── idle_time: 5230ms     ← 这个消费者多久没拉新消息了
            └── pending_count: 2      ← 它手里有 2 条没 ACK 的消息
```

- **`idle_time`**：从消费者最后一次和 Redis 交互到现在的时间。如果特别大（比如 60 秒），说明这个消费者的线程可能卡死或挂了
- **`pending_count`**：这个消费者手里还有几条消息没 ACK

---

### 完整链路：一条消息从发送到消费完毕

```
1. 生产者发送
   XADD resume:analyze:stream * taskId 789 resumeId 456 retryCount 0
   → 消息 1747300002000-0 追加到 Stream

2. 消费者拉取
   XREADGROUP GROUP analyze-group analyze-consumer-a3f2b01c COUNT 1 STREAMS resume:analyze:stream >
   → Redis 返回 1747300002000-0 这条消息
   → Redis 同时把这条消息加入 PEL，记录：投给了 a3f2b01c，投递次数 1

3. 消费者处理中
   processMessage() 执行业务逻辑（调用 LLM 评分）
   此时消息在 PEL 中，pending_count = 1

4a. 处理成功
   XACK resume:analyze:stream analyze-group 1747300002000-0
   → 从 PEL 中删除
   → pending_count = 0

4b. 处理失败（retryCount < 3）
   XACK resume:analyze:stream analyze-group 1747300002000-0  ← 先 ACK 旧消息
   XADD resume:analyze:stream * taskId 789 ... retryCount 1 ← 重新生产新消息
   → 新消息有新的 messageId，进入新的投递循环

4c. 消费者挂了，没 ACK
   消息留在 PEL
   idle_time 不断增长
   → 定时调度器发现 idle_time > 60s
   → XAUTOCLAIM 认领消息给其他消费者（或重启后自己认领）
   → 投递次数从 1 变成 2
```

---

### 和图解里的数据对应

你图中 `analyze-consumer-a3f2b01c` 有两条 pending 消息，idle 了 5230ms：

```
这就是代码里 AbstractStreamConsumer 生成的消费者名称
（consumerPrefix + 8位UUID)
            │
    analyze-consumer-a3f2b01c
            │
    pending_count: 2 ← 正在 processMessage() 里处理的两条消息还没 ACK
    idle_time: 5230ms ← 当前轮询间隔 1000ms，说明正在处理消息中，还没下一轮拉取
```

完全吻合——一个消费者线程拿了消息在处理，还没 ACK，所以 idle 了几秒、pending 了 2 条。