# WebSocket 语音面试完整流程详解

> 文档范围：从 WebSocket 连接建立到会话结束的完整生命周期  
> 涉及类：`VoiceInterviewWebSocketHandler`、`QwenAsrService`、`QwenTtsService`、`DashscopeLlmService`、`VoiceInterviewService`  
> 说明：本文档串联 WebSocket 握手、ASR 语音识别、LLM 对话生成、TTS 语音合成、暂停恢复、会话结束的全链路流程

---

## 零、前置步骤 — createSession 只是准备工作

在 WebSocket 建立之前，用户首先通过 HTTP 调用 `POST /api/voice-interview/sessions` 创建会话：

```
前端 fetch("POST /api/voice-interview/sessions", { skillId: "java-backend", ... })
  → VoiceInterviewController.createSession()
    → VoiceInterviewService.createSession()
      → 创建 VoiceInterviewSessionEntity 并保存到 DB
      → 写入 Redis 缓存（1h TTL）
      → 返回 SessionResponseDTO，其中包含：
          {
            "sessionId": 42,
            "webSocketUrl": "ws://localhost:8080/ws/voice-interview/42"
          }
```

**`createSession` 不创建 WebSocket 连接**。它只负责准备会话数据并将 WebSocket 地址返回给前端。真正的连接由前端发起。

---

## 一、WebSocket 连接建立

### 1.1 前端发起 WebSocket 握手

前端收到 `SessionResponseDTO` 后，用浏览器原生 `WebSocket` API 连接：

```typescript
const session = await voiceInterviewApi.createSession({ ... });
const ws = new WebSocket(session.webSocketUrl);
// → 浏览器发送 HTTP Upgrade 请求到 ws://localhost:8080/ws/voice-interview/42
```

### 1.2 Spring WebSocket 框架路由

这个 Upgrade 请求**不经过 DispatcherServlet，不走任何 Controller**。

Spring WebSocket 框架的 `WebSocketConfig` 已经注册了路由规则：

```java
// WebSocketConfig.java
registry.addHandler(voiceInterviewWebSocketHandler, "/ws/voice-interview/{sessionId}")
```

框架根据 URL 路径匹配到 `VoiceInterviewWebSocketHandler`，完成 WebSocket 握手后调用 `afterConnectionEstablished()`。

### 1.3 Handler 初始化 — afterConnectionEstablished()

```java
public void afterConnectionEstablished(WebSocketSession session) {
    String sessionId = extractSessionId(session);  // 从 URI 提取 "42"

    // ① 设置消息大小限制（256KB）
    session.setTextMessageSizeLimit(256 * 1024);

    // ② 创建并发安全装饰器（防止并发写入冲突，10s 发送超时，512KB 缓冲区）
    WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
        session, 10000, 512 * 1024
    );

    // ③ 存入三个并发 Map
    sessions.put(sessionId, safeSession);              // WebSocket 会话映射
    sessionStates.put(sessionId, new SessionState());  // 运行时状态（AI 说话标记、合并缓冲等）
    lastActivityTime.put(sessionId, System.currentTimeMillis()); // 活动时间戳

    // ④ 启动 ASR 语音识别
    startDashScopeStt(sessionId, safeSession);

    // ⑤ 发送欢迎消息
    sendMessage(safeSession, createWelcomeMessage());
    // ⑥ 触发起始开场白与第一个提问（仅首次连接）
    triggerOpeningQuestionIfNeeded(sessionId, safeSession);
}
```

### 1.4 欢迎消息（同步推送）

```json
{
    "type": "control",
    "action": "welcome",
    "message": "连接成功，准备开始语音面试",
    "timestamp": 1718700000000
}
```

---

## 二、ASR 语音识别启动

### 2.1 startDashScopeStt()

```java
private void startDashScopeStt(String sessionId, WebSocketSession session) {
    sttService.startTranscription(
        sessionId,
        text -> handleSttResult(sessionId, text, true),   // onFinal: 定稿文本 → LLM
        text -> handleSttResult(sessionId, text, false),  // onPartial: 实时字幕
        error -> sendError(session, "语音识别失败: " + error.getMessage())  // onError
    );
}
```

