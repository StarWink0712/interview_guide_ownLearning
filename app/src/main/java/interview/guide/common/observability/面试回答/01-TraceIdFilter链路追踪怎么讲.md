# TraceIdFilter 链路追踪怎么讲

## 1. 它是什么

`TraceIdFilter` 是一个继承 `OncePerRequestFilter` 的 Spring Web 过滤器。

它为每个 HTTP 请求做四件事：

1. 从请求头 `X-Trace-Id` 读取上游传来的 traceId。
2. 如果请求头没有 traceId，就用 `UUID.randomUUID()` 生成一个新的。
3. 把 traceId 写入 SLF4J 的 MDC，key 是 `traceId`。
4. 把 traceId 写回响应头 `X-Trace-Id`，请求结束后从 MDC 移除。

面试一句话：

> 我用 `TraceIdFilter` 给每个请求建立一个轻量级链路 ID，并写入 SLF4J MDC。后续 Controller、Service、异步 Producer 里的日志都能自动带同一个 traceId，方便线上按一次请求串日志。

## 2. 为什么需要它

AI 面试平台的链路通常不是一个简单接口：

```text
前端请求
  -> Controller
  -> Service
  -> Redis Stream Producer
  -> 后台 Consumer
  -> 文件解析 / 向量化 / LLM 调用
  -> 状态更新
```

如果没有 traceId，线上排查时会遇到几个问题：

- 用户只提供“某次请求失败了”，后端很难从大量日志里找到它。
- HTTP 请求返回后，后台异步任务还在继续执行，日志链路容易断。
- 同一个请求可能触发多次 LLM 调用或多次 RAG 检索，需要一个统一 ID 关联。

`TraceIdFilter` 解决的是“日志怎么串起来”的问题。

## 3. 代码执行流程

核心流程在 `doFilterInternal(...)`：

```text
请求进入
  |
  v
resolveTraceId(request)
  |-- 有 X-Trace-Id: trim 后复用
  |-- 没有 X-Trace-Id: UUID.randomUUID()
  |
  v
MDC.put("traceId", traceId)
  |
  v
response.setHeader("X-Trace-Id", traceId)
  |
  v
filterChain.doFilter(request, response)
  |
  v
finally: MDC.remove("traceId")
```

这里最重要的是 `finally`。

MDC 底层是线程上下文。如果请求处理完不清理，线程池复用时可能出现日志串号：

```text
请求 A 的 traceId = aaa
线程处理完 A 后没有清理
线程又处理请求 B
请求 B 的日志可能错误地带 aaa
```

所以面试官如果问“为什么要 finally 清理 MDC”，答案是：

> 因为 Web 容器线程会复用，MDC 是线程本地上下文。如果不在 finally 中清理，当前请求的 traceId 可能污染下一次请求的日志。

## 4. logback 是怎么配合的

项目的 `logback-spring.xml` 配置了：

```xml
<property name="LOG_LEVEL_PATTERN" value="%5p [traceId:%X{traceId:-}]"/>
```

这表示日志级别后面会输出 MDC 中的 `traceId`。

所以任意业务代码只要正常使用：

```java
log.info("知识库问答完成: kbIds={}, traceId={}", knowledgeBaseIds, traceContext.traceId());
```

或者普通的：

```java
log.info("Session created: sessionId={}", sessionId);
```

日志格式都可以自动带上 MDC 里的 traceId。

更好的面试表达：

> 我没有要求每一行日志都手动传 traceId，而是在请求入口写入 MDC，再通过 logback pattern 自动输出。这样业务代码侵入性低，也减少漏打 traceId 的概率。

## 5. 和异步任务怎么串起来

这是这个项目里 `TraceIdFilter` 最值得讲的地方。

HTTP 请求和 Redis Stream 消费线程不是同一个线程。MDC 默认不会跨线程传递。

所以项目在异步模板里做了桥接：

### 5.1 Producer 入队时

`AbstractStreamProducer` 会调用：

```java
TraceIdFilter.currentTraceId()
    .ifPresent(traceId -> message.put(AsyncTaskStreamConstants.FIELD_TRACE_ID, traceId));
```

也就是说：

```text
HTTP 请求线程 MDC
  -> currentTraceId()
  -> Redis Stream message["traceId"]
```

### 5.2 Consumer 消费时

`AbstractStreamConsumer` 会从消息里取出 `traceId`：

```text
message["traceId"]
  -> MDC.put("traceId", traceId)
  -> processBusiness(...)
  -> finally 恢复旧 MDC
```

这使得日志链路变成：

```text
HTTP 接口日志 traceId=abc
  -> Producer 入队日志 traceId=abc
  -> Consumer 处理日志 traceId=abc
  -> LLM / 文件解析 / 向量化日志 traceId=abc
```

面试回答模板：

> 异步任务会丢失 HTTP 线程上下文，所以我没有依赖 MDC 自动跨线程传播，而是在 Producer 入队时把当前 traceId 写入 Redis Stream 消息体。Consumer 取消息后再写回自己的 MDC，处理结束 finally 恢复。这样即使任务异步执行，日志也能和原始请求串起来。

