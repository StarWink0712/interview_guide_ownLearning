这个问题的关键在于：**后端并不主动创建 WebSocket 连接**。

## 完整流程

### 第一步：后端返回 WebSocket URL

[`VoiceInterviewService.createSession()`](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java#L137-L142) 最后调用 `buildSessionResponse(saved)` 构建 DTO，其中拼出了 WebSocket 地址：

```java
private SessionResponseDTO buildSessionResponse(VoiceInterviewSessionEntity session) {
    return SessionResponseDTO.builder()
            .sessionId(session.getId())
            // ...
            .webSocketUrl(String.format("ws://localhost:8080/ws/voice-interview/%d", session.getId()))
            .build();
}
```

返回的 JSON 示例：

```json
{
    "sessionId": 42,
    "webSocketUrl": "ws://localhost:8080/ws/voice-interview/42"
}
```

### 第二步：前端拿到 URL 后主动发起连接

前端收到 `SessionResponseDTO` 后，用浏览器原生 `WebSocket` API 连接到这个 URL：

```typescript
const session = await voiceInterviewApi.createSession({ ... });
const ws = new WebSocket(session.webSocketUrl);  // 浏览器发起 WebSocket 握手
```

此时浏览器会向 `ws://localhost:8080/ws/voice-interview/42` 发送 HTTP Upgrade 请求。

### 第三步：Spring WebSocket 框架路由到 Handler

[`WebSocketConfig`](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/voiceinterview/config/WebSocketConfig.java#L60-L62) 已将 `VoiceInterviewWebSocketHandler` 注册到 `/ws/voice-interview/{sessionId}` 路径：

```java
registry.addHandler(voiceInterviewWebSocketHandler, "/ws/voice-interview/{sessionId}")
```

所以握手请求会自动路由到 `VoiceInterviewWebSocketHandler`。

### 第四步：Handler 的 `afterConnectionEstablished` 被触发

[`VoiceInterviewWebSocketHandler.afterConnectionEstablished()`](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java#L189-L221) 在握手成功后执行，依次：

1. 从 URL 提取 sessionId
2. 将 WebSocket 会话存入 `ConcurrentHashMap`
3. **启动 ASR 语音识别**（`startDashScopeStt`）
4. **发送欢迎消息**
5. **触发开场问题**（首次连接时 AI 先说开场语）

```java
@Override
public void afterConnectionEstablished(WebSocketSession session) {
    String sessionId = extractSessionId(session);  // 从 /ws/voice-interview/42 提取 "42"
    sessions.put(sessionId, safeSession);
    startDashScopeStt(sessionId, safeSession);      // 启动实时语音识别
    sendMessage(safeSession, createWelcomeMessage()); // 发送欢迎消息
    triggerOpeningQuestionIfNeeded(sessionId, safeSession); // AI 开场提问
}
```

***

## 总结

```
后端 createSession()               前端                       Spring WebSocket
──────────────────    ──────────────────────    ─────────────────────────────
返回 SessionResponseDTO
含 webSocketUrl
      │
      │  HTTP 200 JSON 响应
      ▼
                   →   拿到 webSocketUrl
                       new WebSocket(url)
                            │
                            │   HTTP Upgrade /ws/voice-interview/42
                            ▼
                                              WebSocketConfig 路由到 Handler
                                              afterConnectionEstablished() 触发
                                                 ├── 启动 ASR
                                                 ├── 发送欢迎消息
                                                 └── AI 开场问题
```

**后端只负责提供连接地址，前端主动发起 WebSocket 握手，Spring 框架路由到 Handler，Handler 完成初始化。** 后端代码没有任何"主动创建连接"的调用 —— `createSession()` 的职责到此为止，它只负责准备好会话和连接地址。

<br />

你说得对。第二步 `new WebSocket(webSocketUrl)` 不是通过 Controller 发起的，它完全绕过了 Controller 层。

这是两种完全不同的通信协议：

## HTTP 请求（经过 Controller）

```
前端                           Spring Boot
─────                          ───────────
fetch("/api/voice-interview/sessions", { method: "POST" })
        ──── HTTP POST ────→  DispatcherServlet
                                → VoiceInterviewController.createSession()
                                → VoiceInterviewService.createSession()
                                ← JSON { webSocketUrl: "ws://..." }
        ←─── HTTP 200 ────
```

经过完整的 Spring MVC 链路：DispatcherServlet → Controller → Service。

## WebSocket 握手（不经过 Controller）

```
前端                           Spring Boot
─────                          ───────────
const ws = new WebSocket("ws://localhost:8080/ws/voice-interview/42")
        ──── HTTP Upgrade ──→  Spring WebSocket 框架
         (协议升级请求)           → WebSocketConfig 路由匹配
                                  → VoiceInterviewWebSocketHandler
                                    → afterConnectionEstablished()
        ←─── 101 Switching ──
```

这是**浏览器原生 WebSocket 握手**，发送的是 `Upgrade: websocket` 头的 HTTP 请求。Spring WebSocket 框架直接拦截这个请求，根据 `WebSocketConfig` 的路由规则（`/ws/voice-interview/{sessionId}`）分发给 `VoiceInterviewWebSocketHandler`，**全程不经过 DispatcherServlet，不走任何 Controller**。

两者的区别：

| <br />               | HTTP API 调用                | WebSocket 握手                     |
| :------------------- | :------------------------- | :------------------------------- |
| 触发方式                 | `fetch()` / `axios`        | `new WebSocket(url)`             |
| 协议                   | HTTP/1.1                   | HTTP Upgrade → WebSocket         |
| 经过 DispatcherServlet | 是                          | 否                                |
| 经过 Controller        | 是                          | 否                                |
| URL 前缀               | `/api/voice-interview/...` | `/ws/voice-interview/...`        |
| 后端入口                 | `VoiceInterviewController` | `VoiceInterviewWebSocketHandler` |

所以 `createSession` 的职责就只是：**准备好会话数据并存到数据库 → 把 WebSocket 连接地址通过 JSON 返回给前端**。至于什么时候连接、连不连接，完全由前端决定，后端 Controller 不参与。
