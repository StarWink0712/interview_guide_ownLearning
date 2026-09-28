SSE（Server-Sent Events）是一种基于 HTTP 长连接的服务端单向推送技术，后端通过 Spring WebFlux 的 `Flux<String>` 响应式类型实现流式输出——LLM 调用使用 `.stream().content()` 而非 `.call()`，每个生成的 token 立即作为 Flux 的一个元素推送出去，框架自动序列化为 `data: chunk\n\n` 的 SSE 格式。项目有两个流式接口：直接知识库问答返回原始 `Flux<String>`，RAG 聊天会话返回 `Flux<ServerSentEvent<String>>`，后者先通过 `Flux.concat` 发送一个自定义 `event:trace` 事件携带 traceId 给前端，再将 AI 回答逐块推送，并在 `doOnComplete` 回调中把完整回答写回数据库的 AI 消息占位行、`doOnError` 中也保存已收到的部分内容不丢失。前端不依赖浏览器原生 `EventSource`（因为只支持 GET），而是用 `fetch + response.body.getReader() + TextDecoder` 手动解析 SSE 字节流，按 `\n\n` 分割事件块，配合 `requestAnimationFrame` 和 React 的 `useTransition` 做批量渲染优化，保证打字机效果流畅不卡顿。流式输出还有一个独有的探测窗口优化——`normalizeStreamOutput` 方法先缓冲前 120 个字符进行拒答关键词检测（如"没有找到相关信息"），命中则立即中断原始流并替换为简短拒答文案，避免 LLM 生成长篇无用拒答浪费资源；未命中则释放缓冲区进入零缓冲透传模式，后续 chunk 实时直达前端，兼顾了体验和资源效率。

<br />

这里是用的spring webflux封装的sse，适合流式传输，每生成一个字就可以推送给前端，这样rag问答时就可以通过这个方式返回给前端要的内容。在前端逐行显示。

`Flux`是Project Reactor提供的响应式Publisher，表示一个包含0到多个元素的异步数据流。它可以持续通过 `onNext`发送数据，最后通过 `onComplete`正常结束，或者通过 `onError`异常结束。Flux通常是惰性执行的，只有建立订阅后数据流才真正开始。在Spring WebFlux中，框架会自动订阅Controller返回的Flux，并将其中的数据逐步写入HTTP响应。Flux支持 `map`、`filter`、`flatMap`等操作符，也支持Reactive Streams背压机制。它特别适合数据库多条结果、WebClient流式响应、SSE实时消息以及大模型逐Token输出等场景。需要注意，Flux并不等于多线程，只有数据源本身异步或显式切换调度器时，任务才可能在不同线程上执行。

<br />

<br />

RAG知识库问答非常适合使用Spring WebFlux的Flux实现流式响应。服务端首先根据用户问题执行Embedding和向量检索，获取相关文档片段，再将系统提示词、召回内容和用户问题组合成Prompt发送给LLM。相比使用`.call().content()`等待模型完整生成答案，Spring AI可以通过`.stream().content()`返回`Flux<String>`，模型每生成一个增量内容块，就将其作为Flux中的一个元素继续向下游传递。这里的Flux元素通常是模型服务返回的chunk或delta，不一定严格对应一个Token或一个汉字；前端收到内容块后可以直接追加，也可以进一步拆分，从而实现逐字显示的打字机效果。

SSE是一种基于HTTP长连接的服务端单向推送机制。Controller将响应类型声明为`text/event-stream`后，Spring WebFlux可以随着Flux不断产生元素，将内容持续写入HTTP响应流。对于普通流式知识库问答，可以直接返回`Flux<String>`，由框架按照SSE格式输出；对于需要自定义事件名称、事件ID和其他元数据的会话接口，则可以返回`Flux<ServerSentEvent<String>>`。例如先使用`Flux.concat`发送一个`event: trace`事件，将traceId传递给前端，然后再拼接LLM回答对应的Flux，逐块推送回答内容。需要注意，虽然WebFlux会流式写出Flux元素，但实际网络传输还可能受到Web服务器、代理网关和缓冲策略影响，因此多个chunk有可能被合并后到达前端。

