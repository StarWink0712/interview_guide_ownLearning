# RagChatController 会话生命周期完整调用链

> 本篇覆盖创建、列表、详情、标题、置顶、知识库关联更新和删除；SSE 消息单独见 07 文档。

## 1. 接口与调用情况

| 方法 | 接口 | 当前页面调用 |
|---|---|---|
| `createSession` | `POST /api/rag-chat/sessions` | 首次提问时 |
| `listSessions` | `GET /api/rag-chat/sessions` | 问答页首次加载及操作后刷新 |
| `getSessionDetail` | `GET /api/rag-chat/sessions/{id}` | 点击历史会话 |
| `updateSessionTitle` | `PUT /{id}/title` | 编辑会话标题 |
| `togglePin` | `PUT /{id}/pin` | 置顶/取消置顶 |
| `updateSessionKnowledgeBases` | `PUT /{id}/knowledge-bases` | API 已封装，页面未调用 |
| `deleteSession` | `DELETE /{id}` | 删除会话 |

所有 Request record 都通过 `@Valid` 校验；这些接口当前都没有 `@RateLimit`。

## 2. 页面中的会话状态

`KnowledgeBaseQueryPage` 同时维护：

- `sessions`：左侧会话列表；
- `currentSessionId/currentSessionTitle`：当前会话；
- `selectedKbIds`：当前选中的知识库；
- `messages`：当前会话消息。

用户在未选会话时提交第一个问题，页面先创建会话，再发送 SSE 消息。用户修改知识库选择时，
当前实现直接清空当前会话和消息，下一次提问新建会话，而不是调用
`updateSessionKnowledgeBases`。

## 3. createSession

前端：

```text
handleSubmitQuestion
  → currentSessionId 为空
  → ragChatApi.createSession(Array.from(selectedKbIds))
  → POST /api/rag-chat/sessions
  → body { knowledgeBaseIds, title?: undefined }
```

`CreateSessionRequest.knowledgeBaseIds` 有 `@NotEmpty`。

Service 在事务中：

```text
knowledgeBaseRepository.findAllById(ids)
  → 返回数量 != 请求数组长度：抛“部分知识库不存在”
  → 构造 RagChatSessionEntity
  → title 有值则使用；否则 generateTitle
      0 个：新对话
      1 个：知识库名称
      多个：N 个知识库对话
  → knowledgeBases 转 HashSet 建立多对多关联
  → sessionRepository.save
  → RagChatMapper.toSessionDTO
```

请求 ID 如果包含重复项，`findAllById` 通常只返回唯一 Entity，数量比较也会把它误判为“部分不存在”。

成功后页面保存新会话 ID/标题，并继续发送当前问题；会话列表要等 SSE 完成回调中的
`loadSessions()` 才刷新。

## 4. listSessions

```text
ragChatApi.listSessions
  → Controller sessionService.listSessions
  → repository.findAllOrderByPinnedAndUpdatedAtDesc
  → pinned DESC, updatedAt DESC
  → RagChatMapper.toSessionListItemDTO
```

列表项包括标题、消息数、知识库名称、更新时间和置顶状态。当前没有分页，返回所有会话。

页面首次挂载调用；删除、改标题、切换置顶和 SSE 完成后也会刷新。

## 5. getSessionDetail

用户点击左侧会话：

```text
ragChatApi.getSessionDetail(id)
  → sessionRepository.findByIdWithKnowledgeBases(id)
  → messageRepository.findBySessionIdOrderByMessageOrderAsc(id)
  → KnowledgeBaseMapper 转关联知识库
  → RagChatMapper 组装 SessionDetailDTO
```

Service 故意分两次查询，避免同时 fetch 多个集合造成笛卡尔积。不存在时抛业务异常。

前端用详情恢复当前 ID、标题、知识库选择和按时间排序的消息列表。

## 6. updateSessionTitle

前端要求 trim 后非空，再调用：

```text
PUT /api/rag-chat/sessions/{id}/title
body { title }
```

`UpdateTitleRequest.title` 有 `@NotBlank`。Service 事务内查询、更新 title、保存；成功后页面刷新
列表，并同步当前会话标题。

没有长度限制，最终约束取决于数据库列定义。

