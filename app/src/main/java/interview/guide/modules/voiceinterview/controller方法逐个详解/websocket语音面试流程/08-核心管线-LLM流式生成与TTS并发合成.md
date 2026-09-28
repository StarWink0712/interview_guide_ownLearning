# 核心管线第四步：LLM 流式生成与 TTS 并发合成

## 概述

LLM 流式生成与 TTS 并发合成是语音面试核心管线的最后一步，也是技术最密集的环节。它负责调用 LLM 生成面试官的回复文本，并将文本合成为语音推送给用户。本系统的核心创新是"句子级并发 TTS"——LLM 还在流式生成后续句子时，已生成完成的句子就已经开始 TTS 合成，LLM 生成和 TTS 合成在同一轮对话中并行执行，将首音频延迟从"LLM 生成完整回复 + TTS 合成为音频"降为"首个句子生成 + 首个句子 TTS 合成"。

> **本文档聚焦 LLM 流式生成与 TTS 的编排调度层**（句子检测、并发控制、收集合并、分块推送）。
> TTS 合成底层机制的详细分析（WebSocket 连接建立、DashScope 会话配置、音频分片收集、ByteArrayContainer 实现等）请参阅 → [08b-QwenTtsService语音合成详解](./08b-QwenTtsService语音合成详解.md)。

## 整体结构：流式与非流式双模式

系统通过 `voiceInterviewProperties.isLlmStreamingEnabled()` 配置项支持两种模式。两种模式共享相同的前置步骤（查询会话实体、获取对话历史、设置 AI 说话标记）和后置步骤（解除封锁、设置冷却期、记录指标），区别在于 LLM 调用和 TTS 合成的方式。

***

## 一、流式模式的完整管线

```java
private void triggerLlmResponse(String sessionId, WebSocketSession session, SessionState state) {
    long turnStartNanos = System.nanoTime();
    state.aiSpeaking.set(true);  // 封锁麦克风，在 LLM+TTS 全过程中防止回声

    try {
        if (!session.isOpen()) return;

        String userText = state.getAccumulatedText();
        if (userText == null || userText.trim().isEmpty()) return;

        VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
        if (sessionEntity == null) {
            sendError(session, "会话不存在，请重新开始面试");
            return;
        }

        List<String> conversationHistory = getHistory(sessionId);
        long llmStartNanos = System.nanoTime();
        AtomicLong firstTokenAtNanos = new AtomicLong(0);
        boolean streamEnabled = voiceInterviewProperties.isLlmStreamingEnabled();

        String aiReply;

        if (streamEnabled) {
            // ========== 流式模式：句子级并发 TTS ==========
            // 控制同一会话最多同时进行几个 TTS 合成的并发数
            Semaphore ttsSemaphore = new Semaphore(
                Math.max(1, voiceInterviewProperties.getMaxConcurrentTtsPerSession()));
            List<CompletableFuture<byte[]>> ttsFutures = new ArrayList<>();

            // LLM 流式生成，传入两个回调：
            // onPartialToken：每个 token 实时推送文本到前端
            // onSentenceComplete：检测到完整句子立即启动 TTS
            aiReply = llmService.chatStreamSentences(
                userText,
                partialText -> {
                    if (partialText == null || partialText.isBlank() || !session.isOpen()) return;
                    // 记录首 token 延迟（LLM 的首次响应速度）——仅第一个 token 触发
                    if (firstTokenAtNanos.compareAndSet(0L, System.nanoTime())) {
                        voiceMetricsRecorder.recordTimerSinceNanos(
                            "app.voice.interview.llm.first_token_latency", llmStartNanos);
                    }
                    sendTextMessage(session, partialText);
                },
                sentence -> {
                    if (sentence == null || sentence.isBlank()) return;
                    // 获取 TTS 并发许可——防止同一会话同时发起过多 TTS 请求触发云端限流
                    ttsSemaphore.acquireUninterruptibly();
                    CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            return ttsService.synthesize(sentence);
                        } finally {
                            ttsSemaphore.release();
                        }
                    }, voicePipelineExecutor);
                    ttsFutures.add(future);
                },
                sessionEntity,
                conversationHistory
            );
            // ... 文本送达和音频推送代码见下文 ...
```

这段代码中有三个核心设计点。

### 1.1 首 token 延迟的原子记录

`firstTokenAtNanos.compareAndSet(0L, System.nanoTime())` 只会在第一次被调用时成功（从 0 变为当前纳秒时间），后续所有 token 回调时 CAS 都失败。这样精确记录了 LLM API 从调用到返回第一个 token 的延迟，是衡量 LLM 响应速度和网络状况的核心性能指标。

### 1.2 TTS 并发控制与流水线并行

`ttsSemaphore` 控制同一会话最多同时执行几个 TTS 合成任务。`maxConcurrentTtsPerSession` 默认值通常为 2-3，防止短时间内向 TTS 云服务发起过多并发请求触发限流。`acquireUninterruptibly()` 在许可不足时阻塞，等待前面的 TTS 完成后释放许可。

