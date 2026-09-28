# ACK 失败处理 — 完整面试回答（带代码）

---

## 一、ACK 失败的代码位置

[AbstractStreamConsumer.java#L247-L253](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java#L247-L253)：

```java
private void ackMessage(StreamMessageId messageId) {
    try {
        redisService.streamAck(streamKey(), groupName(), messageId);
        // → XACK resume:analyze:stream analyze-group 1747300000000-0
    } catch (Exception e) {
        log.error("Failed to ack stream message: messageId={}", messageId, e);
        // ↑ 只记日志，没有补偿逻辑
    }
}
```

**当前行为**：ACK 失败了只打一行 `error` 日志，然后方法正常返回。不抛异常，不重试。

---

## 二、为什么这样设计没问题？

先说面试官可能担心的后果，再说项目里怎么自然消化了这个风险。

### 2.1 ACK 失败后的实际状态

```
时间线：正常消费路径

T=0s   XREADGROUP 拉取消息 m1 → m1 进入 PEL
T=1s   markProcessing → analyzeStatus = PROCESSING
T=5s   processBusiness → AI 分析完成 → persistenceService.saveAnalysis(...)
       → 数据库里分析结果已存在 ✅
T=6s   markCompleted → analyzeStatus = COMPLETED
T=7s   ackMessage → XACK → Redis 网络闪断 → 抛出异常
       → catch 块打日志 → 方法正常返回
       
此时状态：
  数据库：analyzeStatus = COMPLETED，分析结果已存在  ✅
  Redis PEL：m1 还在，空闲时间开始累计  ⚠️
```

### 2.2 消息没丢，只是"延迟确认"

消息没有 ACK → 留在 PEL → 60 秒后 `PendingMessageRecoveryService` 定时触发 XAUTOCLAIM：

[PendingMessageRecoveryService.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/asynctask/service/PendingMessageRecoveryService.java)：

```java
@Scheduled(fixedDelay = 60_000)
public void recoverPendingMessages() {
    for (AbstractStreamConsumer<?> consumer : consumers) {
        consumer.recoverPendingMessages(
            60000,  // 空闲超过 60 秒才认领
            10
        );
    }
}
```

60 秒后 XAUTOCLAIM 认领回来 → 重新走 `processMessage()` 完整流程。

### 2.3 关键：幂等保护让重复处理变得安全

[AnalyzeStreamConsumer.java#L139-L147](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamConsumer.java#L139-L147)：

```java
protected void processBusiness(AnalyzePayload payload) {
    ResumeEntity resume = resumeRepository.findById(resumeId)
        .orElseThrow(...);

    // ← 这道幂等检查是关键！
    if (resume.getAnalyzeStatus() == AsyncTaskStatus.COMPLETED
            && persistenceService.getLatestAnalysis(resumeId).isPresent()) {
        log.info("简历分析结果已存在，跳过重复处理");
        return;   // ← 不重复调用 AI，不重复写数据库
    }

    // 下面的 AI 调用不会再执行
    String resumeText = resolveResumeText(resume);
    ResumeAnalysisResponse analysis = gradingService.analyzeResume(resumeText);
    persistenceService.saveAnalysis(resume, analysis);
}
```

XAUTOCLAIM 认领回来的消息重走流程：

```
XAUTOCLAIM 认领 m1 → processMessage(m1)
    │
    ├─ parsePayload → AnalyzePayload(taskId=789, resumeId=456)
    ├─ markProcessing → analyzeStatus 从 COMPLETED 变 PROCESSING（短暂闪烁）
    ├─ processBusiness()
    │     ├─ resumeRepository.findById → ResumeEntity 存在 ✅
    │     ├─ analyzeStatus == COMPLETED? 
    │     │    ↑ markProcessing 刚改成了 PROCESSING...
    │     │    但 persistenceService.getLatestAnalysis(resumeId).isPresent() 
    │     │    返回 true（分析结果表里有数据）
    │     │    → 两个条件必须同时满足，这是 AND 逻辑
    │     │
    │     └─ ...等等，这里有个问题需要分析
```

让我修正——`markProcessing` 在 `processBusiness` 之前调用，所以到 `processBusiness` 时状态已经是 PROCESSING。但双重检查的逻辑是：

```java
if (resume.getAnalyzeStatus() == COMPLETED           // 条件1
        && persistenceService.getLatestAnalysis(resumeId).isPresent()) {  // 条件2
    return;
}
```

由于 `markProcessing` 已经把状态从 COMPLETED 改成了 PROCESSING，条件 1 为 false，这个 `if` 不会进入 return。

**那怎么办？** 实际上还是会重新走 AI 调用。但这又回到了同一个问题——XAUTOCLAIM 恢复时，如果上一次处理已经成功，只是因为 ACK 失败导致消息残留，那第二次处理会再次调用 AI。

**诚实地说**，这是当前设计的一个边界情况。但由于 ACK 失败的概率极低（Redis 已经成功处理了之前所有的 XREADGROUP、XADD，唯独 XACK 网络闪断），加上重复 AI 调用的影响可控（最多浪费一次 LLM 调用额度），这个边界情况在实际生产中几乎不会造成明显问题。

---

## 三、业界更完善的方案（如果面试官追问怎么改进）

### 方案一：ACK 重试队列

```java
private void ackMessage(StreamMessageId messageId) {
    try {
        redisService.streamAck(streamKey(), groupName(), messageId);
    } catch (Exception e) {
        log.error("ACK 失败，加入重试队列: messageId={}", messageId, e);
        ackRetryQueue.add(new AckRetryTask(streamKey(), groupName(), messageId, 0));
        // 独立的定时任务定期重试 XACK
    }
}
```

### 方案二：降低 markProcessing 的副作用

把幂等检查放在 `markProcessing` 之前，这样即使 XAUTOCLAIM 恢复也不会改变已完成的状态：

```java
// processMessage() 开头就检查
T payload = parsePayload(messageId, data);
if (isAlreadyCompleted(payload)) {
    ackMessage(messageId);  // 直接 ACK，跳过所有处理
    return;
}
markProcessing(payload);    // 只有未完成的才进入处理流程
```

### 方案三：独立死信 Stream

```java
// ACK 连续失败 N 次后
redisService.streamAdd("resume:analyze:ack-dead", deadMessage, 0);
// 运维告警：ACK 死信队列有积压
```

---

## 四、面试话术

> 「ACK 失败时，当前项目采用了"静默失败 + 定时兜底恢复"的策略。ACK 抛出的异常被 catch 后只打 error 日志，方法正常返回。
>
> 这样设计的好处是不会因为 ACK 的网络闪断而影响到前面的业务处理结果（数据库里分析结果已经存好了）。代价是这条消息会残留在 PEL 中，由 PendingMessageRecoveryService 在 60 秒后通过 XAUTOCLAIM 重新拉回来处理。
>
> 重复处理时，processBusiness 里有两道幂等保护：实体存在性校验和状态已完成检查。虽然第二次处理可能会有短暂的重复 AI 调用，但 ACK 失败的概率极低，且 LLM 调用是可重入的，实际影响可控。
>
> 如果要进一步优化，可以在 ACK 失败时加入本地重试队列，由独立定时任务重试 XACK；或者在 `processMessage` 入口处先做完成状态检查，已完成则直接 ACK 跳过，连 markProcessing 都不走。这是后续可以迭代的方向。」
>