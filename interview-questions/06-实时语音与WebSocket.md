# 实时语音与 WebSocket

## 1. 面试官：为什么语音面试使用 WebSocket，当前是否已经接入 WebRTC？

我的回答：

我的项目需要浏览器持续上传音频，同时接收字幕、AI 文本和合成语音，双向长连接比轮询或普通 HTTP 更自然，因此使用 Spring WebSocket。前端采集 16kHz 单声道 PCM，每秒转成 Base64 放进 JSON 文本帧；后端转发给 DashScope 实时 ASR，再把 LLM/TTS 结果沿同一连接推回。当前没有 WebRTC 媒体传输，代码中也没有 RTCPeerConnection、ICE 或媒体服务器；README 的 TODO 勾选“已接入 WebRTC”与真实实现不一致。WebSocket 实现简单但有 Base64 膨胀、服务端中转和弱网抖动问题，WebRTC 只能作为后续优化。

代码依据：

- `frontend/src/components/AudioRecorder.tsx`
- `frontend/src/api/voiceInterview.ts`
- `app/src/main/java/interview/guide/modules/voiceinterview/config/WebSocketConfig.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java`
- `README.md`

## 2. 面试官：从用户说话到听到 AI 回答，完整实时链路是什么？

我的回答：

我的项目在连接建立后为 sessionId 创建状态并启动长期 ASR 会话。前端音频帧到达 Handler 后解码并调用 `QwenAsrService.sendAudio`；ASR partial 结果实时推字幕，final 片段先进入缓冲，经过可配置的 utterance debounce 或用户 submit 后触发处理。Handler 用 CAS 保证同一会话只运行一条 LLM 管线，`DashscopeLlmService` 读取会话、简历、技能和历史做流式回答；文本按句切分后并发调用 `QwenTtsService`，默认按原顺序合并 PCM、封装 WAV，再发送音频和文本。最后把双方消息、阶段与指标持久化。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/QwenAsrService.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/DashscopeLlmService.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/QwenTtsService.java`

## 3. 面试官：语音链路怎样防止一次话语触发多个 AI 回答？

我的回答：

我的项目同时处理服务端 VAD 的 final 回调、前端 VAD 控制和手动 submit，天然可能重复触发。Handler 为每个连接保存 `AtomicBoolean processing`，通过 `compareAndSet(false, true)` 抢占处理权；忙碌时不会再开一条 LLM 管线，而是短暂延后合并新转写。多个 final 片段先放入用户话语缓冲，防抖到期后一次性消费，结束时在 finally 释放标记。这个本地 CAS 能解决单 JVM、单连接内的重入，但无法跨实例协调；重连后旧连接与新连接短暂并存也需防护。生产多实例时我会引入 session owner、Redis 租约或带版本的状态机。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/dto/WebSocketControlMessage.java`
- `frontend/src/pages/VoiceInterviewPage.tsx`

## 4. 面试官：TTS 为什么并发生成还要按顺序发送，项目是怎么控制并发的？

我的回答：

我把 LLM 流式文本按完整句子切分，句子之间可以并发合成以降低总等待，但播放顺序必须与回答文本一致。Handler 为每句提交一个虚拟线程任务，并用单会话 `Semaphore` 限制同时 TTS 的数量；Future 按原句索引保存，收集时仍按顺序取结果。配置 `chunkedAudioEnabled` 打开时可逐段发送，默认关闭时等待各段完成后拼接 PCM 并一次发送 WAV，所以当前“并发合成”已实现，但“边合成边播放”默认没有启用。若追求首音频延迟，我会启用分块、增加客户端有序缓冲，并处理某一段失败时的缺口。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/config/VoiceInterviewProperties.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/QwenTtsService.java`
- `frontend/src/api/voiceInterview.ts`

## 5. 面试官：如何处理 AI 播放时用户继续说话造成的回声和抢话？

我的回答：

我的项目有两层控制：浏览器采集时请求 echoCancellation、noiseSuppression 和 autoGainControl；服务端状态含 `aiSpeaking`，AI 合成/发送期间以及约 800ms 冷却期内忽略用户音频，降低扬声器回声再次进入 ASR。前端还接入 VAD，能上报 speech start/end。当前方案不是完整的回声消除或 barge-in：服务端只知道音频已发送，不知道浏览器实际播放何时结束，用户真正想打断 AI 时也可能被丢弃；README 同样把无耳机回声列为已知问题。后续应由客户端回报播放状态，并实现可取消 TTS/LLM 的抢话状态机。

代码依据：

- `frontend/src/components/AudioRecorder.tsx`
- `frontend/src/pages/VoiceInterviewPage.tsx`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java`
- `README.md`

## 6. 面试官：WebSocket 断开、空闲和重连分别怎么处理，有什么边界？

我的回答：

我的项目用 `ConcurrentWebSocketSessionDecorator` 限制发送时间和缓冲，前端非正常关闭最多重连三次；后端每 30 秒扫描活动时间，约 4 分 30 秒提醒、5 分钟空闲则暂停。连接关闭时会中断正在运行的管线、停止 ASR、清理内存状态，并调用服务结束仍在进行的会话；ASR 发送失败会尝试重建连接后重发当前音频。边界在于普通网络抖动也可能触发“结束会话”，与前端重连语义冲突，内存状态也不能跨实例恢复。后续我会区分主动结束、临时断线和超时，设置重连宽限期，并把连接归属和关键状态外置。

代码依据：

- `frontend/src/api/voiceInterview.ts`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/QwenAsrService.java`

## 7. 面试官：语音面试的并发与安全限制是否已经完整落地？

我的回答：

我的项目已经有单会话 CAS、TTS `Semaphore`、WebSocket 发送缓冲限制、消息大小限制和空闲清理，也在配置类中预留了 max-per-session、max-per-ip、max-concurrent 等参数。但我没有在 Handler 中看到完整的全局连接准入和基于身份的并发配额闭环；握手路径只依赖 URL 中的 sessionId，也没有 Spring Security 验证会话所有权。因此当前主要是进程内稳定性保护，不是完整安全治理。上线前我会给 REST 创建接口和 WebSocket handshake 加鉴权、短期签名票据、Origin 校验、连接数 Gauge 与分布式并发计数。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/config/VoiceInterviewProperties.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/config/WebSocketConfig.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java`
- `app/build.gradle`

# 本章总结

语音链路以 WebSocket 为会话通道，串联浏览器 PCM、长连接 ASR、流式 LLM 和并发 TTS，并用防抖、CAS、信号量和空闲扫描控制单会话复杂度。

当前实现不是 WebRTC，也没有完成跨实例状态、断线续接和鉴权配额；这些是从演示可用走向生产实时系统的关键差距。

# 核心主线

用有状态 WebSocket 管理实时语音流水线，并通过单会话并发控制保证转写、生成和播放顺序。
