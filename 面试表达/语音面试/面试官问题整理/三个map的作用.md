第一个 `sessions` Map 本质上不是业务缓存，而是一个**在线 WebSocket 连接注册表**：

```java
Map<String, WebSocketSession> sessions
```

它建立了这样的映射：

```text
业务 sessionId → 当前浏览器对应的 WebSocket 连接
```

例如：

```text
session-001 → 用户 A 的 WebSocketSession
session-002 → 用户 B 的 WebSocketSession
session-003 → 用户 C 的 WebSocketSession
```

核心用途是：后续异步线程只知道 `sessionId` 时，可以找到正确的 WebSocket 连接，把消息主动推送给对应用户。

### 为什么需要保存 WebSocketSession

`afterConnectionEstablished()` 执行结束后，其中的局部变量 `session` 就离开了方法作用域。但后续很多操作是异步发生的：

```text
ASR 回调线程 → 推送实时字幕
LLM 虚拟线程 → 推送文本
TTS 异步线程 → 推送音频
超时检测线程 → 推送暂停警告
ASR 重连逻辑 → 判断前端连接是否还存在
```

这些线程通常只有 `sessionId`，所以需要：

```java
WebSocketSession session = sessions.get(sessionId);
```

找到对应连接。

例如 ASR 返回识别结果后，通过它推送字幕：[VoiceInterviewWebSocketHandler.java:750](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java:750)

```java
WebSocketSession session = sessions.get(sessionId);
sendSubtitle(session, recognizedText, false);
```

用户空闲超时时，也通过它找到连接并发送警告：[VoiceInterviewWebSocketHandler.java:1316](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java:1316)

```java
WebSocketSession session = sessions.get(sessionId);
sendMessage(session, pauseWarning);
```

### 三个 Map 的职责区别

| Map | 保存内容 | 解决的问题 |
|---|---|---|
| `sessions` | `sessionId → WebSocketSession` | 消息应该推送给哪个前端连接 |
| `sessionStates` | `sessionId → SessionState` | 该会话当前是否处理 LLM、ASR 文本缓冲、AI 是否在说话 |
| `lastActivityTime` | `sessionId → 时间戳` | 该会话多久没有活动，是否需要警告或暂停 |

可以简单记成：

```text
sessions         → 找连接
sessionStates    → 找运行状态
lastActivityTime → 判断是否超时
```

WebSocket 关闭时会从三个 Map 中删除相应数据，并停止 ASR，避免内存和连接资源泄漏。

另外，你对装饰器的理解需要稍微修正：`ConcurrentWebSocketSessionDecorator` 主要保证的是**多个线程并发发送消息时进行串行化和缓冲**，不是整个 `WebSocketSession` 的所有操作都只能由一个线程执行。

所以面试时可以这样回答：

> `sessions` Map 是服务端当前在线 WebSocket 连接的本地注册表，以业务 sessionId 为 Key 保存并发安全的 WebSocketSession。因为 ASR、LLM、TTS 和定时任务都运行在不同异步线程中，这些线程需要根据 sessionId 找到正确的客户端连接，才能推送字幕、文本、音频和控制消息。连接关闭后会删除映射并释放相关资源。