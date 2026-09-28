# WebSocket 实时通信协议与运行时状态

## 1. 模块基础讲解

`VoiceInterviewWebSocketHandler` 是语音面试实时链路的核心。它继承 `TextWebSocketHandler`，所有音频、控制命令、字幕、AI 文本和音频都通过 WebSocket 文本帧传输。

连接建立流程：

1. 从 URI `/ws/voice-interview/{sessionId}` 提取 sessionId。
2. 设置文本和二进制消息大小限制为 256KB。
3. 使用 `ConcurrentWebSocketSessionDecorator` 包装原始 session，防止并发写冲突。
4. 把 session 放入 `sessions` map。
5. 创建 `SessionState` 放入 `sessionStates` map。
6. 记录 `lastActivityTime`。
7. 启动 ASR。
8. 发送 welcome 控制消息。
9. 如果没有历史消息，发送开场问题文本和音频。

前端发给后端的消息主要有两类：

```json
{"type":"audio","data":"base64-pcm-audio"}
```

```json
{"type":"control","action":"submit","data":{"text":"可选的手动文本"}}
```

控制消息还支持：

- `submit`：提交当前语音识别缓冲或手动文本，触发 LLM。
- `end_interview`：结束面试。
- `start_phase`：切换阶段。

后端发给前端的消息主要有：

- `control/welcome`：连接成功。
- `subtitle`：ASR 实时字幕或合并后的文本预览。
- `text`：AI 文本回复。
- `audio`：完整 WAV 音频。
- `audio_chunk`：分块 WAV 音频。
- `error`：错误消息。
- `pause_timeout_warning` / `pause_timeout`：暂停超时提醒。

运行时状态 `SessionState` 包含：

- `accumulatedText`：当前轮待送入 LLM 的文本。
- `processing`：是否正在执行 LLM + TTS，CAS 防并发。
- `aiSpeaking`：AI 是否正在说话，用于屏蔽麦克风输入。
- `aiSpeakEndAt`：AI 说话结束后的冷却期。
- `mergeBuffer`：多段 ASR 最终片段的合并缓冲区。
- `mergeStartedAt`：合并开始时间。
- `lastSttActivityAt`：最近 STT 活动时间。
- `processingThread`：当前虚拟线程引用，断连时可中断。

核心设计思想：

- WebSocket IO 线程只做解析和分发。
- 阻塞型 LLM/TTS/JDBC 操作放到虚拟线程池。
- 单会话用 `AtomicBoolean` 防止重复触发 LLM。
- ASR 最终片段先缓冲，用户提交后才触发 LLM。
- AI 播放期间丢弃用户音频，降低回声循环风险。

## 2. 基础面试问题

**问题：WebSocket Handler 接收什么格式的音频？**

回答：前端把 PCM 音频字节做 Base64 编码，包在 JSON 文本帧里发送，格式是 `{"type":"audio","data":"..."}`。后端解码后得到 PCM 字节，再送给 ASR 服务。

**问题：为什么不用 WebSocket binary frame？**

回答：当前实现选择文本帧，便于同时携带 type、control、data 等 JSON 字段，前端处理简单。但代价是 Base64 会增加体积。高性能版本可以改为二进制帧传音频，控制消息继续走文本帧。

**问题：为什么要用 `ConcurrentWebSocketSessionDecorator`？**

回答：LLM 流式文本、TTS 音频、错误消息、暂停通知都可能从不同线程发送 WebSocket 消息。原生 WebSocketSession 并发写不安全，Decorator 可以限制发送时间和缓冲区，减少并发写冲突。

**问题：用户什么时候触发 LLM？**

回答：当前不是 ASR 一定稿就自动触发，而是用户发送 `control submit` 后，后端从 mergeBuffer 取出累积文本并触发 LLM。control 的 `data.text` 也可以手动追加到缓冲区。

**问题：连接关闭后怎么清理？**

回答：`afterConnectionClosed` 会移除 session、移除 SessionState、移除活动时间、停止 ASR。如果有正在处理的虚拟线程，会调用 interrupt。然后尝试自动结束仍在进行中的会话。

## 3. 进阶面试问题

**问题：如何防止用户连续点击提交导致多个 LLM 并发？**

回答：`flushMergedUtteranceToLlm` 用 `AtomicBoolean.compareAndSet(false, true)` 获取处理锁。如果已有处理进行中，会用调度器 400ms 后重试，而不是立即丢弃。处理线程 finally 中一定释放锁，避免会话永久卡住。

**问题：为什么要保存 processingThread？**

回答：LLM/TTS 在虚拟线程中执行，WebSocket 异常关闭时，如果不记录线程引用，后台任务可能继续调用外部服务和发送消息。保存线程后，断连时可以 interrupt，帮助尽快结束资源占用。

**问题：为什么 ASR 最终片段要进 mergeBuffer，而不是马上调用 LLM？**

回答：ASR 服务端 VAD 可能把一句话切成多段，尤其中文面试中用户会停顿思考。如果每个 final 片段都触发 LLM，会导致 AI 抢话。当前设计把多段定稿合并，用户确认后 submit，减少误触发。

**问题：AI 回声冷却解决什么问题？**

