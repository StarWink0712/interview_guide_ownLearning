# RagChatController SSE 流式消息完整调用链

> 接口：`POST /api/rag-chat/sessions/{sessionId}/messages/stream`  
> 当前问答页面的主路径，返回 `Flux<ServerSentEvent<String>>`

## 1. 前端提交前的准备

`KnowledgeBaseQueryPage.handleSubmitQuestion` 校验问题非空、至少选一个知识库且当前不在加载。

如果还没有会话，先调用 `ragChatApi.createSession`。得到 sessionId 后，前端立即在本地追加：

1. 用户消息；
2. 内容为空的 assistant 消息。

然后调用：

```ts
ragChatApi.sendMessageStream(
  sessionId,
  question,
  onMessage,
  onComplete,
  onError,
  onTrace
)
```

该 API 使用原生 `fetch`，不经过 Axios `Result<T>` 拦截器。

## 2. Controller 接收与校验

```java
@PostMapping(
  value="/api/rag-chat/sessions/{sessionId}/messages/stream",
  produces=TEXT_EVENT_STREAM_VALUE
)
public Flux<ServerSentEvent<String>> sendMessageStream(
    @PathVariable Long sessionId,
    @Valid @RequestBody SendMessageRequest request)
```

`question` 有 `@NotBlank`。这个昂贵接口当前没有 `@RateLimit`。

## 3. 第一段事务：保存用户消息和 AI 占位

Controller 在返回 Flux 前同步调用：

```java
Long messageId = sessionService.prepareStreamMessage(
    sessionId, question);
```

事务内完整顺序：

```text
sessionRepository.findByIdWithKnowledgeBases
  → nextOrder = session.messageCount
  → 保存 USER 消息
      order=nextOrder, completed=true
  → 保存 ASSISTANT 占位消息
      content="", order=nextOrder+1, completed=false
  → session.messageCount = nextOrder+2
  → 保存 session，刷新 updatedAt
  → 返回 assistantMessage.id
```

因此客户端收到第一个字节前，问题和占位回答已经提交数据库。页面本地消息与数据库消息是两份
对应状态，刷新页面时通过会话详情重新加载数据库版本。

## 4. 加载会话知识库与历史上下文

Controller 调用：

```java
sessionService.getStreamAnswerWithTrace(sessionId, question)
```

Service：

```text
sessionRepository.findByIdWithKnowledgeBases
  → session.getKnowledgeBaseIds()
  → 如果 history.enabled
      → loadHistoryMessages(sessionId)
```

历史查询取最近 `maxMessages + 1` 条已完成消息，按 messageOrder 倒序。因为当前用户消息刚保存且
`completed=true`，代码删除查询结果第一条，再反转为时间正序，转换为 Spring AI
`UserMessage/AssistantMessage`。

该逻辑假设“最新完成消息一定是当前问题”。同一会话并发发送两个问题时，messageCount 和历史
排除都可能竞态；目前没有悲观锁、乐观锁或会话级串行控制。

## 5. KnowledgeBaseQueryService 构建 RAG 流

调用：

```text
answerQuestionStreamWithTrace(
  sessionId, kbIds, question, history)
```

Service 先创建 traceId，再执行：

1. `KnowledgeBaseCountService.updateQuestionCounts` 验证知识库并批量增加参与次数。
2. 清洗历史消息。
3. 可选 LLM Query Rewrite；失败回退原问题。
4. 按问题长度决定 topK/minScore。
5. 对改写问题和原问题依次执行 pgvector 相似度检索。
6. 记录 retrieval trace、命中文档和 RAG 指标。
7. 无有效命中时返回固定无结果 Flux。
8. 有命中时拼接文档上下文，渲染系统/用户 Prompt，并追加防注入指令。
9. `ChatClient.stream().content()` 产生 token/chunk Flux。
10. `normalizeStreamOutput` 用前 120 字符探测无结果语义，再实时透传。

返回 `RagStreamResult(traceId, contentFlux)`。

## 6. Controller 组装 SSE 事件

先创建一个命名事件：

```text
event: trace
data: {"traceId":"..."}
```

然后对正文 Flux：

```text
doOnNext(fullContent::append)
  → 把原始 chunk 累积到 StringBuilder
  → chunk 中换行转义为字面量 \n / \r
  → ServerSentEvent.data(chunk)
```

最终使用 `Flux.concat(traceEvent, contentEvents)`，保证 trace 在正文前发送。

数据库保存的是转义前的 `fullContent`，SSE 转义只用于避免换行破坏事件格式。

## 7. 前端怎样解析和渲染

`ragChatApi.sendMessageStream`：

```text
fetch response.body.getReader()
  → TextDecoder 增量解码
  → 按空行切分 SSE event block
  → event:trace：JSON.parse 后调用 onTrace
  → 普通 data：把 \n/\r 还原并调用 onMessage
```

