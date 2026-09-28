# AI 实施计划：事务边界与数据一致性

## 一、执行前提

请先阅读：

- `AGENTS.md` 中事务规则。
- `ResumeUploadService.java`
- `KnowledgeBaseUploadService.java`
- `KnowledgeBaseVectorService.java`
- `ResumePersistenceService.java`
- `KnowledgeBasePersistenceService.java`
- `AbstractStreamProducer.java`

目标是缩小事务范围，不是大规模重构业务。

## 二、第一阶段：梳理事务方法清单

扫描所有 `@Transactional` 方法，分类：

- 纯 DB 操作：保留。
- 包含 LLM 调用：必须拆。
- 包含 S3/对象存储：必须拆。
- 包含 Redis 入队：建议拆到 afterCommit 或 Outbox。
- 包含 Embedding：必须重点评估。

输出一个临时清单到：

```text
docs/transaction-boundary-audit.md
```

## 三、第二阶段：改造 reanalyze

目标文件：

- `ResumeUploadService.java`

改造思路：

1. 移除 `reanalyze` 方法上的大事务。
2. 如果需要从对象存储下载并解析，放在事务外。
3. 新增 `ResumePersistenceService.prepareReanalyze(...)`：
   - 更新 resumeText。
   - 设置状态 PENDING。
   - 清空错误信息。
4. 事务提交后发送 Stream。

验收：

- 重新分析时对象存储下载失败不会开启长事务。
- DB 状态更新和 Stream 入队逻辑清晰分离。

## 四、第三阶段：改造 revectorize

目标文件：

- `KnowledgeBaseUploadService.java`
- `KnowledgeBasePersistenceService.java`

思路同 reanalyze：

1. 对象存储下载和解析在事务外。
2. 状态更新在事务内。
3. Stream 入队在事务提交后。

## 五、第四阶段：改造 vectorizeAndStore

目标文件：

- `KnowledgeBaseVectorService.java`

改造目标：

- 不要让整个 `vectorizeAndStore` 持有事务。
- `deleteByKnowledgeBaseId` 保持短事务。
- `vectorStore.add` 如果不可拆，至少不要在外层事务中调用。

可选增强：

- 先写入新向量 batch，成功后再删除旧向量需要有版本字段支持。
- 或使用 `kb_id + vector_version`，切换 active version，避免删除旧数据后新数据失败。

第一版可先做到：

- 移除 `vectorizeAndStore` 的 `@Transactional`。
- 保留 `deleteByKnowledgeBaseId` 的短事务。
- 失败时明确更新 FAILED。

## 六、第五阶段：对象存储补偿

目标文件：

- `ResumeUploadService.java`
- `KnowledgeBaseUploadService.java`
- `FileStorageService.java`

上传对象存储成功后，如果 DB 保存失败：

- catch 异常。
- 调用 storage 删除刚上传的 fileKey。
- 抛出 `BusinessException`。

注意：

- 删除失败只打 warn，不覆盖原始异常。
- 不要吞掉原始 DB 错误。

## 七、第六阶段：afterCommit 入队

新增工具类：

```text
common/transaction/TransactionAfterCommitExecutor.java
```

能力：

- 如果当前存在事务，则注册 afterCommit 回调。
- 如果当前没有事务，则直接执行。

使用场景：

- 保存简历成功后发送分析任务。
- 保存知识库成功后发送向量化任务。
- 保存面试结束状态后发送评估任务。

## 八、第七阶段：Outbox 可选升级

如果要进一步增强：

新增：

- `OutboxEventEntity`
- `OutboxEventRepository`
- `OutboxEventService`
- `OutboxEventPublisher`

先不要替换所有 Producer。可以先在知识库向量化任务上试点。

## 九、测试要求

新增或修改测试：

- DB 保存失败时对象存储补偿删除。
- afterCommit 在事务提交后才发送任务。
- 事务回滚时不发送任务。
- vectorizeAndStore 失败时状态为 FAILED，旧事务不长时间占用连接。

## 十、验收标准

完成后必须满足：

- 没有事务方法直接调用 LLM。
- 没有事务方法直接执行对象存储下载/上传。
- 重要异步任务入队发生在事务提交后。
- 上传 DB 失败时不会留下明显孤儿文件。
- 重新分析和重新向量化流程能正常跑通。

