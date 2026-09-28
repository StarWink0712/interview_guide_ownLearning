### Map 总结

## 〇、WebSocket 连接缓存与并发保护

整个语音面试链路涉及五张内存 Map 和三条 WebSocket 连接。五张 Map 全部存储在 JVM 堆内存而非 Redis 中，分布在不同类里各司其职。WebSocket Handler 维护三张：sessions 存 sessionId 到前端 WebSocket 连接的映射，用于推送字幕、文本和音频；sessionStates 存 sessionId 到 SessionState 的映射，记录 ASR 文本缓冲（mergeBuffer）、LLM 处理锁（AtomicBoolean CAS）、AI 说话状态（aiSpeaking）和回声冷却计时等运行时状态；lastActivityTime 存 sessionId 到最后收到消息的时间戳，供定时任务检测 5 分钟空闲超时自动暂停。ASR Service 维护两张：sessions 存 sessionId 到 AsrSession 的映射，包含与 DashScope ASR 之间的长连接和三个回调引用；sessionLocks 存 sessionId 到纯 Object 锁实例的映射，用于防止同一会话的 ASR 启动、停止、重连操作并发执行。

三条 WebSocket 连接的生命周期各不相同。前端与后端的连接是长连接，整场面试持续保持，负责上传音频帧和接收字幕、文本、语音。后端与 ASR 的连接也是长连接，每个面试会话建立一条，在 startTranscription 时创建并存入 ASR Service 的 sessions Map，在 stopTranscription 或 Bean 销毁时关闭并移除，持续发送用户音频并异步接收识别结果。是的，先缓存再建立连接。`startTranscriptionLocked` 中第五步 `sessions.put` 把 AsrSession 放入 Map，第六步才启动新线程异步执行 `conversation.connect()` 真正握手。原因是 connect 是阻塞操作，如果放在 synchronized 锁内同步等待 TCP 握手完成，锁持有时间太长，其他线程对该会话的 ASR 操作全部会被阻塞。

<br />

<br />

后端与 TTS 的连接是临时短连接，每次 synthesize 被调用时新建，合成完成或超时后在 finally 块中关闭，因此不需要 Map 缓存。两个长连接通过相同的业务 sessionId 关联：Handler.sessions\[sessionId] 管理前端连接，AsrService.sessions\[sessionId] 管理 ASR 连接，TTS 按需独立建连。

前后端 WebSocket 连接在存入 Handler 的 sessions Map 前，会被 ConcurrentWebSocketSessionDecorator 装饰器包装。多个线程可能同时向同一条连接推送消息——ASR 线程推送字幕、LLM 线程推送文本、TTS 线程推送音频、定时线程推送超时警告——如果直接操作原始 WebSocketSession，并发写入会导致帧状态冲突甚至连接关闭。装饰器的作用是将这些并发发送操作串行化：同一时刻只有一个线程真正向底层连接写数据，其他线程的消息先进入发送缓冲区，当前发送完成后按顺序继续发送。不同会话之间的连接互不影响，可以并行发送。

装饰器还设置了两层保护：sendTimeLimit 为 10 秒，单次发送超过此时间限制时认为连接不可靠；bufferSizeLimit 为 512KB，待发送消息堆积超过此限制时触发缓冲区保护。当发送超时或缓冲区超限时，装饰器标记 limitExceeded 并抛出 SessionLimitExceededException，携带 CloseStatus.SESSION\_NOT\_RELIABLE，默认策略为终止该 WebSocket 连接。这样做的目的是防止前端断网或网络极慢时，一条慢连接长期占用发送锁并导致消息无限堆积，拖累大量异步线程。前端检测到断开后可以重新建立 WebSocket 并恢复面试。

<br />

<br />

所在位置

Map 内容

作用

WebSocket Handler

`sessionId → WebSocketSession`

保存前端与后端的 WebSocket 连接，用于推送字幕、文本和音频

WebSocket Handler

`sessionId → SessionState`

保存会话运行状态，例如 ASR 文本缓冲、LLM 是否处理中、AI 是否正在说话

WebSocket Handler

`sessionId → lastActivityTime`

保存最后活动时间，用于空闲超时检测

ASR Service

`sessionId → AsrSession`

保存后端与 ASR 服务的 WebSocket 连接，用于持续发送音频和接收识别结果

这些 Map 都在当前服务实例的 JVM 内存中，不是 Redis。

### WebSocket 连接总结

```
前端 ←──长期 WebSocket──→ 后端
                          │
                          ├──长期 WebSocket──→ ASR
                          │
                          └──临时 WebSocket──→ TTS
```

- 前端与后端：整场面试保持连接，负责上传音频以及接收字幕、文本和语音。
- 后端与 ASR：每个面试会话建立一条长期连接，持续发送用户音频并接收识别结果。
- 后端与 TTS：每次合成语音时临时建立连接，音频生成完成后关闭，因此不需要 Map 保存。

