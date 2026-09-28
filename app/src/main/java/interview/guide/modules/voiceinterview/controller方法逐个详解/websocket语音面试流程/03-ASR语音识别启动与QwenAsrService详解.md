# ASR 语音识别启动与 QwenAsrService 详解

## 概述

ASR（Automatic Speech Recognition，自动语音识别）是语音面试的"耳朵"——负责将用户的语音实时转换为文字。本系统使用阿里云 DashScope 的 `qwen3-asr-flash-realtime` 模型，通过 WebSocket 协议进行实时语音转文字。`QwenAsrService` 封装了与 DashScope API 的所有交互逻辑，每个面试会话对应一个独立的 ASR WebSocket 连接。

一个关键认知是：语音面试系统中存在**两条独立的 WebSocket 连接**。第一条是前端浏览器与后端 Spring Boot 之间的 WebSocket，由 `VoiceInterviewWebSocketHandler` 管理，负责传输用户音频和 AI 回复。第二条是后端 `QwenAsrService` 与阿里云 DashScope 服务之间的 WebSocket，负责将音频实时转为文本。这两条连接的生命周期完全独立——前端断开时停止 ASR，ASR 断开时自动重连不影响前端。理解这个架构是理解整个 ASR 子系统的前提。

---

## 一、ASR 的启动入口 —— startDashScopeStt()

ASR 的启动由 Handler 的 `startDashScopeStt()` 方法触发，它在 `afterConnectionEstablished()` 中被调用，与发送欢迎消息、触发起始开场白等操作在同一个 try 块中执行。

```java
private void startDashScopeStt(String sessionId, WebSocketSession session) {
    sttService.startTranscription(
        sessionId,
        text -> handleSttResult(sessionId, text, true),    // onFinal
        text -> handleSttResult(sessionId, text, false),   // onPartial
        error -> {
            log.error("STT error for session {}", sessionId, error);
            sendError(session, "语音识别失败: " + error.getMessage());
        }                                                    // onError
    );
}
```

这个方法的核心职责是设置三个回调函数，它们决定了 ASR 识别结果如何被消费：

**onFinal 回调**（第二个参数 `text -> handleSttResult(sessionId, text, true)`）处理 VAD 断句后的最终识别结果。当 DashScope 的服务端 VAD 检测到 400ms 静默后判定用户说完了一段话，会返回完整的识别文本。此时 Handler 将文本追加到 `SessionState` 的合并缓冲区（mergeBuffer），等待用户点击"提交"按钮后才送入 LLM。这里有一个精心设计的分工：onFinal 负责"准确性"（完整定稿文本），onPartial 负责"实时性"（让用户看到正在识别的字幕反馈）。

**onPartial 回调**（第三个参数 `text -> handleSttResult(sessionId, text, false)`）处理说话过程中的部分识别结果。用户还在说话时，ASR 模型会实时返回当前已识别出的中间文本。Handler 直接以字幕形式推送到前端，用户看到"正在识别中"的文本更新，获知系统正在工作。

**onError 回调**（第四个参数）处理 ASR 连接或识别过程中的异常。当 DashScope WebSocket 连接出现错误时，通过这个回调告知前端，前端可以显示错误提示。

---

## 二、配置注入 —— QwenAsrService 的数据来源

`QwenAsrService` 有十来个成员变量（url、model、apiKey、language、format、sampleRate、enableTurnDetection 等），它们决定了后续 `startTranscriptionLocked()` 中构建的 `OmniRealtimeParam` 和 `OmniRealtimeConfig` 的所有参数值。这些值只有一个来源——Spring Boot 的配置绑定机制。

### 配置链路

完整的取值链路是：

`.env 文件` → `application.yml (spring.config.import)` → `VoiceInterviewProperties.AsrConfig` → `QwenAsrService` 成员变量

`QwenAsrService` 在 Spring 容器构造时通过构造函数接收 `VoiceInterviewProperties`：

```java
public QwenAsrService(VoiceInterviewProperties voiceInterviewProperties) {
    applyAsrConfig(voiceInterviewProperties.getQwen().getAsr());
}
```

`applyAsrConfig()` 方法将配置对象中的每一个字段逐一赋值给成员变量：

