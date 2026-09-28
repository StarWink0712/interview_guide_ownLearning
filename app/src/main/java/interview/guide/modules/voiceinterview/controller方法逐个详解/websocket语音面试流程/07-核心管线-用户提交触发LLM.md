# 核心管线第三步：用户提交触发 LLM

## 概述

用户在确认 ASR 识别文本无误后，点击前端的"提交"按钮。Handler 收到 `submit` 控制消息后，从合并缓冲区取出累积文本，构建对话上下文（会话实体 + 历史消息），调用 LLM 生成面试官的回复。这一步是核心管线中技术密度最高的环节，涉及 CAS 并发锁、虚拟线程调度、上下文构建和异常后状态必恢复等多个精细设计。

## 控制消息的接收与分发

`handleControl()` 根据 `action` 字段分发三种控制操作。其中 `submit` 操作在触发 LLM 之前还会检查 `data.text` 字段——如果前端通过文本输入框提交了手动编辑的文本，先追加到合并缓冲区再提交：

```java
private void handleControl(String sessionId, WebSocketControlMessage control) {
    switch (control.getAction()) {
        case "submit":
            if (control.getData() != null) {
                Object textObj = control.getData().get("text");
                if (textObj instanceof String text && !text.isBlank()) {
                    SessionState state = sessionStates.get(sessionId);
                    if (state != null) state.appendFinalSttSegment(text);
                }
            }
            flushMergedUtteranceToLlm(sessionId);
            break;
        case "end_interview":
            interviewService.endSession(sessionId);
            break;
        case "start_phase":
            interviewService.startPhase(sessionId, control.getPhase());
            break;
    }
}
```

---

## 一、CAS 并发锁：防止同时生成两个 LLM 回复

这是提交环节的第一个关键设计。用户可能在短时间内连续点击多次提交按钮（前端防抖失效或网络延迟重试），如果两个 LLM 管线同时执行，会导致对话上下文完全混乱——第二个回复可能基于第一个回复尚未写入数据库的状态生成，两个"面试官"会说出冲突的话。

```java
private void flushMergedUtteranceToLlm(String sessionId) {
    WebSocketSession session = sessions.get(sessionId);
    SessionState state = sessionStates.get(sessionId);
    if (session == null || state == null || !session.isOpen()) return;

    // CAS（Compare-And-Swap）原子操作：将 processing 从 false 设为 true
    // 如果当前已经是 true（已有 LLM 在执行中），CAS 失败，排期重试而非丢弃请求
    if (!state.isProcessing().compareAndSet(false, true)) {
        utteranceMergeScheduler.schedule(
                () -> flushMergedUtteranceToLlm(sessionId),
                400, TimeUnit.MILLISECONDS);
        return;
    }

    // 原子地取出 mergeBuffer 中的全部累积文本并清空缓冲区
    String userText = state.takeMergeBufferAndClear();
    if (userText == null || userText.trim().isEmpty()) {
        state.isProcessing().set(false);  // 空文本不需要释放锁
        return;
    }

    state.setAccumulatedText(userText);
    log.info("Merged user utterance for session {}, triggering LLM (length {})",
        sessionId, userText.length());

    // 提交到虚拟线程池执行阻塞的 LLM+TTS 管线
    voicePipelineExecutor.execute(() -> {
        state.setProcessingThread(Thread.currentThread());
        try {
            triggerLlmResponse(sessionId, session, state);
        } finally {
            // 关键：无论成功还是失败，finally 块中必须释放锁
            state.isProcessing().set(false);
            state.setProcessingThread(null);
        }
    });
}
```

`compareAndSet(false, true)` 是 `AtomicBoolean` 的 CAS 操作，在 CPU 指令级别保证原子性，Java 层面等价于"如果当前值是 false，设为 true 并返回 true；否则返回 false"。CAS 失败时不是丢弃请求，而是通过 `utteranceMergeScheduler` 排期 400ms 后重试——这是一个有界延迟的有序队列（`ScheduledExecutorService`，2 个守护线程），排期的回调会在前一次 LLM 完成并释放锁后被调度执行。

`state.setProcessingThread(Thread.currentThread())` 保存当前虚拟线程的引用。这个引用在 WebSocket 异常断开时被使用——`afterConnectionClosed()` 会取出该引用并调用 `t.interrupt()` 中断正在执行的 LLM 管线，避免继续等待 API 响应浪费资源。

---

## 二、对话上下文构建

在 `triggerLlmResponse()` 中，两个核心数据需要从数据库和缓存中获取：

```java
private void triggerLlmResponse(String sessionId, WebSocketSession session, SessionState state) {
    long turnStartNanos = System.nanoTime();
    state.aiSpeaking.set(true);  // 封锁麦克风——防止 AI 的 TTS 音频触发回声循环

    try {
        if (!session.isOpen()) return;

        String userText = state.getAccumulatedText();
        if (userText == null || userText.trim().isEmpty()) return;

        // 获取会话实体：含技能方向、当前阶段、LLM Provider 等 LLM 系统提示构建所需信息
        VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
        if (sessionEntity == null) {
            sendError(session, "会话不存在，请重新开始面试");
            return;
        }

        // 获取对话历史：按时间顺序排列的"候选人：xxx" / "面试官：xxx" 文本列表
        List<String> conversationHistory = getHistory(sessionId);

        // ... LLM + TTS 管线（见下一节：08-LLM流式生成与TTS并发合成）...
    } catch (Exception e) {
        log.error("Error triggering LLM response for session {}", sessionId, e);
        state.aiSpeaking.set(false);
        if (session.isOpen()) sendError(session, "AI响应失败: " + e.getMessage());
    }
}
```