设置了三个回调：

| 回调 | 触发时机 | 做什么 |
|------|---------|--------|
| `onFinal` | 服务端 VAD 检测到一段话说完（静默 400ms 后断句） | 加入合并缓冲区，等待用户提交后送 LLM |
| `onPartial` | 说话过程中实时产生 | 推送实时字幕到前端 |
| `onError` | ASR WebSocket 连接异常 | 推送错误消息到前端 |

### 2.2 QwenAsrService.startTranscription() 底层逻辑

```
QwenAsrService.startTranscription(sessionId, onFinal, onPartial, onError)
  │
  ├── 构建 OmniRealtimeParam（模型名、URL、API Key）
  ├── 创建 OmniRealtimeCallback（onOpen/onEvent/onClose）
  ├── 创建 OmniRealtimeConversation（DashScope SDK WebSocket 实例）
  ├── 存入 sessions Map（conversation 实例 + 回调）
  ├── 启动守护线程 connect() → 异步建立到 DashScope 的 WebSocket
  └── connect 成功后 updateSession() 配置转写参数：
      ├── 语言：中文 (zh)
      ├── 采样率：16000Hz
      ├── 音频格式：PCM
      ├── VAD：启用服务端语音活动检测
      └── 静默断句阈值：400ms
```

注意：`QwenAsrService` 维护的 WebSocket 是**后端到阿里云 DashScope 的独立连接**，不是前端到后端的 WebSocket。两者是独立的。

### 2.3 ASR WebSocket 断线重连

当 `sendAudio()` 检测到 `IllegalStateException`（ASR WebSocket 被服务端关闭），会触发 `restartDashScopeStt()`：

```
handleUserAudio() → sttService.sendAudio() 失败
  → shouldRecoverAsrConnection() 判断是否为可恢复异常
    → restartDashScopeStt(sessionId)
      → sttService.restartTranscription()
        → stopTranscription() → 等 200ms → startTranscriptionLocked()
        → 验证重连（最多 10 次，每次间隔 100ms）
```

---

## 三、开场问题触发（AI 先说第一句话）

### 3.1 triggerOpeningQuestionIfNeeded()

在虚拟线程中异步执行，不阻塞 `afterConnectionEstablished` 返回：

```java
voicePipelineExecutor.execute(() -> {
    // ① 判读是否需要开场：无历史消息时才触发（重连/恢复不重复开场）
    List<String> history = getHistory(sessionId);
    if (history != null && !history.isEmpty()) return;

    // ② 根据 skillId 选择开场问题模板
    VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
    String aiReply = buildOpeningQuestion(sessionEntity);

    // ③ 先推送文本（前端立即可见）
    sendTextMessage(session, aiReply);
    saveMessage(sessionId, null, aiReply);  // 保存到 DB

    // ④ 再推送 WAV 音频（从 TTS 缓存或实时合成）
    byte[] wavAudio = getOpeningWavAudio(aiReply);
    sendAudio(session, wavAudio, aiReply);
});
```

### 3.2 开场问题选择逻辑

```
buildOpeningQuestion(sessionEntity)
  ├── 查 VoiceInterviewProperties.OpeningConfig.skillQuestions Map
  │   └── 命中 → 使用技能专属开场问题
  ├── skillId 在 algorithmSkills 列表中 → 使用算法方向默认问题
  └── 其他 → 使用后端方向默认问题
```

### 3.3 开场问题音频预热缓存

应用启动时，`@PostConstruct warmupOpeningAudioCache()` 会把所有开场问题模板预先合成为 WAV 音频存入 `openingAudioCache`（ConcurrentHashMap），首次连接时直接命中缓存，无需等待 TTS 合成。

---

## 四、用户音频 → LLM → TTS 核心管线

这是语音面试的一轮完整对话处理链路。总共五步：

