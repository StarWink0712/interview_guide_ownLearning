# RAG 会话持久化、历史上下文与并发

## 1. 模块基础讲解

`RagChatSessionEntity` 与知识库是多对多，与消息是一对多级联关系。会话支持标题、置顶、ACTIVE/ARCHIVED、更新时间
和冗余 messageCount；消息保存 USER/ASSISTANT、messageOrder、content 和 completed。创建会话会验证知识库，
流式发送先在一个事务中保存 user 消息与空 assistant 占位，再查询最近完成消息构造 Spring AI 历史。

详情查询刻意分两次加载“会话 + 知识库”和“消息”，避免同时 fetch 两个集合造成笛卡尔积。历史查询按 order 倒序
分页，排除刚保存的当前 user，再 reversed 成正序。这个思路清楚，但 messageOrder/messageCount 的并发控制、
取消后的占位恢复和列表 N+1 尚未解决。

## 2. 基础面试问题

**问题：为什么流式回答要先建占位消息？**

回答：先获得 messageId 并表示回答进行中，流完成后一次写入完整内容，避免每个 token 都更新数据库。它也允许
前端展示生成状态。必须配套 FAILED/CANCELLED 恢复，否则占位会永久悬挂。

**问题：为什么 messageCount 是冗余字段？**

回答：列表页可不用 count 消息表就显示数量，也可作为下一 messageOrder 的快速来源。冗余带来一致性成本，任何
并发写、补偿或手工删除都可能让它与实际行数漂移。

**问题：为什么历史只取最近 N 条？**

回答：控制 Prompt token、延迟和成本，近期对话通常最相关。严格按消息条数不等于按 token 预算，长消息仍可能
超限，成熟方案还会做摘要和重要信息记忆。

**问题：为什么多对多使用 Set？**

回答：同一会话不应重复关联同一知识库，Set 表达唯一性；中间表还应建立 `(session_id, knowledge_base_id)` 唯一
约束。Set 的迭代顺序不稳定，因此生成 kbId 列表或展示时若要求顺序，需要显式排序。

## 3. 进阶面试问题

**问题：同一会话并发发送两个问题会怎样？**

回答：两个事务都可能读到相同 messageCount，生成相同 messageOrder，再各自把 count 写为相同新值，出现顺序重复
和 lost update。当前没有 `@Version`、悲观锁或 `(session_id, message_order)` 唯一约束。

**问题：创建会话时传重复 kbId 为什么会误报不存在？**

回答：`findAllById` 返回唯一实体数，而代码与原请求列表长度比较。请求 `[1,1]` 得到一个实体，大小不等，于是抛
“部分知识库不存在”。应该先 distinct，再验证集合差集。

**问题：更新知识库关联为何可能静默丢数据？**

回答：`updateSessionKnowledgeBases` 直接把 `findAllById` 结果设入 Set，没有验证所有请求 ID 都存在。缺失 ID 被静默
忽略；空集合还可能让会话继续存在但永远检索不到内容。

**问题：列表查询可能有什么 ORM 性能问题？**

回答：列表仓库只查 Session，Mapper 若访问 LAZY knowledgeBases，OSIV 开启时产生 N+1；关闭时可能抛
LazyInitializationException。应使用 EntityGraph、batch fetch 或 DTO projection，并加分页。

## 4. 技术延伸问题

**问题：并发消息顺序有哪些实现方案？**

回答：悲观锁会话行后分配序号最直接；乐观锁 `@Version` 冲突重试适合低冲突；数据库 sequence 可全局唯一但非每
会话连续；也可使用 UUID/时间游标排序。无论哪种都应有数据库唯一约束兜底。

**问题：长对话如何管理上下文？**

回答：最近窗口 + 滚动摘要 + 结构化记忆；摘要本身带版本和来源消息范围；每次按 token 预算组合 system、检索、
摘要和最近轮次。敏感信息和过期指令不应永久注入。

**问题：会话状态 ACTIVE/ARCHIVED 如何真正生效？**

回答：写接口应拒绝 ARCHIVED 会话或先恢复；列表支持状态筛选；归档可触发冷存储/摘要。当前 enum 主要是数据字段，
Service 并未在发送消息时校验，属于未完成的状态机。

## 5. 改进方案问题

**问题：如何重构消息写入？**

回答：会话加 `@Version`；消息加唯一约束；事务内锁定/更新 nextSequence 并插入一轮 turnId 关联的 user/assistant；
assistant 状态为 STREAMING；完成、失败、取消都用条件更新，重复回调幂等。

**问题：如何完善历史加载？**

回答：查询 `completed=true` 且状态正常的完整 turn；按 token 预算而非条数；排除当前 turnId，不依赖“DESC 第一条
一定是当前 user”；必要时加载摘要；记录实际 history token 和截断原因。

**问题：如何加入用户隔离？**

回答：Session、KnowledgeBase 都带 owner/tenantId；每个 Repository 查询包含租户条件；服务校验会话和知识库同属
当前用户；中间表不能跨租户；管理员访问单独审计。仅靠前端隐藏 ID 不构成权限。

## 6. 故障处理问题

**问题：消息顺序重复或错乱怎么处理？**

回答：查询同 session 的重复 messageOrder、messageCount 与 count(*)；关联并发请求日志；修复前先禁止同会话并发；
迁移时按 createdAt/id 重排并加唯一约束，再启用锁或乐观版本。

**问题：历史回答影响了当前检索怎么办？**

回答：查看实际注入的历史范围，检查未完成/错误消息是否进入 completed 集合；临时关闭 history 配置；评估 Rewrite
是否错误解析指代；补充 turnId、token 截断和历史回归测试。

**问题：删除知识库后会话异常怎么办？**

回答：当前删除会从会话 Set 移除知识库，但可能留下零知识库会话。应在删除事务/补偿中将空会话归档或标记需要
重新选择；发送前验证非空和 READY，并向客户端返回可操作错误。

