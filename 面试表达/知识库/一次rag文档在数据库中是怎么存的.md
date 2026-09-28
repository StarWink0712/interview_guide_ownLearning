你的项目里 RAG 问答分两种模式，对应不同的持久化策略。

***

### 一、直接问答（无会话）

不存消息，只在 `rag_retrieval_logs` 表里记录一次检索日志（traceId、候选查询词、检索参数、命中数、耗时），纯粹用于可观测性，不关联用户也不支持多轮对话。

对，完全可以。用户这种"无会话问答"其实就是单轮 RAG，存下来对分析和回溯都很有价值，比如看用户都在问什么、哪些问题检索效果差。

不过直接用 traceId 做主键有一些权衡。traceId 是 UUID 字符串（比如 `a3f2b01c-...`），在 InnoDB 里作为聚簇索引主键会有两个问题：一是字符串比 BIGINT 占用更多空间，二是 UUID 的随机性会导致页分裂，插入性能不如自增 ID。更好的做法是用自增 BIGINT 做主键，traceId 加一个唯一索引，兼顾查询和插入性能：

```sql
CREATE TABLE rag_direct_qa_records (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id    VARCHAR(36) NOT NULL UNIQUE,   -- 唯一索引，通过 traceId 查记录
    question    TEXT NOT NULL,
    rewritten   TEXT,
    answer      TEXT,
    hit_count   INT DEFAULT 0,
    latency_ms  BIGINT,
    created_at  DATETIME DEFAULT NOW()
);
```

本质就是把现在只写检索日志的逻辑扩展成写一条完整的问答记录，开销很小。

<br />

***

### 二、RAG 聊天会话（有上下文）

三张表：

**`rag_chat_sessions`（会话表）**

| 字段                          | 类型                         | 说明                                    |
| :-------------------------- | :------------------------- | :------------------------------------ |
| `id`                        | BIGINT（自增主键）               | 会话 ID                                 |
| `title`                     | VARCHAR                    | 会话标题                                  |
| `status`                    | VARCHAR（ACTIVE / ARCHIVED） | 会话状态                                  |
| `message_count`             | INT                        | 消息数量冗余，方便列表渲染                         |
| `is_pinned`                 | BOOLEAN                    | 是否置顶                                  |
| `created_at` / `updated_at` | DATETIME                   | 时间戳，`@PrePersist` / `@PreUpdate` 自动维护 |

**`rag_session_knowledge_bases`（会话-知识库关联表）**

| 字段                  | 说明                          |
| :------------------ | :-------------------------- |
| `session_id`        | 外键 → `rag_chat_sessions.id` |
| `knowledge_base_id` | 外键 → `knowledge_bases.id`   |

多对多中间表，一个会话可以关联多个知识库。

**`rag_chat_messages`（消息表）**

| 字段                          | 类型                        | 说明                             |
| :-------------------------- | :------------------------ | :----------------------------- |
| `id`                        | BIGINT（自增主键）              | 消息 ID，流式输出时先插入占位行拿到的就是这个 ID    |
| `session_id`                | BIGINT（外键）                | 所属会话                           |
| `type`                      | VARCHAR（USER / ASSISTANT） | 消息类型                           |
| `content`                   | TEXT                      | 消息内容。ASSISTANT 消息流式输出前先存空字符串占位 |
| `message_order`             | INT                       | 消息序号，保证排序                      |
| `completed`                 | BOOLEAN                   | 流式消息是否已完成                      |
| `created_at` / `updated_at` | DATETIME                  | 时间戳                            |

索引：`(session_id)` + `(session_id, message_order)`，覆盖了"查某个会话的所有消息并按序号排序"这个核心查询。

***

一次 RAG 聊天的完整写入流程：

1. 前端发问题 → `prepareStreamMessage` 在同事务里插入两条消息：USER（content=问题） + ASSISTANT（content=""，completed=false），返回 ASSISTANT 消息的 ID
2. LLM 流式输出通过 SSE 逐段推给前端
3. 流式结束后 → `completeStreamMessage` 用之前的 ID 把 ASSISTANT 消息的 content 回写成完整答案，completed=true