两个长期连接使用相同的业务 `sessionId` 关联：

```
Handler.sessions[sessionId]  → 前端连接
AsrService.sessions[sessionId] → ASR 连接
```

一句话总结：

> Handler 的 Map 负责管理前端连接和本地运行状态，ASR Service 的 Map 负责管理 ASR 长连接；TTS 按需建立临时连接。业务 `sessionId` 贯穿整个语音处理链路。

<br />

### WebSocket 并发发送保护 缓存的这些websocket用并发类包裹，确保并发安全，即只能有一个线程在同一个时间用这个websocket通道。

<br />

对，更准确地说：

> 多个线程都可以调用同一个 WebSocket 的 `sendMessage()`，但同一时刻只有一个线程真正向底层连接写数据，其他消息会先进入发送缓冲区，之后按顺序发送。

例如语音面试中可能同时有：

```
ASR线程 → 推送字幕
LLM线程 → 推送文本
TTS线程 → 推送音频
定时线程 → 推送超时警告
```

如果它们同时操作原始 `WebSocketSession`：

```
线程A正在发送字幕
线程B同时发送LLM文本
线程C同时发送TTS音频
```

可能出现并发写入异常、消息帧状态冲突甚至连接关闭，因为原始 `WebSocketSession` 通常不保证并发发送安全。

使用装饰器后：

```
线程A → 正在写入底层 WebSocket
线程B → 消息进入缓冲区
线程C → 消息进入缓冲区

线程A完成
→ 发送线程B的消息
→ 再发送线程C的消息
```

因此下面这句话最准确：

> 装饰器不是禁止其他线程调用 WebSocket，而是将多个线程的并发发送操作串行化，确保底层同一时刻只有一个发送操作。

项目还设置了两层保护：

```
sendTimeLimit = 10秒
bufferSizeLimit = 512KB
```

- 单次发送长时间阻塞，超过时间限制时触发连接保护。
- 前端消费太慢导致待发送消息超过 512KB 时，触发缓冲区限制，避免持续堆积造成内存溢出。

它只保护“向前端发送消息”这件事，不会限制：

- 前端向后端发送音频。
- ASR、LLM、TTS 自身并行处理。
- 不同 WebSocket 会话之间并行发送。

因此，不同用户的连接可以同时发送；只有同一个用户的同一条 WebSocket 连接内部，发送操作会被串行化。

<br />

超时防护：服务端发现 WebSocket 长时间无法发送或缓冲区超限，就放弃这个不可靠连接；前端检测到断开或长时间收不到消息后，重新建立 WebSocket。

这里的“连接保护”不是修复连接，而是：

> 当某个前端连接发送过慢时，主动停止继续向该连接发送，必要时关闭连接，防止它长期占用发送锁并导致消息无限堆积。

正常情况：

```
线程 A 正在发送音频
线程 B、C 的消息进入缓冲区
线程 A 完成后继续发送 B、C
```

异常情况：

```
线程 A 发送超过 10 秒
或者缓冲消息超过 512KB
  ↓
装饰器认为该连接不再可靠
  ↓
标记 limitExceeded
  ↓
抛出 SessionLimitExceededException
  ↓
默认策略为 TERMINATE，终止 WebSocket 连接
```

异常携带的关闭状态是：

```
CloseStatus.SESSION_NOT_RELIABLE
```

这样做主要是为了防止：

- 前端断网或网络极慢，发送操作长期占用锁。
- ASR、LLM、TTS 消息不断进入缓冲区。
- 缓冲区持续占用服务器内存。
- 一个慢连接拖累大量异步线程。

需要注意，10 秒不是一个后台定时器主动计时。一般是另一个线程再次发送消息、发现发送锁仍被占用时，才检查当前发送是否已经超过 10 秒。

另外，你项目当前还有一个细节：发送辅助方法捕获了所有异常并只记录日志：\[VoiceInterviewWebSocketHandler.java (line 286)]\(/Users/ogawaaya/Desktop/学习/interview\_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java:286)

因此 `SessionLimitExceededException` 可能被这里吞掉。装饰器会标记该连接超限并停止后续发送，但底层连接不一定立刻被主动关闭。更完善的处理应该单独捕获这个异常并关闭、清理连接：

```
catch (SessionLimitExceededException e) {
  session.close(e.getStatus());
  // 清理 sessions、sessionStates、ASR 连接等
}
```

所以“连接保护”的准确含义是：

> 当发送耗时或待发送缓冲超过限制时，牺牲这个不可靠的 WebSocket 连接，避免慢客户端持续消耗服务端线程和内存；前端之后可以重新连接并恢复面试。

