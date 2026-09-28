# uploadKnowledgeBase 与 revectorize 完整调用链

## 1. 两个接口

| 方法 | 接口 | 前端入口 | 限流 |
|---|---|---|---|
| `uploadKnowledgeBase` | `POST /api/knowledgebase/upload` | `KnowledgeBaseUploadPage` | GLOBAL 3/s + IP 3/s |
| `revectorize` | `POST /api/knowledgebase/{id}/revectorize` | 管理页失败任务重试 | GLOBAL 2/s + IP 2/s |

两条限流都由 `RateLimitAspect` 在 Controller 前通过 Redis Lua 滑动窗口执行；任一维度失败都
不会进入 Controller。

## 2. 上传：前端到 Controller

`KnowledgeBaseUploadPage.handleUpload` 调用：

```text
knowledgeBaseApi.uploadKnowledgeBase(file, name)
  → FormData.append("file", file)
  → name 非空时 append("name", name)
  → category 非空时才会 append，但当前页面未传 category
  → request.upload('/api/knowledgebase/upload', formData)
```

`request.upload` 使用 `multipart/form-data` 和 5 分钟超时。

Controller 绑定：

```java
@RequestParam("file") MultipartFile file
@RequestParam(required=false) String name
@RequestParam(required=false) String category
```

随后直接调用 `uploadService.uploadKnowledgeBase(file, name, category)`。

## 3. 上传 Service：验证、类型检测和去重

### 文件验证

```text
FileValidationService.validateFile(file, 50MB, "知识库")
  → 空文件、大小和基础合法性校验
```

### 类型检测

```text
KnowledgeBaseParseService.detectContentType(file)
  → ContentTypeDetectionService
  → validateContentType
  → 允许 PDF、DOC/DOCX、TXT、MD 等
```

### 哈希去重

```text
FileHashService.calculateHash(file)
  → KnowledgeBaseRepository.findByFileHash(hash)
```

命中重复文件时不会重新解析、上传或向量化，而是：

```text
KnowledgeBasePersistenceService.handleDuplicateKnowledgeBase
  → accessCount + 1
  → 保存旧 Entity
  → 返回 duplicate=true 和旧 storage 信息
```

重复响应没有返回 `category`、`vectorStatus` 等新上传分支字段，与前端声明的统一响应结构并不
完全一致；如果旧记录本来向量化失败，也不会自动重试。

## 4. 新文件：解析、RustFS 和数据库

未重复时：

```text
parseService.parseContent(file)
  → DocumentParseService 使用 Tika/解析器提取文本
  → 空文本抛 BusinessException
```

然后调用外部对象存储：

```text
storageService.uploadKnowledgeBase(file)
  → 得到 fileKey
storageService.getFileUrl(fileKey)
  → 得到 fileUrl
```

再调用有事务的持久化服务：

```text
persistenceService.saveKnowledgeBase
  → 构造 KnowledgeBaseEntity
  → name 为空时从原文件名去扩展名
  → category 空白时保存 null
  → 保存 hash、文件元数据、storageKey/storageUrl
  → KnowledgeBaseRepository.save
```

Entity 默认向量状态为 `PENDING`。数据库保存失败时，UploadService 调用
`storageService.deleteKnowledgeBase(fileKey)` 补偿删除 RustFS 文件；补偿失败只记录警告。

## 5. 新文件：创建向量化异步任务

元数据保存完成后：

```java
vectorizeStreamProducer.sendVectorizeTask(savedKb.getId(), content);
```

Producer 不把全文写进 Stream：

```text
AsyncTaskService.createOrReusePendingTask
  → taskType=KB_VECTORIZE
  → businessId=kbId
  → payloadJson 只记录 kbId 和 contentLength
  → 已有活跃任务则跳过
  → 新建 VectorizeTaskPayload(taskId, kbId, retryCount=0)
```

`sendVectorizeTask` 调用时，`saveKnowledgeBase` 的事务已经结束，所以
`TransactionAfterCommitExecutor.execute` 检测不到活动事务，会立即调用：

```text
AbstractStreamProducer.sendTask
  → XADD knowledgebase:vectorize:stream
  → 消息字段 taskId/businessId/kbId/retryCount/traceId
  → AsyncTaskService.markEnqueued
```

## 6. 入队失败仍可能返回上传成功

`AbstractStreamProducer.sendTask` 捕获 XADD 异常，调用 `onSendFailed`：

```text
KnowledgeBaseEntity.vectorStatus=FAILED
  → 记录 vectorError
  → AsyncTaskService.markFailed
  → sendTask 返回 null
```