```
 前端                         后端 WebSocket Handler                   DashScope API
─────                         ──────────────────────                   ────────────
 ① 用户说话，浏览器录音
 ② Base64 编码后发 WebSocket
      ─── {type:"audio", data:"..."} ──→
                              ③ handleUserAudio()
                                  Base64 解码 → PCM 字节
                                  ──── sendAudio() ────→  ASR 实时识别
                                                                      ④ VAD 断句
                                 ←── onFinal("识别结果") ──
                              ⑤ handleSttResult(final=true)
                                  追加到 mergeBuffer
                                  ─── {type:"subtitle", text:...} ──→  实时字幕
      ←── 字幕更新 ──

 ⑥ 用户点击"提交"按钮
      ─── {type:"control", action:"submit"} ──→
                              ⑦ flushMergedUtteranceToLlm()
                                  取出 mergeBuffer 累积文本
                                  送入虚拟线程池
                              ⑧ triggerLlmResponse()
                                  ── chatStreamSentences() ──→  LLM 流式生成
                                 ←── 流式 tokens ──
                                  每句完整句子检测到后：
                                  ── synthesize() ──→  TTS 合成
                                      └── 句子级并发：LLM 还在生成，TTS 已开始
                              ⑨ sendTextMessage(aiReply)  ──→  文本回复
                              ⑩ sendAudio(wavAudio)       ──→  WAV 音频
                              ⑪ saveMessage(sessionId, user, ai) → DB
      ←── 文本和音频 ──
```

### 4.1 步骤③④：音频推送与 ASR 识别

```java
// handleUserAudio()
private void handleUserAudio(String sessionId, String base64Audio) {
    // ① AI 说话/冷却期间丢弃麦克风输入，防止回声
    SessionState state = sessionStates.get(sessionId);
    if (state != null && state.isAiSpeakingOrCooldown()) {
        return;  // 丢弃
    }

    // ② Base64 解码 → PCM 字节
    byte[] audioData = Base64.getDecoder().decode(base64Audio);

    // ③ 发送到 ASR → DashScope 实时识别
    sttService.sendAudio(sessionId, audioData);
    //    ↓ DashScope 服务端 VAD 检测到静默 400ms → onFinal 回调返回
}
```

**AI 回声冷却机制**：`isAiSpeakingOrCooldown()` 在 AI 的 TTS 音频播放期间及播放结束后 800ms 内，丢弃麦克风输入。这防止 AI 的 TTS 音频通过扬声器被麦克风拾取，触发了新一轮的 ASR→LLM 循环（回声循环）。

### 4.2 步骤⑤：ASR 识别结果处理

```java
private void handleSttResult(String sessionId, String recognizedText, boolean isFinalSegment) {
    if (!isFinalSegment) {
        // 部分结果 → 实时字幕
        sendSubtitle(session, recognizedText, false);
        return;
    }

    // 最终定稿 → 追加到 mergeBuffer，等待用户手动提交
    state.appendFinalSttSegment(recognizedText);
    sendSubtitle(session, state.getMergeBufferPreview(), false);
}
```

**设计要点**：ASR 的 `onFinal` 只把文本追加到 `mergeBuffer`，**不自动触发 LLM**。必须等前端发 `{type: "control", action: "submit"}` 控制消息。这给了用户检查和修正识别结果的机会。

### 4.3 步骤⑦：用户提交 → 触发 LLM

前端点击"提交"或按快捷键发送控制消息：

```json
{ "type": "control", "action": "submit" }
```

后端 `handleControl()` 分发到 `flushMergedUtteranceToLlm()`：

```java
private void flushMergedUtteranceToLlm(String sessionId) {
    // ① CAS 锁：防止并发触发（并发提交时排期 400ms 后重试）
    if (!state.isProcessing().compareAndSet(false, true)) {
        utteranceMergeScheduler.schedule(
            () -> flushMergedUtteranceToLlm(sessionId), 400, TimeUnit.MILLISECONDS);
        return;
    }

    // ② 取出 mergeBuffer 中全部累积文本
    String userText = state.takeMergeBufferAndClear();
    if (userText == null || userText.trim().isEmpty()) {
        state.isProcessing().set(false);
        return;
    }

    // ③ 提交到虚拟线程池执行阻塞的 LLM+TTS 管线
    voicePipelineExecutor.execute(() -> {
        state.setProcessingThread(Thread.currentThread());
        try {
            triggerLlmResponse(sessionId, session, state);
        } finally {
            state.isProcessing().set(false);
            state.setProcessingThread(null);
        }
    });
}
```

