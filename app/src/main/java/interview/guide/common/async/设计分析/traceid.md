
          
## traceId 的作用

**一句话：traceId 是贯穿整个请求处理链路的唯一标识，让你能在日志系统中把同一个请求的所有日志串在一起。**

### 没有 traceId 时

```
[线程A] Controller 收到上传请求
[线程B] Producer 发送消息到 Stream
[线程C] Consumer 开始向量化处理
[线程C] Consumer 向量化完成
```

你无法知道这四行日志是不是同一个请求的——线程名一直在变。

### 有 traceId 时

```
[traceId=abc-123] Controller 收到上传请求
[traceId=abc-123] Producer 发送消息到 Stream
[traceId=abc-123] Consumer 开始向量化处理
[traceId=abc-123] Consumer 向量化完成
```

在日志平台搜 `abc-123`，整条链路全出来了。

---

## traceId 是怎么来的

代码在 [TraceIdFilter.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/observability/TraceIdFilter.java) ，这是一个 `OncePerRequestFilter`，每个 HTTP 请求到达时自动执行。

### 生成逻辑（第 116-122 行）

```java
private String resolveTraceId(HttpServletRequest request) {
    String incoming = request.getHeader("X-Trace-Id"); // 优先从请求头拿
    if (incoming != null && !incoming.isBlank()) {
        return incoming.trim();      // 前端或上游服务传了，就直接用
    }
    return UUID.randomUUID().toString(); // 没有就生成一个新的
}
```

两种来源：

| 来源 | 场景 | 值例 |
|------|------|------|
| 请求头 `X-Trace-Id` | 前端传过来，或微服务间调用时上游传过来 | 上游生成的 UUID |
| 自动生成 | 没有收到请求头时 | `a1b2c3d4-e5f6-7890-abcd-ef1234567890` |

### 写入 MDC（第 93-94 行）

```java
String traceId = resolveTraceId(request);   // 获取或生成
MDC.put("traceId", traceId);                // 写入当前线程的 MDC
```

MDC 是 SLF4J 的线程本地 Map。写入后，该线程后续所有 `log.info()` 都会自动带上 `traceId`。

### 写入响应头（第 95 行）

```java
response.setHeader("X-Trace-Id", traceId);
```

前端可以从响应头拿到 traceId，出问题时报给后端排查。

### 请求结束时清理（第 98-100 行）

```java
finally {
    MDC.remove("traceId"); // 防止线程池复用时污染下一个请求
}
```

---

## traceId 的完整传输链路

```
1. HTTP 请求到达
   TraceIdFilter.doFilterInternal()
     ├── 从 X-Trace-Id 头获取 / 自动生成 UUID
     ├── MDC.put("traceId", "abc-123")
     └── 响应头 setHeader("X-Trace-Id", "abc-123")
            │
2. Controller → Service 处理
   全程 MDC 中有 traceId，日志自动带 [traceId=abc-123]
            │
3. Producer.sendTask(payload)
   buildMessageWithTrace()
     ├── TraceIdFilter.currentTraceId()  ← 从 MDC 取出 "abc-123"
     └── 写入消息 Map: {..., traceId: "abc-123"}
            │
4. XADD 写入 Redis Stream
   消息体包含 traceId=abc-123
            │
5. Consumer 线程拉取消息
   processMessage(messageId, data)
     ├── data.get("traceId") → "abc-123"
     ├── MDC.put("traceId", "abc-123")  ← 写入消费者线程的 MDC
     ├── processBusiness() 日志带 [traceId=abc-123]
     └── finally: MDC.remove("traceId")  ← 清理，不污染下一条消息
```

**关键：同一个 `abc-123` 从 HTTP 请求 → Producer → Redis Stream → Consumer，全程贯穿。**