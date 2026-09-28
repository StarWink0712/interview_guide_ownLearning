## 搜索结果汇总

已完整找到 `AbstractStreamConsumer` 全部四个子类的 `parsePayload` 实现和对应的类型参数。以下是详细对比：

---

### 1. VectorizeStreamConsumer

**文件**: `/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamConsumer.java`

**类声明**（第 41 行）:
```java
public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload>
```

**内部 record**（第 63 行）:
```java
record VectorizePayload(Long taskId, Long kbId) {}
```

**parsePayload 实现**（第 91--98 行）:
```java
protected VectorizePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
    String taskIdStr = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
    String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
    if (taskIdStr == null || kbIdStr == null) {
        log.warn("消息格式错误，跳过: messageId={}", messageId);
        return null;
    }
    return new VectorizePayload(Long.parseLong(taskIdStr), Long.parseLong(kbIdStr));
}
```

**T 的具体类型**: `VectorizePayload(Long taskId, Long kbId)` -- 包含通用任务 ID 和知识库 ID。

---

### 2. AnalyzeStreamConsumer

**文件**: `/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamConsumer.java`

**类声明**（第 43 行）:
```java
public class AnalyzeStreamConsumer extends AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload>
```

**内部 record**（第 82 行）:
```java
record AnalyzePayload(Long taskId, Long resumeId) {}
```

**parsePayload 实现**（第 119--128 行）:
```java
protected AnalyzePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
    String taskIdStr = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
    String resumeIdStr = data.get(AsyncTaskStreamConstants.FIELD_RESUME_ID);
    if (taskIdStr == null || resumeIdStr == null) {
        log.warn("消息格式错误，跳过: messageId={}", messageId);
        return null;
    }
    return new AnalyzePayload(Long.parseLong(taskIdStr), Long.parseLong(resumeIdStr));
}
```

**T 的具体类型**: `AnalyzePayload(Long taskId, Long resumeId)` -- 包含通用任务 ID 和简历 ID。

---

### 3. EvaluateStreamConsumer

**文件**: `/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/interview/listener/EvaluateStreamConsumer.java`

**类声明**（第 49 行）:
```java
public class EvaluateStreamConsumer extends AbstractStreamConsumer<EvaluateStreamConsumer.EvaluatePayload>
```

**内部 record**（第 77 行）:
```java
record EvaluatePayload(Long taskId, String sessionId) {}
```

**parsePayload 实现**（第 105--113 行）:
```java
protected EvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
    String taskIdStr = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
    String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
    if (taskIdStr == null || sessionId == null) {
        log.warn("消息格式错误，跳过: messageId={}", messageId);
        return null;
    }
    return new EvaluatePayload(Long.parseLong(taskIdStr), sessionId);
}
```

**T 的具体类型**: `EvaluatePayload(Long taskId, String sessionId)` -- 包含通用任务 ID 和面试会话 ID（字符串类型）。

---

### 4. VoiceEvaluateStreamConsumer

**文件**: `/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/voiceinterview/listener/VoiceEvaluateStreamConsumer.java`

**类声明**（第 36 行）:
```java
public class VoiceEvaluateStreamConsumer extends AbstractStreamConsumer<VoiceEvaluateStreamConsumer.VoiceEvaluatePayload>
```

**内部 record**（第 80 行）:
```java
record VoiceEvaluatePayload(Long taskId, String sessionId) {}
```

**parsePayload 实现**（第 141--148 行）:
```java
protected VoiceEvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
    String taskIdStr = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
    String sessionId = data.get(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID);
    if (taskIdStr == null || sessionId == null) {
        log.warn("消息格式错误，跳过: messageId={}", messageId);
        return null;
    }
    return new VoiceEvaluatePayload(Long.parseLong(taskIdStr), sessionId);
}
```

**T 的具体类型**: `VoiceEvaluatePayload(Long taskId, String sessionId)` -- 包含通用任务 ID 和语音面试会话 ID（字符串类型）。

---

### 整体对比表

| 子类 | T 的具体类型 | 字段一 | 字段二 | 第二个字段的字段名常量 |
|---|---|---|---|---|
| `VectorizeStreamConsumer` | `VectorizePayload` | `Long taskId` | `Long kbId` | `FIELD_KB_ID` |
| `AnalyzeStreamConsumer` | `AnalyzePayload` | `Long taskId` | `Long resumeId` | `FIELD_RESUME_ID` |
| `EvaluateStreamConsumer` | `EvaluatePayload` | `Long taskId` | `String sessionId` | `FIELD_SESSION_ID` |
| `VoiceEvaluateStreamConsumer` | `VoiceEvaluatePayload` | `Long taskId` | `String sessionId` | `FIELD_VOICE_SESSION_ID` |

---

### 模式总结

四个实现遵循完全一致的模式：

1. 每个子类都定义一个 **内部 Java record** 作为类型 T，包含 `Long taskId`（通用异步任务追踪 ID）+ 一个业务标识字段。
2. `VectorizeStreamConsumer` 和 `AnalyzeStreamConsumer` 的业务标识字段是 `Long` 类型（kbId、resumeId），因为它们对应的实体使用自增 Long 主键。
3. `EvaluateStreamConsumer` 和 `VoiceEvaluateStreamConsumer` 的业务标识字段是 `String` 类型（sessionId），因为面试会话使用 UUID 字符串作为主键。
4. 每个 `parsePayload` 都使用相同的防御性编程模式：从 Map 中取出所需字段，任一字段为 null 则记录警告日志并返回 null（父类会自动 ACK 跳过该毒消息），否则封装为 record 返回。
        
          
`payload` 的具体类型取决于哪个子类，但它们都是简单的 **Java record**（类似不可变的轻量级数据类），只包含 2 个字段：

### 四种子类的 Payload

| 子类 | Payload 类型 | 字段 |
|------|-------------|------|
| `VectorizeStreamConsumer` | `VectorizePayload` | `Long taskId` + `Long kbId` |
| `AnalyzeStreamConsumer` | `AnalyzePayload` | `Long taskId` + `Long resumeId` |
| `EvaluateStreamConsumer` | `EvaluatePayload` | `Long taskId` + `String sessionId` |
| `VoiceEvaluateStreamConsumer` | `VoiceEvaluatePayload` | `Long taskId` + `String sessionId` |

### 数据的来和去

```
Redis Stream 消息（Map<String, String>）
    { "taskId": "789", "kbId": "456" }
                │
                ▼ parsePayload() —— 子类实现的解析方法
    new VectorizePayload(
        Long.parseLong("789"),   → taskId = 789
        Long.parseLong("456")    → kbId = 456
    )
                │
                ▼ 传给后续方法
    markProcessing(payload)      → 用 payload.kbId() 找到知识库记录，更新状态
    processBusiness(payload)     → 用 payload.kbId() 查到文档，调 LLM 向量化
    markCompleted(payload)       → 用 payload.taskId() 更新异步任务表
```

### 格式特点

- 所有子类的 payload 都是定义在子类内部的 **`record`**（Java 14+，不可变、自动生成 getter/equals/hashCode）
- 格式统一：**一个 taskId（通用异步任务追踪 ID）+ 一个业务 ID**
- `kbId`/`resumeId` 用 `Long`（自增主键），`sessionId` 用 `String`（UUID 主键）
- 解析失败（字段为 null）返回 null，父类自动 ACK 丢弃这条"毒消息"

**本质上就是把 Redis 里那条 Map 消息转换成一个类型安全的 Java 对象，方便后续方法使用。**