异常不会继续抛给 UploadService。UploadService 随后仍返回硬编码
`vectorStatus=PENDING` 的成功响应。因此前端可能收到“上传成功、PENDING”，而数据库实际已经
是 `FAILED`；管理页下一次加载才会看到真实状态。

## 7. Consumer：从 PENDING 到 COMPLETED

`VectorizeStreamConsumer` 拉取消息后，父类模板调用：

```text
parsePayload(taskId, kbId)
  → AsyncTaskService.markProcessing
  → KnowledgeBaseEntity.vectorStatus=PROCESSING
  → processBusiness
```

`processBusiness`：

1. 查询知识库；已删除则抛 `AsyncTaskCancelledException` 并 ACK。
2. 如果状态已完成且 `chunkCount>0`，按幂等成功跳过。
3. 从 RustFS 重新下载原文件并再次解析文本。
4. 调用 `KnowledgeBaseVectorService.vectorizeAndStore(kbId, content)`。

但当前向量化成功路径没有任何代码调用 `KnowledgeBaseEntity.setChunkCount`，该字段默认保持 0。
因此第 2 步的幂等条件在正常流程中实际上无法成立，重复消息仍会删除并重建全部向量。

向量化内部：

```text
删除旧向量
  → TokenTextSplitter 默认约 800 tokens 分块
  → 每个 Document 写 metadata.kb_id
  → 每批最多 10 个 chunk
  → VectorStore.add 调用 Embedding 并写 pgvector
```

成功后统一任务和知识库状态改成 `COMPLETED` 并 ACK。失败时最多自动重试 3 次；耗尽后统一
任务进入 `DEAD_LETTER`，知识库状态进入 `FAILED`。

上传阶段解析全文是为了提前验证内容；Consumer 不复用该内容，而是再次下载解析，所以存在重复
解析成本。

## 8. 上传响应回到页面

Controller 返回 `Result.success(Map)`，Axios 拦截器提取 data。上传页调用 `onUploadSuccess`，
`App` 随后导航到知识库管理页。

管理页发现 `PENDING`/`PROCESSING` 项时，每 5 秒调用列表、分类和统计接口，直到向量状态终止。

## 9. 手动重新向量化

管理页点击失败项的重试按钮：

```text
handleRevectorize(id)
  → knowledgeBaseApi.revectorize(id)
  → POST /api/knowledgebase/{id}/revectorize
  → Controller uploadService.revectorize(id)
```

Service 顺序：

```text
persistenceService.findById
  → RustFS 下载并解析
  → 空文本则失败，不修改状态
  → persistenceService.updateVectorStatusToPending
  → vectorizeStreamProducer.sendVectorizeTask
```

成功响应后页面立即静默刷新，随后按 5 秒轮询。

与上传相同，Producer 入队失败会内部标记 `FAILED` 但不向上抛，因此 revectorize 接口仍可能
返回成功。此外，状态先改成 `PENDING` 后才发消息，二者不是原子操作。

## 10. 当前实现边界

- 上传解析与 Consumer 下载解析重复。
- Stream 发送失败被 Producer 吞掉，Controller 仍可能返回成功。
- 新上传响应硬编码 `PENDING`，可能和数据库 `FAILED` 不一致。
- 重复文件只增加访问次数，不检查或修复旧记录的失败向量状态。
- `chunkCount` 没有在向量化成功后更新，Consumer 的完成态幂等判断基本失效。
- `TransactionAfterCommitExecutor` 在这两条真实调用链中实际立即执行。
- 向量化先删除全部旧向量再分批写入；中途失败会留下不完整向量集，之后依赖自动/手动重试重建。

## 流程总结

用户在前端选择文件上传后，请求经双重限流到达 `KnowledgeBaseUploadService`。该方法作为总指挥按序编排七步：`FileValidationService` 校验文件大小→ `KnowledgeBaseParseService` 通过 Apache Tika 检测类型→ `FileHashService` 计算 SHA-256 哈希去重（命中则 `PersistenceService.handleDuplicate` 累加访问计数直接返回）→ Tika 提取纯文本→ `FileStorageService` 上传原始文件到 RustFS 得到 key 和 url→ `PersistenceService.saveKnowledgeBase` 在事务中将元数据写入 PostgreSQL（状态 PENDING，入库失败补偿删除 RustFS 文件）→ `VectorizeStreamProducer.sendVectorizeTask` 将任务发送到 Redis Stream。另一端的 `VectorizeStreamConsumer` 在独立线程中异步消费：从 RustFS 下载原始文件并重新解析→ `TokenTextSplitter` 分块→ 调用 DashScope Embedding API 转为 1024 维向量→ 存入 pgvector，完成后状态变为 COMPLETED。前端通过轮询每 5 秒检查直到完成，失败后用户可点击重新向量化触发完整的重试闭环。
