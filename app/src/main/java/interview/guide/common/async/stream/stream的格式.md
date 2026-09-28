# streamKey 与 Redis Stream 数据结构详解

***

## 一、streamKey 是什么

`streamKey` 就是 **Redis 中这条 Stream 的键名**，相当于关系型数据库里的**表名**，或者文件系统里的**文件名**。它是 `RStream` 接口指向的那个 Redis Key 的字符串标识。

在这个项目里，简历分析管道的 `streamKey` 是子类 `AnalyzeStreamConsumer` 实现返回的常量：

```java
// AnalyzeStreamConsumer.java
protected String streamKey() {
    return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    //     → "resume:analyze:stream"
}
```

类比理解：

```
关系型数据库          Redis Stream
───────────          ────────────
表名: resume_analysis    streamKey: "resume:analyze:stream"
```

***

## 二、项目中的四个 streamKey

| streamKey                        | 用途       |
| -------------------------------- | -------- |
| `resume:analyze:stream`          | 简历分析任务   |
| `knowledgebase:vectorize:stream` | 知识库向量化任务 |
| `interview:evaluate:stream`      | 面试评估任务   |
| `voice:evaluate:stream`          | 语音面试评估任务 |

***

## 三、Redis Stream 内部数据结构

Redis Stream 是 Redis 5.0 引入的数据类型。**它本质上是一个追加日志（append-only log）**，但比单纯的 List 强很多。

一条消息插入 Stream 后，实际存储结构是这样的：

```
Key: resume:analyze:stream (类型: Stream)
messageid代表一个消息，然后field1是taskid，2是简历id，3是重试次数
─────────────────────────────────────────────────────────
│ messageId              │ field1   │ field2    │ field3     │
├────────────────────────┼──────────┼───────────┼────────────┤
│ 1747300000000-0        │ taskId   │ resumeId  │ retryCount │
│                        │ "789"    │ "456"     │ "0"        │
├────────────────────────┼──────────┼───────────┼────────────┤
│ 1747300001000-0        │ taskId   │ resumeId  │ retryCount │
│                        │ "790"    │ "457"     │ "0"        │
├────────────────────────┼──────────┼───────────┼────────────┤
│ 1747300002000-0        │ taskId   │ resumeId  │ retryCount │
│                        │ "791"    │ "456"     │ "1"        │
│                           ↑ 同一条任务的重试消息              │
└────────────────────────┴──────────┴───────────┴────────────┘
```

***

## 四、消息 ID 的格式

消息 ID 格式是 `<毫秒时间戳>-<序号>`，比如 `1747300000000-0`：

```
1747300000000-0
│            │
│            └── 同一毫秒内的自增序号，从 0 开始
└─────────────── 服务器本地时间戳（毫秒）
```

这意味着消息 ID 天然按时间排序，支持范围查询。

***

## 五、消费者组的内部结构

消费者组是 Stream 之上的一个附加结构。每个消费者组维护自己独立的消费进度。

resume:analyze:stream 中就是streamKey，然后下面有若干个消费者组，不同的组有不同的组名；同时streamkey下也存有消息表。

```
resume:analyze:stream
    │
    ├── 消费者组: analyze-group
    │       │
    │       ├── last_delivered_id: "1747300001000-0"  
    │       │     ↑ 组内最后投递的消息 ID
    │       │
    │       ├── PEL (Pending Entries List) ← 已投递但未 ACK 的消息
    │       │       │
    │       │       ├── 1747300002000-0 → consumer: analyze-consumer-a3f2b01c
    │       │       │     ↑ 消息 ID                ↑ 归属的消费者
    │       │       │     投递时间: 1747300002500
    │       │       │     投递次数: 1
    │       │       │
    │       │       └── 1747300003000-0 → consumer: analyze-consumer-a3f2b01c
    │       │
    │       └── consumers:
    │               └── analyze-consumer-a3f2b01c
    │                       ├── idle_time: 5230ms
    │                       └── pending_count: 2
    │
    └── 消费者组: voice-evaluate-group
            └── ...（另一个独立的消费进度）
```

***

对，理解完全正确。总结如下：

```
resume:analyze:stream  (Stream Key = 表名)
    │
    ├── [消息列表]                 ← 所有消息存这里，按 messageId 排序
    │   ├── 1747300000000-0  {taskId:789, ...}
    │   ├── 1747300001000-0  {taskId:790, ...}
    │   └── ...
    │
    ├── analyze-group             ← 消费者组1，独立的消费进度
    │   ├── last_delivered_id     ← 该组消费到哪了
    │   ├── PEL（pending 列表）   ← 已投未 ACK 的消息
    │   └── consumers:            ← 该组下有哪些消费者实例
    │       └── analyze-consumer-xxx
    │
    └── voice-evaluate-group      ← 消费者组2，完全独立的消费进度
        ├── last_delivered_id     ← 和 analyze-group 互不影响
        ├── PEL
        └── consumers:
            └── voice-evaluate-consumer-xxx
```

三个关键点：

