# Provider 管理、默认切换与热重载

## 1. 模块基础讲解

`LlmProviderController` 提供 15 个接口，Controller 本身只负责路由、`@Valid`、限流和统一响应，
符合项目的薄 Controller 规范。Provider 管理的主要路径包括：

- `GET /api/llm-provider/list`：查询全部配置，API Key 只返回脱敏值。
- `GET /api/llm-provider/{id}`：查询详情。
- `POST /api/llm-provider`：创建并加密密钥。
- `PUT /api/llm-provider/{id}`：PATCH 风格的部分更新。
- `DELETE /api/llm-provider/{id}`：禁止删除任一默认 Provider。
- `POST /api/llm-provider/{id}/test`：发送最小 Chat Completions 请求。
- `POST /api/llm-provider/reload`：清空运行时缓存。
- 默认 Chat/Embedding 查询和切换接口。
- ASR/TTS 查询、修改及 ASR TCP 探测接口。

数据库创建流程：校验必填字段 -> 推导 Embedding 能力 -> 校验模型与维度 -> AES-GCM 加密 API Key
-> 保存 Entity -> `registry.reload()`。

更新采用“非 null 才修改”：`embeddingModel=""` 被视为清空；baseUrl、model、apiKey 的空字符串则
被拒绝。删除前检查全局默认 ID，默认 Provider 必须先切换。

接口都只有 GLOBAL 维度限流。限流能降低误操作频率，但不能替代管理员认证、授权和审计。

## 2. 基础面试问题

**问题：为什么更新请求全部使用可空字段？**

回答：为了支持部分更新，未传字段表示保持原值。当前虽然 HTTP 方法是 PUT，实际语义更接近 PATCH。
需要明确“null 不修改、空字符串在不同字段上的语义”，否则前后端容易误解。

**问题：为什么返回 DTO 而不是 Entity？**

回答：Entity 包含密文、nonce、内部状态和时间戳，直接返回会泄露持久化结构。DTO 只暴露必要字段，
API Key 经过脱敏，并附加是否默认等派生状态。

**问题：为什么创建或更新后要 reload？**

回答：注册中心缓存的是已经绑定旧 URL、旧模型和旧密钥的客户端。只更新数据库不会改变已有对象，
必须失效缓存，让后续请求重新构建。

**问题：为什么默认 Provider 不允许删除？**

回答：避免全局指针悬空，导致所有未显式指定模型的业务请求失败。先切换再删除也让运维动作更明确。

## 3. 进阶面试问题

**问题：`save()` 后立刻 `registry.reload()` 有什么一致性问题？**

回答：方法仍处于事务内，数据库提交尚未完成。另一个线程可能在缓存清空后查询到旧的已提交数据并
重新缓存旧客户端；若事务随后回滚，缓存已经被清空或错误重建。正确做法是在事务 `afterCommit`
阶段发布配置变更事件，再由监听器使缓存失效。

**问题：本地 `ReentrantReadWriteLock` 能保证什么？**

回答：它只串行化当前 JVM 内通过这个 Service 发起的操作，尤其是 Legacy 文件修改。它保护不了
Registry 直接查数据库，也保护不了其他实例、外部改库和多个进程写同一文件。数据库一致性仍需事务、
乐观锁或约束，多实例缓存同步需要消息机制。

**问题：读取时为什么不应长时间持有读锁？**

回答：`testProvider` 在读锁内执行最长约 10 秒的外部 HTTP；`testAsrConfig` 在读锁内做 5 秒 TCP
连接。这会阻塞所有写操作。应只在锁内读取不可变配置快照，释放锁后再执行网络 I/O。

**问题：Legacy 模式有哪些不对称行为？**

回答：`updateDefaultEmbeddingProvider` 没有 Legacy 分支，会访问 null Repository；删除只保护默认
Chat Provider，不保护默认 Embedding Provider；创建不做与数据库模式相同的必填和 Embedding
校验；写 YAML 时没有持久化 `supports-embedding`。这说明双实现已经发生行为漂移。

**问题：API Key 脱敏为什么仍可能泄漏信息？**

回答：显示前三位和后三位有助于识别密钥，但也暴露长度和片段。对普通管理员可只返回“是否已配置”
或固定掩码；只有高权限角色才看片段，并避免日志、异常响应携带上游请求头。

## 4. 技术延伸问题

**问题：如何实现严格的命令查询分离？**

回答：QueryService 只读取 DTO；CommandService 在事务内修改数据库并写 Outbox；提交后消费者发布
`ProviderConfigChanged(version)`，各实例按版本清缓存。这样读取、写入和发布的边界更清楚。

**问题：热重载还有哪些实现方式？**

回答：可以用带版本号的不可变 `AtomicReference<RegistrySnapshot>` 整体替换；可以用 Caffeine 的单 key
失效；也可以配置中心推送 Refresh Event。整体快照适合 Provider 数量少的场景，可避免半更新状态。

**问题：多管理员同时编辑如何避免覆盖？**

回答：Entity 增加 `@Version`，前端提交版本号，版本冲突返回明确错误并展示最新值。也可用 ETag 和
`If-Match` 实现 HTTP 乐观并发控制。

## 5. 改进方案问题

**问题：Controller 层最优先改什么？**

回答：增加管理员身份认证和 RBAC；写操作叠加 USER/IP 限流；记录审计日志；为所有 Request 增加
Bean Validation；对路径 ID 做字符白名单；不要把上游完整错误正文直接返回前端。

**问题：如何让修改操作真正原子？**

回答：数据库模式只写数据库，事务提交后发事件。文件模式使用临时文件写入、`fsync`、原子 rename，
并在写入前完整校验；不要先改内存再尝试写文件，否则失败时内存和磁盘不一致。

**问题：如何优化缓存失效粒度？**

回答：更新某个 Provider 只失效 `{id}`、`{id}:plain`、`{id}:voice` 和对应 Embedding；切换默认值
通常不需要清空每个 Provider 的客户端，因为默认解析下一次会选择新 ID。Advisor 全局配置改变时才
全量失效。

**问题：如何降低大 Service 复杂度？**

回答：把数据库 Provider 命令、Legacy 文件适配、默认设置、语音配置和健康探测拆开；用统一
Validator 保证两种存储实现行为一致；把 DTO 映射提到 MapStruct。

## 6. 故障处理问题

**问题：创建 Provider 返回成功，但业务仍提示不存在，怎么查？**

回答：确认请求进入数据库模式还是 Legacy 模式；查事务是否已提交、Entity 是否 enabled、默认 ID
是否正确；看 reload 后是否在提交前被并发请求重建为旧缓存；多实例时检查请求落在哪个实例。

**问题：删除 Provider 失败提示“默认 Provider 不可删除”怎么办？**

回答：同时检查默认 Chat 和默认 Embedding，分别切换到有效 Provider 后再删除。前端只切换其中一个
仍会被拒绝是正确行为。

**问题：修改 API Key 后测试成功，但重启后失败，为什么？**

回答：数据库模式应持久化密文，重点检查加密主密钥是否变化；Legacy/语音文件模式则要检查外部
YAML/.env 是否真的被启动配置导入。当前仓库没有导入它们，重启丢失是已存在的问题。

**问题：配置更新接口卡住，怎么定位？**

回答：看是否有连通性测试持有读锁、文件系统 I/O 卡顿、数据库锁等待或加密初始化异常。线程 dump
中关注 `ReentrantReadWriteLock` 等待者，并把网络探测移出锁范围。
