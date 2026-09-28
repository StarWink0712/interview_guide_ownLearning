# getMessages 方法完整实现流程详解

> 接口路径：`GET /api/voice-interview/sessions/{sessionId}/messages`  
> 所属类：`VoiceInterviewController`  
> 功能：获取指定会话的完整对话历史记录，按序号排序返回消息列表  
> 限流策略：无

---

## 一、前端交互触发点

### 触发场景

前端在以下场景调用此接口：
- 面试结束后查看对话回放
- 面试过程中下拉加载历史消息
- 评估页面展示对话详情

```typescript
// 前端 API 调用（voiceInterviewApi.ts）
const messages = await voiceInterviewApi.getMessages(sessionId);
// 按 phase 分组展示对话
const introMessages = messages.filter(m => m.phase === "INTRO");
const techMessages = messages.filter(m => m.phase === "TECH");
```

### API 层封装

普通 HTTP GET 请求，无特殊超时配置。

---

## 二、后端处理全链路

### 2.1 路由映射

```
GET /api/voice-interview/sessions/{sessionId}/messages
  │
  ▼
VoiceInterviewController.getMessages(sessionId)
```

### 2.2 Controller 层

```java
@GetMapping("/sessions/{sessionId}/messages")
public Result<List<VoiceInterviewMessageDTO>> getMessages(@PathVariable Long sessionId) {
    log.info("Getting messages for session: {}", sessionId);
    List<VoiceInterviewMessageDTO> messages =
            voiceInterviewService.getConversationHistoryDTO(sessionId.toString());
    return Result.success(messages);
}
```

### 2.3 Service 层 — getConversationHistoryDTO()

**文件**：`VoiceInterviewService.java` 第 330-343 行

```java
public List<VoiceInterviewMessageDTO> getConversationHistoryDTO(String sessionId) {
    return getConversationHistory(sessionId).stream()
        .map(msg -> VoiceInterviewMessageDTO.builder()
            .id(msg.getId())
            .sessionId(msg.getSessionId())
            .messageType(msg.getMessageType())
            .phase(msg.getPhase() != null ? msg.getPhase().name() : null)
            .userRecognizedText(msg.getUserRecognizedText())      // ASR 识别结果
            .aiGeneratedText(msg.getAiGeneratedText())            // AI 生成的回复
            .timestamp(msg.getTimestamp())
            .sequenceNum(msg.getSequenceNum())
            .build())
        .collect(Collectors.toList());
}
```

### 2.4 底层查询 — getConversationHistory()

```java
public List<VoiceInterviewMessageEntity> getConversationHistory(String sessionId) {
    Long sessionIdLong = parseSessionId(sessionId);
    return messageRepository.findBySessionIdOrderBySequenceNumAsc(sessionIdLong);  // 按序号升序
}
```

**排序方式**：按 `sequenceNum` 升序（`ASC`），保证消息按对话时间先后排列。

### 2.5 消息持久化位置

消息的写入由 `VoiceInterviewService.saveMessage()` 完成，在 WebSocket 处理器中每轮对话结束后调用：

```java
@Transactional
public void saveMessage(String sessionId, String userText, String aiText) {
    // ...查询会话...
    VoiceInterviewMessageEntity message = VoiceInterviewMessageEntity.builder()
            .sessionId(sessionIdLong)
            .messageType("DIALOGUE")
            .phase(session.getCurrentPhase())
            .userRecognizedText(userText)     // ASR 识别的用户语音文本
            .aiGeneratedText(aiText)          // AI 回复文本
            .sequenceNum(getNextSequenceNum(sessionIdLong))
            .build();
    messageRepository.save(message);
}
```

---

## 三、数据模型说明

### 3.1 VoiceInterviewMessageDTO（响应体）

```java
@Data @Builder
public class VoiceInterviewMessageDTO {
    private Long id;                 // 消息 ID
    private Long sessionId;          // 所属会话 ID
    private String messageType;      // 消息类型（如 "DIALOGUE"）
    private String phase;            // 所属阶段（INTRO/TECH/PROJECT/HR）
    private String userRecognizedText;  // 用户语音识别文本
    private String aiGeneratedText;     // AI 回复文本
    private LocalDateTime timestamp;    // 消息时间戳
    private Integer sequenceNum;        // 消息序号
}
```

### 3.2 实际返回 JSON 示例

```json
{
    "code": 200,
    "message": "success",
    "data": [
        {
            "id": 101,
            "sessionId": 42,
            "messageType": "DIALOGUE",
            "phase": "INTRO",
            "userRecognizedText": "你好，我是张三，我有五年Java开发经验",
            "aiGeneratedText": "你好张三，请详细介绍一下你的主要技术栈和项目经验。",
            "timestamp": "2026-06-18T10:30:15",
            "sequenceNum": 1
        },
        {
            "id": 102,
            "sessionId": 42,
            "messageType": "DIALOGUE",
            "phase": "TECH",
            "userRecognizedText": "我主要使用Spring Boot和MyBatis，对微服务架构有深入理解",
            "aiGeneratedText": "请描述一下你使用Spring Boot构建微服务时的服务间通信方式。",
            "timestamp": "2026-06-18T10:32:20",
            "sequenceNum": 2
        }
    ]
}
```

---

## 四、代码模块调用关系

```
GET /api/voice-interview/sessions/{sessionId}/messages
  │
  ▼
VoiceInterviewController.getMessages(sessionId)
  └── voiceInterviewService.getConversationHistoryDTO(sessionId.toString())
      └── getConversationHistory(sessionId)
          ├── parseSessionId(sessionId) → Long
          ├── messageRepository.findBySessionIdOrderBySequenceNumAsc()
          │   └── SELECT * FROM voice_interview_messages
          │       WHERE session_id = ? ORDER BY sequence_num ASC
          └── stream().map() → Entity 转 DTO

  Result.success(messages)
  ▼ JSON { code: 200, data: [...] }
```

---

## 五、异常处理机制

| 场景 | 处理方式 |
|------|---------|
| sessionId 非法格式 | `parseSessionId()` 返回 null，查询返回空结果 |
| 数据库查询失败 | 异常向上抛出，全局异常处理器处理 |
| 无对话记录 | 返回空列表 `[]` |

---

## 六、流程总结

`getMessages` 是语音面试的**对话历史查询接口**。根据 sessionId 查询 `voice_interview_messages` 表（按 `sequence_num` 升序）→ 将 Entity 逐条映射为 `VoiceInterviewMessageDTO` → 返回包含用户 ASR 识别文本和 AI 回复文本的消息列表。每条消息标记所属阶段（`phase`），方便前端按阶段分组展示对话。消息在 WebSocket 对话过程中由 `saveMessage()` 实时写入，此接口仅负责查询。