```java
private void applyAsrConfig(VoiceInterviewProperties.AsrConfig asr) {
    this.url = asr.getUrl();
    this.model = asr.getModel();
    this.apiKey = asr.getApiKey();
    this.language = asr.getLanguage();
    this.format = asr.getFormat();
    this.sampleRate = asr.getSampleRate();
    this.enableTurnDetection = asr.isEnableTurnDetection();
    this.turnDetectionType = asr.getTurnDetectionType();
    this.turnDetectionThreshold = asr.getTurnDetectionThreshold();
    this.turnDetectionSilenceDurationMs = asr.getTurnDetectionSilenceDurationMs();
}
```

每个字段的含义和典型值如下：

| 成员变量 | 典型值 | 作用 |
|---------|--------|------|
| `url` | `wss://dashscope.aliyuncs.com/api-ws/v1/realtime` | DashScope 实时转写 API 的 WebSocket 端点 |
| `model` | `qwen3-asr-flash-realtime` | 使用的 ASR 模型名称 |
| `apiKey` | 从 `.env` 注入 | 阿里云 DashScope API 密钥 |
| `language` | `zh` | 识别语言，中文 |
| `format` | `pcm` | 发送到 ASR 的音频格式，16bit 原始 PCM |
| `sampleRate` | `16000` | 音频采样率，16kHz |
| `enableTurnDetection` | `true` | 是否启用服务端轮流检测（VAD） |
| `turnDetectionType` | `server_vad` | VAD 类型，服务端语音活动检测 |
| `turnDetectionThreshold` | `0.5` | VAD 灵敏度，值越高越容易判定为语音 |
| `turnDetectionSilenceDurationMs` | `400` | 静默多久后断句，400ms |

这些值通过 `@ConfigurationProperties(prefix = "app.voice-interview")` 自动绑定，全程不硬编码。`@PostConstruct init()` 在 Bean 初始化后校验 apiKey 是否已配置，若未配置则抛出 `IllegalStateException` 阻止应用启动——避免运行时才发现凭证缺失。

---

## 三、会话锁机制 —— 保证同一会话的 ASR 操作串行化

`QwenAsrService` 管理着多个并发的 ASR 会话。每个面试会话可能在不同时间点被启动、停止、重连。为了防止并发操作导致多个连接并存（例如两个线程同时对同一个 sessionId 执行 start 操作），必须保证同一会话的所有 ASR 操作是串行化的。

### 每个会话一把独立锁

`QwenAsrService` 使用 `ConcurrentHashMap<String, Object>` 维护锁对象映射：

```java
private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

private Object lockForSession(String sessionId) {
    return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
}
```

`computeIfAbsent` 是 ConcurrentHashMap 的原子操作，确保了惰性创建锁对象的线程安全性。每个 sessionId 第一次调用时创建唯一的锁对象，后续返回同一个对象。

### 所有公共方法都持有会话锁

`startTranscription`、`stopTranscription`、`restartTranscription` 这三个公开方法的第一个操作都是：

```java
synchronized (lockForSession(sessionId)) {
    // ...
}
```

这意味着对同一个 sessionId 的任何 ASR 操作（启动、停止、重连）在任何时刻只能有一个在执行。例如：如果 ASR 断开触发了重连（restartTranscription），而此时用户刚好断开 WebSocket 触发了停止（stopTranscription），`synchronized` 保证这两个操作不会交错执行——要么先重连再停止，要么先停止再重连。

---

## 四、startTranscriptionLocked() 的核心实现

这是整个 ASR 建立的核心方法，在持有会话锁的前提下执行。方法较长但逻辑清晰，分为六个有序步骤。每个步骤都有其存在的具体原因，而不是随意编排。

### 第一步：防重复校验

```java
if (sessions.containsKey(sessionId)) {
    throw new IllegalStateException("Session already exists: " + sessionId);
}
```

在持有会话锁的情况下，`sessions` Map 的操作是线程安全的。如果同一个 sessionId 已经存在条目，说明已经有 ASR 连接在运行，抛出 `IllegalStateException` 阻止重复启动。这是防止同类并发操作的兜底保护。

### 第二步：构建 DashScope 连接参数

```java
OmniRealtimeParam param = OmniRealtimeParam.builder()
        .model(model)       // ASR 模型名，构造时从配置注入
        .url(url)           // DashScope WebSocket 地址
        .apikey(apiKey)     // API Key，来自 .env
        .build();
```

`OmniRealtimeParam` 是 DashScope SDK 的连接参数对象，只需要三个字段：模型名、WebSocket URL 和 API Key。这三个值全部来自第一步中赋值的成员变量。API Key 的完整路径是 `.env → application.yml → @ConfigurationProperties → VoiceInterviewProperties.AsrConfig.apiKey → QwenAsrService.apiKey → OmniRealtimeParam.apikey`。

