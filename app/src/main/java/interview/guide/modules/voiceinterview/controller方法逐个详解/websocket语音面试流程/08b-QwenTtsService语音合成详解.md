# QwenTtsService 语音合成详解

## 概述

`QwenTtsService` 是语音面试的文本转语音（TTS）服务，将 LLM 生成的面试官回复文本合成为 PCM 音频数据，再通过 WebSocket 推送到前端播放给用户听。它基于阿里云 DashScope QwenTtsRealtime SDK 实现，采用 commit 模式手动控制合成时机——每次合成建立一条临时的 WebSocket 连接到 DashScope 服务端，发送文本后等待服务端返回音频分片，收集完成后关闭连接并返回合并后的完整 PCM 字节数组。

QwenTtsService 在整个语音面试架构中的位置：LLM 流式生成的每个完整句子 → `onSentenceComplete` 回调 → `CompletableFuture.supplyAsync` 在虚拟线程池中调用 `qwenTtsService.synthesize(sentence)` → 返回 PCM 字节数组 → 所有句子合成完成后按模式（分块推送或合并推送）通过 WebSocket 发送到前端。

***

## 一、服务初始化与配置注入

QwenTtsService 的所有运行时参数（模型名、API Key、音色、采样率等）通过构造器注入，来源是 Spring Boot 的 `VoiceInterviewProperties` 配置绑定。

### 1.1 构造器注入配置

```java
// QwenTtsService.java — 构造器
public QwenTtsService(VoiceInterviewProperties voiceInterviewProperties) {
    applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
}
```

```java
// QwenTtsService.java — 配置赋值
private void applyTtsConfig(VoiceInterviewProperties.QwenTtsConfig tts) {
    this.model = tts.getModel();          // 模型名，如 "qwen3-tts-flash-realtime"
    this.apiKey = tts.getApiKey();        // DashScope API Key，来自 .env 的环境变量
    this.voice = tts.getVoice();          // 音色名，如 "zhixiaobai"
    this.format = tts.getFormat();        // 音频格式，如 "pcm"
    this.sampleRate = tts.getSampleRate(); // 采样率，24000Hz
    this.mode = tts.getMode();            // 合成模式，"commit"
    this.languageType = tts.getLanguageType(); // 语言类型，"Chinese"
    this.speechRate = tts.getSpeechRate(); // 语速倍率，1.0
    this.volume = tts.getVolume();         // 音量倍率，1
}
```

**设计原因分析**：所有配置参数集中在构造时一次读取并缓存到成员变量中，后续每次 `synthesize()` 调用直接使用成员变量构建连接参数和会话配置，无需重复读取配置文件。`reload()` 方法支持运行时热更新（通过 `VoiceInterviewProperties` 重新注入），更新后新的合成请求立即使用新配置。

### 1.2 配置数据来源

配置链路与 ASR 服务完全一致：

```
application.yml / .env
  │  app.voice-interview.qwen.tts.model=qwen3-tts-flash-realtime
  │  app.voice-interview.qwen.tts.voice=zhixiaobai
  │  ...
  ▼
VoiceInterviewProperties
  │  @ConfigurationProperties(prefix = "app.voice-interview")
  │  内嵌类 QwenTtsConfig 的字段自动绑定
  ▼
QwenTtsService 构造器
  │  applyTtsConfig() → this.model, this.voice, ...
  ▼
synthesize() 方法
  │  用成员变量构建 QwenTtsRealtimeParam 和 QwenTtsRealtimeConfig
```

### 1.3 @PostConstruct API Key 校验

```java
@PostConstruct
public void init() {
    if (apiKey == null || apiKey.trim().isEmpty()) {
        throw new IllegalStateException(
            "API key must be configured before initializing QwenTtsService");
    }
    log.info("QwenTtsService initialized with model: {}, voice: {}, sampleRate: {}Hz",
             model, voice, sampleRate);
}
```

**设计原因分析**：API Key 为空时在应用启动阶段立即抛出 `IllegalStateException`，让 Spring 容器初始化失败并终止启动，而不是在运行中用户面试时才失败。这是"启动时检查"（fail-fast）的最佳实践。日志打印的 model、voice、sampleRate 信息有助于运维人员确认当前生效的 TTS 配置。

***

## 二、synthesize() 方法详解

