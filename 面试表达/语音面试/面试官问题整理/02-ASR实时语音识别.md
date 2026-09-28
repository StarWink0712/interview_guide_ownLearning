# ASR 实时语音识别

## 1. 前端、服务端和 ASR 之间的语音识别链路是什么？

**回答：** 前端通过浏览器麦克风采集 PCM 音频，Base64 编码后使用前后端 WebSocket 发送给服务端；服务端再通过另一条 WebSocket 把 PCM 音频发送给 DashScope ASR。ASR 把中间识别结果、最终识别结果或异常通过回调返回服务端，服务端一边累积最终文本，一边通过前后端 WebSocket 把字幕同步给前端。

前端通过浏览器麦克风采集pcm音频，然后编码后通过websocket传给服务端，服务端再通过另一个websocket把pcm音频发送给dashscope ASR，识别结果再返回给服务端后，异步累计生成文本把字幕同步给前端，。

<br />

## 2. startTranscription 为什么要传入三个回调？

**回答：** 三个回调分别是 `onFinal`、`onPartial` 和 `onError`。`onFinal` 处理 ASR 判断一句话已经完成后的最终文本，Handler 会把它追加到 `mergeBuffer`；`onPartial` 处理用户说话过程中不断变化的中间文本，直接作为实时字幕推送；`onError` 处理 ASR WebSocket 连接或识别异常，并把错误通知前端。前两个最终都进入 `handleSttResult`，区别是 `isFinal` 参数不同。

## 3. 为什么每个 ASR 会话都要有一把监视器锁？

**回答：** `QwenAsrService.startTranscription()` 以 `sessionId` 为 Key 在并发 Map 中为每个会话维护一把锁，再在锁内执行 `startTranscriptionLocked()`。这样可以防止同一个面试会话并发启动、停止或重连 ASR，避免同时创建多条 DashScope 连接。停止会话时会同时删除锁对象，避免内存泄漏。

## 4. startTranscriptionLocked 如何避免重复启动 ASR？

**回答：** 方法先从活跃 ASR 会话 Map 中按 `sessionId` 检查连接是否已经存在，存在就不重复创建。只有没有活跃连接时，才构建包含模型、DashScope URL 和 API Key 的连接参数，创建回调与 `OmniRealtimeConversation`，把新会话存入 Map，然后在守护线程中调用 `connect()`。

## 5. 为什么 ASR 连接要使用 AtomicReference 保存引用？

**回答：** DashScope 的 `onClose` 是异步回调，旧连接断开后，系统可能已经完成重连并把新连接放入 Map。如果旧连接的 `onClose` 此时直接按 sessionId 删除，会误删新连接。因此创建连接时用 `AtomicReference` 保存当前连接对象；关闭回调触发时，把回调中的对象与 Map 里的对象做引用比较，只有两者是同一个对象才删除，否则把它视为旧连接的陈旧回调并忽略。

## 6. 服务端与 DashScope ASR 建立连接时配置了什么？

**回答：** 服务端构建 `OmniRealtimeParam`，传入模型名、DashScope URL 和 API Key，然后在守护线程中调用 `conversation.connect()`。连接使用中文、16kHz 采样率和 PCM 音频格式，只要求输出文本，并启用服务端 VAD，让 ASR 自动判断语音起止；笔记中的配置以静默约 1 秒作为断句条件。守护线程不会阻碍 JVM 关闭。

## 7. DashScope 推送事件后是怎么路由的？

**回答：** DashScope 推送 JSON 事件后触发 `OmniRealtimeCallback.onEvent()`，再进入 `handleServerEvent()`。方法根据事件类型分为 completed、text/delta 和 error：completed 解析 transcript、language 和 emotion 后调用 `onFinal.accept(transcript)`；text 或 delta 调用 `onPartial.accept(partialText)`；error 解析异常详情后调用 `onError.accept(exception)`。

## 8. ASR 的中间结果和最终结果分别怎么处理？

**回答：** 中间结果调用 `handleSttResult(sessionId, text, false)`，更新活动时间后直接通过 `sendSubtitle` 推送给前端，前端用它覆盖显示实时字幕。最终结果调用 `handleSttResult(sessionId, text, true)`，先通过 `appendFinalSttSegment` 追加到 `mergeBuffer`，再把累计文本预览推送给前端。字幕最终被包装为 `WebSocketSubtitleMessage`，通过 `session.sendMessage(new TextMessage(...))` 发送。

中间结果调用sendtext实时推送给前端，最终结果的话就包装起来给llm。

## 9. 为什么不能把每个 onFinal 结果直接提交给 LLM？

**回答：** 服务端 VAD 可能在用户一句话中间因为换气或短暂停顿就触发 completed，导致一段完整回答被拆成多个最终片段。因此系统先把多个 `onFinal` 文本拼进 `mergeBuffer`，等用户点击提交后再原子地取出全部文本，一次性交给 LLM，避免模型只拿到残缺回答。

## 10. ASR 为什么不需要像 TTS 一样使用 CountDownLatch？

**回答：** ASR 是持续流式模式，`startTranscription()` 只负责建立连接和注册回调，随后立即返回。用户可能持续说话 30 分钟，partial、completed 和 error 都在事件发生时异步通知服务端，不需要当前方法等待一个最终结果。TTS 则是一次请求必须拿到完整音频后才能发送给前端，所以才需要把异步回调转换为同步等待。即语音转文本是流式持续转的，而文本转语音，需要拿到全部的语音，一次返回给前端，让前端播放语音，本质是文本可以逐个给前端显示，而语音不行。