### 4.4 步骤⑧⑨⑩：LLM 流式生成 + TTS 并发合成（核心）

```java
private void triggerLlmResponse(String sessionId, WebSocketSession session, SessionState state) {
    state.aiSpeaking.set(true);  // 标记 AI 正在说话（阻止回声）

    // ① 从 DB 获取会话实体和对话历史
    VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
    List<String> conversationHistory = getHistory(sessionId);

    // ② 流式 LLM + 句子级并发 TTS
    boolean streamEnabled = voiceInterviewProperties.isLlmStreamingEnabled();

    if (streamEnabled) {
        // 流式模式：每检测到一个完整句子就启动 TTS 合成
        Semaphore ttsSemaphore = new Semaphore(maxConcurrentTtsPerSession);
        List<CompletableFuture<byte[]>> ttsFutures = new ArrayList<>();

        aiReply = llmService.chatStreamSentences(
            userText,
            partialText -> sendTextMessage(session, partialText),  // 流式推送到前端
            sentence -> {
                // 每句完整话 → 启动 TTS（LLM 还在生成后续句子）
                ttsSemaphore.acquireUninterruptibly();
                CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(
                    () -> { try { return ttsService.synthesize(sentence); }
                            finally { ttsSemaphore.release(); } },
                    voicePipelineExecutor
                );
                ttsFutures.add(future);
            },
            sessionEntity, conversationHistory
        );

        // ③ 文本先送达（AI 回复完整文本）
        sendTextMessage(session, aiReply);
        saveMessage(sessionId, userText, aiReply);

        // ④ 合并所有 TTS 结果发送 WAV 音频
        byte[] mergedPcm = mergeTtsResults(ttsFutures);
        byte[] wavAudio = convertPcmToWav(mergedPcm);
        sendAudio(session, wavAudio, aiReply);
    } else {
        // 非流式模式：先等 LLM 完成，再 TTS
        aiReply = llmService.chat(userText, sessionEntity, conversationHistory);
        sendTextMessage(session, aiReply);
        saveMessage(sessionId, userText, aiReply);
        byte[] pcm = ttsService.synthesize(aiReply);
        byte[] wav = convertPcmToWav(pcm);
        sendAudio(session, wav, aiReply);
    }

    // ⑤ 解除 AI 说话标记，设置 800ms 回声冷却期
    state.aiSpeaking.set(false);
    state.aiSpeakEndAt.set(System.currentTimeMillis() + 800);
}
```

**流式模式的关键设计**：句子级并发 TTS。LLM 一边流式输出 tokens，一边检测完整句子。每检测到一个完整句子就启动一个 `CompletableFuture` 并行合成 TTS。LLM 生成和 TTS 合成并行执行，大幅降低首音频延迟。

### 4.5 前端收到的消息格式

在一次完整的问答轮次中，前端会依次收到：

```
① subtitle 消息（实时字幕）:
   {"type":"subtitle","text":"我主要使用Spring Boot...","isFinal":false}

② subtitle 最终定稿:
   {"type":"subtitle","text":"我主要使用Spring Boot构建微服务，对MyBatis有深入理解","isFinal":true}

③ text 消息（AI 回复文本——流式推送多段 partial）:
   {"type":"text","content":"很好，请描述一下你"}
   {"type":"text","content":"很好，请描述一下你在项目中如何处理服务间通信和事务一致性的"}

④ audio 消息（AI 回复 WAV 音频，Base64 编码）:
   {"type":"audio","data":"UklGRi...","text":"很好，请描述一下你在项目中..."}
```

---

## 五、暂停与恢复机制

### 5.1 用户主动暂停

前端调用 `PUT /api/voice-interview/sessions/{id}/pause`：