`synthesize()` 是整个 TTS 服务的核心方法，接收一段文本，返回对应的 PCM 音频字节数组。它的设计哲学是"建立临时连接 → 发送文本 → 收集结果 → 关闭连接"，每次调用都是一次完整的生命周期。

以下按七个执行阶段逐一拆解。

### 阶段一：空文本检查

```java
// ===== 阶段一：空文本检查 =====
// null、空字符串、纯空白文本无合成意义，直接返回空数组
// 避免建立无意义的 WebSocket 连接
if (text == null || text.trim().isEmpty()) {
    log.debug("Empty or null text provided, returning empty audio array");
    return new byte[0];
}
```

**设计原因分析**：这是性能保护逻辑。TTS 合成需要建立 WebSocket 连接、TCP 握手、DashScope 会话配置，整个过程即使文本为空也要消耗几百毫秒和网络资源。提前过滤空文本可避免调用方的无效请求导致资源浪费。返回 `new byte[0]`（空数组）而非 null，保证调用方可以安全地使用返回值而不需要 null 检查。

### 阶段二：并发工具初始化

```java
// ===== 同步等待工具 =====
// CountDownLatch：将异步 WebSocket 响应转为同步阻塞等待
// 初始计数 1，onEvent 收到 audio.done 或 onClose 时 countDown() 解除阻塞
CountDownLatch synthesisLatch = new CountDownLatch(1);

// ByteArrayContainer：可动态增长的字节数组容器，收集 WebSocket 推送的多个音频分片
ByteArrayContainer audioContainer = new ByteArrayContainer();

// AtomicReference<Throwable>：存储异步回调中发生的异常引用
// 主线程在 CountDownLatch 解除后读取此引用，非 null 表示合成失败
AtomicReference<Throwable> errorRef = new AtomicReference<>();

// AtomicReference<String>：存储 TTS 服务端返回的 responseId（用于日志追踪）
AtomicReference<String> responseIdRef = new AtomicReference<>();
```

**设计原因分析**：

- `CountDownLatch`：TTS 的 WebSocket 通信是异步的——文本提交后不知道音频何时返回。`CountDownLatch` 将异步响应同步化，让主线程可以 `await()` 等待音频返回。初始计数 1，`countDown()` 后为 0，`await()` 解除阻塞。
- `ByteArrayContainer`：TTS 服务端通常分批推送音频数据（每个 `response.audio.delta` 事件携带一个 Base64 编码的音频分片），需要收集所有分片后合并为完整音频。`ByteArrayContainer` 基于 `ByteArrayOutputStream` 实现，追加性能为均摊 O(1)，避免手动数组扩容导致的 O(n²) 复制。
- `AtomicReference<Throwable>`：WebSocket 的 `onEvent` 回调在另一个线程中执行，`Throwable` 不能通过返回值传递。`AtomicReference` 提供线程安全的跨线程异常传递。
- `AtomicReference<String>`：存储 `responseId` 用于日志，方便排查 DashScope 侧的问题。

### 阶段三：构建 WebSocket 连接参数

```java
// ===== 阶段二：构建连接参数 =====
// model、apiKey 来自构造时通过 applyTtsConfig() 注入的成员变量
QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
        .model(model)    // "qwen3-tts-flash-realtime"
        .apikey(apiKey)  // 阿里云 DashScope API Key
        .build();
```

`QwenTtsRealtimeParam` 仅包含连接级别的参数（模型名和认证信息），不包含合成参数（音色、语速等）。合成参数在后续 `updateSession()` 时下发。

### 阶段四：创建 WebSocket 事件回调

```java
// ===== 创建 WebSocket 事件回调 =====
// onOpen：连接建立成功，无额外操作
// onEvent：收到服务端推送 → 根据 type 分发处理
//          - audio：提取 PCM 音频分片（Base64 解码）追加到 audioContainer
//          - audio.done：合成完成 → countDown 解除主线程阻塞
//          - error：记录错误 → countDown 解除阻塞
// onClose：连接关闭 → countDown 解除阻塞（兜底，防止未收到 audio.done 时死锁）
QwenTtsRealtimeCallback callback = new QwenTtsRealtimeCallback() {
    @Override
    public void onOpen() {
        log.debug("TTS WebSocket connection established");
    }

    @Override
    public void onEvent(JsonObject message) {
        handleServerEvent(message, audioContainer, synthesisLatch, errorRef, responseIdRef);
    }

    @Override
    public void onClose(int code, String reason) {
        log.debug("TTS WebSocket closed - code: {}, reason: {}", code, reason);
        // 无论正常还是异常关闭，都要解除主线程阻塞
        synthesisLatch.countDown();
    }
};
```