- **消息只有一份**，存在 Stream Key 下面，所有消费者组共享
- **消费进度是各组独立的**，analyze-group 消费到哪，不影响 voice-evaluate-group
- **这就是为什么同样的消息可以给不同业务的消费者各处理一次**——简历分析组和语音评估组都能拿到同一份消息数据，各自按自己的节奏消费

<br />

与redis获取消息的代码相对应，

## 六、关键说明

### 6.1 消息不是按组复制的

一个消息被一个消费者组消费后，其他组依然可以独立消费。Stream 里只有一份消息，每个组维护各自的指针和 PEL。

### 6.2 PEL 不是消息副本

PEL 只记录"哪些消息已投递给哪个消费者但还没 ACK"，不复制消息内容。消息内容永远只在 Stream 本体中存一份。

### 6.3 自动裁剪

项目中设置了 `MAXLEN ~1000`，Stream 中的消息总数超过约 1000 条时，Redis 自动删除最旧的消息（已添加到已裁剪的旧消息）。这叫"trimming"，防止 Stream 无限增长。

***

## 七、用 Redis 命令查看

你可以直接用 `redis-cli` 查看这个项目中的 Stream：

```bash
# 查看 Stream 里的消息（前 5 条）
XRANGE resume:analyze:stream - + COUNT 5

# 查看所有消费者组
XINFO GROUPS resume:analyze:stream

# 查看 analyze-group 的 PEL（有哪些消息待 ACK）
XPENDING resume:analyze:stream analyze-group

# 查看 Stream 总消息数
XLEN resume:analyze:stream
```

<br />

Redis Stream 的结构其实和一张**数据库表**非常像，用这个类比就容易懂了：

***

### 把 Stream 理解成一张表

```
表名: resume:analyze:stream
┌──────────────────────┬──────────┬───────────┬─────────────┐
│      messageId       │  taskId  │ resumeId  │ retryCount  │ ← 列名（字段名）
│    （主键，自动生成） │          │           │             │
├──────────────────────┼──────────┼───────────┼─────────────┤
│  1747300000000-0     │  "789"   │  "456"    │    "0"      │ ← 第 1 行数据
├──────────────────────┼──────────┼───────────┼─────────────┤
│  1747300001000-0     │  "790"   │  "457"    │    "0"      │ ← 第 2 行数据
├──────────────────────┼──────────┼───────────┼─────────────┤
│  1747300002000-0     │  "791"   │  "456"    │    "1"      │ ← 第 3 行数据
└──────────────────────┴──────────┴───────────┴─────────────┘
```

- **Stream Key**（`resume:analyze:stream`）= 表名
- **messageId** = 主键，Redis 自动生成，保证唯一且自增
- **field:value** = 列名:列值，一行消息就是一个键值对 Map

***

### 对应到代码里

生产者发送消息时做的事，等价于往表里 INSERT 一行：

```java
// AbstractStreamProducer 里大概这样写（伪代码）
Map<String, String> fields = new HashMap<>();
fields.put("taskId", "789");
fields.put("resumeId", "456");
fields.put("retryCount", "0");

// 等价于 INSERT INTO resume:analyze:stream VALUES (fields)
redisService.streamAdd("resume:analyze:stream", fields);
// Redis 返回自动生成的 messageId: 1747300000000-0
```

消费者读取消息时，等价于 SELECT：

```java
// 返回的就是上表里的一行数据
Map<String, String> data = redisService.streamRead(...);
// data = {"taskId": "789", "resumeId": "456", "retryCount": "0"}

// 这行数据传入 processMessage：
processMessage(messageId, data);
// messageId = "1747300000000-0"
// data       = Map 形式的 {taskId=789, resumeId=456, retryCount=0}
```

***

### 消息 ID 格式：`1747300000000-0`

```
1747300000000  -  0
     │             │
     │             └── 同一毫秒内的序号（这毫秒内第几条消息）
     └──────────────── 毫秒级时间戳（消息插入的时间）
```

举个例子，同一毫秒内插入了 3 条消息，ID 会是这样：

```
1747300000000-0   ← 这毫秒第 1 条
1747300000000-1   ← 这毫秒第 2 条
1747300000000-2   ← 这毫秒第 3 条
```

时间戳保证大方向递增（消息按时间排），序号保证同一毫秒内也能区分先后。

***

### 和传统 List/Queue 的区别

| <br /> | List（LPUSH/RPOP） | Redis Stream          |
| :----- | :--------------- | :-------------------- |
| 消息内容   | 单个字符串            | Map 键值对（多个字段）         |
| 唯一 ID  | 无                | 自动生成，永远唯一             |
| 消费确认   | 取走就没了            | 消费完要 ACK，否则永久 pending |
| 消费者组   | 不支持              | 原生支持，多条消费者竞争          |
| 消息重放   | 不支持              | 可以用消息 ID 回溯历史消息       |

所以你的项目里 Stream 的一行消息 `{"taskId":"789", "resumeId":"456", "retryCount":"0"}` 就是一个简单的 Map，`taskId`、`resumeId`、`retryCount` 只是字段名，由 `AsyncTaskStreamConstants` 定义成常量统一使用，不是什么特殊语法。