`aiSpeaking.set(true)` 的设置时机在 LLM 调用开始之前——不是 LLM 返回之后，也不是 TTS 播放时。这是因为从 LLM 开始生成到 TTS 播放完毕的整个区间内，AI 都在"说话"，麦克风输入都应该被封锁。这个标记在 `handleUserAudio()` 的 `isAiSpeakingOrCooldown()` 中被读取。

### 会话实体查询（支持 Redis 缓存）

```java
private VoiceInterviewSessionEntity getSessionEntity(String sessionId) {
    try {
        Long sessionIdLong = Long.parseLong(sessionId);
        return interviewService.getSession(sessionIdLong);
        // getSession() 内部先查 Redis 缓存（Key: voice:interview:session:{id}），
        // 缓存未命中再查 PostgreSQL。缓存 TTL 1 小时，由 createSession 和 resumeSession 写入。
    } catch (NumberFormatException e) {
        log.error("Invalid session ID format: {}", sessionId);
        return null;
    }
}
```

### 对话历史查询

```java
private List<String> getHistory(String sessionId) {
    try {
        List<VoiceInterviewMessageEntity> messages =
            interviewService.getConversationHistory(sessionId);
        List<String> history = new ArrayList<>();

        for (VoiceInterviewMessageEntity msg : messages) {
            if (msg.getUserRecognizedText() != null && !msg.getUserRecognizedText().isEmpty()) {
                history.add("候选人：" + msg.getUserRecognizedText());
            }
            if (msg.getAiGeneratedText() != null && !msg.getAiGeneratedText().isEmpty()) {
                history.add("面试官：" + msg.getAiGeneratedText());
            }
        }
        log.debug("Loaded {} messages from history for session {}", history.size(), sessionId);
        return history;
    } catch (Exception e) {
        log.error("Error loading conversation history for session {}", sessionId, e);
        return new ArrayList<>();
    }
}
```

对话历史从 `voice_interview_messages` 表按 `sequence_num ASC` 排序读取，每条消息被格式化为"候选人：xxx"和"面试官：xxx"交替的文本列表。这个列表作为 LLM 的对话上下文传入 `chatStreamSentences()`，LLM 基于此历史来避免重复提问、遗忘之前的对话内容和保持面试风格一致。

注意这里有个空列表兜底——如果数据库查询异常，返回空列表而非抛异常。这样即使数据库暂时不可用，LLM 仍然可以生成回复（只是没有历史上下文），面试不会因查询失败而中断。

---

## 三、AI 说话标记与回声封锁

管线完成后在 LLM+TTS 方法末尾执行状态恢复：

```java
// 管线完成：解除 AI 说话标记
state.aiSpeaking.set(false);
// 设置 800ms 回声冷却期——扬声器尾音残留时间内继续丢弃麦克风输入
state.aiSpeakEndAt.set(System.currentTimeMillis() + AI_SPEAK_COOLDOWN_MS);
// 清空累积文本，为下一轮对话做准备
state.setAccumulatedText("");
```

`aiSpeakEndAt` 设置为当前时间 + 800ms，这个冷却期长度是经验值——太短则 TTS 播放的尾音可能被麦克风拾取，太长则用户说完话后响应延迟明显。

---

## 四、异常后状态必恢复

`flushMergedUtteranceToLlm()` 中 `voicePipelineExecutor.execute()` 的 finally 块是整个管道最关键的保障：

```java
} finally {
    state.isProcessing().set(false);   // 释放 CAS 锁
    state.setProcessingThread(null);   // 清除线程引用
}
```

如果没有这个 finally 块，任何异常（LLM 超时、TTS 调用失败、WebSocket 关闭等）发生后 `processing` 标志将永久为 true，用户的后续提交被排期到 `utteranceMergeScheduler` 中永远不会被执行。释放锁的时机是关键——写在 `voicePipelineExecutor.execute()` 的 Lambda 内部 finally 中，而非 `flushMergedUtteranceToLlm()` 方法的 finally 中，因为 LLM 调用在另一个线程中执行，方法本身的 finally 在提交任务后立即执行但此时 LLM 可能尚未完成。

---

## 五、总结

用户提交触发 LLM 是核心管线中技术密度最高的环节。它通过 `AtomicBoolean.CAS` 保证 LLM 管线不并发执行、通过 `ScheduledExecutorService` 排期重试保证并发提交的文本不丢失、通过虚拟线程池 `voicePipelineExecutor` 隔离阻塞的 LLM 和 TTS 调用不阻塞 WebSocket IO 线程、通过 `setProcessingThread` 保存线程引用供 WebSocket 断开时中断、通过 `aiSpeaking` 标记和 `aiSpeakEndAt` 冷却期防止回声循环、通过 finally 块保证异常发生后 CAS 锁和线程引用必恢复。这些设计共同构成了一套健壮的并发控制和状态管理机制，确保语音面试在用户快速操作和异常场景下不会出现状态错乱或永久锁定。