```
→ VoiceInterviewController.pauseSession()
  → VoiceInterviewService.pauseSession()
    → 校验状态必须为 IN_PROGRESS（否则抛 400）
    → 更新 status=PAUSED, pausedAt=now
    → 保存 DB，清除 Redis 缓存
```

暂停**不关闭 WebSocket**——WebSocket 保持连接，但用户不发送音频时自然无 ASR 输入。

### 5.2 恢复面试

前端调用 `PUT /api/voice-interview/sessions/{id}/resume`：

```
→ VoiceInterviewController.resumeSession()
  → VoiceInterviewService.resumeSession()
    → 校验状态必须为 PAUSED
    → 更新 status=IN_PROGRESS, resumedAt=now
    → 保存 DB，重新写入 Redis 缓存
    → 返回新 SessionResponseDTO（含 WebSocket URL，前端需重新连接）
```

### 5.3 自动暂停超时

`VoiceInterviewWebSocketHandler` 内置 `@Scheduled(fixedRate = 30000)` 定时检测：

```
每 30 秒执行 checkPauseTimeout()
  ├── 超过 4 分 30 秒无活动 → 发送警告消息 "会话将在30秒后暂停"
  └── 超过 5 分钟无活动 → 自动暂停
      ├── 发送暂停通知
      ├── interviewService.pauseSession(sessionId, "timeout")
      ├── 停止 ASR 识别
      └── 关闭 WebSocket 连接
```

---

## 六、WebSocket 连接关闭与会话结束

### 6.1 用户主动结束

前端发送控制消息 `{type: "control", action: "end_interview"}`：

```java
case "end_interview":
    interviewService.endSession(sessionId);
    break;
```

`endSession()` 执行：
1. 设置 `endTime`、`phase=COMPLETED`、`status=COMPLETED`
2. 计算实际时长 `actualDuration = startTime 到 now 的秒数差`
3. 设置 `evaluateStatus = PENDING`
4. 保存 DB，清除 Redis 缓存
5. **触发异步评估任务**：`voiceEvaluateStreamProducer.sendEvaluateTask(sessionId)`
   - 入队 Redis Stream → `VoiceEvaluateStreamConsumer` 后台消费 → LLM 评估 → 保存结果

### 6.2 WebSocket 异常断开兜底

`afterConnectionClosed()` 被触发时：

```java
public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    String sessionId = extractSessionId(session);

    // ① 清理运行时缓存
    sessions.remove(sessionId);
    SessionState removedState = sessionStates.remove(sessionId);
    if (removedState != null) {
        Thread t = removedState.getProcessingThread();
        if (t != null) t.interrupt();  // 中断正在执行的 LLM+TTS 管线
    }
    lastActivityTime.remove(sessionId);

    // ② 停止 ASR 识别
    sttService.stopTranscription(sessionId);

    // ③ 兜底结束会话（仅当状态仍为 IN_PROGRESS）
    interviewService.endSessionIfInProgress(sessionId);
}
```

`endSessionIfInProgress()` 的幂等保护：

```java
public void endSessionIfInProgress(String sessionId) {
    VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong).orElse(null);
    if (session == null || session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
        return;  // 已结束或已暂停，不重复操作
    }
    endSession(session);  // 仅 IN_PROGRESS 状态执行结束
}
```

### 6.3 用户主动结束 vs 异常断开

| 场景 | 触发方式 | 是否触发评估 | 处理 |
|------|---------|------------|------|
| 正常结束 | 前端控制消息或 HTTP POST /end | 是 | `endSession()` → 更新状态 → 触发评估 |
| WebSocket 异常断开 | `afterConnectionClosed()` 回调 | 是（若状态为 IN_PROGRESS） | `endSessionIfInProgress()` → 状态不变不重复操作 |
| 会话已 COMPLETED/PAUSED 后断开 | `afterConnectionClosed()` | 否 | `endSessionIfInProgress()` 直接 return |

---

## 七、评估流程（面试结束后的异步评估）

面试结束后，异步评估任务通过 Redis Stream 执行：