页面 `onMessage` 把 chunk 累积到 `fullContent`，使用 `requestAnimationFrame + startTransition`
批量更新最后一条 assistant 消息，减少高频 React 渲染。traceId 保存到该消息，支持跳转 AI
观测链路。

流正常结束时前端 `onComplete` 关闭 loading 并刷新会话列表。

## 8. 正常完成后的数据库回写

正文 Flux 的 `doOnComplete` 调用：

```text
sessionService.completeStreamMessage(
  assistantMessageId,
  fullContent.toString()
)
  → messageRepository.findById
  → content=完整回答
  → completed=true
  → save
```

这形成“用户消息 + AI 占位 → SSE → 完整 AI 消息”的持久化闭环。

## 9. 错误路径

### RAG/LLM 内部错误

`KnowledgeBaseQueryService` 大多把异常转换成普通 Flux 文本：

```text
【错误】知识库查询失败：...
```

这种 Flux 会正常 complete，所以 Controller 的 `doOnComplete` 把错误文本保存为已完成 assistant
消息，前端也走 `onMessage + onComplete`，不是 `onError`。

### Flux 真正触发 onError

Controller `doOnError` 会把已收到正文或错误文案写回占位消息。但 `doOnError` 只是旁路动作，
错误仍沿 Flux 传播，连接可能异常结束，前端 fetch/read catch 后调用 `onError`。

### 建流前失败

用户消息和占位已保存后，如果第二次查询会话时记录被删除，
`getStreamAnswerWithTrace` 会直接抛异常，Controller 无法返回正常 Flux；占位消息已经落库。

## 10. 客户端断开留下未完成占位

浏览器关闭页面、网络断开或主动取消会触发 Reactor cancel，不等于 complete，也不一定触发 error。
当前链路只有 `doOnComplete` 和 `doOnError`，没有 `doFinally(CANCEL)`/`doOnCancel` 补偿，所以：

```text
ASSISTANT placeholder completed=false
  → 可能永久保留空内容
```

Repository 虽然定义了 `findBySessionIdAndCompletedFalse`，当前没有恢复/清理任务使用它。

## 11. 当前实现边界

- SSE 无限流，可能产生高并发 LLM 和向量检索压力。
- 同一会话并发提问会竞争 `messageCount` 和“排除当前用户消息”的历史逻辑。
- 客户端 cancel 不回写占位消息。
- 多数业务错误作为普通 SSE 文本并正常完成，HTTP 状态不能表示回答成功。
- 前端对字面量 `\n` 做全局还原，回答中的代码字符串可能被误转换成真实换行。
- 消息准备事务和最终内容回写事务分离，中间状态需要显式恢复策略。

## 流程总结

`sendMessageStream` 是 RAG 聊天模块最核心的方法，采用三步提交模式将同步事务和异步流式输出解耦。第一步在 Controller 返回 Flux 之前同步执行 `sessionService.prepareStreamMessage`——在事务中通过 `findByIdWithKnowledgeBases` 加载会话，根据 `messageCount` 获取当前序号后依次保存用户消息（type=USER, completed=true, order=N）和 AI 占位消息（type=ASSISTANT, content="", completed=false, order=N+1），将 `messageCount` 加 2 并保存会话，返回 AI 占位消息的 ID。这一步确保即使后续流式崩溃，用户问题已落库。第二步调用 `sessionService.getStreamAnswerWithTrace` 获取流式回答——先重新加载会话拿到知识库 ID 列表，如果配置启用了多轮上下文则通过 `loadHistoryMessages` 查询最近 `maxMessages+1` 条已完成消息、排除当前轮用户消息后反转为正序并转换为 Spring AI 的 Message 类型，然后委托 `KnowledgeBaseQueryService.answerQuestionStreamWithTrace` 执行完整的 RAG 流程（Query Rewrite→向量检索→Prompt 组装→LLM 流式调用→探测窗口归一化），返回 `RagStreamResult` 包含 traceId 和 `Flux<String>`。第三步由 Controller 将 traceEvent（event:"trace" data:{"traceId":"xxx"}）和正文 Flux 通过 `Flux.concat` 拼接为 SSE 事件流，正文每个 chunk 先累积到 `StringBuilder` 再转义换行符后包装为 `ServerSentEvent`，流正常结束时在 `doOnComplete` 回调里调用 `sessionService.completeStreamMessage` 将完整内容写入 AI 占位消息并标记 completed=true，异常时在 `doOnError` 回调里将已接收的部分内容或错误信息写入占位。前端通过原生 fetch 读取 SSE 流，按空行切分事件块后解析：trace 事件提取 traceId 用于跳转 AI 观测链路，普通 data 事件将 `\n` 还原后逐块渲染到聊天界面的最后一条 assistant 消息上形成打字机效果，流正常结束关闭 loading 并刷新会话列表。这套"同步落库占位 → 异步 SSE 推送 → 回调补全内容"的三步模式，保证了消息历史的最终完整性——无论用户中途关闭浏览器、网络断开还是 LLM 生成失败，数据库里至少能找到用户问了什么问题、AI 是否已回答。