关键设计在于 LLM 生成和 TTS 合成在此处形成流水线并行：当 LLM 还在生成第三个句子时，第一个句子的 `CompletableFuture` 已经在虚拟线程中执行 TTS 合成了。这不像非流式模式那样需要等 LLM 全部完成后再合成 TTS。

`CompletableFuture` 按每个句子在 LLM 回复中的顺序添加到 `ttsFutures` 列表中，后续音频合并时按此顺序收集结果，保证音频播放顺序与文本内容一致。

### 1.3 文本先送达、音频随后

```java
            // LLM 流式返回完成后，所有文本已通过 onPartialToken 实时推送
            // 再发送一遍完整文本确保前端有完整副本
            if (!session.isOpen()) return;
            sendSubtitle(session, userText, true);      // 用户字幕定稿
            sendTextMessage(session, aiReply);           // AI 回复完整文本
            saveMessage(sessionId, userText, aiReply);   // 持久化到数据库

            // 收集所有 TTS 结果
            if (!ttsFutures.isEmpty()) {
                long ttsStartNanos = System.nanoTime();
                boolean chunkedEnabled = voiceInterviewProperties.isChunkedAudioEnabled();

                if (chunkedEnabled) {
                    // 分块推送：每句 TTS 完成后立即推送到前端，前端逐块播放
                    for (int i = 0; i < ttsFutures.size(); i++) {
                        try {
                            byte[] pcm = ttsFutures.get(i).get();
                            if (pcm != null && pcm.length > 0 && session.isOpen()) {
                                byte[] wavAudio = convertPcmToWav(pcm);
                                sendAudioChunk(session, wavAudio, i,
                                    i == ttsFutures.size() - 1);
                            }
                        } catch (Exception e) {
                            log.warn("[Session: {}] TTS chunk {} failed", sessionId, i, e.getMessage());
                        }
                    }
                } else {
                    // 合并模式：收集所有句子的 PCM 后合并为一个完整 WAV
                    List<byte[]> pcmChunks = new ArrayList<>();
                    int totalSize = 0;
                    for (CompletableFuture<byte[]> f : ttsFutures) {
                        try {
                            byte[] pcm = f.get();
                            if (pcm != null && pcm.length > 0) {
                                pcmChunks.add(pcm);
                                totalSize += pcm.length;
                            }
                        } catch (Exception e) {
                            log.warn("[Session: {}] TTS future failed: {}", sessionId, e.getMessage());
                        }
                    }
                    if (totalSize > 0 && session.isOpen()) {
                        byte[] mergedPcm = new byte[totalSize];
                        int offset = 0;
                        for (byte[] chunk : pcmChunks) {
                            System.arraycopy(chunk, 0, mergedPcm, offset, chunk.length);
                            offset += chunk.length;
                        }
                        byte[] wavAudio = convertPcmToWav(mergedPcm);
                        sendAudio(session, wavAudio, aiReply);
                    }
                }
                voiceMetricsRecorder.recordTimerSinceNanos(
                    "app.voice.interview.tts.duration", ttsStartNanos);
            }
```

分块推送模式每条 `audio_chunk` 消息携带 `index`（分块序号）和 `isLast`（是否最后一块），前端可以逐块接收并播放，无需等待全部音频合成完毕。分块模式的用户体验更好——用户几乎在 LLM 文本到达的同时就能开始听到声音。

合并模式等待所有 `CompletableFuture` 完成后，将所有 PCM 字节数组合并为一个完整的 PCM 数组，通过 `System.arraycopy` 逐块拷贝到目标数组的对应偏移位置，再转换为 WAV 一次性发送。合并模式的优点是一次发送、前端播放更连贯，但等待时间更长。

***

## 二、非流式模式

```java
        } else {
            // 非流式模式：先等 LLM 完整生成，再 TTS 合成，串行执行
            aiReply = llmService.chat(userText, sessionEntity, conversationHistory);
            voiceMetricsRecorder.recordTimerSinceNanos(
                "app.voice.interview.llm.duration", llmStartNanos);

            if (!session.isOpen()) return;

            sendSubtitle(session, userText, true);
            sendTextMessage(session, aiReply);
            saveMessage(sessionId, userText, aiReply);

            long ttsStartNanos = System.nanoTime();
            byte[] pcm = ttsService.synthesize(aiReply);
            voiceMetricsRecorder.recordTimerSinceNanos(
                "app.voice.interview.tts.duration", ttsStartNanos);

            if (pcm != null && pcm.length > 0 && session.isOpen()) {
                byte[] wav = convertPcmToWav(pcm);
                sendAudio(session, wav, aiReply);
            } else if (pcm == null || pcm.length == 0) {
                // TTS 返回空结果时记录监控指标，但文本已送达——降级策略
                log.error("[Session: {}] TTS returned empty audio", sessionId);
                voiceMetricsRecorder.incrementCounter("app.voice.interview.tts.empty_audio");
            }
        }
```

非流式模式下 TTS 空结果的处理是一个降级策略——TTS 合成返回空时只记录监控指标和错误日志，不发送错误消息给前端。因为文本已经通过 `sendTextMessage` 送达，用户可以阅读 AI 回复的文字内容。对于用户来说，音频缺失比整个回复丢失要好得多。