### 第三步：创建 OmniRealtimeCallback 回调处理器

```java
final AtomicReference<OmniRealtimeConversation> conversationRef = new AtomicReference<>();

OmniRealtimeCallback callback = new OmniRealtimeCallback() {
    @Override public void onOpen() {
        log.debug("[Session: {}] WebSocket connection established", sessionId);
    }

    @Override public void onEvent(JsonObject message) {
        handleServerEvent(sessionId, message, onFinal, onPartial, onError);
    }

    @Override public void onClose(int code, String reason) {
        OmniRealtimeConversation closed = conversationRef.get();
        sessions.compute(sessionId, (id, existing) -> {
            if (existing != null && closed != null
                    && existing.getConversation() == closed) {
                return null;
            }
            return existing;
        });
    }
};
```

这是 DashScope SDK 要求的回调接口，实现了三个方法。`onOpen` 最简单，仅在 WebSocket 连接成功建立时记录日志。`onEvent` 是核心分发器，将 DashScope 推送的 JSON 事件委托给 `handleServerEvent()` 方法，根据事件类型（转写完成、部分结果、错误等）触发不同的上层回调。

`onClose` 是这段代码中设计最精妙的部分。由于 ASR 支持断线重连，存在如下时序问题：连接 A 被服务端关闭 → 系统创建连接 B 进行重连 → 连接 A 的 onClose 回调延迟到达。如果 onClose 简单地执行 `sessions.remove(sessionId)`，就会错误地移除连接 B 的条目，导致后续音频发送失败（典型表现为"第三轮起用户说话完全没反应"）。

解决方案是使用 `AtomicReference<OmniRealtimeConversation>` 保存当前连接的 Conversation 实例引用。`onClose` 时通过 `sessions.compute()` 做**对象引用比较**——仅当 Map 中当前条目引用的 Conversation 对象就是本次被关闭的那个实例时（`existing.getConversation() == closed`），才移除该条目。如果 Map 中已经是重连后的新 Conversation，说明新连接已经建立，`==` 比较不成立，保留新条目不动。这种"引用去重"的模式是处理 WebSocket 重连场景下回调时序问题的经典解法。

### 第四步：创建 Conversation 实例

```java
OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, callback);
conversationRef.set(conversation);
```

用第二步的连接参数和第三步的回调处理器构造 `OmniRealtimeConversation` 对象。这是 DashScope SDK 的核心类，内部管理着到 DashScope 的 WebSocket 连接（建立、维护、关闭、消息收发）。构造后立即通过 `conversationRef.set()` 保存引用，以便 onClose 回调中做引用比较。

### 第五步：先存入 Map，再建立连接

```java
sessions.put(sessionId, new AsrSession(conversation, onFinal, onPartial, onError));
```

这是一个微妙的顺序安排——在 `conversation.connect()` 之前就存入 `sessions` Map。原因是 `hasActiveSession()` 方法被暂停超时检测等定时任务调用，需要在连接建立过程中就已返回 `true`。如果先 connect 成功后再 put，中间有一个时间窗口内 `hasActiveSession()` 返回 `false`，可能导致定时任务检测不到活跃会话而做出错误判断。

`AsrSession` 是一个私有内部类，包装了 Conversation 实例和三个回调函数（onFinal、onPartial、onError），提供统一的访问接口：

```java
private static class AsrSession {
    private final OmniRealtimeConversation conversation;
    private final Consumer<String> onFinal;
    private final Consumer<String> onPartial;
    private final Consumer<Throwable> onError;
    // getter 方法省略
}
```

### 第六步：异步建立连接并配置