```
endSession() → evaluateStatus = PENDING
  → voiceEvaluateStreamProducer.sendEvaluateTask(sessionId)
    └── Redis Stream XADD voice:evaluate:stream * sessionId "42"

VoiceEvaluateStreamConsumer 后台轮询消费
  → VoiceInterviewEvaluationService.generateEvaluation(sessionId)
    ├── 查询 voice_interview_messages 表（按 sequence_num ASC）
    ├── 构建 QaRecord 列表（AI 提问 → 用户回答 配对）
    ├── ⚠️ LLM 调用在 @Transactional 外部（避免占用 DB 连接）
    ├── UnifiedEvaluationService.evaluate()
    │   └── LLM 全面评估（总分、逐题评分、优势、改进建议、参考答案）
    └── saveEvaluationTransactional()
        └── VoiceInterviewEvaluationEntity 持久化到 voice_interview_evaluations 表

前端轮询 GET /evaluation
  → evaluateStatus: PENDING → PROCESSING → COMPLETED
  → COMPLETED 时 getEvaluation(sessionId)
    └── 从 voice_interview_evaluations 反序列化 → VoiceEvaluationDetailDTO
        └── 前端渲染评估面板
```

---

## 八、完整流程总结（一段话）

一次完整的语音面试从用户在前端配置面试模板（技能方向、难度、阶段开关）并点击"开始面试"开始：前端调用 `POST /api/voice-interview/sessions` 经由 `VoiceInterviewController` 委托 `VoiceInterviewService.createSession()` 创建会话实体并保存到数据库和 Redis 缓存，返回包含 `sessionId` 和 `webSocketUrl` 的 `SessionResponseDTO`。**到此 Controller 的职责结束**——它不参与 WebSocket 的建立。前端拿到 URL 后调用 `new WebSocket(webSocketUrl)` 发起 WebSocket 握手，这个 Upgrade 请求绕过 DispatcherServlet 直接由 Spring WebSocket 框架根据 `WebSocketConfig` 路由到 `VoiceInterviewWebSocketHandler.afterConnectionEstablished()`，Handler 随即完成三门初始化：将 WebSocket 会话存入内存映射表、启动 ASR 语音识别（后端到阿里云 DashScope 的独立 WebSocket，配置中文识别、16kHz 采样率、400ms 静默断句）、触发起始开场白与第一个提问（文本先行、WAV 音频随后下发，音频优先从预热缓存命中避免首次 TTS 延迟）。用户开始说话后，前端将麦克风 PCM 音频 Base64 编码后通过 WebSocket 发送到后端，Handler 的 `handleUserAudio()` 解码后调用 `QwenAsrService.sendAudio()` 送入 DashScope ASR，期间 AI 说话时及说话后 800ms 冷却期内主动丢弃麦克风输入防止回声。ASR 的实时部分结果以字幕形式推送到前端，最终定稿（VAD 断句）追加到 `SessionState` 的合并缓冲区。用户点击提交按钮后，Handler 以 CAS 锁防止并发触发，取出合并缓冲区的累积文本，提交到虚拟线程池执行阻塞的 LLM+TTS 管线：`DashscopeLlmService.chatStreamSentences()` 流式调用 LLM，每检测到一个完整句子就启动 `CompletableFuture` 并行合成 TTS，LLM 生成与 TTS 合成并发执行以降低首音频延迟，全部完成后发送 AI 回复文本和合并的 WAV 音频到前端，并将对话消息保存到数据库。面试进行中可通过暂停/恢复接口管理会话状态，Handler 内置每 30 秒的定时检测在 5 分钟无活动时自动暂停。用户结束面试（或 WebSocket 异常断开）时，Handler 清理运行时缓存、停止 ASR 识别，调用 `VoiceInterviewService.endSession()` 设置结束时间和状态为 COMPLETED，并触发异步评估任务（入队 Redis Stream），后台 `VoiceEvaluateStreamConsumer` 消费任务调用 `VoiceInterviewEvaluationService.generateEvaluation()` 进行 LLM 评估并持久化结果，前端轮询 `GET /evaluation` 获取评估进度，评估完成后渲染评估面板展示总分、逐题评分、优势、改进建议和参考答案——至此一轮完整的语音面试结束。