在流式会话持久化方面，可以在请求开始时先创建一条状态为“生成中”的AI消息记录，并通过`doOnNext`将收到的内容块追加到内存缓冲区。流正常结束后，再将完整回答和完成状态写回数据库；流发生异常时，可以保存已经收到的部分内容和异常状态。不过，`doOnComplete`和`doOnError`本质上是观察终止信号的副作用操作，如果数据库保存方法本身返回Mono，不能只是调用后丢弃，而应通过`then`、`concatWith`或`onErrorResume`等操作符将保存动作正式组合进响应式链路。同时还要处理用户关闭页面或主动停止生成产生的cancel信号，因为这种情况不会触发`doOnComplete`，也不一定触发`doOnError`，可以通过`doOnCancel`或`doFinally`进行资源清理、停止上游模型调用以及记录部分回答。

前端由于需要使用POST提交问题、会话ID、知识库ID以及鉴权信息，因此不一定使用只方便发起GET请求的原生EventSource，而可以使用`fetch + response.body.getReader() + TextDecoder`读取响应字节流。前端需要维护一个未解析缓冲区，将每次读取到的字节解码后追加进去，***再按照SSE事件边界解析`event`、`data`和`id`字段。***不能假设一次`reader.read()`恰好得到一个完整事件，因为一个SSE事件可能被拆成多个网络包，多个事件也可能被合并在同一次读取中。解析完成后，可以将多个细小chunk合并到`requestAnimationFrame`中批量更新，并结合React的`useTransition`降低高频状态更新造成的页面卡顿。

流式输出还可以增加一个拒答探测窗口。例如`normalizeStreamOutput`先缓冲前120个字符，检测“没有找到相关信息”“无法根据资料回答”等拒答特征。如果命中，就取消原始LLM流，并向前端输出统一的简短拒答文案，避免模型继续生成冗长的无效内容；如果没有命中，则先释放已经缓冲的内容，再切换为实时透传模式，后续chunk直接发送给前端。这种方案能够减少部分无效生成，但会使正常回答的首段内容延迟到探测窗口结束后才显示，而且只有取消信号能够向上传递到模型客户端、模型供应商也真正停止生成时，才能实际节省推理资源。因此，工程上还应结合检索相似度阈值、Rerank分数和结构化拒答标记，在调用LLM之前或生成早期共同完成质量门控。

<br />

以下是对前端目录中与 RAG chat 和知识库流式查询相关的 SSE（Server-Sent Events）/ ReadableStream / 流式 fetch 代码的完整搜索结果。

***

## 搜索结果总结

前端项目中**没有使用浏览器原生的** **`EventSource`** **API**，而是采用 **`fetch()`** **+** **`ReadableStream`** **+** **`getReader()`** 的方式手动解析 SSE 流。涉及以下 3 个源文件：

***

### 1. `/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/frontend/src/api/ragChat.ts`

**核心流式方法：`sendMessageStream()`（第 111-224 行）**

这是 RAG 聊天会话模块的流式消息发送方法，关键实现细节：

- **端点**：`POST /api/rag-chat/sessions/{sessionId}/messages/stream`（第 120-121 行）
- **流式方式**：`fetch()` + `response.body?.getReader()` + `TextDecoder`（第 142-147 行）
- **SSE 解析**：按 `\n\n` 分隔事件块，同时兼容单行 `data:` 格式（第 202-213 行）
- **多事件支持**：支持 `event:trace` 自定义事件（携带 `traceId`），以及默认 `event:message`（第 150-187 行）
- **特殊处理**：
  - `event:trace` 中的 traceId 通过 `onTrace` 回调传出，解析失败不影响主内容（第 174-183 行）
  - 转义换行符 `\n` / `\r` 会还原为真实换行（第 170-172 行）