***

## 三、管线结束后的状态恢复

```java
        // 无论流式还是非流式，管线完成后统一状态恢复
        state.aiSpeaking.set(false);
        state.aiSpeakEndAt.set(System.currentTimeMillis() + AI_SPEAK_COOLDOWN_MS);
        state.setAccumulatedText("");

        voiceMetricsRecorder.recordTimerSinceNanos(
            "app.voice.interview.turn.duration", turnStartNanos);
        voiceMetricsRecorder.incrementCounter("app.voice.interview.turn.completed");

    } catch (Exception e) {
        log.error("Error triggering LLM response for session {}", sessionId, e);
        state.aiSpeaking.set(false);
        if (session.isOpen()) sendError(session, "AI响应失败: " + e.getMessage());
    }
}
```

`aiSpeaking.set(false)` 和 `aiSpeakEndAt` 共同决定了麦克风封锁何时解除——不是 AI 说话标签为 false 时立即解除，而是 `isAiSpeakingOrCooldown()` 中检查 `aiSpeaking` 为 false **且** 当前时间已超过 `aiSpeakEndAt` 才允许通过。800ms 冷却期内即使 AI 标签已解除，麦克风输入仍被丢弃。

catch 块中也执行了 `aiSpeaking.set(false)`——即使 LLM 或 TTS 调用异常，这个标记也必须被解除，否则麦克风将永远被封锁，用户再也无法让系统听到自己的声音。

***

## 四、消息发送辅助方法

```java
private void sendTextMessage(WebSocketSession session, String text) {
    sendMessage(session, toJson(Map.of("type", "text", "content", text)));
}

private void sendAudio(WebSocketSession session, byte[] audio, String text) {
    if (!session.isOpen()) return;
    String base64Audio = Base64.getEncoder().encodeToString(audio);
    sendMessage(session, toJson(Map.of("type", "audio", "data", base64Audio, "text", text)));
}

private void sendAudioChunk(WebSocketSession session, byte[] wavAudio, int index, boolean isLast) {
    if (!session.isOpen()) return;
    String base64Audio = Base64.getEncoder().encodeToString(wavAudio);
    sendMessage(session, toJson(Map.of(
        "type", "audio_chunk", "data", base64Audio, "index", index, "isLast", isLast)));
}

private void sendError(WebSocketSession session, String error) {
    sendMessage(session, toJson(Map.of("type", "error", "message", error)));
}

private void sendMessage(WebSocketSession session, String message) {
    try {
        if (session.isOpen()) session.sendMessage(new TextMessage(message));
    } catch (Exception e) {
        log.error("Error sending message to session", e);
    }
}
```

底层 `sendMessage` 是最基础的消息发送方法。它不做任何 JSON 构造或类型标记，只负责将字符串通过 WebSocket 发送。所有上层发送方法（文本、音频、音频分块、错误）都先构造好 JSON 字符串再调用它。`session.isOpen()` 检查是必需的——在 WebSocket 关闭的过程中发送消息会抛异常，同时也避免了向已关闭但尚未被 GC 的 Session 发送数据。

***

## 五、前端收到的消息序列

在一次完整的提交-回复周期中，前端依次收到以下格式的消息：

```
① subtitle 字幕消息（用户定稿）:
   {"type":"subtitle","text":"我主要使用Spring Boot构建微服务...","isFinal":true}

② text 消息（流式推送，多段）:
   {"type":"text","content":"很好，请描述一下你"}
   {"type":"text","content":"很好，请描述一下你在项目中的服务间通信"}

③ audio 或 audio_chunk 消息:
   合并模式: {"type":"audio","data":"UklGRi...Base64...","text":"很好，请描述..."}
   分块模式: {"type":"audio_chunk","data":"...","index":0,"isLast":false}
             {"type":"audio_chunk","data":"...","index":1,"isLast":true}
```

***

## 六、总结

LLM 流式生成与 TTS 并发合成是整个语音面试系统的技术核心。句子级并发 TTS 通过 `chatStreamSentences` 在 LLM 流式输出期间逐句启动 `CompletableFuture` 并行 TTS 合成（每个句子的合成底层经由 `QwenTtsService.synthesize()` → DashScope 临时 WebSocket → commit 模式 → 音频分片收集，详见 [08b-QwenTtsService语音合成详解](./08b-QwenTtsService语音合成详解.md)），将首音频延迟从"完整回复生成时间 + 完整 TTS 合成时间"降为"首个句子生成时间 + 首个句子 TTS 合成时间"，这是本系统与纯串行方案相比最核心的性能优势。`Semaphore` 控制了每会话的 TTS 并发上限防止云端限流，分块推送模式通过 `audio_chunk` 消息实现边合成边播放进一步降低延迟，非流式模式作为降级选项保证基础可用性。整个管线在虚拟线程 `voicePipelineExecutor` 中执行不阻塞 WebSocket IO 线程，异常后通过 catch 块保证 `aiSpeaking` 必解除防止麦克风永久封锁，`sendAudio` 和 `sendTextMessage` 在发送前检查 `session.isOpen()` 避免向已关闭的连接发送数据。
