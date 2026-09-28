# RAG 聊天会话与消息回填

## 1. RAG 聊天会话是怎么创建的？

**回答：** 创建会话只是一个 `@Transactional` 数据库操作：先校验传入的知识库 ID 是否全部存在，再创建 `RagChatSessionEntity`。标题有传入值就直接使用，没有时根据知识库名称自动生成；随后通过 `Set<KnowledgeBaseEntity>` 建立多对多关联，使用 JPA 自增主键保存，最后由 MapStruct 转成 `SessionDTO` 返回。创建阶段不调用 LLM，也不使用缓存。

## 2. RAG 会话和普通模拟面试会话有什么本质区别？

**回答：** RAG 会话创建时只是一个包含标题和知识库关联的空壳容器，问题由用户后续逐条提出，LLM 结合知识库回答。普通面试会话创建时会检查未完成会话、生成自定义 sessionId、读取历史题目去重，并调用 LLM 预先生成题目，再写 Redis 和 PostgreSQL；其交互方向是 LLM 出题、用户回答并接受评估。

## 3. 有会话的 RAG 问答为什么只提供流式接口？

**回答：** 会话场景需要持续展示 LLM 回答、携带 trace 事件，并在流结束后保存完整 AI 消息，所以使用 `POST /api/rag-chat/sessions/{sessionId}/messages/stream` 返回 `Flux<ServerSentEvent<String>>`。流程被拆成同步准备、流式输出和回调收尾三个阶段。

## 4. prepareStreamMessage 会预先保存哪些数据？

**回答：** 方法通过 `findByIdWithKnowledgeBases(sessionId)` 加载会话与知识库，读取当前消息总数作为 `nextOrder`。先保存一条 USER 消息，content 为问题、order 为 nextOrder、completed 为 true；再保存一条 ASSISTANT 占位消息，content 为空、order 为 nextOrder+1、completed 为 false。最后将会话 `messageCount` 更新为 nextOrder+2，并返回 AI 占位消息的数据库 ID。

## 5. 为什么要采用“先占位、后回填”的消息模式？

**回答：** LLM 流式输出时，答案逐块异步到达，开始响应时还不知道最终内容，无法直接保存完整 AI 消息。先插入空的 ASSISTANT 行可以立即获得稳定 `messageId`，让消息顺序和状态先落库；流完成或异常后再用这个 ID 更新内容，解决流式响应与数据库持久化之间的时序冲突。

<br />

就是先在数据库中创建这行数据，等llm生成完答案之后再把答案填进去，其实就是两次落库而已，第一次落库问题，第二次落库答案。

## 6. RAG 会话的历史消息从哪里加载？

**回答：** `getStreamAnswerWithTrace()` 重新加载会话及关联知识库 ID。如果 `history.enabled=true`，就从 `RagChatMessageRepository` 查询该会话最近已经完成的 user/assistant 消息对并转换为 history；配置关闭或没有历史时传空列表。只有 completed 的消息参与上下文，避免把尚未回填的 AI 占位行传给模型。

<br />

从数据库中查询历史消息，可以在数据库中维护一个表，通过会话id关联到会话表，每次问答完就把历史问题拼到一起；当然也可以从问题表来查询，整合，比如查询问题表中已经有的这个会话的所以问题，然后合并成一个字符串。

<br />

## 7. 会话历史如何参与本轮 RAG 流程？

**回答：** Controller 把 sessionId、知识库 ID、当前问题和 history 交给 `answerQuestionStreamWithTrace()`。历史一方面参与问题改写，帮助消解“那第二种呢”这类指代；另一方面在 PromptSpec 中通过 `.messages(effectiveHistory)` 注入模型上下文。向量检索、文档拼接、防注入 Prompt 和探测窗口仍与直接流式问答复用同一套实现。

## 8. 为什么要先发送 event:trace 事件？

**回答：** Controller 用 `ServerSentEvent.builder().event("trace")` 构造自定义事件，把 `{"traceId":"xxx"}` 放入 data。它在任何回答内容之前到达前端，前端可以立即把 traceId 绑定到当前 AI 消息气泡，用户点击后跳转到可观测性平台查看本次检索与模型调用链路，而不会和普通回答文本混在一起。如果不分 event:trace 而直接混在 data 里，那就得约定一个特殊格式比如 \[TRACE:a3f2b01c] ，前端还得解析剔除，用户还可能看到乱七八糟的前缀。用命名事件就是干净地把 元数据 （traceId）和 业务数据 （回答文本）隔离开。

<br />

意思就是先发送一个事件声明，表示后面发送的data数据都是属于这个事件的，这样前端就可以根据这个标识来拿取信息。

<br />

## 9. Flux.concat 在会话流式响应中有什么作用？

**回答：** `Flux.concat` 按顺序连接两个 Flux：第一个 `Flux.just(traceEvent)` 只有一个 trace 元素，发出后立即完成；第二个是 LLM 的回答内容流。concat 保证前端一定先收到追踪信息，再收到默认 message 事件的文本片段，不会因为异步执行导致事件顺序混乱。

用concat的作用是确保先让第一个fulx标记事件的flux发送，第一个flux结束后再发第二个，因为两个flux是并发的，网络抖动可能导致发送顺序混乱。

<br />

<br />

## 10. 为什么回答 chunk 中的换行符必须转义？

**回答：** LLM 的 Markdown 内容可能包含真实 `\n` 和 `\r`。SSE 使用换行组织 `data:` 行，并用空行分隔事件，原始换行直接放入单个 data 字符串可能被前端误判为事件边界，导致 chunk 截断。因此后端把它们替换为字面量 `\\n` 和 `\\r`，保持 data 行完整，前端收到后再还原。

## 11. doOnNext(fullContent::append) 为什么不会改变响应内容？

**回答：** `doOnNext` 是 Reactor 的副作用操作符，每个 chunk 到达时只额外把它追加到 `StringBuilder fullContent`，不会把流元素转换成其他值。原 chunk 仍继续经过 `map` 包装为默认 `ServerSentEvent` 并推给前端，而累积结果留给流结束时回写数据库。

## 12. 流正常结束后如何回填 AI 消息？

**回答：** `doOnComplete` 触发后，Controller 使用准备阶段返回的 `messageId` 调用 `completeStreamMessage(messageId, fullContent.toString())`，把占位消息的 content 从空字符串更新为完整回答，并将 completed 从 false 改为 true。之后会话详情接口就能查询到本轮完整的 AI 消息。

<br />

llm回答完毕之后，流传输也结束了，通过dooncomplete回调方法把累积在stringbuilder中的答案填到数据库表中。

<br />

## 13. 流中途异常时如何避免消息丢失？

**回答：** `doOnError` 同样调用 `completeStreamMessage`。如果 `fullContent` 已经累积了一部分回答，就优先保存这部分内容；如果一个 chunk 都没有收到，则写入“回答生成失败：xxx”这类友好错误提示。这样 AI 消息不会永远保持空内容和 completed=false，也尽量保留用户已经看到的部分答案。

## 14. 前端如何消费 RAG 会话的流式响应？

**回答：** Spring WebFlux 以 `Transfer-Encoding: chunked` 持续输出 SSE。前端通过 `fetch`、`response.body.getReader()` 和 `TextDecoder` 读取字节流，按 `\n\n` 拆分事件；trace 事件进入 `onTrace` 并显示追踪链接，默认 data 事件还原换行后逐块累积到消息气泡，形成打字机效果。流结束后页面会刷新会话列表。