## 6. 和 AI 调用日志怎么关联

`StructuredOutputInvoker` 会先尝试从 `TraceIdFilter.currentTraceId()` 获取当前请求 traceId。

如果当前不是 HTTP 请求线程，且存在 `AiInvocationLogService`，会创建一个新的 traceId。

这说明代码考虑了两类场景：

- HTTP 请求触发的 AI 调用：复用请求 traceId。
- 后台任务或非 HTTP 线程触发的 AI 调用：生成新的 traceId 用于调用日志。

这样 AI 调用日志不会因为没有 HTTP 上下文就完全失去追踪 ID。

## 7. `traceId`、`sessionId`、`taskId` 的区别

面试官经常会问这个。

| 概念 | 粒度 | 用途 |
|---|---|---|
| `traceId` | 一次请求或一次链路 | 排查日志、关联 AI/RAG 调用 |
| `sessionId` | 一场面试或一次聊天会话 | 业务会话管理 |
| `taskId` | 一个异步任务 | Redis Stream 任务状态、重试、失败处理 |
| `userId` | 一个用户 | 权限、归属、用户行为 |

回答：

> `traceId` 是排查维度，不是业务主键。`sessionId` 和 `taskId` 是业务对象，`traceId` 用来把一次链路里的日志和调用明细串起来。一个 session 可以包含很多 traceId，一个 traceId 也可能触发多个 AI 调用或多个异步处理步骤。

## 8. 为什么用 `OncePerRequestFilter`

`OncePerRequestFilter` 的意义是保证同一个请求在一次过滤链中只执行一次过滤逻辑。

如果用普通 `Filter`，在 forward/include/error dispatch 等场景可能重复执行。

面试可以这样讲：

> traceId 是请求级上下文，正常情况下一个请求只应该解析或生成一次。`OncePerRequestFilter` 能避免重复写 MDC 和响应头，语义上更适合这种请求上下文初始化逻辑。

## 9. 为什么请求头里有 traceId 要复用

因为前端、网关、上游服务可能已经生成了 traceId。

如果后端强制重新生成，就会导致跨服务链路断开。

所以 `resolveTraceId(...)` 的策略是：

```text
上游传了 X-Trace-Id -> 复用
没传 -> 后端生成
```

回答：

> 复用上游 traceId 是为了保证链路连续。比如以后前面加 API Gateway 或 BFF，它们生成的 traceId 可以一路传到后端；后端只在缺失时兜底生成。

## 10. 当前实现的边界

### 10.1 它不是 OpenTelemetry

当前实现只有一个 traceId，没有：

- spanId
- parentSpanId
- span duration
- service topology
- trace sampling
- 跨服务瀑布图

所以要说：

> 当前是轻量级日志链路追踪，主要满足单体服务和异步任务日志关联。如果未来服务拆分，需要接入 OpenTelemetry 或类似方案。

### 10.2 请求头 traceId 没有做格式校验

代码会对 `X-Trace-Id` 做 `trim()`，但没有限制：

- 最大长度
- 字符集
- 是否符合 UUID 或特定格式

一般影响不大，但生产环境更严谨可以补：

- 长度上限，例如 64 或 128。
- 只允许字母、数字、`-`、`_`。
- 不合法时丢弃并重新生成。

面试时可以主动补一句：

> 当前实现已经满足链路追踪，但更生产级的做法会对传入的 traceId 做长度和字符校验，避免异常 header 污染日志。

### 10.3 异步任务没有 traceId 时会空着

如果异步任务不是从 HTTP 请求产生，而是定时任务或系统任务触发，Producer 可能拿不到 `currentTraceId()`。

这不是 bug，但需要知道：

- 有请求上下文时，traceId 会传递。
- 无请求上下文时，消息里可能没有 traceId。
- 某些 AI 调用日志会由 `AiInvocationLogService` 生成新 traceId。

## 11. 高频追问

### 问：为什么不用 ThreadLocal 自己存 traceId？

答：

> MDC 本质上就是日志框架提供的线程上下文，和日志输出天然集成。自己写 ThreadLocal 还要额外改日志格式或每次手动打印，收益不大。用 MDC 可以直接通过 `%X{traceId}` 输出，侵入性更低。

### 问：MDC 在线程池里有什么坑？

答：

> 最大的问题是线程复用导致上下文污染。所以 HTTP Filter 里必须 finally remove，异步 Consumer 里也要先备份旧值，处理完再恢复。否则下一条请求或下一条消息可能带上上一条的 traceId。

### 问：前端怎么使用这个 traceId？

答：

> 后端会把 traceId 写到响应头 `X-Trace-Id`。前端如果遇到错误，可以把这个 ID 展示在错误提示或上报给后端。后端根据这个 traceId 查日志和 AI/RAG 调用明细。

### 问：traceId 和日志里的手动 traceId 字段重复吗？

答：

> 不冲突。MDC 是全局日志上下文，能保证大部分日志自动带 traceId；少数关键业务日志手动打印 `traceId` 是为了在消息内容里更醒目，或者方便结构化日志字段提取。