## 7. togglePin

```text
PUT /api/rag-chat/sessions/{id}/pin
  → Service findById
  → null 按 false 处理
  → isPinned = !current
  → save
  → 页面重新 listSessions
```

这是“切换”而不是显式设置目标值。两个并发请求可能互相抵消；重试请求也不是幂等操作。

## 8. updateSessionKnowledgeBases

Controller 使用 `@Valid UpdateKnowledgeBasesRequest`，只保证列表非空。Service：

```text
find session
  → knowledgeBaseRepository.findAllById(requestIds)
  → new HashSet(foundEntities)
  → 替换会话关联并保存
```

与创建会话不同，它没有比较“查询结果数量 == 请求 ID 数量”。不存在的 ID 会被静默忽略，接口
仍成功；如果只有部分存在，会话最终只关联存在的子集。

当前前端没有调用此 API。用户改变知识库选择时会退出当前会话并创建新会话。

## 9. deleteSession

页面先打开确认状态，然后：

```text
ragChatApi.deleteSession(id)
  → Service.existsById
  → 不存在：BusinessException
  → sessionRepository.deleteById
```

会话 Entity 的消息关系配置 `cascade=ALL, orphanRemoval=true`，所以消息随会话删除；多对多关联表
行也会清理，知识库实体本身不会删除。

成功后页面刷新会话列表；如果删的是当前会话，调用 `handleNewSession` 清空当前 ID、标题和消息。

## 10. 响应与错误

这些接口都返回 `Result<T>`，经 Axios 统一解包。Service 使用 `BusinessException` 表示会话或
知识库不存在，Controller 不捕获。

当前页面 catch 基本只写 `console.error`，没有用户可见错误状态，因此操作失败时可能表现为界面
没有变化。

## 11. 当前实现边界

- 会话列表无分页。
- RAG 会话管理和昂贵 SSE 均无限流、无权限校验。
- 创建会话对重复知识库 ID 会误报缺失。
- 更新知识库关联不验证全部 ID，缺失项静默丢弃。
- `togglePin` 是非幂等 toggle，网络重试可能产生意外结果。
- 页面没有使用更新知识库关联 API，而是选择变化后新建会话。

## 流程总结

RAG 聊天的会话管理围绕 `RagChatSessionService` 提供了完整的 CRUD 接口。`createSession`（POST /api/rag-chat/sessions）接收知识库 ID 列表，在事务中通过 `findAllById` 校验全部知识库存在性（数量不匹配抛异常），然后生成标题——用户提供则用自定义、单个知识库用其名称、多个则显示"N 个知识库对话"，以 HashSet 建立多对多关联后 `sessionRepository.save`，返回包含 id/title/knowledgeBaseIds/createdAt 的 `SessionDTO`。`listSessions`（GET /api/rag-chat/sessions）通过 `findAllByOrderByPinnedAndUpdatedAtDesc` 置顶优先+时间降序排列，MapStruct 转换为包含标题、消息数、知识库名称、置顶状态的列表项 DTO，页面首次加载及各项操作后都会刷新此列表。`getSessionDetail`（GET /api/rag-chat/sessions/{id}）采用分两次查询的设计——先用 `findByIdWithKnowledgeBases` JOIN FETCH 会话和知识库，再单独用 `findBySessionIdOrderByMessageOrderAsc` 查询消息列表，避免了会话-知识库-消息三表的笛卡尔积膨胀，最终通过 `RagChatMapper` 组装包含知识库详情列表和消息历史的完整 `SessionDetailDTO`。`updateSessionTitle` 是单字段事务更新操作，`togglePin` 则是 null-safe 的布尔值翻转（null 视为 false），`updateSessionKnowledgeBases` 替换会话关联的知识库集（但不校验请求 ID 是否全部存在，缺失项静默忽略），`deleteSession` 通过 `existsById` 前置校验后 `deleteById`，JPA 的 `cascade=ALL, orphanRemoval=true` 自动级联删除所有关联消息并清理多对多中间表记录，知识库实体本身不受影响。当前页面实际只用到创建、列表、详情、标题修改、置顶切换和删除六个接口，更新知识库关联尚未集成。
