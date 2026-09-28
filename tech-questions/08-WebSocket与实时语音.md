# WebSocket 与实时语音

## 1. 面试官：为什么实时语音使用 WebSocket，而不是普通 HTTP 或轮询？

我的回答：

普通 HTTP 是请求响应模型，轮询会增加延迟与无效请求；实时语音需要客户端持续上传音频，同时服务端主动推送字幕、文本和音频，WebSocket 的全双工长连接更合适。我的项目前端建立 `/ws/voice-interview/{sessionId}` 连接，通过 JSON 帧发送音频和控制消息，后端在同一连接推送 ASR partial/final、LLM 文本和 TTS 音频。REST 仍负责创建、暂停、结束和查询会话，协议职责清晰。当前连接没有协议级心跳和完整鉴权，多实例也依赖内存状态；生产上需补 ping/pong、票据和连接路由。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/config/WebSocketConfig.java` — WebSocket 路由
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — 双向处理
- `frontend/src/api/voiceInterview.ts` — `VoiceInterviewWebSocket`
- `app/src/main/java/interview/guide/modules/voiceinterview/controller/VoiceInterviewController.java` — REST 生命周期

## 2. 面试官：为什么当前没有使用 WebRTC，WebSocket 和 WebRTC 如何选择？

我的回答：

WebRTC 为实时媒体提供 UDP 优先传输、抖动缓冲、拥塞控制、NAT 穿透和回声处理，适合低延迟音视频；WebSocket 基于可靠有序 TCP，集成简单，但弱网下可能产生队头阻塞，音频还要经过应用服务器。我的项目实际使用 WebSocket 和 Base64 PCM，没有 RTCPeerConnection、ICE、STUN/TURN 或媒体服务器；README 中“WebRTC 已完成”的勾选与代码不一致。当前方案适合原型与单人语音面试。若延迟、弱网和打断成为核心指标，我会评估 WebRTC，但还要承担信令、媒体网关和录制链路复杂度。

代码依据：

- `frontend/src/api/voiceInterview.ts` — WebSocket 客户端
- `frontend/src/components/AudioRecorder.tsx` — PCM 采集
- `app/src/main/java/interview/guide/modules/voiceinterview/config/WebSocketConfig.java` — 服务端协议
- `README.md` — WebRTC TODO 与已知问题

## 3. 面试官：浏览器如何采集音频并转换成后端需要的 PCM？

我的回答：

浏览器原始麦克风样本通常是 Float32，实时 ASR 常要求固定采样率的 Int16 PCM。我的项目前端通过 `getUserMedia` 获取音频，开启 echoCancellation、noiseSuppression 和 autoGainControl，创建 16kHz `AudioContext`；`ScriptProcessorNode` 读取单声道 Float32，裁剪到 [-1,1] 后映射为 Int16，每累计 16000 个样本形成约一秒 chunk，再转 Base64。Analyser 同时展示音量，vad-web 复用 MediaStream 做语音起止检测。当前 ScriptProcessor 已废弃，主线程 Base64 开销也高，生产上应改 AudioWorklet 和二进制帧。

代码依据：

- `frontend/src/components/AudioRecorder.tsx` — `startRecording()`
- `frontend/src/components/AudioRecorder.tsx` — `float32ToInt16Pcm()`
- `frontend/src/components/AudioRecorder.tsx` — `int16ToBase64()`
- `frontend/index.html` — VAD 资源

## 4. 面试官：请说明 PCM、ASR、LLM 和 TTS 的完整实时处理链路。

我的回答：

我的项目在 WebSocket 建立后为 sessionId 创建运行状态并启动 DashScope 长连接 ASR。前端音频 JSON 到达后，Handler 解码 Base64 为 PCM，调用 `QwenAsrService.sendAudio`；partial 结果实时发字幕，final 片段进入话语缓冲，防抖或手动 submit 后触发 LLM。`DashscopeLlmService` 结合技能、简历和历史流式生成文本，Handler 按句切分并发调用 `QwenTtsService`，默认按原顺序合并 PCM、封装 WAV 后发送。用户与 AI 消息落库，结束后通过 Stream 异步生成统一评估报告。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — 音频与消息管线
- `app/src/main/java/interview/guide/modules/voiceinterview/service/QwenAsrService.java` — `sendAudio()`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/DashscopeLlmService.java` — 流式生成
- `app/src/main/java/interview/guide/modules/voiceinterview/service/QwenTtsService.java` — 语音合成

## 5. 面试官：WebSocket 连接如何和业务会话、用户身份绑定？

我的回答：

连接建立时必须验证调用者有权访问业务会话，而不能只相信 URL 参数。我的项目从 `/ws/voice-interview/{sessionId}` 提取 sessionId，查询会话并在多个 ConcurrentHashMap 中保存 WebSocket、SessionState、开场音频和活动时间；`HttpSessionHandshakeInterceptor` 只传递 HTTP Session 属性。当前没有 Spring Security，握手也没有验证 JWT、短期票据或会话所有权，语音服务还会使用默认 userId，因此绑定主要是业务 ID，不是安全身份。生产上我会在握手阶段校验签名 token、Principal 和 session owner，并限制票据单次使用与有效期。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/config/WebSocketConfig.java` — 握手配置
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `afterConnectionEstablished()`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java` — 会话查询与默认用户
- `app/build.gradle` — 当前无 Spring Security