```java
Thread connectionThread = new Thread(() -> {
    try {
        // 6.1 建立 WebSocket 连接——这是一个阻塞调用，会等待 WebSocket 握手完成。
        //     放在守护线程中执行，确保不阻塞调用方（Handler 的 afterConnectionEstablished）。
        conversation.connect();

        // 6.2 构建转写参数：语言、采样率、音频格式。
        //     这些值同样来自构造时缓存的成员变量。
        OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
        transcriptionParam.setLanguage(language);               // zh（中文）
        transcriptionParam.setInputSampleRate(sampleRate);      // 16000Hz
        transcriptionParam.setInputAudioFormat(format);         // pcm

        // 6.3 构建会话配置。
        //     仅需 TEXT 模态输出（对 ASR 来说不需要 DashScope 返回音频）。
        //     启用服务端 VAD，配置 400ms 静默断句阈值。
        OmniRealtimeConfig config = OmniRealtimeConfig.builder()
            .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
            .enableTurnDetection(enableTurnDetection)                // 启用服务端 VAD
            .turnDetectionType(turnDetectionType)                    // server_vad
            .turnDetectionThreshold(turnDetectionThreshold)          // 灵敏度 0.5
            .turnDetectionSilenceDurationMs(turnDetectionSilenceDurationMs) // 400ms 断句
            .transcriptionConfig(transcriptionParam)
            .build();

        // 6.4 下发配置到 DashScope 服务端。
        //     此后所有发送到该连接的音频数据都将按此配置实时转写。
        conversation.updateSession(config);
        log.info("[Session: {}] Transcription session started successfully", sessionId);

    } catch (Exception e) {
        // 连接失败时的清理：同样用对象引用比较的方式安全移除条目
        sessions.compute(sessionId, (id, existing) -> {
            if (existing != null && existing.getConversation() == conversation) {
                return null;
            }
            return existing;
        });
        onError.accept(e);  // 通知上层 Handler
    }
}, "ASR-Connection-" + sessionId);
connectionThread.setDaemon(true);
connectionThread.start();
```

这里的设计考量有三点。第一，`conversation.connect()` 是阻塞调用，会等待与 DashScope 的 WebSocket 握手完成。如果放在 `afterConnectionEstablished()` 的主线程中执行，会阻塞整个 Handler 的初始化，导致欢迎消息和开场问题延迟发送。因此放在守护线程中异步执行。

第二，设为守护线程（`setDaemon(true)`）确保 JVM 退出时不会因为该线程而阻塞进程关闭。如果 WebSocket 握手正在进行中应用被关闭，守护线程会被自动终止。

第三，`OmniRealtimeConfig` 的配置决定了 ASR 的行为模式。`modalities` 设为仅 TEXT——对语音识别场景来说不需要 DashScope 返回音频，只要文本。`enableTurnDetection` 启用服务端 VAD，这样 DashScope 会自动检测用户何时开始说话、何时说完（400ms 静默后断句），前端不需要自己做静默检测或手动触发断句。这是语音面试体验的关键设计——用户自然说话即可，系统自动判断何时提交到 LLM。

---

## 五、ASR 事件处理 —— handleServerEvent()

当 DashScope 服务端通过 WebSocket 推送事件时，`OmniRealtimeCallback.onEvent()` 被触发，委托给 `handleServerEvent()` 处理：

```java
private void handleServerEvent(String sessionId, JsonObject message,
        Consumer<String> onFinal, Consumer<String> onPartial, Consumer<Throwable> onError) {
    String eventType = message.get("type").getAsString();

    switch (eventType) {
        // 最核心的事件：VAD 断句后返回的完整识别文本
        case "conversation.item.input_audio_transcription.completed":
            String transcript = message.getAsJsonObject().get("transcript").getAsString();
            onFinal.accept(transcript);
            break;

        // 部分识别结果：说话过程中的实时中间文本
        case "conversation.item.input_audio_transcription.text":
        case "conversation.item.input_audio_transcription.delta":
            if (onPartial != null) {
                dispatchPartialTranscript(sessionId, message, onPartial);
            }
            break;

        // ASR 错误事件
        case "error":
            JsonObject errorObj = message.getAsJsonObject("error");
            String errorMsg = String.format("ASR Error [%s/%s]: %s",
                errorObj.get("type"), errorObj.get("code"), errorObj.get("message"));
            onError.accept(new IllegalStateException(errorMsg));
            break;
    }
}
```

`conversation.item.input_audio_transcription.completed` 是最核心的事件。当 DashScope 的 VAD 检测到 400ms 静默后，将当前语音片段送入 ASR 模型转写，完成后推送此事件。事件携带完整的识别文本、识别语言（zh）和情绪信息（neutral/positive/negative）。这段文本通过 `onFinal.accept(transcript)` 传递给 Handler，进入合并缓冲区。

`conversation.item.input_audio_transcription.text` 和 `.delta` 是部分识别结果。前者返回已确认的前缀文本 + 草稿后缀，后者返回增量 delta。两者都通过 `dispatchPartialTranscript()` 提取文本后调用 `onPartial.accept(text)`，最终以字幕形式推送到前端。

转写失败事件（`conversation.item.input_audio_transcription.failed`）只记录错误日志，不中断会话。这是因为单次转写失败（例如用户说了无法识别的内容）不应影响后续语音的正常识别。

---

## 六、音频发送 —— sendAudio()

