# 前端流式交互与 SSE

## 1. 面试官：前端如何统一处理普通 REST 响应，为什么流式接口不能完全复用？

我的回答：

普通 REST 在完整响应到达后解析 JSON，适合统一拦截；流式接口需要边接收边消费字节，不能等待整个 body。我的项目前端创建 Axios 实例，统一 baseURL、超时和 `Result` 解包：body code 为 200 返回 data，否则抛 message；上传单独延长到五分钟。RAG SSE 使用 fetch 直接读取 `response.body`，不经过 Axios 响应拦截器，文件下载也保留原始 Blob 响应。因此目前存在多套错误解析。生产上我会抽取统一的业务错误转换，同时保留 REST、Blob、SSE 各自合适的传输处理。

代码依据：

- `frontend/src/api/request.ts` — Axios 实例与拦截器
- `frontend/src/api/knowledgebase.ts` — SSE 与 Blob
- `frontend/src/api/ragChat.ts` — 流式聊天
- `app/src/main/java/interview/guide/common/result/Result.java` — 后端响应结构

## 2. 面试官：为什么 POST 流式问答使用 fetch，而不是 EventSource？

我的回答：

浏览器原生 EventSource 使用简单、自动重连，但标准接口只支持 GET，难以发送 JSON 请求体和自定义复杂请求。我的项目的知识库查询和 RAG Chat 都需要 POST question、knowledgeBaseIds 或 sessionId，因此用 fetch 发 JSON，再从 `ReadableStream` 手工解析 SSE。这样保留 POST 语义，也能使用 AbortController 扩展取消。代价是自动重连、Last-Event-ID、超时和事件解析都要自己实现。若请求参数可先通过 REST 创建任务，再用 GET 订阅，EventSource 会更省代码；当前方案更直接。

代码依据：

- `frontend/src/api/knowledgebase.ts` — `queryKnowledgeBaseStream()`
- `frontend/src/api/ragChat.ts` — `sendMessageStream()`
- `app/src/main/java/interview/guide/modules/knowledgebase/KnowledgeBaseController.java` — POST SSE
- `app/src/main/java/interview/guide/modules/knowledgebase/RagChatController.java` — 会话流式接口

## 3. 面试官：SSE 为什么不能按每次 reader.read() 直接当成一条消息？

我的回答：

网络 chunk 边界与 SSE 事件边界无关，一个 `read()` 可能只有半行，也可能包含多个事件；UTF-8 多字节字符也可能跨 chunk。我的项目用 `TextDecoder` 的 stream 模式把字节追加到 buffer，知识库接口按行识别 `data:`，RAG Chat 则按空行切 event block，再解析 `event:` 和多个 `data:` 行；剩余半块保留到下次读取，流结束时再处理尾部。这样能正确应对拆包和粘包。当前两处解析器实现不同，转义换行规则也不同，后续应统一成经过协议测试的 SSE parser。

代码依据：

- `frontend/src/api/knowledgebase.ts` — buffer 与 `TextDecoder`
- `frontend/src/api/ragChat.ts` — `handleEventBlock()`
- `app/src/main/java/interview/guide/modules/knowledgebase/RagChatController.java` — 事件格式

## 4. 面试官：RAG Chat 的 trace 事件为什么要和正文事件分开？

我的回答：

元数据和正文语义不同，混在一个字符串流中会污染用户回答，也不利于扩展。我的项目服务端开始流式回答前发送 `event: trace`，data 是包含 traceId 的 JSON；前端 parser 识别 eventName，调用 onTrace 保存关联 ID，不追加到回答正文，普通 message 才交给 onMessage。这样用户遇到错误时可以从管理端查到对应检索和 AI 调用明细。当前只有 trace 这一类控制事件，错误与完成主要依赖流结束。生产上可增加 usage、source、done、error 事件，并为每种事件定义 TypeScript 联合类型。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/RagChatController.java` — trace 事件
- `frontend/src/api/ragChat.ts` — trace 解析
- `frontend/src/pages/KnowledgeBaseQueryPage.tsx` — trace 状态
- `app/src/main/java/interview/guide/modules/aiobservability/service/RagRetrievalLogService.java` — trace 查询数据

## 5. 面试官：前端如何处理流式请求的取消、超时、重试和错误？

我的回答：

流式请求可能在 DNS、HTTP、读取中途或业务生成阶段失败，前端要区分已显示内容和最终状态。我的项目检查 `response.ok`、body reader 是否存在，并在 catch 中调用 onError；读取完成调用 onComplete，页面逐 chunk 累加回答。当前 API 没有传入 AbortSignal，也没有读取超时、自动重连或幂等 requestId，组件卸载后仍可能继续读流；中断时已显示的半段回答也缺统一状态。生产上我会用 AbortController 在用户停止或页面卸载时取消，设置 inactivity timeout，并只对生成前失败自动重试，避免重复消息。

代码依据：

- `frontend/src/api/knowledgebase.ts` — 流式错误处理
- `frontend/src/api/ragChat.ts` — `onComplete()`、`onError()`
- `frontend/src/pages/KnowledgeBaseQueryPage.tsx` — 页面流式状态
- `app/src/main/java/interview/guide/modules/knowledgebase/service/RagChatSessionService.java` — 服务端占位消息状态

## 6. 面试官：SSE 和 WebSocket 在项目中如何分工，为什么不统一一种协议？

我的回答：

SSE 是服务端到客户端的单向文本事件流，天然基于 HTTP，适合模型逐 token 回答；WebSocket 是双向长连接，适合持续上传音频并接收字幕与 TTS。我的项目知识库问答和 RAG Chat 使用 POST+SSE，用户只在请求开始时发送一次问题；语音面试使用 WebSocket，因为麦克风音频与控制消息会持续上行。统一成 WebSocket 会增加普通问答的连接状态和协议复杂度，统一成 SSE 又无法承载实时上行音频。生产上仍可共享 trace、错误事件和重连规范，但不应为了技术统一忽略交互方向。

代码依据：

- `frontend/src/api/knowledgebase.ts` — SSE 问答
- `frontend/src/api/ragChat.ts` — SSE 会话
- `frontend/src/api/voiceInterview.ts` — WebSocket 语音
- `app/src/main/java/interview/guide/modules/voiceinterview/config/WebSocketConfig.java` — WebSocket 服务端

## 7. 面试官：后端流式响应为什么要避免长事务，断流后如何保存消息？

我的回答：

SSE 可能持续几十秒，如果事务覆盖整个流，会长期占用数据库连接，且客户端断开时回滚语义复杂。我的项目的 `RagChatSessionService` 先在短事务保存 USER 消息和 `completed=false` 的 ASSISTANT 占位，随后事务外执行检索与模型 Flux；正常完成后回写答案与完成状态，异常则记录失败。前端因此可以显示生成中并在刷新后识别残缺消息。当前应用崩溃会留下未完成占位，也没有同会话并发轮次锁。生产上应定时标记超时消息、提供重试/继续生成，并用唯一顺序或版本控制并发。

代码依据：

- `app/src/main/java/interview/guide/modules/knowledgebase/service/RagChatSessionService.java` — `prepareStreamMessage()`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/RagChatSessionService.java` — 完成与失败回写
- `app/src/main/java/interview/guide/modules/knowledgebase/RagChatController.java` — Flux 流式编排
- `frontend/src/api/ragChat.ts` — 客户端接收

