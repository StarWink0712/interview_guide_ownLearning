# 核心管线第一步：用户音频到 ASR 识别

## 概述

用户开始说话后，浏览器的麦克风持续采集 PCM 音频（16kHz 16bit 单声道），前端将音频数据 Base64 编码后通过 WebSocket 发送到后端，即message。Handler 的 `handleTextMessage()` 解析消息 type 字段进行分发——`audio` 类型进入 `handleUserAudio()`，开始语音识别核心管线。这条管线的第一步是将 Base64 解码后的 PCM 音频发送到 DashScope ASR 进行实时语音转文字。即从message中，解析出audio类型的字段，即语音部分，然后转到处理语音的方法进行识别。

## 消息接收与分发

`handleTextMessage()` 是所有 WebSocket 文本消息的统一入口：

```java
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    String sessionId = extractSessionId(session);

    int messageSizeKB = message.getPayload().length() / 1024;
    log.info("[WebSocket] Received message: sessionId={}, size={}KB ({} bytes)",
            sessionId, messageSizeKB, message.getPayload().length());

    try {
        JsonNode msg = objectMapper.readTree(message.getPayload());
        String type = msg.get("type").asText();

        // 更新最后活动时间——每次消息到达都重置超时计时器
        // checkPauseTimeout() 定时任务依赖此时间戳判断是否 5 分钟无活动
        lastActivityTime.put(sessionId, System.currentTimeMillis());

        switch (type) {
            case "audio":
                // 用户音频消息：前端 Base64 编码的 PCM 音频数据
                String audioData = msg.has("data") ? msg.get("data").asText() : null;
                if (audioData != null && !audioData.isEmpty()) {
                    handleUserAudio(sessionId, audioData);
                }
                break;
            case "control":
                // 控制消息：submit / end_interview / start_phase
                handleControl(sessionId, objectMapper.treeToValue(msg, WebSocketControlMessage.class));
                break;
            default:
                log.warn("Unknown message type: {} for session {}", type, sessionId);
        }
    } catch (Exception e) {
        log.error("Error handling message for session {}", sessionId, e);
        sendError(session, "消息处理失败: " + e.getMessage());
    }
}
```

`lastActivityTime.put()` 在 switch 之前执行——不区分消息类型，只要收到消息就说明用户在活动。这个时间戳是 Handler 层唯一的活动来源，`checkPauseTimeout()` 每 30 秒检查所有会话的这个时间戳。

***

## handleUserAudio() —— 音频处理三阶段

```java
private void handleUserAudio(String sessionId, String base64Audio) {
    WebSocketSession session = sessions.get(sessionId);
    if (session == null) return;

    // ====== 阶段一：AI 回声冷却检查 ======
    // AI 正在播放 TTS 音频或刚播放完 800ms 内，丢弃麦克风输入
    SessionState state = sessionStates.get(sessionId);
    if (state != null && state.isAiSpeakingOrCooldown()) {
        return;
    }

    try {
        // ====== 阶段二：Base64 解码 → PCM 字节数组 ======
        byte[] audioData = Base64.getDecoder().decode(base64Audio);

        try {
            // ====== 阶段三：发送到 DashScope ASR ======
            sttService.sendAudio(sessionId, audioData);
        } catch (IllegalStateException ex) {
            // ASR WebSocket 连接断开 → 触发断线重连
            if (shouldRecoverAsrConnection(ex)) {
                restartDashScopeStt(sessionId);
                // 重连后重试发送同一段音频（最多 15 次，每次间隔 80ms）
                boolean sent = false;
                for (int i = 0; i < 15; i++) {
                    try {
                        Thread.sleep(80);
                        sttService.sendAudio(sessionId, audioData);
                        sent = true;
                        break;
                    } catch (IllegalStateException retry) {
                        if (!shouldRecoverAsrConnection(retry)) throw retry;
                    }
                }
                if (!sent) {
                    sendError(session, "语音识别连接中断，请刷新页面后重试");
                }
            } else {
                throw ex;  // 不可恢复异常直接抛出
            }
        }
    } catch (Exception e) {
        log.error("Error handling user audio for session {}", sessionId, e);
        sendError(session, getErrorMessage(e));
    }
}
```

### 阶段一：AI 回声冷却

`isAiSpeakingOrCooldown()` 的返回条件有两个——`aiSpeaking` 原子布尔值为 true（AI 的 TTS 音频正在播放）或 `aiSpeakEndAt` 时间戳尚未过期（播放结束后 800ms 冷却期）：

