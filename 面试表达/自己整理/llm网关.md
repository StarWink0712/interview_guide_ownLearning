对，模型切换后的上下文应该保存，但建议是：

- PostgreSQL 保存完整、可靠的会话上下文，作为事实来源。
- Redis 缓存最近几轮消息和当前 Provider 绑定，提高读取速度。
- 不要只放在某个 Spring Boot 实例的本地内存里，否则 API 网关把下一次请求转发到另一实例后，上下文就丢了。

而且你当前项目已经有这个基础：RAG 消息和语音面试消息都已经持久化到数据库，切换模型时可以重新加载并组装 Prompt。

## 一、面试时先讲清楚当前已经实现了什么

你可以这样说：

> 当前项目的限流不是单机内存限流，而是基于 Redis 的分布式滑动窗口限流。Controller 通过可重复的 `@RateLimit` 注解配置规则，支持 GLOBAL、IP、USER 三个维度，`RateLimitAspect` 拦截请求后为每个维度构造 Redis Key，再通过 Lua 脚本原子完成过期令牌回收、配额判断和扣减。
>
> 因为所有应用实例连接同一个 Redis，所以即使 API Gateway 把请求分配到不同 Spring Boot 实例，看到的仍然是同一份限流状态，实现了跨实例的分布式限流。

对应实现是：

- [RateLimit.java](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/annotation/RateLimit.java:28)
- [RateLimitAspect.java](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java:85)
- [rate_limit_single.lua](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/resources/scripts/rate_limit_single.lua:13)

例如知识库查询已经配置为：

```java
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
@RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
```

由于默认时间单位是秒，这表示：

```text
整个系统每秒最多 10 次
并且单个 IP 每秒最多 10 次
两个条件都通过才允许执行
```

Redis Key 类似：

```text
ratelimit:{KnowledgeBaseController:queryKnowledgeBase}:global
ratelimit:{KnowledgeBaseController:queryKnowledgeBase}:ip:192.168.1.10
ratelimit:{KnowledgeBaseController:queryKnowledgeBase}:user:10001
```

Hash Tag 可以保证同一接口的限流 Key 在 Redis Cluster 中落到同一个 Slot。

还可以继续主动指出两个细节：

- `timeout` 当前只是预留字段，并没有实现等待令牌。
- `X-Forwarded-For` 和 `X-User-Id` 不能直接信任客户端，生产环境必须由 API Gateway 校验 JWT 后覆盖这些 Header，防止伪造 IP 或用户 ID 绕过限流。

## 二、为什么 API Gateway 之后还需要业务限流

建议做两级限流。

第一层是 API Gateway 粗粒度限流：

- 单 IP 总请求数
- 单用户总请求数
- 登录、上传等高风险接口
- 请求体大小
- 同时连接数
- WebSocket 建连频率

这一层的目的，是尽早拒绝恶意流量，不让请求进入业务服务。

第二层是当前的业务方法限流：

- 简历分析每秒多少次
- 知识库流式查询每秒多少次
- 创建面试会话每秒多少次
- Provider 配置修改每秒多少次

这一层知道具体业务成本，可以进行更精细的保护。

因此面试时不要说“有了 API Gateway，就把现在的 `@RateLimit` 删除”，而是：

> API Gateway 做入口粗粒度保护，业务服务保留场景级细粒度限流，形成两层防护。

## 三、还需要增加 LLM 出口限流

你现在限制的是“用户请求”，但模型厂商通常限制的是：

- RPM：每分钟请求数
- TPM：每分钟 Token 数
- 最大并发数
- 单 API Key 配额
- 每日预算

所以 LLM Gateway 还需要单独做 Provider 出口限流：

```text
llm:quota:{dashscope:qwen}:rpm
llm:quota:{dashscope:qwen}:tpm
llm:inflight:{dashscope:qwen}
```

具体流程：

1. 根据 Prompt 估算输入 Token。
2. 申请 RPM 配额。
3. 申请 TPM 配额。
4. 申请并发槽位。
5. 三项都通过才调用 Provider。
6. 调用结束后在 `finally` 中释放并发槽位。
7. 根据 Provider 返回的真实 Token 用量修正估算值。

同步请求达到 Provider 限额时，可以切换其他 Provider；异步简历分析或评估任务可以留在 Redis Stream 中延迟执行。

这能防止出现：

```text
用户入口没有超限
但是大量用户同时通过
最终把同一个 LLM API Key 打到 429
```

