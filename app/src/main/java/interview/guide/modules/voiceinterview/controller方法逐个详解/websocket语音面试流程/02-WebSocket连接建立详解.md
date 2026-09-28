# WebSocket 连接建立详解

## 概述

前端拿到 `createSession` 返回的 `webSocketUrl`（如 `ws://localhost:8080/ws/voice-interview/42`）后，通过浏览器原生 `WebSocket` API 发起连接。这个连接建立过程完全不经过 Spring MVC 的 DispatcherServlet，不走任何 Controller——HTTP Upgrade 请求由 Spring WebSocket 框架直接拦截，根据 `WebSocketConfig` 的路由规则匹配到 `VoiceInterviewWebSocketHandler`，完成协议升级后调用 `afterConnectionEstablished()`。从这一刻起，所有实时语音通信通过 WebSocket 协议在 Handler 层完成，Controller 不再参与。    

## 一、Spring WebSocket 框架路由

### WebSocketConfig 注册

```java
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceInterviewWebSocketHandler voiceInterviewWebSocketHandler;
    private final CorsProperties corsProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 将 Handler 注册到 /ws/voice-interview/{sessionId} 路径
        // {sessionId} 是路径变量，与 createSession 返回 URL 中的 ID 对应
        registry.addHandler(voiceInterviewWebSocketHandler, "/ws/voice-interview/{sessionId}")
                // HttpSessionHandshakeInterceptor：握手阶段读取 HTTP Session 属性并传递到 WS Session
                .addInterceptors(new HttpSessionHandshakeInterceptor())
                // 跨域支持：从 CorsProperties 读取允许的前端来源列表
                .setAllowedOrigins(corsProperties.getAllowedOrigins().split(","));
    }
}
```

`/ws/voice-interview/{sessionId}` 中的 `{sessionId}` 是 Spring WebSocket 框架的路径变量语法，与 `@RequestMapping` 中的 `@PathVariable` 类似。`addInterceptors` 注册了 `HttpSessionHandshakeInterceptor`——在 WebSocket 握手阶段可以将 HTTP Session 中的属性复制到 WebSocket Session 的属性中，用于传递用户认证信息等上下文数据。`setAllowedOrigins` 从配置文件动态读取跨域白名单（前端可能运行在 3000/5173 等不同端口），逗号分隔的字符串被拆分为多个允许的来源。

***

## 二、afterConnectionEstablished() 核心入口

这是 WebSocket 连接建立后最核心的方法，在一个同步调用中完成了全部初始化。它可以分为六个步骤：

```java
@Override
public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    // 从 URI /ws/voice-interview/42 中提取 sessionId "42"
    String sessionId = extractSessionId(session);

    // ====== 第一步：设置消息大小限制 ======
    // 1秒 PCM 16kHz 16bit ≈ 32KB 原始 ≈ 42KB Base64编码后
    // 256KB ≈ 可容纳约 6 秒音频，提供充足缓冲
    session.setTextMessageSizeLimit(256 * 1024);
    session.setBinaryMessageSizeLimit(256 * 1024);

    // ====== 第二步：创建并发安全装饰器 ======
    // 原生 WebSocketSession.sendMessage() 不支持并发调用
    // 但 ASR 字幕、LLM 流式文本、TTS 音频可能从不同线程同时发送消息
    // ConcurrentWebSocketSessionDecorator 内部用锁保证并发安全
    // 参数：10秒发送超时、512KB 发送缓冲区
    WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
        session, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT_BYTES
    );

    // ====== 第三步：存入三个并发 Map ======
    sessions.put(sessionId, safeSession);              // WebSocket 会话映射
    sessionStates.put(sessionId, new SessionState());  // 运行时状态
    lastActivityTime.put(sessionId, System.currentTimeMillis()); // 超时检测时间戳

    log.info("WebSocket connection established for session: {}", sessionId);

    try {
        // ====== 第四步：启动 ASR 语音识别 ======
        startDashScopeStt(sessionId, safeSession);

        // ====== 第五步：发送欢迎消息 ======
        sendMessage(safeSession, createWelcomeMessage());

        // ====== 第六步：触发开场问题（仅首次连接、无历史消息时） ======
        triggerOpeningQuestionIfNeeded(sessionId, safeSession);
    } catch (Exception e) {
        log.error("Error establishing WebSocket connection for session {}", sessionId, e);
        sendError(safeSession, "初始化语音识别失败: " + e.getMessage());
    }
}
```

### 第一步：消息大小限制

256KB 这个数值不是随意设的。1 秒 PCM 16kHz 16bit 原始音频 ≈ 32000 字节（16000 × 2），Base64 编码后 ≈ 42700 字节。256KB 可容纳约 6 秒音频数据，提供了充足的缓冲空间。同时 256KB 也是一个安全上限——防止恶意或异常的超大消息导致内存溢出。

### 第二步：并发安全装饰器

