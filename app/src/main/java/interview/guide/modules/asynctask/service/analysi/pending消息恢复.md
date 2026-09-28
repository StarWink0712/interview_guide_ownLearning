确保消息不会丢失能确保执行 的措施

对，而且整个系统有三层保障：

| 层级     | 机制                                           | 保护什么                                 |
| :----- | :------------------------------------------- | :----------------------------------- |
| ① 自动恢复 | `PendingMessageRecoveryService` 每 60 秒 + 启动时 | 消费者崩溃后未 ACK 的 pending 消息             |
| ② 自动重试 | Consumer 内部 `retryCount < 3` 时重新 XADD        | 处理失败（LLM 超时、网络抖动等临时错误）               |
| ③ 管理后台 | `POST /api/async-tasks/{id}/retry`           | 重试耗尽后的 FAILED / DEAD\_LETTER 任务，人工介入 |

三者覆盖了从**瞬间故障 → 持续故障 → 彻底失败**的完整链路。① 救消费者崩溃，② 救临时错误，③ 是最后的兜底。消息真正丢失的窗口非常小。

<br />

这行代码是 Spring 把**所有 Consumer 注入到这个 Service 里**，而不是把 Service 注入到 Consumer 里。

```java
private final List<AbstractStreamConsumer<?>> consumers;
//                  ↑ 类型是 List<Consumer>，不是单个 Consumer
```

Spring 有一个特殊能力：当你声明 `List<某类型>` 作为注入字段时，Spring 会自动把容器中**所有匹配该类型的 Bean**收集到这个 List 中。启动后 `consumers` 的实际内容是：

```
consumers = [
    VectorizeStreamConsumer,        // 知识库向量化管道
    AnalyzeStreamConsumer,          // 简历分析管道
    EvaluateStreamConsumer,         // 面试评估管道
    VoiceEvaluateStreamConsumer     // 语音面试评估管道
]
```

然后 `PendingMessageRecoveryService` 遍历它们：

```java
for (AbstractStreamConsumer<?> consumer : consumers) {
    recovered += consumer.recoverPendingMessages(60000, 10);
    //              ↑ 调度者叫每个消费者去干活
}
```

**调用方向**：

```
PendingMessageRecoveryService（调度者）
    │
    ├──→ VectorizeStreamConsumer.recoverPendingMessages()
    ├──→ AnalyzeStreamConsumer.recoverPendingMessages()
    ├──→ EvaluateStreamConsumer.recoverPendingMessages()
    └──→ VoiceEvaluateStreamConsumer.recoverPendingMessages()
```

是恢复服务**指挥**四个消费者，每个消费者自己调 `streamAutoClaim` 去认领自己管道的 pending 消息。这样新增一种异步任务管道时，只需加一个 Consumer Bean，恢复服务自动覆盖，不用改任何代码。