## 四、LLM 负载均衡具体怎么做

不是从所有 Provider 中随机选一个，而是分四步。

### 1. 能力过滤

先过滤出满足当前场景的 Provider：

```text
简历评分 → 支持结构化 JSON、上下文长度足够
RAG 对话 → 支持流式输出
语音面试 → 首 Token 延迟低、支持流式
Embedding → 模型、版本、维度必须完全一致
```

不同能力、质量差异过大的模型不能直接放进同一个负载均衡池。

### 2. 健康过滤

排除：

- 已禁用 Provider
- 熔断状态为 OPEN
- RPM/TPM 已耗尽
- 当前并发已满
- API Key 配置错误
- 不支持当前场景

### 3. 会话粘滞

对于面试和聊天会话：

```text
sessionId → providerId + model
```

第一次请求选中 Provider 后，把绑定关系保存到 Redis或数据库。后续请求优先使用原 Provider，保证模型风格、能力和上下文一致。

只有原 Provider 熔断或达到配额，才切换备用 Provider。

无状态任务，例如简历评分、JD 解析、批量评估，可以每次重新负载均衡。

### 4. 选择算法

第一版可以使用加权最少并发：

```text
loadScore = currentInflight / weight
```

选择 `loadScore` 最小的 Provider。

例如：

| Provider | 权重 | 当前并发 | 负载分数 |
|---|---:|---:|---:|
| DashScope | 5 | 10 | 2 |
| DeepSeek | 3 | 3 | 1 |
| Kimi | 2 | 4 | 2 |

这次请求选择 DeepSeek。

后续可以加入成功率、延迟和成本：

```text
score =
    weight
    × successRate
    × remainingQuota
    ÷ (p95Latency × currentInflight × tokenCost)
```

但面试时可以说明，第一版不会上来就实现复杂动态算法，而是使用：

```text
能力过滤 + 健康过滤 + 会话粘滞 + 加权最少并发
```

## 五、熔断具体怎么熔断

熔断应该按下面的粒度隔离：

```text
providerId + model + operation
```

例如：

```text
dashscope:qwen:CHAT
dashscope:qwen:STREAM_CHAT
dashscope:text-embedding-v3:EMBEDDING
```

不能因为聊天接口异常，就把同一个厂商的 Embedding 一起熔断。

可以设计这样的初始参数：

- 最近 20 次调用作为滑动窗口
- 至少采集 10 次才计算
- 失败率超过 50%时打开熔断
- 慢调用比例超过 60%时打开熔断
- 慢调用阈值例如 8 秒
- OPEN 状态保持30秒
- 之后进入 HALF_OPEN
- HALF_OPEN 放行3个探测请求
- 探测全部或大部分成功则恢复 CLOSED
- 再次失败则回到 OPEN

状态变化：

```text
CLOSED
  ↓ 失败率/慢调用率超过阈值
OPEN
  ↓ 等待30秒
HALF_OPEN
  ├─ 探测成功 → CLOSED
  └─ 探测失败 → OPEN
```

错误统计要分类：

| 错误 | 熔断统计 | 处理 |
|---|---:|---|
| 连接失败、超时 | 是 | 有限重试后切换 |
| HTTP 5xx | 是 | 有限重试后切换 |
| HTTP 429 | 单独配额治理 | 尊重 `Retry-After`，切换节点 |
| HTTP 400 | 否 | 修正请求，不切换 |
| HTTP 401/403 | 配置故障 | 禁用配置并告警 |
| JSON 解析失败 | 不算网络故障 | 修复 Prompt，可换同能力模型 |

一次请求的完整处理应该是：

```text
调用主 Provider
  ↓
发生超时或 5xx
  ↓
同 Provider 最多重试一次
  ↓
仍然失败
  ↓
记录熔断窗口
  ↓
选择下一个健康 Provider
  ↓
使用相同业务上下文重新调用
```

不要在同一个 Provider 上重试十几次后才切换，否则会产生重试风暴，也失去了故障转移的意义。

## 六、模型切换后上下文怎么处理

可以存数据库，而且应该存数据库，但不要保存成某个厂商私有的 `conversationId` 作为唯一上下文。

建议保存统一消息格式：

```text
llm_conversation_message
├── id
├── session_id
├── turn_id
├── sequence_no
├── role                 USER / ASSISTANT / SYSTEM / TOOL
├── content
├── tool_call_json
├── provider_id
├── model
├── status               PENDING / STREAMING / COMPLETED / FAILED
├── input_tokens
├── output_tokens
└── created_at
```