`WebSocketSession.sendMessage()` 的 Javadoc 明确说明不支持并发调用。但在语音面试场景中，ASR 实时字幕、LLM 流式文本 token、TTS 合成音频都可能从不同线程（虚拟线程池、ASR 回调线程、定时任务线程）同时向同一个 WebSocket 会话发送消息。`ConcurrentWebSocketSessionDecorator` 内部使用 `Lock` 机制将并发写操作串行化，同时设置了 10 秒发送超时（防止一条慢消息阻塞所有后续消息）和 512KB 发送缓冲区限制。

### 第三步：三个 ConcurrentHashMap

`sessions` Map 以 sessionId 为 Key 存储包装后的 `WebSocketSession`，供后续所有消息发送操作使用。`sessionStates` Map 存储每个会话的 `SessionState` 实例——一个包含 AI 说话标记、合并缓冲区、LLM 处理锁等运行时字段的内部类。`lastActivityTime` Map 记录最后活动时间戳，供 `checkPauseTimeout()` 定时任务使用。

```java
private static class SessionState {
    // 累积的用户文本——当前轮次送入 LLM 的完整内容
    private final AtomicReference<String> accumulatedText = new AtomicReference<>("");
    // LLM 处理中标志——CAS 锁，防止并发触发多个 LLM 管线
    private final AtomicBoolean processing = new AtomicBoolean(false);
    // AI 正在播放 TTS 音频——此期间丢弃麦克风输入防止回声
    private final AtomicBoolean aiSpeaking = new AtomicBoolean(false);
    // AI 音频播放结束后额外等待时间（ms）——防止扬声器尾音被拾取
    private final AtomicLong aiSpeakEndAt = new AtomicLong(0);
    // 多段 ASR 定稿的累积拼接缓冲区——用户提交时一次性送入 LLM
    private final AtomicReference<String> mergeBuffer = new AtomicReference<>("");
    // mergeBuffer 首次有数据的时间戳
    private final AtomicLong mergeStartedAt = new AtomicLong(0);
    // 最近一次 ASR 活动时间——用于暂停超时检测
    private final AtomicLong lastSttActivityAt = new AtomicLong(System.currentTimeMillis());
    // 当前正在执行 LLM+TTS 的虚拟线程——WebSocket 断连时用于 interrupt
    private volatile Thread processingThread = null;
}
```

所有字段都使用 `AtomicXxx` 类——因为 `SessionState` 的字段可能被不同线程并发读写（WebSocket IO 线程写入合并缓冲区、虚拟线程读取累积文本、定时任务线程读取活动时间）。

### 第四步到第六步

ASR 启动、欢迎消息、开场问题分别在 03、04 号文档中深入分析。这里只说明它们的调用顺序——ASR 启动在最前面，因为其内部是异步建立连接，不阻塞后续步骤。欢迎消息同步发送，立即让前端知道连接成功。开场问题在虚拟线程池中异步执行，不阻塞 `afterConnectionEstablished` 返回。

***

## 三、辅助方法

### 从 URI 提取 sessionId

```java
private String extractSessionId(WebSocketSession session) {
    String path = session.getUri().getPath();  // "/ws/voice-interview/42"
    return path.substring(path.lastIndexOf('/') + 1);  // "42"
}
```

### 欢迎消息

```java
private String createWelcomeMessage() {
    return toJson(Map.of(
        "type", "control",
        "action", "welcome",
        "message", "连接成功，准备开始语音面试",
        "timestamp", System.currentTimeMillis()
    ));
}
```

前端收到的第一条 JSON 消息：

```json
{"type":"control","action":"welcome","message":"连接成功，准备开始语音面试","timestamp":1718700000000}
```

***

## 四、连接建立后的系统状态

此时系统中有两条 WebSocket 连接正在建立或已建立：前端-后端的连接已就绪（握手完成，`afterConnectionEstablished` 已返回），后端-DashScope ASR 的连接正在异步建立中（守护线程执行 `conversation.connect()` 阻塞等待握手）。前端界面显示了欢迎消息，AI 开场问题正在虚拟线程中生成。用户可以开始说话了。

***

## 五、总结

WebSocket 连接建立是语音面试流程的分水岭。在此之前所有交互通过 HTTP 协议经过 DispatcherServlet → Controller 层完成，在此之后所有实时语音通信通过 WebSocket 协议在 Handler 层完成，Controller 退出舞台。`afterConnectionEstablished()` 是同步入口，内部通过六步有序编排完成初始化——消息限制、并发装饰器、Map 存储、ASR 启动、欢迎消息、开场问题。其中 ASR 连接和开场问题在守护线程和虚拟线程中异步执行，确保方法快速返回不阻塞 WebSocket 握手完成通知。`SessionState` 使用全 `AtomicXxx` 字段设计支持多线程并发安全访问，为后续的 ASR 回调、LLM 虚拟线程、定时检测等并发操作提供了可靠的数据基础。