```java
public void sendAudio(String sessionId, byte[] audioData) {
    AsrSession session = sessions.get(sessionId);
    if (session == null) {
        throw new IllegalStateException("No active session found: " + sessionId);
    }
    String audioBase64 = Base64.getEncoder().encodeToString(audioData);
    session.getConversation().appendAudio(audioBase64);
}
```

这段代码很简单，但它的异常抛出设计是关键。当 ASR 连接已被服务端关闭（例如网络波动导致 WebSocket 断开），`appendAudio()` 会抛出 `IllegalStateException`。这个异常被上层 Handler 的 `handleUserAudio()` 捕获，通过 `shouldRecoverAsrConnection()` 判断是否可修复后触发 `restartDashScopeStt()` 重连流程。

---

## 七、断线重连 —— restartTranscription()

ASR 到 DashScope 的 WebSocket 连接可能因网络波动、服务端主动关闭等原因断开。断线重连的完整流程封装在 `restartTranscription()` 中：

```java
public void restartTranscription(String sessionId, Consumer<String> onFinal,
        Consumer<String> onPartial, Consumer<Throwable> onError) {
    synchronized (lockForSession(sessionId)) {
        log.info("[Session: {}] Restarting DashScope ASR (stop + start)", sessionId);

        // 第一步：停止旧连接，清理 sessions Map 和 DashScope WebSocket
        stopTranscription(sessionId);

        // 第二步：等待 200ms 让服务端彻底释放旧连接资源
        try { Thread.sleep(200); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // 第三步：创建新连接（异步守护线程中建立）
        startTranscriptionLocked(sessionId, onFinal, onPartial, onError);

        // 第四步：轮询验证重连是否成功（最多 10 次，每次 100ms，总计约 1 秒）
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Thread.sleep(100);
                AsrSession newSession = sessions.get(sessionId);
                if (newSession != null && newSession.getConversation() != null) {
                    log.info("[Session: {}] ASR reconnection verified successfully", sessionId);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // 1 秒后仍未检测到新连接，记录警告但不阻断流程
        // 后续 sendAudio 时会再次触发 IllegalStateException 进行重试
        log.warn("[Session: {}] ASR reconnection may not be fully ready after 1 second", sessionId);
    }
}
```

整个流程在同一把会话锁保护下执行，保证了 stop + start 的原子性——不会出现"停了一半又开始"的中间状态。200ms 等待让 TCP 四次挥手和 DashScope 内部资源释放有时间完成。轮询验证是必需的，因为 `startTranscriptionLocked()` 中的 `connect()` 在另一个守护线程中异步执行，`startTranscriptionLocked()` 返回时连接可能尚未建立完成。

重连成功后，ASR 使用相同的 onFinal/onPartial/onError 回调（Handler 在 `restartDashScopeStt()` 中传入），上层 Handler 无需感知重连的发生，识别结果无缝恢复。

---

## 八、总结

`QwenAsrService` 是语音面试系统中"耳朵"角色的实现，管理着后端到阿里云 DashScope 的独立 WebSocket 连接，每个面试会话一条。它的核心设计完整覆盖了从配置注入到连接管理、从识别回调到断线恢复的全流程：配置参数通过 `@ConfigurationProperties` 从 yaml 注入、API Key 通过 `.env` 提供、`@PostConstruct` 启动时校验，全程不硬编码；每个 sessionId 一把独立的 `synchronized` 锁保证所有 start/stop/restart 操作串行执行，防止并发导致多连接并存；`startTranscriptionLocked()` 六步流程中先 put 再 connect 的顺序确保 `hasActiveSession()` 在异步连接期间立即返回 true 供定时检测使用；`AtomicReference` 对象引用比较在 onClose 回调中判断是否为本次连接，防止重连场景下旧连接延迟关闭误删新连接条目（「第三轮起无声」的根因）；`connect()` 阻塞操作在守护线程中异步执行，不阻塞 Handler 的 `afterConnectionEstablished` 返回，确保欢迎消息和开场问题及时送达；服务端 VAD 以 400ms 静默阈值自动断句，用户自然说话即可，无需前端做静默检测；部分识别结果通过 onPartial 回调实时推送字幕，最终定稿通过 onFinal 回调进入合并缓冲区。ASR 的输出（识别文本）不会自动送入 LLM——ASR 只负责"听到什么"，至于听到之后做什么（暂存、等待提交、自动触发），由上层 `VoiceInterviewWebSocketHandler` 中的 `handleSttResult()` 和合并缓冲区机制决定。