- **调用方**：`KnowledgeBaseQueryPage.tsx` 第 322 行（见下文）

```typescript
// 核心API签名
async sendMessageStream(
  sessionId: number,
  question: string,
  onMessage: (chunk: string) => void,
  onComplete: () => void,
  onError: (error: Error) => void,
  onTrace?: (traceId: string) => void
): Promise<void>
```

***

### 2. `/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/frontend/src/api/knowledgebase.ts`

**核心流式方法：`queryKnowledgeBaseStream()`（第 188-280 行）**

这是知识库直接问答的流式查询方法（独立于 RAG Chat 会话），关键实现细节：

- **端点**：`POST /api/knowledgebase/query/stream`（第 195 行）
- **流式方式**：`fetch()` + `response.body?.getReader()` + `TextDecoder`（第 216-221 行）
- **SSE 解析**：按行 `\n` 分隔，逐行处理 `data:` 前缀（第 262-275 行）
- **差异点**：与 `ragChat.ts` 的 `sendMessageStream` 不同，此方法：
  - 按**单行**解析（而非 `\n\n` 事件块）
  - 处理 `data:` 后的前导空格（SSE 协议标准，第 232-234 行）
  - 将空内容的 `data:` 行解释为换行符 `\n`（第 236-237 行）
  - 仅支持 `data:` 行，**不支持**多事件类型（没有 `event:` 字段解析）
- **注意**：此方法在当前代码中**似乎未被任何页面直接调用**（搜索结果中未见调用方），可能是旧版本保留代码。

```typescript
// 核心API签名
async queryKnowledgeBaseStream(
  req: QueryRequest,
  onMessage: (chunk: string) => void,
  onComplete: () => void,
  onError: (error: Error) => void
): Promise<void>
```

***

### 3. `/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/frontend/src/pages/KnowledgeBaseQueryPage.tsx`

**流式消费方：`handleSubmitQuestion()`（第 258-352 行）**

这是知识库问答页面的主组件，调用 `ragChatApi.sendMessageStream()` 来消费流式响应：

- **调用位置**：第 322 行 `await ragChatApi.sendMessageStream(sessionId, userQuestion, ...)`
- **流式渲染优化**：使用 `requestAnimationFrame`（第 328-333 行）节流消息更新频率，避免频繁重渲染
- **React 并发特性**：使用 `useTransition()` 的 `startTransition`（第 331 行）将状态更新标记为低优先级，保证 UI 响应
- **追踪支持**：通过 `updateAssistantTraceId` 回调（第 307-318 行）接收 traceId，在消息气泡中显示为可点击链接
- **流结束后**：刷新会话列表 `loadSessions()`（第 338 行）

***

## 两个流式实现的对比

| 特性         | `ragChat.sendMessageStream`                   | `knowledgebase.queryKnowledgeBaseStream` |
| :--------- | :-------------------------------------------- | :--------------------------------------- |
| 端点         | `/api/rag-chat/sessions/{id}/messages/stream` | `/api/knowledgebase/query/stream`        |
| SSE 分隔符    | `\n\n`（事件块）                                   | `\n`（单行）                                 |
| 多事件支持      | 是（`event:trace` + `event:message`）            | 否（仅 `data:`）                             |
| traceId 支持 | 是（独立 `onTrace` 回调）                            | 否                                        |
| `\n` 转义还原  | 是                                             | 否                                        |
| 实际使用方      | `KnowledgeBaseQueryPage.tsx`                  | 未发现调用方                                   |

***

## 补充说明

1. **未使用** **`EventSource`** **API**：项目完全通过 `fetch` + `ReadableStream` 手动实现 SSE 解析，因为需要 `POST` 请求（`EventSource` 仅支持 `GET`），且需要自定义请求头和请求体。
2. **异步任务模块**：`asyncTask.ts` 和 `aiObservability.ts` 中的 `stream` / `streaming` 字段是**普通的属性定义**（如 `streamKey: string`、`streaming: boolean`），与 SSE 流式传输无关，属于 Redis Stream 异步任务状态和 AI 调用观测数据。
3. **语音面试模块**：`AudioRecorder.tsx` 中的 `stream` / `MediaStream` 是 WebRTC 音频流，与 SSE 无关。