**onClose 兜底设计原因分析**：这是防止死锁的关键设计。正常情况下合成完成后服务端会发送 `response.done` 事件，`handleServerEvent` 收到后 `countDown()` 解除阻塞。但有一种极端情况——WebSocket 连接意外断开（网络故障、服务端重启）时，可能还未收到 `response.done`。如果此时没有 `onClose` 中的 `countDown()`，主线程的 `synthesisLatch.await(30, TimeUnit.SECONDS)` 将一直等到 30 秒超时。有了 `onClose` 的兜底，连接断开时立即 `countDown()`，主线程迅速感知并返回空数组。

### 阶段五：建立 WebSocket 连接

```java
// ===== 阶段三：建立 WebSocket 连接 =====
// QwenTtsRealtime 是 DashScope TTS SDK 的 WebSocket 客户端
// connect() 方法是阻塞的——会等待 TCP 握手 + WebSocket 协议升级完成
QwenTtsRealtime qwenTtsRealtime = new QwenTtsRealtime(param, callback);

try {
    qwenTtsRealtime.connect();
```

`QwenTtsRealtime` 是 DashScope SDK 提供的 WebSocket 封装类。`connect()` 方法内部执行 TCP Socket 连接 + HTTP Upgrade 请求 + 101 Switching Protocols 响应接收，整个过程是阻塞的（同步 I/O）。连接成功后，SDK 内部启动读线程持续接收服务端推送的事件。

注意 `QwenTtsRealtime` 和 `QwenTtsRealtimeCallback` 的生命周期管理：调用方在 `try-finally` 中确保 `close()` 被执行。

### 阶段六：配置 TTS 合成参数

```java
// ===== 阶段四：配置 TTS 会话参数 =====
// voice：音色（如 "zhixiaobai" / "zhichu" 等 DashScope 预置音色）
// responseFormat：音频输出格式（pcm / wav）
// mode："commit" 模式——文本一次性提交，服务端完整合成后返回
//        （对比：实时模式会逐字符合成，适合流式场景）
// languageType：语言类型（Chinese / English）
// speechRate：语速倍率（1.0 正常，0.5 慢速，2.0 快速）
// volume：音量倍率（1.0 正常）
QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
        .voice(voice)                    // 音色
        .responseFormat(getAudioFormat()) // PCM 24kHz 16bit 单声道
        .mode(mode)                       // "commit"
        .languageType(languageType)       // "Chinese"
        .speechRate(speechRate)          // 1.0
        .volume(volume)                  // 1
        .build();

// updateSession 将配置下发到 DashScope 服务端
qwenTtsRealtime.updateSession(config);
```

各参数含义：

| 参数             | 含义   | 当前值                       | 说明                                     |
| -------------- | ---- | ------------------------- | -------------------------------------- |
| voice          | 音色   | zhixiaobai                | DashScope 预置音色，不同音色对应不同的说话风格和音质        |
| responseFormat | 输出格式 | PCM\_24000HZ\_MONO\_16BIT | 24kHz 采样率、16bit 位深、单声道，与前端播放器参数匹配      |
| mode           | 合成模式 | commit                    | 一次性提交文本、完整合成后返回。对比实时模式（streaming）逐字符合成 |
| languageType   | 语言   | Chinese                   | 影响发音、语调、停顿的准确性                         |
| speechRate     | 语速   | 1.0                       | 倍率，1.0 为正常语速，0.5 慢速适合听力有障碍的场景          |
| volume         | 音量   | 1                         | 倍率，1.0 为正常音量                           |

**commit 模式选择原因**：语音面试中 AI 回复文本在 LLM 流式生成时逐步获得，但 `synthesize()` 调用时机是"一个完整句子生成完毕"（`onSentenceComplete` 回调），此时文本已确定。使用 commit 模式一次性提交整个句子让服务端完整合成，得到的音频更自然、衔接更流畅。实时模式适用于文本在生成过程中就需要播放的场景（如 iOS Siri 逐字朗读）。

### 阶段七：发送文本并提交合成

