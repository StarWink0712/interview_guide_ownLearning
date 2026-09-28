这是一个非常好的观察，你发现了一个关键的设计决策。

---

## `content` 确实没有进入 Redis Stream

你说得完全对。`content`（简历文本）被封装到 `payloadJson` → 存入数据库 `async_task` 表 → **到此为止，不再向下传递**。

真正发送到 Redis Stream 的消息只有：

```
XADD resume:analyze:stream * taskId 789 businessId 456 resumeId 456 retryCount 0 traceId abc-def
```

**没有 content。**

---

## 为什么不把 content 放进 Stream？

因为**没必要**，而且**有害**：

| 如果放 content | 后果 |
|----------------|------|
| 简历文本可能上万字 | Stream 消息膨胀，Redis 内存暴增 |
| 消息重试 3 次 | 同一份大文本存 4 份（原消息 + 3 条重试消息） |
| STREAM_MAX_LEN=1000 自动裁剪 | 可能把其他任务的消息也裁剪掉 |

---

## 消费者怎么拿到简历文本？

看 [AnalyzeStreamConsumer 的 processBusiness()](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamConsumer.java#L139-L154)：

```java
protected void processBusiness(AnalyzePayload payload) {
    // 消息里只有 resumeId，没有 content
    // ↓ 通过 resumeId 从数据库查出完整的 ResumeEntity
    ResumeEntity resume = resumeRepository.findById(payload.resumeId())
        .orElseThrow(...);

    // ↓ 从数据库实体中获取简历文本
    String resumeText = resolveResumeText(resume);
    //     ├─ 优先用 resume.getResumeText()（数据库已有缓存）
    //     └─ 否则从 RustFS 重新下载解析

    // ↓ 用数据库查到的文本调用 AI
    ResumeAnalysisResponse analysis = gradingService.analyzeResume(resumeText);
}
```

---

## `payloadJson` 的用途到底是什么？

```java
// payloadJson 只存在于 async_task 表中，纯粹用于人工排查
private String payloadJson(Long resumeId, String content) {
    int contentLength = content == null ? 0 : content.length();
    return "{\"resumeId\":" + resumeId + ",\"contentLength\":" + contentLength + "}";
}
//   生成：{"resumeId":456, "contentLength":3500}
```

注意：它只记录了 `contentLength`（长度），**连文本内容本身都没存**。纯粹是运维排查用的元数据。

---

## 完整数据流向对比

```
                    ┌─────────── async_task 表（运维用，不参与消费）───────────┐
                    │  payloadJson: {"resumeId":456, "contentLength":3500}      │
                    └──────────────────────────────────────────────────────────┘
                                              ↑ 存入，不走后续流程

    resume_text ──→ 数据库 resume 表（resume_text 字段，消费者从这里读取）
                         ↑
                         │  ResumeUploadService 第 6 步已存入
                         │  persistenceService.saveResume(file, resumeText, ...)
                         │
                    Redis Stream（消息体，轻量）
                         │  {taskId, resumeId, retryCount, traceId}
                         │  ↑ 只有 ID，没有文本
                         ↓
                    Consumer 收到消息
                         │  resumeRepository.findById(resumeId)
                         │  → 从数据库取出 resume_text
                         │  → gradingService.analyzeResume(resumeText)
```

---

## 一句话总结

> **Stream 消息里只放 ID，不放内容。消费者通过 ID 回查数据库获取内容。** 这是消息队列设计中的经典模式——**消息瘦身**，避免大文本撑爆消息中间件的内存。`payloadJson` 只是数据库里的一条运维记录，和消息流转没有关系。