## SSE 流式传输详解

### 一、什么是 SSE

SSE（Server-Sent Events）是一种基于 HTTP 的服务端向客户端单向推送数据的技术。与传统的"请求-响应"模式不同，它建立的是一个**长连接**，服务端可以持续向客户端推送数据块，直到数据传完才关闭连接。在你的项目中，它用于让 LLM 逐字生成答案并实时推送给前端，实现类似 ChatGPT 的打字机效果。

### 二、后端如何产生流（Spring WebFlux + Reactor）

后端没有直接操作 HTTP 的 OutputStream，而是依赖 Spring WebFlux 的响应式编程模型。核心是 `Flux<String>` 这个类型——它是一个异步的、可产生 0 到 N 个 `String` 元素的序列。

流式传输的起点是这行代码：

```java
Flux<String> responseFlux = promptSpec
        .user(userPrompt)
        .stream()   // 关键：不是 .call()，而是 .stream()
        .content();
```

Spring AI 的 `.stream()` 方法内部连接 LLM API 的 streaming 端点（如 OpenAI 的 `stream: true`），LLM 每生成一个小 token 就通过 TCP 连接推送过来，Spring AI 将其包装为一个 `Flux<String>` 中的元素。与 `.call()` 的区别在于：`.call()` 会阻塞等待 LLM 全部生成完毕后一次性返回完整字符串，而 `.stream()` 立即返回 `Flux`，每个 token 到达时自动触发下游订阅者的处理逻辑。

Controller 层返回 `Flux<String>` 或 `Flux<ServerSentEvent<String>>` 时，Spring WebFlux 框架会自动处理底层协议——当 `produces = MediaType.TEXT_EVENT_STREAM_VALUE` 时，每个 `Flux` 元素被序列化为 SSE 格式：

```
data: 你好

data: ，这是

data: 知识库的

data: 回答内容
```

### 三、两个流式接口及差异

你的项目有两个流式接口，返回的数据格式不同：

#### 接口 1：直接知识库流式问答

```
POST /api/knowledgebase/query/stream
返回类型：Flux<String>
```

Controller 直接返回 raw `Flux<String>`，Spring WebFlux 自动将每个字符串包装为 `data: {content}\n\n`。最简单，不带 sessionId、不带历史消息。

#### 接口 2：RAG 聊天会话流式问答

```
POST /api/rag-chat/sessions/{sessionId}/messages/stream
返回类型：Flux<ServerSentEvent<String>>
```

这个接口返回 `ServerSentEvent<String>`，可以手动控制 SSE 的事件类型和元数据。具体流程：

1. **先同步准备消息**：调用 `sessionService.prepareStreamMessage(sessionId, question)` 同步保存用户消息到数据库，创建一个 AI 消息占位行，返回 `messageId`。
2. **获取流式内容**：调用 `sessionService.getStreamAnswerWithTrace(sessionId, question)` 拿到 `RagStreamResult`（包含 `traceId` 和 `Flux<String>`）。
3. **构造 trace 事件**：手动构造一个 `event:trace` 类型的 `ServerSentEvent`，data 为 `{"traceId":"xxx"}`，让前端先知道本次请求的追踪 ID。
4. **拼接流**：通过 `Flux.concat` 先将 trace 事件发出去，再将 AI 回答内容逐块包装为 `ServerSentEvent` 发送，每块中对 `\n` 和 `\r` 做转义（`\\n`、`\\r`）防止破坏 SSE 协议格式。
5. **流结束回调**：`doOnComplete` 中调用 `sessionService.completeStreamMessage(messageId, fullContent.toString())` 将完整回答写回数据库的 AI 消息占位行。
6. **异常回调**：`doOnError` 中将已收到的部分内容也保存到数据库，不会丢失。

