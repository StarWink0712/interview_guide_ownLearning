# TraceId、日志、指标关联与上下文传播

## 1. 模块基础讲解

`TraceIdFilter` 继承 `OncePerRequestFilter`，对每个 HTTP 请求执行：

1. 优先读取 `X-Trace-Id` 请求头。
2. 请求头为空时生成 UUID。
3. 把 traceId 写入 SLF4J MDC。
4. 把 traceId 写入响应头。
5. 执行后续 Filter/Controller。
6. 在 finally 中清理 MDC，避免线程复用串号。

`logback-spring.xml` 把 `%X{traceId:-}` 加入日志级别格式，因此当前线程的日志会携带 traceId。
`StructuredOutputInvoker` 再把 traceId 写入 `ai_invocation_logs`，前端失败列表可以跳转到 trace 详情。

这是“Correlation ID”能力：能把同一 HTTP 请求中的日志和 AI 审计记录关联起来。它并没有创建 Span、
父子关系、服务传播时长和采样决策，因此不应称为完整分布式追踪。

三类观测信号的分工：

- Metrics：聚合趋势，适合告警，禁止高基数 traceId 标签。
- Logs：记录离散事件，可带 traceId 搜索。
- Traces：表达一次请求跨组件的路径、父子 Span 和耗时。

## 2. 基础面试问题

**问题：为什么 MDC 必须在 finally 清理？**

回答：传统线程池会复用线程。如果上一个请求的 MDC 残留，下一个请求日志会带错 traceId，造成严重
排障误导和潜在数据串扰。finally 保证异常路径也清理。

**问题：为什么把 traceId 返回响应头？**

回答：用户报错时可以提供 traceId，前端也能展示或上报该值，后端据此查询日志和 AI 调用明细，缩短
定位时间。

**问题：为什么 traceId 不能放 Metrics Tag？**

回答：traceId 每请求唯一，会为每次请求创建新时间序列，造成高基数爆炸。它适合日志/Trace，不适合
聚合指标。

**问题：OncePerRequestFilter 保证什么？**

回答：在一个 Servlet 请求分派流程中避免同一 Filter 被重复执行。它不自动保证异步线程、消息队列和
WebSocket 后续消息都保有 MDC。

## 3. 进阶面试问题

**问题：接受客户端传入任意 X-Trace-Id 有什么风险？**

回答：未限制长度、字符和格式，攻击者可制造日志污染、超长 MDC，传入超过数据库 `trace_id` 64 长度
还会导致 AI 观测日志写入失败。应只接受安全字符和有限长度，非法时生成新 ID，并保留 trusted/untrusted
边界。

**问题：虚拟线程和异步任务会自动继承 MDC 吗？**

回答：不能依赖自动继承。MDC 通常基于 ThreadLocal，提交到 Executor、虚拟线程或 Redis Stream 后
需要显式捕获和恢复上下文，任务完成后清理。否则异步日志无 traceId，或使用错误上下文。

**问题：WebSocket 为什么不能只靠 HTTP Filter Trace？**

回答：Filter 只覆盖握手请求，后续多轮消息在长连接回调和异步执行器中处理。应为连接保存 trace/context，
每一轮创建 child span 或 turnId，并在异步任务中传播。

**问题：当前 traceId 与 W3C Trace Context 有什么差异？**

回答：W3C 使用 `traceparent`/`tracestate`，包含 traceId、spanId 和采样标志，生态工具可自动传播。
自定义 `X-Trace-Id` 只有一个字符串，无法表达 Span。生产可兼容读取旧头，但以 OpenTelemetry/Micrometer
Tracing 标准上下文为主。

**问题：日志里有 traceId 就等于全链路追踪吗？**

回答：不是。日志相关性只能搜索事件；完整 Trace 还要显示请求经过哪些服务/数据库/LLM、每段耗时、
错误和父子关系。当前是追踪基础，不是 Trace backend。

## 4. 技术延伸问题

**问题：如何接入 OpenTelemetry？**

回答：使用 Micrometer Tracing/OpenTelemetry bridge 或 Java Agent，HTTP、JDBC、Redis、LLM 调用产生
Span，经 OTLP 导出到 Tempo/Jaeger。日志格式加入 trace_id/span_id，指标使用 exemplars 关联具体 Trace。

**问题：消息队列如何传播 Trace？**

回答：生产消息时把 W3C traceparent 或必要上下文写入 message fields/header，消费时提取并创建 consumer
span；重试保留原 trace 并创建新 attempt span，避免只生成完全无关 UUID。

**问题：采样怎么设计？**

回答：正常流量低比例采样，错误、高延迟、重要面试流程提高采样；使用 tail sampling 可在看到完整
结果后保留异常 Trace。指标始终全量聚合，Trace 才采样。

## 5. 改进方案问题

**问题：如何加固 TraceIdFilter？**

回答：限制 64 字符以内，仅允许 `[A-Za-z0-9._-]`；支持 W3C `traceparent`；标记外部 ID 来源；日志
同时输出 spanId；对响应头设置一致策略；测试超长、控制字符和异常清理。

**问题：如何传播 MDC 到异步执行器？**

回答：使用 Spring `TaskDecorator` 或 Micrometer Context Propagation，在提交时捕获 ContextSnapshot，
执行时恢复并在 finally 清理。Redis Stream 则显式序列化 trace context。

**问题：如何把 Dashboard 和 Trace 关联？**

回答：指标告警链接到对应时间窗和标签的 Grafana 面板；histogram exemplar 指向代表性 trace；失败列表
按 errorCode/provider 聚合后可下钻抽样 trace，而不是把 traceId 塞进指标标签。

**问题：如何处理个人信息？**

回答：Trace 和日志只保存必要元数据，不保存原始简历、问题全文或 API Key；敏感字段脱敏；设置 RBAC、
保留期、访问审计和导出限制。traceId 本身不是权限凭证。

## 6. 故障处理问题

**问题：同一次请求的部分日志没有 traceId，怎么查？**

回答：看缺失日志是否来自 CompletableFuture、虚拟线程、定时任务或 Redis 消费者；确认执行器是否有
TaskDecorator/context propagation；WebSocket 后续消息是否保存连接上下文。

**问题：两个请求出现相同 traceId 怎么办？**

回答：检查客户端是否固定复用 X-Trace-Id、网关传播策略和 MDC 清理。外部 ID 允许重复会影响关联，
可在服务入口始终创建内部标准 Trace，同时把外部 requestId 作为独立字段。

**问题：AI 日志总是新 UUID，无法和 HTTP 日志关联，为什么？**

回答：调用发生在线程上下文之外，`TraceIdFilter.currentTraceId()` 为空，LogService 生成新 UUID。应在
进入异步前把 trace context 写入任务或显式参数。

**问题：传入超长 traceId 后监控日志消失，怎么查？**

回答：`AiInvocationLogEntity.traceId` 最大 64，数据库 save 失败会被 fail-silent，只留下 warn。检查日志
写入失败 Counter/警告，修复入口校验并为历史攻击流量做审计。