```java
// ===== 阶段五：发送文本并提交合成 =====
// appendText(text)：将文本发送到服务端
// commit()：告知服务端文本已全部发送，开始合成
// 服务端合成完成后逐个音频分片推送，每个分片触发 onEvent("audio")
qwenTtsRealtime.appendText(text);
qwenTtsRealtime.commit();
```

`appendText()` 和 `commit()` 是两步操作。`appendText()` 将文本发送到 DashScope 服务端但不会立即合成，`commit()` 是明确指令："我发完了，开始合成吧"。底层使用 WebSocket 文本帧发送 JSON 格式的消息，SDK 内部处理序列化和帧分片。

`commit()` 调用后，DashScope 服务端开始合成，合成为音频后通过一系列 `response.audio.delta` 事件分批推送音频分片（每个分片约 20-40KB），最后发送 `response.done` 事件表示完成。

### 阶段八：同步等待合成完成

```java
// ===== 阶段六：同步等待合成完成 =====
// await 会阻塞当前线程（调用方为虚拟线程，阻塞不消耗 OS 线程）
// 解除条件：onEvent 收到 audio.done / error / onClose 回调中的 countDown()
// 30 秒超时——正常中文 TTS 合成 200 字约 5-10 秒，30 秒足够覆盖长文本
boolean completed = synthesisLatch.await(30, TimeUnit.SECONDS);

if (!completed) {
    log.error("TTS synthesis timeout after 30 seconds");
    return new byte[0];
}

// 检查异步回调中是否发生异常
Throwable error = errorRef.get();
if (error != null) {
    log.error("TTS synthesis failed", error);
    return new byte[0];
}
```

**虚拟线程阻塞不消耗 OS 线程**：`synthesize()` 在虚拟线程池 `voicePipelineExecutor` 中执行，`CountDownLatch.await()` 在虚拟线程中阻塞时，JVM 将对应的平台线程（carrier thread）释放给其他虚拟线程使用。阻塞操作不会造成线程资源浪费——这是 Java 21 虚拟线程的核心优势。

**30 秒超时的合理性**：正常 200 字中文文本合成耗时约 5-10 秒。30 秒覆盖了网络波动、DashScope 服务负载波动、以及较长文本（300-500 字）的合成需求。

### 阶段九：合并返回音频数据

```java
// ===== 阶段七：返回合并后的完整音频数据 =====
// ByteArrayContainer 中已累积了所有 WebSocket 推送的音频分片
byte[] audioData = audioContainer.toByteArray();
log.info("[TTS] Synthesis completed successfully - {} bytes of audio data, responseId: {}",
         audioData.length, responseIdRef.get());

return audioData;
```

`audioContainer.toByteArray()` 将收集的所有音频分片（每个 `response.audio.delta` 事件中的 Base64 解码后的字节数组）合并为完整 PCM 数据的字节数组。24kHz 16bit 单声道 PCM 的音频大小计算公式：`采样率 × 位深/8 × 声道数 × 秒数 = 24000 × 2 × 1 × 秒数`，所以 1 秒音频约 48KB，10 秒约 480KB。

### 阶段十：WebSocket 连接清理

```java
} finally {
    // 无论成功、超时还是异常，确保 WebSocket 连接被关闭
    try {
        qwenTtsRealtime.close();
    } catch (Exception e) {
        log.error("Error closing TTS connection", e);
    }
}
```

**try-finally 的必要性**：WebSocket 连接是有限资源，DashScope 侧有并发连接数限制。如果不关闭连接，资源泄漏会在高并发场景下导致连接池耗尽。`finally` 块保证即使超时返回或异常抛出，连接也被正确关闭。`close()` 内部的 try-catch 防止关闭异常覆盖原始异常（不掩盖业务错误信息）。

### 异常处理

```java
} catch (InterruptedException e) {
    // await 被外部中断（如面试会话被强制结束、虚拟线程被 interrupt）
    // 必须恢复中断状态（遵循 Java 中断最佳实践）
    log.error("TTS synthesis interrupted", e);
    Thread.currentThread().interrupt();
    return new byte[0];
} catch (Exception e) {
    // 其他未预期异常（网络错误、SDK 异常等）
    log.error("Failed to synthesize text", e);
    return new byte[0];
}
```