## 6. 面试官：实时流中如何处理音频顺序、丢包和重复消息？

我的回答：

WebSocket 基于 TCP，单连接内帧可靠有序，因此当前前端没有给音频 chunk 增加 sequence，后端收到后直接送 ASR；这避免了应用层重排，但网络丢包会表现为 TCP 重传和延迟抖动，而不是可选择丢弃的音频包。断线重连时正在传输的 chunk 不会自动补发，服务端也没有去重序号；TTS 下行 chunk 则携带 index 和 isLast，前端按队列播放。当前适合稳定网络。若需要精确恢复，我会给上行音频增加 sequence、会话 epoch 和时间戳，检测 gap/duplicate，并决定补发、填静音或跳过。

代码依据：

- `frontend/src/api/voiceInterview.ts` — `sendAudio()`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — 音频接收
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `sendAudioChunk()`
- `frontend/src/pages/VoiceInterviewPage.tsx` — 分块音频播放队列

## 7. 面试官：断线重连和心跳机制当前如何实现，有什么问题？

我的回答：

我的项目前端在非正常关闭时最多重连三次，每次间隔两秒；后端每 30 秒扫描 lastActivity，约 4 分 30 秒发空闲警告，5 分钟后暂停会话。ASR 发送失败时，服务还会尝试重建 DashScope 连接并重发当前音频。它不是标准 WebSocket ping/pong 心跳：只看业务活动，代理可能在无音频时提前断开；更重要的是 `afterConnectionClosed` 会清理状态并结束进行中的会话，与前端自动重连存在语义冲突。生产上应增加协议心跳、重连宽限期、connection epoch，并区分主动结束、临时断线和真正超时。

代码依据：

- `frontend/src/api/voiceInterview.ts` — 自动重连
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `afterConnectionClosed()`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — 空闲扫描
- `app/src/main/java/interview/guide/modules/voiceinterview/service/QwenAsrService.java` — ASR 重连

## 8. 面试官：如何处理背压和慢客户端，项目当前做了哪些保护？

我的回答：

背压是生产速度高于消费速度时限制或减缓上游，避免内存无限堆积。我的项目用 `ConcurrentWebSocketSessionDecorator` 包装连接，设置约 10 秒发送时间限制和 512KB 缓冲，文本与二进制消息也有 256KB 上限；同一会话用 CAS 限制只运行一条 LLM 管线，TTS 用 Semaphore 限制并发。超过发送缓冲时连接会失败或关闭，防止单个慢客户端拖垮进程。当前没有按连接统计待发送字节、动态降采样或丢弃策略，Base64 又增加约三分之一体积。生产上会监控 queue depth，优先丢 partial 字幕，并使用二进制音频。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `ConcurrentWebSocketSessionDecorator`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — 消息大小配置
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — CAS 与 `Semaphore`

## 9. 面试官：系统如何处理回声、用户打断和重复触发 AI？

我的回答：

我的项目浏览器侧开启系统回声消除、降噪和自动增益；服务端在 AI 合成与发送期间设置 `aiSpeaking`，并在结束后保留约 800ms 冷却，期间丢弃麦克风输入，避免扬声器声音再次进入 ASR。final 片段先合并防抖，`AtomicBoolean.compareAndSet` 保证同一会话只触发一条 LLM 管线。它能降低回声与重复回答，却不是真正 barge-in：用户想打断 AI 时也可能被丢弃，而且服务端不知道浏览器实际播放结束时间。生产上应让客户端回报播放状态，并支持取消 LLM/TTS、保留打断文本后重新提问。

代码依据：

- `frontend/src/components/AudioRecorder.tsx` — 浏览器音频约束
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `aiSpeaking`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `processing.compareAndSet`
- `frontend/src/pages/VoiceInterviewPage.tsx` — 播放状态

## 10. 面试官：并发 TTS 和多实例 WebSocket 部署分别有哪些难点？

我的回答：

TTS 可按句并发降低总耗时，但必须保持文本顺序。我的项目把句子提交到虚拟线程，单会话 `Semaphore` 限制并发，Future 按原索引收集；默认等待后合并 WAV，开启 chunkedAudio 才逐段发送。多实例更难，因为连接、ASR、processing、音频缓存和活动时间都在本机 Map，随机负载均衡会让 REST、重连和 WebSocket 找不到同一状态。当前没有连接注册中心或跨实例接管。生产上可先用 sticky session 与外置会话状态，再把媒体节点注册到 Redis，并通过网关按 session owner 路由。

代码依据：

- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — TTS Future 与 `Semaphore`
- `app/src/main/java/interview/guide/modules/voiceinterview/config/VoiceInterviewProperties.java` — `chunkedAudioEnabled`
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — 会话 Map
- `frontend/src/pages/VoiceInterviewPage.tsx` — chunk 播放

# 本章总结

实时语音使用 WebSocket 串联浏览器 PCM、DashScope ASR、流式 LLM 与并发 TTS，并以 CAS、发送缓冲、空闲扫描和音频播放队列控制单连接复杂度。

当前并非 WebRTC，也缺少可信握手、协议心跳、真正打断、上行序号和多实例连接接管，这些是生产实时系统的主要差距。

# 核心主线

用全双工长连接承载实时语音状态机，并在每一阶段控制顺序、背压、并发和连接生命周期。