### 四、前端如何消费流

前端使用 `fetch() + ReadableStream + getReader()` 手动解析 SSE，而不是浏览器的原生 `EventSource`。原因是 `EventSource` 只支持 GET 请求，无法携带请求体。

#### 核心流程（以 RAG 聊天为例，`ragChat.ts:sendMessageStream`）：

```
fetch('/api/rag-chat/sessions/123/messages/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question: '...' })
})
  ↓
response.body.getReader()  → 拿到 ReadableStreamDefaultReader
  ↓
reader.read() 循环  →  每次返回 { done, value: Uint8Array }
  ↓
TextDecoder 解码  →  文本字符串
  ↓
按 '\n\n' 分割事件块  →  解析每个 SSE 事件
  ↓
识别 event: 字段  →  'trace' 事件 → onTrace(traceId)
                  →  默认事件 → onMessage(chunk)
```

对于直接知识库流式（`knowledgebase.ts:queryKnowledgeBaseStream`），解析逻辑更简单：按行 `\n` 分割，每行去掉 `data:` 前缀后传递给 `onMessage`。

#### 渲染优化（`KnowledgeBaseQueryPage.tsx`）：

```typescript
pendingChunksRef.current += chunk;
if (!animationFrameRef.current) {
    animationFrameRef.current = requestAnimationFrame(() => {
        startTransition(() => {
            // 更新 UI 中的消息内容
        });
        pendingChunksRef.current = '';
        animationFrameRef.current = null;
    });
}
```

不是每收到一个 chunk 就渲染一次，而是用 `requestAnimationFrame` 配合 React 的 `useTransition`：在每一帧渲染周期内累积多个 chunk 后批量更新 UI，既保证了流畅的打字机效果，又避免了高频渲染导致的卡顿。

### 五、探测窗口归一化（流式独有）

流式输出最大的问题是：LLM 生成的内容是逐步到达的，如果 LLM 生成了长篇拒答文本（如"抱歉，在知识库中没有找到相关信息，我无法回答这个问题，但是我可以为您提供以下建议：1. 请重新描述您的问题 2. …"），前端会逐字打出这些无意义内容，体验很差。

`normalizeStreamOutput` 方法通过**探测窗口**机制解决这个问题：

- 前 120 个字符不直接透传，而是缓冲在 `probeBuffer` 中
- 如果这 120 字符内检测到拒答关键词（"没有找到相关信息"等），立即中断原始流，替换为一句简短的统一拒答文案后结束
- 如果 120 字符内未检测到拒答关键词，说明不是拒答，将缓冲区内容一次性推出去，后续 chunk 全部**零缓冲直接透传**，保证延迟最低

这就像先看一眼内容再决定要不要让水龙头继续流水，而不是等到水满了才发现是一池脏水。

### 六、完整数据流图示

```
前端 fetch() POST /api/rag-chat/sessions/{id}/messages/stream
    │
    ▼
Controller.sendMessageStream()
    ├── 1. prepareStreamMessage() → 保存用户消息，创建 AI 占位
    ├── 2. getStreamAnswerWithTrace() → RAG 检索 + LLM stream
    │       ├── RagTraceContext 创建
    │       ├── 问题改写 → 向量检索 → 文档拼接
    │       ├── LLM .stream().content() → Flux<String>
    │       └── normalizeStreamOutput() → 探测窗口归一化
    └── 3. Flux<ServerSentEvent> 返回
            ├── trace 事件: {"traceId":"xxx"}
            ├── data: chunk1
            ├── data: chunk2
            ├── ...
            ├── doOnComplete → completeStreamMessage() 存库
            └── doOnError → 部分内容存库
    │
    ▼  HTTP SSE 流
    │
前端 ReadableStream.getReader()
    ├── 解析 \n\n → 提取 event 和 data
    ├── trace 事件 → 显示 traceId 链接
    ├── chunk → requestAnimationFrame 批量渲染
    └── 流结束 → 刷新会话列表
```

