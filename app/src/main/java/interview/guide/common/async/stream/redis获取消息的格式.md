## RStream 是什么

核心是：/Users/ogawaaya/Desktop/学习/interview\_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/redis/RedisService.java

在stream的格式的文档中，是完全对应的，获取的消息的rstream格式的key是消息id，然后value本身也是map结构，包含消息id，简历id对等信息。如下所述。

`RStream` 是 **Redisson 对 Redis Stream 数据结构**的对象封装，类似于 `RMap` 对应 Redis Hash、`RList` 对应 Redis List。它提供了一系列操作 Redis Stream 的方法（`add`、`readGroup`、`ack`、`autoClaim` 等）。

***

## 泛型参数 `RStream<String, String>` 的含义

```java
RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
```

两个泛型 `<String, String>` 分别表示 **Stream 消息的 key 类型** 和 **value 类型**。配合 `StringCodec.INSTANCE`（字符串编解码器），表示：

- key 是 `String`（消息中的字段名）
- value 是 `String`（消息中的字段值）

***

## 取出来的消息长什么样

消息消费的返回值类型是：

```java
Map<StreamMessageId, Map<String, String>>
```

举个例子，假设生产者通过 [streamAdd 方法](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/redis/RedisService.java#L339-L350) 发了这样一条消息：

```java
Map<String, String> message = Map.of(
    "taskId", "abc-123",
    "resumeId", "456",
    "retryCount", "0"
);
redisService.streamAdd("my-stream", message);
```

那么 `streamConsumeMessages` 拿到的 `messages` 数据结构就是：

```
Map<StreamMessageId, Map<String, String>>

┌─────────────────────────────────────────────────┐
│  StreamMessageId: "1746000000000-0"              │
│  └── Map<String, String>:                       │
│       ├── "taskId"      → "abc-123"             │
│       ├── "resumeId"    → "456"                 │
│       └── "retryCount"  → "0"                   │
└─────────────────────────────────────────────────┘
```

然后代码遍历处理：

```java
for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
    StreamMessageId messageId = entry.getKey();    // "1746000000000-0"
    Map<String, String> data   = entry.getValue(); // {taskId=abc-123, resumeId=456, ...}
    processor.process(messageId, data);
}
```

***

## 完整的数据流

```
生产者                                  Redis Stream                    消费者
───────                                ────────────                    ───────
streamAdd(streamKey, {                         ↓
  taskId: "abc",               ┌─────────────────────────┐
  resumeId: "456"              │ 1746000000000-0          │    streamConsumeMessages()
})  ──────────────────────────→│   taskId=abc            │←────────
                               │   resumeId=456          │
                               │ 1746000000001-0          │    返回:
                               │   taskId=def            │    Map<StreamMessageId,
                               │   resumeId=789          │        Map<String, String>>
                               └─────────────────────────┘
```

关键点：**Stream 中每条消息本质上就是一个** **`Map<String, String>`（字段名→字段值的键值对集合）**，和 Hash 结构类似，但有消息 ID、消费者组、ACK 确认等队列特性。