```java
boolean isAiSpeakingOrCooldown() {
    if (aiSpeaking.get()) return true;
    return System.currentTimeMillis() < aiSpeakEndAt.get();
}
```

这个机制防止了一个严重的回声循环问题：AI 的 TTS 音频通过浏览器扬声器播放 → 同一台设备的麦克风拾取 → 送入 ASR 识别出文本 → 送入 LLM 生成回复 → 再 TTS 播放 → 无限循环。冷却期 800ms 是一个经验值——考虑了音频播放后扬声器的物理残响和麦克风的拾取延迟，确保尾音不会触发新的对话轮次。

### 阶段二：Base64 解码

前端将 PCM 音频数据 Base64 编码后通过 JSON 传输。解码后的 `byte[]` 是原始 16bit PCM 数据，直接发送到 DashScope 不需要额外处理。音频格式（16kHz 采样率、单声道、PCM）在 ASR 会话配置阶段已下发。

### 阶段三：ASR 发送与断线重连

`sttService.sendAudio()` 内部将 PCM 再次 Base64 编码后通过 `OmniRealtimeConversation.appendAudio()` 发送。如果 ASR WebSocket 已被服务端关闭（网络波动或服务端主动断连），`appendAudio()` 抛出 `IllegalStateException`。

异常可恢复性通过 `shouldRecoverAsrConnection()` 判断：

```java
private static boolean shouldRecoverAsrConnection(IllegalStateException ex) {
    String m = ex.getMessage();
    if (m == null) return false;
    return m.contains("No active session") || m.contains("ASR append failed");
}
```

只有包含 "No active session" 或 "ASR append failed" 的异常才触发重连。其他异常（如参数错误、认证失败）直接向上抛出。

`restartDashScopeStt()` 触发完整的重连流程：

```java
private void restartDashScopeStt(String sessionId) {
    WebSocketSession session = sessions.get(sessionId);
    if (session == null || !session.isOpen()) return;

    sttService.restartTranscription(
        sessionId,
        text -> handleSttResult(sessionId, text, true),
        text -> handleSttResult(sessionId, text, false),
        error -> {
            log.error("STT error for session {}", sessionId, error);
            sendError(session, "语音识别失败: " + error.getMessage());
        }
    );
}
```

重连使用**完全相同的回调**——`handleSttResult` 的文本处理逻辑不变，上层 Handler 无需感知重连的发生。重连后的重试循环（最多 15 次，每次 80ms 间隔，总计约 1.2 秒）覆盖了 ASR WebSocket 重新握手 + 配置下发的典型耗时，确保短暂的网络抖动不会导致音频数据丢失。

***

## DashScope ASR 的实时识别过程

音频通过 `OmniRealtimeConversation.appendAudio()` 发送后，DashScope 的服务端 VAD 持续分析音频流。VAD 基于深度学习模型实时判断当前音频帧是否包含人声。当检测到连续 400ms 的静默时判定用户说完了一段话，将当前语音片段送入 ASR 模型转写。转写完成后通过 WebSocket 推送 `conversation.item.input_audio_transcription.completed` 事件，携带完整识别文本。同时推送的还有语言标签（`zh`）和情绪标签（`neutral/positive/negative` 等）。

在用户说话过程中，ASR 模型每识别出一个新的字或词都会通过 `conversation.item.input_audio_transcription.text` 或 `.delta` 事件推送部分识别结果。这些中间文本通过 `onPartial` 回调以 `{"type":"subtitle","text":"...","isFinal":false}` 格式推送到前端——用户在说话时能实时看到字幕变化。

***

## 总结

音频到 ASR 识别是语音面试核心管线的第一步，完成从前端麦克风到 DashScope （语音转文字的模型）实时识别的全链路。链路为：浏览器录音 → Base64 编码 → WebSocket JSON 传输 → Handler 接收分发 → 回声冷却检查（`isAiSpeakingOrCooldown` 防止 TTS 回声循环）→ Base64 解码 → `sendAudio` 发送 → DashScope 实时识别。核心设计包括：AI 回声冷却避免扬声器→麦克风→ASR→LLM→TTS 无限循环（`aiSpeaking` 标记 + 800ms `aiSpeakEndAt` 冷却期）；ASR 断线自动重连（`restartDashScopeStt` + 15 次 × 80ms 重试窗口共 1.2 秒，监听 `shouldRecoverAsrConnection` 判断可恢复性）；服务端 VAD 400ms 静默自动断句无需前端做静默检测；部分识别结果通过 `onPartial` 实时推送字幕提升交互体感；`lastActivityTime` 每次消息到达时更新供超时检测使用。