**InterruptedException 的特殊处理**：当用户强制结束面试时，Handler 的 `afterConnectionClosed()` 会调用 `processingThread.interrupt()` 中断虚拟线程。`CountDownLatch.await()` 收到中断信号后抛出 `InterruptedException`。此时必须调用 `Thread.currentThread().interrupt()` 恢复中断状态——遵循 Java 规范要求，不吞掉中断标记，让上层调用者（虚拟线程池）感知到中断。

**为什么返回空数组而不是抛异常**：`synthesize()` 被 `CompletableFuture.supplyAsync` 调用，返回空数组可以让 TTS 收集逻辑优雅地跳过这一句（不推送音频），而不是让整个 `CompletableFuture` 失败。面试官的口头回复如果丢失一句音频仍可继续面试，用户体验影响可控。

***

## 三、handleServerEvent 事件处理

`synthesize()` 中所有服务端事件的解析由 `handleServerEvent()` 方法处理。该方法在 WebSocket 读线程中回调，按事件类型分发处理。

```java
private void handleServerEvent(JsonObject message, ByteArrayContainer audioContainer,
                                CountDownLatch synthesisLatch, AtomicReference<Throwable> errorRef,
                                AtomicReference<String> responseIdRef) {
    try {
        String eventType = message.get("type").getAsString();

        switch (eventType) {
            case "session.created":
                // 连接后 DashScope 自动创建 TTS 会话（包含 session ID），用于日志追踪
                String sessionId = message.has("session") && message.get("session").isJsonObject()
                        ? message.get("session").getAsJsonObject().get("id").getAsString()
                        : "unknown";
                log.debug("TTS session created: {}", sessionId);
                break;

            case "session.updated":
                // updateSession() 的确认事件，仅记录日志
                log.debug("TTS session configuration updated");
                break;

            case "response.audio.delta":
                // 服务端返回的单个音频分片，Base64 编码
                // 解码后追加到 ByteArrayContainer 中累积
                if (message.has("delta")) {
                    String audioBase64 = message.get("delta").getAsString();
                    if (audioBase64 != null && !audioBase64.isEmpty()) {
                        byte[] audioChunk = Base64.getDecoder().decode(audioBase64);
                        audioContainer.append(audioChunk);
                        log.trace("Received audio chunk - {} bytes", audioChunk.length);
                    }
                }
                break;

            case "response.done":
                // 所有音频分片发送完毕，这是 Qwen TTS API 的最终事件
                // countDown() 解除主线程的 await() 阻塞
                synthesisLatch.countDown();
                break;

            case "error":
                // DashScope 返回的错误，解析错误类型/代码/消息
                // 设置 errorRef 供主线程检查，同时 countDown() 解除阻塞
                String fullErrorMessage = String.format("TTS Error [%s/%s]: %s",
                    errorType, errorCode, errorMessage);
                errorRef.set(new IllegalStateException(fullErrorMessage));
                synthesisLatch.countDown();
                break;

            default:
                log.trace("Unhandled TTS event type: {}", eventType);
        }

    } catch (Exception e) {
        // 事件处理异常本身也会设置 errorRef 并解除阻塞
        errorRef.set(e);
        synthesisLatch.countDown();
    }
}
```

### 事件处理时序图

```
时间 ────────────────────────────────────────────────────────►

客户端                          DashScope 服务端
  │                                    │
  │  connect() ──────────────────────►  │
  │                                    │  session.created
  │  ◄───────────────────────────────  │
  │                                    │
  │  updateSession(config) ──────────►  │
  │                                    │  session.updated
  │  ◄───────────────────────────────  │
  │                                    │
  │  appendText(text) ───────────────►  │
  │  commit() ───────────────────────►  │
  │                                    │  开始合成...
  │  ◄─── response.audio.delta (分片1)  │
  │  ◄─── response.audio.delta (分片2)  │
  │  ◄─── response.audio.delta (分片3)  │
  │  ◄─── response.audio.delta (分片N)  │
  │                                    │  合成完成
  │  ◄─── response.done                 │
  │                                    │
  │  countDown() → 主线程解除阻塞      │
  │                                    │
  │  close() ──────────────────────────►  │
```

### 事件语义说明

