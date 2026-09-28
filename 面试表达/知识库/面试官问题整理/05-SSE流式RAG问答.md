# SSE 流式 RAG 问答

## 1. 什么是 SSE，项目为什么用它输出 LLM 回答？

**回答：** SSE 是基于 HTTP 长连接的服务端单向推送技术。LLM 生成回答时，后端可以把每个文本片段持续推给前端，直到流结束，而不是等待完整答案后一次性返回。项目通过 Spring WebFlux 的 `Flux` 和 `text/event-stream` 实现类似 ChatGPT 的打字机效果。

<br />

有会话的知识库问答，是用的sse，这样可以在前端逐字显示答案，并且调用llm也是流式的，因为答案是llm生成的，想让服务端通过sse的方式把内容返回给前端，也要让llm生成完全部内容前就把内容返回给服务端。llm生成的一小段内容作为flux的一个元素。

## 2. 直接知识库流式问答接口是什么？

**回答：** 前端把知识库 ID 列表和问题封装成 `QueryRequest`，调用 `POST /api/knowledgebase/query/stream`。接口声明 `produces = text/event-stream`，按 GLOBAL 和 IP 分别执行每秒 5 次限流，因为长连接比同步请求占用更多资源。Controller 委托 `answerQuestionStream()`，并直接返回 `Flux<String>`。

## 3. 流式接口与同步接口复用了哪些流程？

**回答：** 两者的前半段使用同一套逻辑：参数校验、创建 `RagTraceContext`、更新知识库计数、问题归一化与改写、候选查询词构建、动态检索参数、pgvector 相似度检索、检索日志、文档上下文拼接以及系统和用户 Prompt 构建。核心差异只在模型输出和后续流处理。

## 4. .stream().content() 和 .call().content() 有什么区别？

**回答：** `.call().content()` 会等待 LLM 生成完整答案后一次性返回字符串；`.stream().content()` 立即返回 `Flux<String>`，LLM 每生成一个小片段就成为 Flux 的一个元素，由 Spring WebFlux 继续向下游推送。流式接口还能在调用前通过 `.messages(effectiveHistory)` 注入多轮历史。

## 5. Spring WebFlux 如何把 Flux 转成 SSE 响应？

**回答：** Controller 返回 `Flux<String>` 且响应类型为 `text/event-stream` 时，Spring WebFlux 自动把每个元素序列化为 `data: {content}\n\n`，并以分块传输持续写入 HTTP 响应。RAG 聊天接口返回 `Flux<ServerSentEvent<String>>`，可以进一步显式指定 `event` 类型和 `data` 内容。

<br />

spring的webflux会字段把flux转成sse响应，把内容封装到data中，event

## 6. normalizeStreamOutput 的探测窗口解决什么问题？

**回答：** 模型可能流式生成一大段“没有找到相关信息，但是可以提供其他建议”的无用拒答。如果直接透传，用户要逐字等待，模型也继续消耗 token。系统先缓冲前 120 个字符检测拒答关键词，命中就替换成统一简短拒答并终止原始流；未命中才进入正常透传。

## 7. 探测窗口内部如何控制状态和取消上游？

**回答：** 方法通过 `Flux.create` 创建新流，维护 `probeBuffer`、`AtomicBoolean passthrough`、`AtomicBoolean completed`，并保存原始流的订阅引用。探测阶段把 chunk 追加到缓冲区；命中拒答关键词时设置完成标志，发送统一文案、结束下游，并调用 `dispose()` 取消原始订阅，避免 LLM 继续生成。超过 120 字符未命中时设置透传标志，先释放缓冲区，之后的 chunk 直接转发。

## 8. 如果完整回答不足 120 个字符怎么办？

**回答：** 原始流正常结束时，如果仍处于探测阶段，说明完整回答没有达到 120 字符。系统会对 `probeBuffer` 中剩余内容执行一次 `normalizeAnswer()`，再把归一化结果推给前端并结束，不会因为未达到窗口大小而丢失短回答。

## 9. 流正常结束和异常时如何记录？

**回答：** 处理后的 Flux 使用 `doOnComplete` 记录流式成功指标；`onErrorResume` 在流中断时记录失败指标，并向流中注入“AI服务暂时不可用，请稍后重试”的友好提示。检索等前置阶段还有外层 catch 兜底，同样返回包装在 `RagStreamResult` 中的错误流。

## 10. 项目中的两个 SSE 接口有什么区别？

**回答：** `/api/knowledgebase/query/stream` 是无会话的直接问答，返回原始 `Flux<String>`，没有 sessionId 和历史消息，只处理默认 `data:` 事件；`/api/rag-chat/sessions/{id}/messages/stream` 返回 `Flux<ServerSentEvent<String>>`，包含真实会话和历史，先发送自定义 `event:trace`，再发送回答内容，并在完成或异常回调中把答案写回数据库。

## 11. 前端为什么不用原生 EventSource？

**回答：** 浏览器原生 `EventSource` 只支持 GET，而项目需要使用 POST 提交知识库 ID、问题、请求体和自定义请求头。因此前端采用 `fetch()` 获取响应，再通过 `response.body.getReader()` 和 `TextDecoder` 手动读取并解析 SSE 字节流。

## 12. 前端如何解析两种 SSE 响应？

**回答：** RAG 聊天的 `sendMessageStream()` 按 `\n\n` 分割事件块，解析 `event:` 和 `data:`，`event:trace` 交给 `onTrace`，默认事件交给 `onMessage`，并还原后端转义的换行。直接问答的 `queryKnowledgeBaseStream()` 按单行 `\n` 解析 `data:` 前缀，只支持默认内容事件；笔记中该方法当前似乎没有页面直接调用。

## 13. 前端如何避免每个 chunk 都触发一次 React 重渲染？

**回答：** 页面先把连续到达的 chunk 累积到 `pendingChunksRef`，再使用 `requestAnimationFrame` 在下一帧批量更新；更新时通过 React `useTransition()` 的 `startTransition` 标记为低优先级。这样可以保留流式打字机效果，同时避免 token 到达频率过高导致页面卡顿。
