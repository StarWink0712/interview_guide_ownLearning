# deleteKnowledgeBase：跨存储删除完整调用链

> 接口：`DELETE /api/knowledgebase/{id}`  
> 当前前端调用：`KnowledgeBaseManagePage.handleDelete`

## 1. 前端触发

用户点击删除后先打开 `DeleteConfirmDialog`。确认时：

```text
setDeleting(true)
  → knowledgeBaseApi.deleteKnowledgeBase(id)
  → request.delete('/api/knowledgebase/{id}')
  → 成功后关闭确认框
  → await loadData() 刷新统计、列表和分类
```

失败只写 `console.error`，页面没有错误提示。

## 2. Controller 层

```java
@DeleteMapping("/api/knowledgebase/{id}")
public Result<Void> deleteKnowledgeBase(@PathVariable Long id) {
  deleteService.deleteKnowledgeBase(id);
  return Result.success(null);
}
```

Controller 把完整删除编排交给 `KnowledgeBaseDeleteService`。

## 3. Service 第一步：查询元数据

```text
KnowledgeBaseRepository.findById(id)
  → 不存在：BusinessException(NOT_FOUND, "知识库不存在")
  → 存在：取得 storageKey 等删除信息
```

## 4. Service 第二步：解除 RAG 会话关联

知识库与 RAG 会话是多对多关系，必须先清理关联表：

```text
RagChatSessionRepository.findByKnowledgeBaseIds([id])
  → 遍历每个会话
  → session.knowledgeBases.removeIf(kb.id == id)
  → sessionRepository.save(session)
```

删除知识库不会删除聊天会话和历史消息；会话保留，但关联知识库集合可能变空。

这里是逐会话查询结果后循环保存，存在 N 次数据库写入。`deleteKnowledgeBase` 本身没有
`@Transactional`，每次 Repository save 是独立边界，中途失败会留下部分会话已解绑、部分未解绑。

## 5. Service 第三步：删除 pgvector 数据

```text
KnowledgeBaseVectorService.deleteByKnowledgeBaseId(id)
  → VectorRepository.deleteByKnowledgeBaseId
  → DELETE FROM vector_store
     WHERE metadata->>'kb_id' = id
        OR metadata->>'kb_id_long' = id
```

需要注意，`KnowledgeBaseVectorService.deleteByKnowledgeBaseId` 自己已经捕获并吞掉底层异常，
只记录日志。因此 DeleteService 外层的 try/catch 通常观察不到向量删除失败，流程仍继续。

## 6. Service 第四步：删除 RustFS 文件

```text
storageService.deleteKnowledgeBase(kb.storageKey)
```

对象存储删除异常被 catch，只记录警告，然后继续删除数据库记录。这样优先保证管理列表中记录
消失，但可能留下 RustFS 孤儿文件。

## 7. Service 第五步：删除数据库元数据

```java
knowledgeBaseRepository.deleteById(id);
```

成功后 Controller 返回 `Result.success(null)`。前端重新加载后列表项消失。

Service 注释写“在事务中”，但方法本身没有 `@Transactional`；真正只有 Repository 方法自己的
事务。会话解绑、向量删除、文件删除和元数据删除不在同一个原子事务中。

## 8. 失败后的部分状态

| 失败位置 | 已发生动作 | 结果 |
|---|---|---|
| 查询不到 ID | 无 | 返回业务错误 |
| 会话解绑中途失败 | 部分会话已保存 | 后续删除停止，关联不一致 |
| 向量删除失败 | 会话已解绑 | 异常被吞，继续，可能留孤儿向量 |
| RustFS 删除失败 | 会话已解绑，向量可能已删 | 继续，可能留孤儿文件 |
| 数据库删除失败 | 外部数据可能已删 | 元数据仍在，但文件/向量可能缺失 |

跨 PostgreSQL、pgvector 和 RustFS 无法靠单个本地事务完全原子化，需要幂等清理、补偿任务或
删除状态机。

## 9. 与正在运行的向量任务竞态

删除接口没有取消统一异步任务或 Stream 消息。

### Consumer 尚未开始

```text
删除数据库记录
  → Consumer 之后 findById 为空
  → AsyncTaskCancelledException
  → 异步任务 CANCELLED
  → ACK，不再向量化
```

### Consumer 已经开始

如果 Consumer 已经加载 Entity、下载文件并进入 Embedding：

```text
删除接口先删除现有向量和元数据
  → 运行中的 Consumer 继续 VectorStore.add
  → 删除完成后可能重新写入没有知识库元数据的孤儿向量
  → Consumer 更新知识库状态时 findById 为空，静默跳过
```

这是当前真实竞态。删除前应协调取消/等待活跃 `KB_VECTORIZE` 任务，或在向量写入前再次校验
实体存在并使用可识别的删除状态。

## 10. 删除后的 RAG 会话

会话历史仍可加载，但知识库关联被移除。若会话关联集合变空，再发送问题时
`KnowledgeBaseQueryService` 会返回固定“未检索到相关信息”文本；前端当前没有专门提示“原知识库
已删除”。

## 11. 当前实现边界

- 删除不是事务闭环，而是尽力执行的多资源串行流程。
- 会话解绑循环写库，失败会部分完成。
- 向量与文件删除失败被降级，可能留下孤儿数据。
- 数据库最终删除失败时可能留下指向已删除文件/向量的记录。
- 不协调活跃向量化任务，可能在删除后重新产生孤儿向量。

## 流程总结

用户在管理页点击删除并确认后，`DELETE /api/knowledgebase/{id}` 进入 `KnowledgeBaseDeleteService.deleteKnowledgeBase`，按严格顺序执行五步跨存储的清理操作。第一步通过 `KnowledgeBaseRepository.findById` 查询元数据，不存在时抛出 `BusinessException(NOT_FOUND)`。第二步解除 RAG 会话的多对多关联——通过 `RagChatSessionRepository.findByKnowledgeBaseIds` 查找所有关联该知识库的会话，遍历每个会话从 `knowledgeBases` 集合中移除该实体后 `save`，这一步必须放在最前面因为外键约束会阻止直接删除知识库；注意此处循环保存可能部分失败导致关联不一致。第三步通过 `KnowledgeBaseVectorService.deleteByKnowledgeBaseId` 执行 `DELETE FROM vector_store WHERE metadata->>'kb_id' = ?` 清理 pgvector 中的向量数据，该方法内部已静默吞异常所以流程必继续。第四步通过 `FileStorageService.deleteKnowledgeBase` 删除 RustFS 对象存储中的原始文件，同样容错处理不阻断。第五步通过 `knowledgeBaseRepository.deleteById` 删除数据库中的知识库记录和 JPA 级联数据，会话的消息通过 `cascade=ALL, orphanRemoval=true` 随会话保留——删除的是知识库而非会话。整个删除流程遵循"外部资源软容错 + 数据库最后删除"的设计原则，二三四步失败只记日志不抛异常，保证管理页面记录一定会消失，但可能留下 RustFS 孤儿文件和 pgvector 孤儿向量需要定时任务兜底清理。此外删除操作不与正在运行的向量化消费者协调——如果 Consumer 已在处理该知识库，删除后它仍可能将新向量写入 pgvector 产生孤儿数据。
