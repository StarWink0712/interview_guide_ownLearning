# ASR 实时语音识别链路

## 1. 模块基础讲解

ASR 链路由 `QwenAsrService` 和 `VoiceInterviewWebSocketHandler` 配合完成。前端采集用户麦克风音频，编码成 Base64 PCM，通过 WebSocket 发送给后端；后端解码后送入 DashScope Qwen ASR 实时 WebSocket；ASR 服务返回部分识别结果和最终识别结果。

配置来源：

- `app.voice-interview.qwen.asr.url`
- `model=qwen3-asr-flash-realtime`
- `api-key=${AI_BAILIAN_API_KEY}`
- `language=zh`
- `format=pcm`
- `sample-rate=16000`
- `enable-turn-detection=true`
- `turn-detection-type=server_vad`
- `turn-detection-silence-duration-ms=2000`

ASR 会话启动流程：

1. WebSocket 连接建立后调用 `startDashScopeStt`。
2. `QwenAsrService.startTranscription` 为 sessionId 加锁。
3. 构造 `OmniRealtimeParam`，创建 `OmniRealtimeConversation`。
4. 创建 `OmniRealtimeCallback` 处理 onOpen/onEvent/onClose。
5. 先把会话放入 `sessions` map。
6. 启动守护线程执行 `conversation.connect()`。
7. 连接成功后下发 `OmniRealtimeConfig`，包含文本输出和 VAD 参数。

音频发送流程：

1. Handler 收到 `type=audio`。
2. 检查 AI 是否正在说话或处于冷却期。
3. Base64 解码得到 PCM 字节。
4. 调用 `sttService.sendAudio(sessionId, audioData)`。
5. ASR service 再次 Base64 编码，调用 `conversation.appendAudio(audioBase64)`。

ASR 回调事件：

- `conversation.item.input_audio_transcription.text`
- `conversation.item.input_audio_transcription.delta`
- `conversation.item.input_audio_transcription.completed`
- `conversation.item.input_audio_transcription.failed`
- `error`

partial 事件用于实时字幕；completed 事件作为最终片段进入 mergeBuffer。

断线恢复机制：

- `sendAudio` 遇到 `No active session` 或 `ASR append failed` 时，Handler 调用 `restartDashScopeStt`。
- ASR service 先 stop 旧连接，等待 200ms，再 start 新连接。
- Handler 对当前音频 chunk 最多重试 15 次，每次间隔 80ms。
- 仍失败则通知前端刷新页面。

## 2. 基础面试问题

**问题：ASR 在语音面试里负责什么？**

回答：ASR 负责把用户的语音音频转成文字。语音面试后续的 LLM 追问和最终评估都依赖这份识别文本，所以 ASR 是语音输入进入 AI 逻辑的第一道关口。

**问题：什么是 partial result 和 final result？**

回答：partial result 是用户说话过程中实时变化的临时识别文本，适合展示字幕；final result 是 ASR 认为一段语音结束后的定稿结果，代码会把它追加到 mergeBuffer，等待用户 submit 后送给 LLM。

**问题：VAD 的作用是什么？**

回答：VAD 是语音活动检测，用于判断用户什么时候开始说话、什么时候停止。当前使用服务端 VAD，根据静默时长判断一句话结束，触发 completed 转写事件。

**问题：每个面试会话有几条 ASR 连接？**

回答：每个语音面试 sessionId 对应一条独立的 DashScope ASR WebSocket 连接。这样多个用户或多个会话可以并发识别，互不影响。

**问题：ASR 服务关闭时怎么清理？**

回答：WebSocket 关闭、暂停超时或 Bean 销毁时会调用 `stopTranscription`，结束 ASR session、关闭连接、移除 sessions map 和 session lock，避免资源泄漏。

## 3. 进阶面试问题

**问题：为什么启动 ASR 时要对同一 sessionId 加锁？**

回答：stop/start/restart 可能并发发生，例如网络断开和用户关闭同时触发。如果不加锁，同一个 sessionId 可能创建多条 ASR 连接，或者旧连接 onClose 把新连接删掉。`sessionLocks` 保证同一 session 的连接操作串行。

**问题：为什么 onClose 要比较 conversation 引用再删除 map？**

回答：重连时旧连接的 onClose 可能延迟到达，如果直接 `sessions.remove(sessionId)`，会误删新连接。代码通过比较 `existing.getConversation() == closed` 确认关闭的是当前 map 中这条连接，才移除。

**问题：`extractTranscriptPayload` 为什么要处理 text + stash？**

回答：DashScope partial 事件可能把确认前缀放在 `text`，草稿后缀放在 `stash`。如果只读一个字段，实时字幕会缺失。测试里也覆盖了 `text + stash` 合并。

**问题：ASR 断线重连为什么还要重试发送当前音频 chunk？**

回答：重连需要时间，如果当前 chunk 直接丢弃，用户会少一段话。Handler 在重连后对当前 chunk 做短时间重试，尽量把这段音频送进去。重试窗口约 1.2 秒。

