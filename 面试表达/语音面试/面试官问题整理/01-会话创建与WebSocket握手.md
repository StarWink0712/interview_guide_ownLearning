# 会话创建与 WebSocket 握手

## 1. 创建语音面试会话时，前端需要传入哪些参数？

**回答：** 前端传入 `CreateSessionRequest`，其中包括角色类型或模板 ID，例如 `java-backend`；面试难度，例如初级、中级或高级；简历 ID；自定义 JD 文本；开场介绍、技术面、项目面和 HR 面四个阶段的开关；以及 `llmProvider`。开场介绍默认关闭，技术面、项目面和 HR 面默认开启。`llmProvider` 用于指定模型，没有传时后端使用默认 Provider。

<br />

角色类型，模版id即skillid面试方向。然后还有需不需要开场介绍，以及llm指定设置。

## 2. 创建会话时为什么要传入简历、JD、技能和难度？

**回答：** 这些信息共同决定语音面试的上下文。技能或模板 ID 表示面试方向，难度决定题目层次，简历提供候选人的经历，自定义 JD 提供目标岗位要求。后续 LLM 生成问题时会结合这些信息，使提问更有针对性，而不是使用固定题库。

这不是废话吗，创建会话的时候，简历内容，jd岗位，还有技能面试方向和难道，llm需要依靠足够多的信息才能更好的出题，把简历内容jd，这些都拼到提示词里面即可。

## 3. 会话的初始阶段是怎么确定的？

**回答：** `currentPhase` 不是由前端直接指定，而是后端按照 INTRO、TECH、PROJECT、HR 的顺序查找第一个被开启的阶段，将它作为面试起点。如果四个阶段全部关闭，则兜底把阶段设为 `COMPLETED`。

## 4. 创建会话后，数据库和 Redis 分别保存什么？

**回答：** Service 使用 Builder 构建 `VoiceInterviewSessionEntity`，填入用户 ID、角色类型、模板 ID、难度、阶段开关和计划时长等字段，然后保存到 PostgreSQL。JPA 的 `@PrePersist` 会自动设置创建时间、更新时间和开始时间。保存后再把会话实体缓存到 Redis，Key 为 `voice:interview:session:{id}`，TTL 为 1 小时，供实时通话期间快速读取会话信息。

数据库和redis都保存完整的会话，Redis中缓存是为了让前端更即时的查询

## 5. 创建会话接口为什么要返回 webSocketUrl？

**回答：** 创建完成后，后端通过 `buildSessionResponse` 返回 `SessionResponseDTO`，其中除了会话 ID、角色类型、当前阶段、状态、开始时间和计划时长，还包含 `/ws/voice-interview/{sessionId}` 对应的 `webSocketUrl`。前端拿到 URL 后建立 WebSocket 长连接，后续音频上传、ASR 字幕、AI 文本回复和 TTS 音频都通过这条连接传输，不再走普通 REST 请求。

## 6. W*ebSocket 端点是怎么注册的？*

**回答：** Spring Boot 启动时，`WebSocketConfig.registerWebSocketHandlers()` 把 `VoiceInterviewWebSocketHandler` 绑定到 `/ws/voice-interview/{sessionId}`。配置中还添加了一个用于传递会话信息的握手拦截器，但当前没有实际使用，可以删除。前端连接返回的 URL 后，Spring 根据路径把请求交给这个 Handler。

## *7. 浏览器建立 WebSocket 连接时，底层握手过程是什么*？

**回答：** 浏览器先发送一个 HTTP/1.1 GET 请求，并携带 `Upgrade: websocket` 请求把协议升级为 WebSocket。请求里还有浏览器随机生成的 Base64 字符串 `Sec-WebSocket-Key`，服务端按 RFC 6455 的固定算法计算 `Sec-WebSocket-Accept`；同时会携带 `Origin`，供服务端判断发起连接的网页来源是否允许。服务端校验成功后返回 `101 Switching Protocols`，之后同一条 TCP/TLS 连接从普通 HTTP 请求响应模式切换为 WebSocket 双向帧通信。

## 8. afterConnectionEstablished 在什么时候执行，主要做什么？

**回答：** `afterConnectionEstablished()` 在 HTTP Upgrade 握手成功后执行。它先从 URL 提取 `sessionId`，配置消息大小和发送限制，封装并缓存 WebSocket 会话及运行状态，随后启动服务端到 DashScope ASR 的连接、向前端发送欢迎消息，并异步触发首次开场问题。

## 9. 为什么单条 WebSocket 文本消息限制为 256KB？

**回答：** 前端录音采用边录边发，一秒音频大约 36KB，正常情况下每两秒发送一个音频片段。将单条消息上限设为 256KB 可以覆盖正常音频片段，同时阻止前端一次发送过大的消息。`handleTextMessage` 还会记录消息大小，超过 200KB 时打印告警日志。

## 10. 为什么要使用 ConcurrentWebSocketSessionDecorator？

**回答：** ASR 回调、LLM 流式输出、TTS 结果和控制通知都可能从不同线程向同一个前端连接写消息，因此原始 `WebSocketSession` 被 `ConcurrentWebSocketSessionDecorator` 包装，确保发送操作串行化。装饰器将单条消息发送时限设为 10 秒，超时就放弃，避免一次发送堵死整个通道；发送缓冲区设为 512KB，用于承接并发产生的待发送数据。

## 11. Handler 中为什么维护三个 ConcurrentHashMap？

**回答：** 第一个 Map 以会话 ID 为 Key，保存并发安全的 WebSocket 会话；第二个 Map 保存每个会话的 `SessionState`，包括 ASR 文本缓冲、是否正在处理和运行线程等状态；第三个 `lastActivityTime` 保存最后一次收到 audio 或 control 消息的时间，用于定时检查会话是否长时间无操作。它们是实时连接的内存运行状态，不是 PostgreSQL 中的持久化会话数据。