## 8. 面试官：React 路由懒加载和流式渲染如何影响前端性能？

我的回答：

路由懒加载降低首屏包体，流式渲染则要避免每个微小 token 都触发昂贵的整页重排。我的项目在 `App.tsx` 用 `lazy` 与 `Suspense` 按页面拆分简历、RAG、语音和管理模块，代码高亮组件也延迟加载；流式页面逐 chunk 更新回答。这样非当前页面不会进入首包。当前如果服务端 token 很碎，频繁 setState、Markdown 解析和代码高亮仍可能卡顿。生产上我会按 30～50ms 批量刷新 token，长会话列表使用虚拟化，生成期间延迟语法高亮，并用 React Profiler 验证，而不是盲目 memo。

代码依据：

- `frontend/src/App.tsx` — `lazy()` 与 `Suspense`
- `frontend/src/components/CodeBlock.tsx` — 懒加载高亮器
- `frontend/src/pages/KnowledgeBaseQueryPage.tsx` — 流式回答状态
- `frontend/src/components/InterviewChatPanel.tsx` — 消息渲染

## 9. 面试官：浏览器音频流的前端实现有哪些性能与兼容性问题？

我的回答：

我的项目用 16kHz AudioContext 和已废弃的 `ScriptProcessorNode` 在主线程处理样本，把每秒 PCM 逐字节转 Base64 再发 JSON；同时运行 Analyser 和基于 ONNX 的 VAD。功能直观，但主线程转换、字符串复制和 Base64 约三分之一膨胀会增加 CPU、GC 与带宽，ScriptProcessor 在不同浏览器也可能有延迟差异；VAD 资源还从 CDN 加载。生产上我会迁移 AudioWorklet，在工作线程生成二进制 Int16 帧，缩短且动态调整 chunk，VAD 资源自托管，并对 Safari、移动端和麦克风权限做兼容测试。

代码依据：

- `frontend/src/components/AudioRecorder.tsx` — `createScriptProcessor()`
- `frontend/src/components/AudioRecorder.tsx` — PCM 与 Base64 转换
- `frontend/src/api/voiceInterview.ts` — JSON 音频帧
- `frontend/index.html` — VAD 外部资源

## 10. 面试官：Nginx 代理流式接口和 WebSocket 时要注意什么？

我的回答：

反向代理可能缓冲 SSE，导致前端一次性收到全部内容；WebSocket 则需要 Upgrade/Connection 头和足够长的读超时。我的项目前端镜像使用 Nginx 代理 API，上传客户端超时注释也要求与 Nginx 对齐。流式生产配置应对 SSE 关闭 proxy buffering、禁用不必要缓存并延长 read timeout；WebSocket location 要转发升级头，设置连接空闲策略；上传还需 client_max_body_size。慢客户端会占用连接和代理缓冲，因此要限制并发与响应大小。当前 Compose 只是单实例，生产上还要 TLS、可信转发头和 WebSocket 粘性路由。

代码依据：

- `frontend/nginx.conf` — API 与 WebSocket 代理
- `frontend/src/api/request.ts` — 上传超时
- `docker-compose.yml` — Nginx 前端部署
- `app/src/main/java/interview/guide/modules/voiceinterview/config/WebSocketConfig.java` — WebSocket 路径

# 本章总结

前端根据交互方向选择 Axios REST、fetch SSE 和 WebSocket，并通过缓冲解析、trace 控制事件、路由懒加载和音频处理支撑实时体验。

当前还需统一 SSE parser、取消与超时、批量渲染、AudioWorklet/二进制音频，以及生产 Nginx 的流式和 WebSocket 配置。

# 核心主线

把网络字节流正确还原为业务事件，并在协议、状态、渲染和代理四层共同处理取消、背压与实时性。