| 事件                     | 触发时机                 | 作用                                |
| ---------------------- | -------------------- | --------------------------------- |
| `session.created`      | connect() 成功后服务端自动创建 | 返回 sessionId 用于调试追踪               |
| `session.updated`      | updateSession() 后    | 确认配置已生效                           |
| `response.audio.delta` | 合成过程中陆续推送            | 每个 delta 是一小段 Base64 编码的 PCM 音频分片 |
| `response.done`        | 所有音频分片推送完毕           | 标志着合成完成，触发 countDown()            |
| `error`                | 合成过程发生错误             | 携带 type/code/message 描述错误原因       |

***

## 四、ByteArrayContainer 音频分片收集器

```java
private static class ByteArrayContainer {
    private final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

    public synchronized void append(byte[] chunk) {
        baos.write(chunk, 0, chunk.length);
    }

    public synchronized byte[] toByteArray() {
        return baos.toByteArray();
    }
}
```

**设计原因分析**：

- **基于** **`ByteArrayOutputStream`**：`ByteArrayOutputStream` 内部自动扩容（容量不足时扩容为当前大小的两倍），追加性能为均摊 O(1)。如果手动用 `System.arraycopy` 扩大数组，每次追加都复制全量数据将导致 O(n²) 的复杂度和严重的 GC 压力。
- **`synchronized`** **方法**：`handleServerEvent` 中的 `append()` 在 WebSocket 读线程中回调，而 `toByteArray()` 在主线程中调用。`synchronized` 保证两个操作的互斥性和内存可见性。
- **`synchronized`** **修饰方法的性能考量**：`append()` 和 `toByteArray()` 方法体极短（分别只有一行），锁持有时间极短（微秒级），不会成为性能瓶颈。

***

## 五、TTS 与 LLM 的衔接——句子级并发

TTS 是由 `triggerLlmResponse()` 中的流式 LLM 管线触发的，具体衔接方式如下：

```java
// triggerLlmResponse() 中（简化）
Semaphore ttsSemaphore = new Semaphore(maxConcurrentTtsPerSession);
List<CompletableFuture<byte[]>> ttsFutures = new ArrayList<>();

// LLM 流式生成，每检测到一个完整句子立即启动 TTS 合成
aiReply = llmService.chatStreamSentences(
    userText,
    onToken,    // 实时文本推送
    sentence -> {
        // onSentenceComplete 回调中启动 TTS
        ttsSemaphore.acquire();  // 控制并发数
        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(
            () -> {
                try {
                    return ttsService.synthesize(sentence);  // ← 调用 QwenTtsService
                } finally {
                    ttsSemaphore.release();
                }
            },
            voicePipelineExecutor  // 虚拟线程池
        );
        ttsFutures.add(future);
    },
    session, conversationHistory
);

// LLM 生成完成，等待所有 TTS 完成
CompletableFuture.allOf(ttsFutures.toArray(new CompletableFuture[0])).join();
```

**句子级并发 TTS 的核心价值**：LLM 生成一个完整回复（如三句话的面试官点评）时，第一句话生成完毕即开始 TTS 合成第一句话，同时 LLM 继续生成第二句话。LLM 生成和三句话的 TTS 合成时间重叠，首音频延迟 = max(首个句子生成时间, 首个句子 TTS 时间)，而非"LLM 全部生成 + 全部 TTS 合成"。

用时间线对比就很清楚了。假设 LLM 要回复三句话，每句生成 1 秒，每句 TTS 合成 2 秒：

## 串行方案（先全部生成，再全部合成）

```
秒  0    1    2    3    4    5    6    7    8    9
    ├──────────── LLM 生成三句 ────────────┤├────── TTS 合成三句 ───────┤
    │ 句1生成  │ 句2生成  │ 句3生成  ││ 句1TTS │ 句2TTS │ 句3TTS │
    └───────────────────────────────────┘└──────────────────────────┘
                                          ↑ 第 3 秒才开始听到声音
                                          首音频延迟 = 3 + 6 = 9 秒
```

## 句子级并发（本系统）

```
秒  0    1    2    3    4    5    6
    ├─ LLM + TTS 并行 ────────────────────────┤
    │ 句1生成 │ 句2生成 │ 句3生成 │
    │   句1 TTS ──────┤
    │         │  句2 TTS ──────┤
    │         │         │  句3 TTS ──────┤
    └──────────────────────────────────────┘
      ↑ 第 1 秒就开始 TTS，首音频在 max(1, 2) ≈ 2 秒后播放
      首音频延迟 = max(1, 2) = 2 秒
```

