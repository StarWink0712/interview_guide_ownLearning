
`payloadJson` 是一个存入数据库的 **JSON 快照字符串**，作用就一个：**人工排查时能看清楚这个任务是干什么的**。

---

## 它存了什么

以简历分析为例，[`AnalyzeStreamProducer`](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamProducer.java) 第 166-168 行：

```java
private String payloadJson(Long resumeId, String content) {
    int contentLength = content == null ? 0 : content.length();
    return "{\"resumeId\":" + resumeId + ",\"contentLength\":" + contentLength + "}";
}
```

最终存入数据库 `async_tasks` 表的 `payload_json` 字段：

```json
{"resumeId": 123, "contentLength": 4567}
```

不同业务的 `payloadJson` 内容不同：

| 任务类型 | payloadJson 示例 |
|---|---|
| 简历分析 | `{"resumeId":123, "contentLength":4567}` |
| 知识库向量化 | `{"kbId":5, "filename":"面试题集锦.pdf"}` |
| 面试评估 | `{"sessionId":"a1b2c3d4"}` |

---

## 它和 Redis Stream 消息的区别

**两者存的数据不一样：**

```
┌──────────────────────────────────────────────┐
│ 数据库 async_tasks.payload_json              │
│  {"resumeId":123, "contentLength":4567}      │
│                                              │
│  作用：人工在管理后台看到这条任务时，          │
│        能知道是对哪份简历、多大内容做的分析     │
│        不参与消费者逻辑，纯排查用              │
└──────────────────────────────────────────────┘

┌──────────────────────────────────────────────┐
│ Redis Stream 消息体 (AnalyzeTaskPayload)      │
│  {taskId: 42, resumeId: 123, retryCount: 0} │
│                                              │
│  作用：消费者拿到消息后，                      │
│        用 resumeId 去数据库查简历原文          │
│        用 taskId 调 AsyncTaskService 更新状态  │
│        这是实际驱动业务的数据                  │
└──────────────────────────────────────────────┘
```

简单说：**Stream 消息里只有最小必要的 ID（消费者自己查库取完整数据），`payloadJson` 是给人工看的问题快照。**

[`AsyncTaskService`](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/asynctask/service/AsyncTaskService.java) 第 123 行注释也明确写了：

> `@param payloadJson 任务载荷 JSON，用于人工排查，不参与消息消费逻辑`