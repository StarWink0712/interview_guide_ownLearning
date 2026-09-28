# SSE 流式输出、Reactor 取消与背压

SSE传输详解：

<br />

## 1. 模块基础讲解

模块有两种流式 API：知识库直接查询返回 `Flux<String>` 且声明 `text/event-stream`；聊天 API 返回
`Flux<ServerSentEvent<String>>`，先发送名为 `trace` 的事件，再发送回答数据。LLM 的 `stream().content()` 提供
原始 Flux，`normalizeStreamOutput` 缓冲前 120 字符以识别“无信息”模板，随后透传。

聊天 Controller 在订阅前同步创建 user 消息和 assistant 占位，流中累积完整文本，完成后更新占位。Spring WebFlux
官方支持 `Flux<ServerSentEvent>`；若只传 data 也可以省略包装，但 `produces` 必须是 `text/event-stream`。

当前实现并非全链路非阻塞：Rewrite、计数、JPA、向量检索都在构造 Flux 时执行；`Flux.create` 内又手动 subscribe，
会绕过常规操作符链的上下文与背压传播。虚拟线程降低阻塞成本，但不修复 Reactive Streams 语义。

SE（Server-Sent Events）是一种服务端向客户端单向推送数据的技术。在这里的具体作用是： LLM 每生成一小段文本就立即推送给前端，而不是等全部生成完再一次性返回 。前端逐块追加显示，实现类似 ChatGPT 的打字机效果。技术上通过 Flux<String> + produces = text/event-stream 实现，每个生成的 chunk 作为一个 SSE data 事件发送。

## 2. 基础面试问题

**问题：为什么用 SSE 而不是普通 HTTP？**

回答：LLM 生成耗时长，SSE 可逐段展示，降低感知延迟；协议基于 HTTP，浏览器支持简单，适合服务器单向推送。
它不适合客户端高频双向通信，那类场景更适合 WebSocket。

**问题：SSE 与 WebSocket 有何区别？**

回答：SSE 是单向、文本、自动重连语义简单；WebSocket 是全双工帧协议，适合实时语音和互动。知识库回答主要由
服务端连续输出，SSE 更轻；语音面试需要双向音频，WebSocket 更合适。

**问题：什么是背压？**

回答：下游处理速度低于上游生产速度时，订阅者通过 request 数量控制需求，避免无限缓冲。手动 subscribe 后再往
`Flux.create` sink 推数据，若不设置 overflow 策略，背压行为不再清晰。

**问题：为什么先发送 traceId？**

回答：客户端可在回答尚未完成时获得关联 ID，用于错误反馈和观测下钻。比把 traceId 拼进文本更结构化，也便于
后续增加 citation、usage、done 等事件。

## 3. 进阶面试问题

**问题：`normalizeStreamOutput`** **有哪些并发风险？**

回答：它在 `Flux.create` 中立即订阅 rawFlux，并用数组保存 Disposable。若上游同步发出命中“无信息”的 chunk，
回调发生时数组还未完成赋值，无法及时 dispose；虽然有 completed 标志阻止继续下发，上游资源可能继续运行。

**问题：错误为什么可能被当成成功完成？**

回答：QueryService 用 `onErrorResume` 把异常转成普通 `【错误】...` 文本，随后 Flux 正常 complete。Controller 的
`doOnComplete` 会把它保存为 completed=true，客户端和指标难以区分业务答案与错误降级。

**问题：客户端断开会留下什么？**

回答：Controller 只在 complete/error 更新 assistant 占位，没有 `doOnCancel` 或覆盖 CANCEL 的 `doFinally`。浏览器
关闭时可能仅触发 cancel，消息一直 `completed=false`；仓库虽有查询未完成消息的方法，但没有恢复任务。

**问题：为什么手工转义换行值得质疑？**

回答：`ServerSentEvent` 编码器负责 SSE framing。把 `\n` 替换为两个字符 `\\n` 改变了 Markdown 数据语义，前端
必须额外反转义，且完整消息保存的是未转义版本，线上显示与历史记录可能不一致。

## 4. 技术延伸问题

**问题：如何不用手动 subscribe 实现前缀探测？**

回答：可用 `scanWith`/`handle` 构造状态机，或 `windowUntil`、`takeUntil` 与 `switchOnFirst` 组合，让取消、错误和
Context 沿同一订阅链传播。更简单的做法是取消自然语言前缀探测，使用结构化模型状态。

**问题：SSE 事件协议如何设计？**

回答：定义 `trace`、`token`、`citation`、`usage`、`error`、`done`；每个事件 data 是 typed JSON，包含 messageId、
sequence 和 traceId。应用错误用 `error` 事件而非伪装 token，完成事件带最终状态。

**问题：虚拟线程与 SSE 的最佳实践是什么？**

回答：阻塞 JPA/对象存储可在虚拟线程执行；不要在 Netty event-loop 做阻塞操作；明确 MVC 还是 WebFlux 运行模型；
流式模型 SDK 的 Flux 保持单订阅链，取消时传播到 HTTP Client。虚拟线程不是背压方案。

参考：[Spring WebFlux 返回值与 SSE](https://docs.spring.io/spring-framework/reference/web/webflux/controller/ann-methods/return-types.html)。

## 5. 改进方案问题

**问题：如何重构流式生命周期？**

回答：使用 `Flux.defer` 把校验、检索和模型调用延迟到订阅；外层 `doFinally(signal)` 统一处理 COMPLETE、ERROR、
CANCEL；消息状态改为 STREAMING/COMPLETED/FAILED/CANCELLED；最终持久化放独立服务并保证幂等。

**问题：如何减少 TTFT？**

回答：评估关闭 Rewrite 或并行原问题检索；缓存查询 Embedding；缩短检索和 Prompt；移除 120 字符人为缓冲；在
前置阶段发送 status/trace 事件和 heartbeat，防止代理超时；分别监控 retrieval latency 与 model TTFT。

**问题：如何做流量保护？**

回答：按用户/IP/全局限制并发流和 token 配额，设置最大持续时间、idle timeout 和响应字数；断开即取消上游；
反向代理关闭不当缓冲并配置 keepalive；慢客户端采用有界缓冲或主动终止。

## 6. 故障处理问题

**问题：浏览器长时间收不到首字，如何排查？**

回答：按阶段记录 Rewrite、Embedding、SQL、Prompt 和 TTFT；检查 120 字符缓冲；验证 Nginx/proxy buffering、压缩和
flush；确认 Provider 真正流式而非攒完整响应；发送 heartbeat 判断是前置计算还是网络缓冲。

**问题：回答中途停止但服务端没有错误怎么办？**

回答：查客户端取消、代理 idle timeout、Provider 连接关闭和 backpressure；按 traceId 对比 token 数与消息状态；
确保 `doFinally` 记录 termination signal，并把 CANCELLED 与 COMPLETE 分开。

**问题：未完成占位消息越来越多怎么办？**

回答：先按 createdAt 和 completed=false 统计，关联应用重启、断网和取消；补定时恢复，把过期占位标 FAILED/CANCELLED；
修复 Controller 生命周期后再清理历史数据，避免简单删除破坏会话顺序。