**关键区别**：串行方案中 TTS 必须等 LLM 全部三句话生成完才开始，所以用户 9 秒后才听到声音。句子级并发方案中 LLM 刚生成完第一句话就立刻启动第一句话的 TTS，LLM 同时继续生成第二句话——两道工序在时间上重叠。用户 2 秒后就能听到第一句话，而不需要等全部生成完。

简单说就是：**LLM 写回答和 TTS 念回答同时进行，不用等写完再念**。

***

## 六、音频格式与 PCM 说明

`getAudioFormat()` 固定返回 `PCM_24000HZ_MONO_16BIT`：

```java
private QwenTtsRealtimeAudioFormat getAudioFormat() {
    return QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;
}
```

| 参数    | 值         | 含义                                     |
| ----- | --------- | -------------------------------------- |
| 采样率   | 24000 Hz  | 每秒采样 24000 次，覆盖人耳听觉范围（20Hz-20kHz）的中高频段 |
| 位深    | 16 bit    | 每个采样点 2 字节，动态范围 96dB                   |
| 声道    | 单声道       | 语音面试不需要立体声，单声道节省 50% 带宽和存储             |
| 每分钟大小 | 约 2.88 MB | 24000 × 2 × 60 = 2,880,000 字节          |

TTS 返回的 PCM 原始数据会被转换为 WAV 格式后推送——WAV 在 PCM 基础上添加了 44 字节的文件头（采样率、位深、声道数、数据大小等元信息），前端 `AudioContext` 解码时需要这些信息才能正确播放。

***

## 七、与 ASR 服务的镜像对比

QwenTtsService 和 QwenAsrService 在架构上呈镜像对称关系，对比清晰体现了两者的设计差异：

| 维度   | QwenAsrService（ASR）       | QwenTtsService（TTS）   |
| ---- | ------------------------- | --------------------- |
| 方向   | 语音 → 文本                   | 文本 → 语音               |
| 连接模式 | 持久连接（整个面试期间保持）            | 临时连接（每次合成建连、用完关闭）     |
| 数据流向 | 客户端持续推送音频分片               | 服务端持续推送音频分片           |
| 回调处理 | onFinal、onPartial、onError | onEvent 按 type 分发     |
| 并发策略 | 单会话单连接                    | 单会话多句并发（Semaphore 控制） |
| 线程模型 | 守护线程建立连接                  | 虚拟线程阻塞等待              |
| 超时策略 | 无超时（持久连接）                 | 30 秒合成超时              |

***

## 八、完整流程总结

QwenTtsService 的语音合成从 `synthesize(text)` 被 `triggerLlmResponse` 中 LLM 流式生成的 `onSentenceComplete` 回调通过 `CompletableFuture.supplyAsync` 在虚拟线程池中触发。方法内部首先检查文本非空避免无效连接，然后用构造时从 `VoiceInterviewProperties` 读取并缓存到成员变量的 model 和 apiKey 构建 `QwenTtsRealtimeParam` 连接参数，创建 `QwenTtsRealtimeCallback` 回调（onOpen 记录日志、onEvent 委托 `handleServerEvent` 处理所有服务端推送、onClose 执行 `countDown` 兜底防止主线程死锁），通过 `QwenTtsRealtime.connect()` 同步建立到 DashScope 的独立 WebSocket 连接。连接成功后 `updateSession()` 下发合成配置——24kHz PCM 单声道输出格式、预置音色、1.0 倍语速和音量、"commit" 模式一次性提交文本。配置生效后 `appendText(text)` 发送文本并 `commit()` 告知服务端开始合成，服务端合成完成后逐个 `response.audio.delta` 事件推送 Base64 编码的 PCM 音频分片，每个分片在 `handleServerEvent` 中解码后通过 `ByteArrayContainer.append()` 累积，最后一个 `response.done` 事件触发 `countDown()` 解除 `synthesisLatch.await(30s)` 的阻塞。主线程苏醒后通过 `audioContainer.toByteArray()` 获取合并后的完整 PCM 音频数据，在 `finally` 块中 `qwenTtsRealtime.close()` 关闭 WebSocket 连接释放资源，返回音频字节数组给上层的 TTS 收集逻辑。所有异常（包括超时、中断、网络错误）均返回空数组不抛异常，保证调用方 `CompletableFuture` 优雅降级而不影响面试流程。