回答：AI TTS 音频通过扬声器播放时，麦克风可能再次拾取，ASR 会把 AI 的声音识别成用户输入，进而触发 LLM 和 TTS，形成回声循环。`aiSpeaking` 和 `aiSpeakEndAt` 用于在 AI 说话期间和冷却期丢弃麦克风输入。

**问题：这个回声冷却有什么边界？**

回答：当前后端在音频发送完成后就设置 `aiSpeaking=false` 并进入 800ms 冷却，但后端不知道前端实际播放何时结束。如果音频较长，前端播放期间仍可能采到回声。更稳的方案是前端播放期间暂停录音或播放结束后发送 ack。

**问题：暂停超时机制怎么实现？**

回答：Handler 用 `lastActivityTime` 记录每个 session 最近收到消息的时间，`@Scheduled(fixedRate = 30000)` 每 30 秒检查。超过 4 分 30 秒发送警告，超过 5 分钟调用 `pauseSession`，关闭 WebSocket 并停止 ASR。

## 4. 技术延伸问题

**问题：如何设计 WebSocket 消息协议更规范？**

回答：可以定义统一 envelope：`type`、`sessionId`、`requestId`、`sequence`、`timestamp`、`payload`。音频 chunk 增加 chunkId、format、sampleRate。服务端响应也带 requestId，方便前端关联请求和错误。

**问题：如何支持断线重连后的续传？**

回答：前端保存最近未确认的音频 chunk 和控制消息，重连后带 lastAckSequence。后端保存已处理 sequence，重复消息幂等丢弃。当前只支持从数据库加载历史消息，不支持音频级断点续传。

**问题：如何从文本帧升级到二进制帧？**

回答：可以让二进制帧只传音频，首几个字节或独立控制帧描述 session/chunk 元数据；控制、字幕、文本和错误继续走 JSON 文本帧。这样减少 Base64 开销，但前后端协议复杂度增加。

**问题：如何做 WebSocket 连接限流？**

回答：握手阶段按 IP、用户和全局连接数限流；连接建立后按 session 消息频率和音频大小限流。还可以限制同一用户同时进行的语音面试数量。当前配置有 rate-limit，但未看到实际接入。

**问题：如何做跨实例 WebSocket？**

回答：WebSocket 连接状态在单实例内存中，横向扩容需要网关粘性会话，或把会话事件通过 Redis Pub/Sub/Kafka 广播到持有连接的实例。ASR 连接也跟实例绑定，不能简单无状态迁移。

## 5. 改进方案问题

**问题：当前 WebSocket 协议最需要补什么？**

回答：补 requestId/sequence 和 ack。现在消息没有幂等标识，重连、重发、乱序时难排查。特别是音频和 submit 控制消息，应有明确顺序和确认机制。

**问题：如何改进回声处理？**

回答：后端冷却只能兜底，最好前端在播放 AI 音频时暂停麦克风采集，播放结束后再恢复，并把播放状态通过 control 告诉后端。也可以接入浏览器回声消除约束或 WebRTC AEC。

**问题：如何避免 WebSocket Handler 过重？**

回答：可以拆出 `VoiceTurnPipelineService` 处理 ASR 文本到 LLM/TTS，拆出 `VoiceSessionRuntimeRegistry` 管理内存状态，Handler 只做协议解析和消息发送。这样代码更容易测试。

**问题：如何处理大音频消息？**

回答：当前超过 200KB 只打 warn。可以直接拒绝过大的音频帧，返回错误，并让前端降低 chunkDuration 或采样参数。也可以改二进制传输减少 Base64 后体积。

**问题：如何让暂停超时更准确？**

回答：当前只看收到任何消息的时间。更准确的是区分用户语音活动、AI 播放、LLM 处理中和纯心跳。用户沉默但 AI 正在说话不应暂停；前端心跳也不应代表用户有效参与。

## 6. 故障处理问题

**问题：用户点击提交后一直没有反应，怎么排查？**

回答：先看 mergeBuffer 是否有内容，前端是否发送 submit。再看 `processing` 是否一直为 true，可能上一次 LLM/TTS 卡住没有释放。检查日志里是否有 `AI响应失败` 或虚拟线程异常。

**问题：WebSocket 经常断开，怎么排查？**

回答：看网关 idle timeout、前端心跳、消息大小、服务端发送超时和缓冲区限制。当前 Decorator 发送时间限制 10 秒、缓冲区 512KB，超出可能导致关闭。

**问题：前端收到重复 AI 文本怎么办？**

回答：流式模式下 onToken 会多次发送 partial text，最后还会发送完整 optimized text。前端需要按消息语义处理：partial 用于覆盖显示，final 用于确认。如果协议没有 final 标记，建议后端补充。

**问题：ASR 定稿字幕和提交内容不一致怎么办？**

回答：ASR partial 只是临时字幕，final 才进入 mergeBuffer。submit 时如果前端传 `data.text`，还会追加手动文本。需要确认前端是否重复传入已在 mergeBuffer 中的文本。

**问题：暂停超时后资源是否清理干净？**

回答：`handlePauseTimeout` 会保存暂停状态、关闭 WebSocket、停止 ASR、清理 session maps 和 lastActivityTime。若 TTS/LLM 正在虚拟线程中执行，关闭回调也会尝试 interrupt processingThread。