**问题：ASR 链路有哪些可能的性能瓶颈？**

回答：Base64 编解码增加 CPU 和网络体积；WebSocket 文本帧比二进制帧更大；ASR 外部服务延迟不可控；每会话独立 ASR 连接会消耗连接资源；大音频 chunk 会增加端到端延迟。

**问题：为什么输入采样率是 16kHz，TTS 输出是 24kHz？**

回答：ASR 配置要求输入 PCM 16kHz，适合语音识别；TTS 配置返回 PCM 24kHz，音质更好。后端转 WAV 时按 TTS 输出采样率 24kHz 写头。

## 4. 技术延伸问题

**问题：如果要换成其他 ASR 服务，怎么抽象？**

回答：可以定义 `SpeechRecognitionService` 接口，包含 start、sendAudio、restart、stop。DashScope Qwen ASR 作为一个实现，其他如 Whisper、Azure Speech、讯飞、百度 ASR 都实现同一接口。Handler 只依赖接口。

**问题：如果要支持本地 Whisper，架构怎么变？**

回答：本地 Whisper 通常不是实时 WebSocket SDK，而是本地进程或服务。可以用音频缓冲窗口批量转写，延迟更高但成本可控。实时字幕体验会弱一些，需要 VAD 和分片策略配合。

**问题：如何处理多语言识别？**

回答：CreateSession 增加 language 字段，传给 ASR 配置。Prompt 和评估也要同步语言。对于中英混合，可以选择 auto detect 或支持多语言模型，同时最终报告要保留原文和标准化文本。

**问题：如何提升 ASR 准确率？**

回答：可以根据面试方向提供热词，例如 Java、Redis、Kafka、MySQL 等；前端保证采样率和降噪；后端过滤太短或明显噪音片段；最终评估前可以对 ASR 文本做技术词纠错。

**问题：如何支持自动提交而不是手动 submit？**

回答：需要真正接入配置中的 debounce/min-silence/min-commit-chars/max-wait。ASR final 后启动定时器，如果期间没有 partial 或新 final，且文本足够长，就自动 flush 到 LLM。当前代码有相关配置项和调度器，但实际触发仍以手动 submit 为主。

## 5. 改进方案问题

**问题：当前 ASR 链路最明显的改进是什么？**

回答：补齐自动提交逻辑。`application.yml` 有 user utterance debounce 和最小静音配置，但 `VoiceInterviewProperties` 类和 Handler 实际没有完整使用这些字段。可以把“手动 submit”和“自动静音提交”做成可配置模式。

**问题：如何降低 Base64 成本？**

回答：改用 WebSocket 二进制帧传音频，或者用更高压缩率的 Opus。但如果 ASR 只接受 PCM，还要服务端解码 Opus 成 PCM。二进制帧是更直接的优化。

**问题：如何避免 ASR 连接泄漏？**

回答：确保所有路径都调用 `stopTranscription`：正常结束、暂停超时、WebSocket 断开、传输错误、删除会话。还可以定时扫描 sessions map，清理长时间无 WebSocket 对应的 ASR 连接。

**问题：如何增强 ASR 错误可观测性？**

回答：按 sessionId 记录 ASR start、connect、appendAudio、partial、final、restart、stop 的计数和耗时。错误要打上 errorCode、provider、model、sessionId。当前已有部分日志，但指标还可以更系统。

**问题：如何防止音频参数不一致？**

回答：连接建立时服务端可以发送音频参数要求，前端按此采集；后端对 chunk 大小和采样率做粗略校验。若前端误传 48kHz 或 stereo，ASR 准确率会下降。

## 6. 故障处理问题

**问题：ASR 初始化失败，应用启动不了怎么办？**

回答：`QwenAsrService.init` 会校验 API Key，未配置会抛异常。检查 `.env` 或环境变量 `AI_BAILIAN_API_KEY`，以及 `app.voice-interview.qwen.asr.api-key` 是否正确绑定。

**问题：第三轮开始突然无法识别，可能是什么？**

回答：可能是 ASR WebSocket 被服务端关闭，旧连接 onClose 误删新连接也曾是典型风险。当前代码通过引用比较和 restart 机制处理。排查时看 `ASR append failed`、`Restarting DashScope ASR` 和 onClose 日志。

**问题：实时字幕一直闪烁但最终文本为空怎么办？**

回答：partial 事件能到，completed 事件不到。可能 VAD 静默阈值过长，用户一直没停顿；也可能 ASR 服务没有发送 completed。可以调低 silence duration，或增加前端手动提交当前 partial 的能力。

**问题：最终文本重复怎么办？**

回答：可能 partial 和 final 都被前端当作最终文本展示，或者 submit 的 `data.text` 又追加了一遍已进入 mergeBuffer 的文本。需要区分临时字幕和最终提交文本，前端不要重复传。

**问题：ASR 重连后仍失败怎么办？**

回答：Handler 重试 15 次仍失败会发送“语音识别连接中断，请刷新页面后重试”。后端排查 API Key、网络、DashScope 服务状态、模型配置和会话是否已经关闭。