每次调用前重新构建上下文：

```text
System Prompt
+ 当前业务状态
+ 早期历史摘要
+ 最近 N 条完整消息
+ 当前用户输入
```

例如面试上下文包括：

- 面试方向和难度
- 简历摘要
- 当前问题
- 历史问答
- 能力画像
- 已发现的薄弱知识点
- 最近的自适应决策

模型切换时，把这些标准消息重新发送给新 Provider。这样上下文属于你的系统，而不是绑定在某个模型厂商内部。

你当前项目已经有类似基础：

- RAG 的 `rag_chat_messages` 保存用户和 AI 消息，并加载最近 N 条完成消息：[RagChatSessionService.java](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/RagChatSessionService.java:279)
- 语音面试的 `voice_interview_messages` 保存识别文本和 AI 回复：[VoiceInterviewMessageEntity.java](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/voiceinterview/model/VoiceInterviewMessageEntity.java:27)
- 普通面试会话已经保存 `questionsJson`、`abilityProfileJson`、`adaptiveTraceJson` 和 `llmProvider`：[InterviewSessionEntity.java](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/interview/model/InterviewSessionEntity.java:66)

所以你的项目不是完全没有上下文持久化，而是还缺少统一的跨 Provider 上下文组装层。

### PostgreSQL 和 Redis 怎么分工

```text
PostgreSQL：完整上下文，保证可靠性和可恢复性
Redis：最近 N 轮消息、会话绑定、上下文摘要，降低数据库压力
应用本地内存：只做极短生命周期缓存，不能作为唯一来源
```

外部 LLM 调用不能放在数据库事务里。正确顺序是：

1. 短事务保存用户消息，提交事务。
2. 事务外调用 LLM。
3. 成功后用另一个短事务保存 AI 消息。
4. 失败则把本轮标记为 FAILED。
5. 切换 Provider 时使用相同 `turnId`，防止重复保存。

流式调用还要注意：

- 第一个 Token 返回前，可以切换 Provider。
- 已经向前端输出 Token 后，不能自动切换，否则用户会看到两份内容拼接。
- 可以将 AI 消息先保存为 `STREAMING`，完成后改成 `COMPLETED`，中断则改成 `FAILED`。

## 七、面试时可以这样完整回答

> 当前项目已经实现了两类流量治理能力。入口侧，我通过可重复的 `@RateLimit` 注解支持 GLOBAL、IP、USER 多维度限流，AOP 根据接口和限流维度生成 Redis Key，再通过 Lua 脚本原子完成滑动窗口内的配额判断与扣减。因为多个应用实例共享 Redis，所以这是跨实例的分布式限流，不是单机计数。生产环境中还可以在 API Gateway 增加一层粗粒度限流，提前拦截恶意流量，而业务层继续保留场景级细粒度限流。
>
> LLM 出口侧还需要针对 Provider 增加 RPM、TPM 和最大并发限制，因为入口请求没有超限，并不代表模型 API Key 没有达到厂商配额。所有 Provider 先按照结构化输出、流式、上下文长度等能力进行分组，再排除熔断、配额耗尽和并发已满的节点。无状态任务使用加权最少并发进行负载均衡；面试、RAG 和语音会话则通过 sessionId 绑定 Provider，优先保证会话一致性。
>
> 熔断器按 Provider、模型和操作类型隔离，通过滑动窗口统计超时、网络异常和 5xx。达到失败率或慢调用阈值后进入 OPEN，冷却后进入 HALF_OPEN，通过少量探测请求决定恢复还是继续熔断。单个 Provider 只做一次有限重试，仍失败才切换备用 Provider，避免重试风暴。400 等请求错误不重试，401 和 403 作为配置故障告警，429 则结合 Retry-After 和 Provider 配额进行处理。
>
> 模型切换后的上下文由平台自己管理，不依赖厂商的会话 ID。完整消息、业务状态和 Provider 使用记录保存在 PostgreSQL，Redis 缓存最近几轮消息和 session 到 Provider 的绑定。切换时重新组装 System Prompt、历史摘要、最近消息、当前题目和能力画像发送给新模型。这样即使请求被 API Gateway 分配到不同应用实例，或者运行过程中切换了 Provider，也可以恢复完整上下文。

面试中需要明确说明：**分布式限流是你当前已经实现的；Provider 负载均衡和熔断切换属于基于现有架构给出的下一阶段设计，不要说成已经完成。**