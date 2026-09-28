# Registry 路由、缓存与三类 ChatClient

## 1. 模块基础讲解

`LlmProviderRegistry` 是运行时核心。业务模块不直接 new Spring AI 客户端，而是通过它获得：

| 变体 | 缓存 Key | 工具 | Advisor | 典型场景 |
|---|---|---|---|---|
| Default | `{providerId}` | SkillsTool | 可配置 ToolCall/Memory/Logger/SafeGuard | 普通对话、部分评估 |
| Plain | `{providerId}:plain` | 无 | 仅 SafeGuard | JSON 结构化出题、即时评分 |
| Voice | `{providerId}:voice` | SkillsTool | 流式 ToolCall + SafeGuard | 实时语音面试 |

客户端按需创建：

```text
getPlainChatClient(providerId)
  -> providerId 为空则解析默认 Chat Provider
  -> ConcurrentHashMap.computeIfAbsent(id + ":plain")
  -> 从数据库读取启用 Provider
  -> AES-GCM 解密 API Key
  -> ApiPathResolver 构建 OpenAiApi
  -> OpenAiChatModel + OpenAiChatOptions
  -> ChatClient.builder(...).defaultAdvisors(...).build()
```

`OpenAiChatModel` 被当作 OpenAI 兼容协议适配器，所以 DashScope、GLM、DeepSeek、Kimi 和本地模型
只要协议兼容，就能共享工厂逻辑。默认温度在 Provider 未配置时取 0.2。

缓存使用 `ConcurrentHashMap`，避免每次请求重新创建 HTTP 客户端、模型和 Advisor 链；`reload()`
通过清空两个 Map 实现延迟重建。

## 2. 基础面试问题

**问题：为什么使用 `computeIfAbsent`？**

回答：它把“检查缓存、创建、放入缓存”合并成线程安全操作，同一个 key 在正常并发访问下只需构建
一次，适合昂贵对象的懒加载。

**问题：Plain 客户端为什么不注册工具？**

回答：结构化输出希望模型直接返回 JSON。工具调用会引入 tool call/message 多轮协议，增加解析失败
概率，因此 Plain 只保留安全 Advisor，让输出路径尽量确定。

**问题：Voice 客户端为什么不用 Memory Advisor？**

回答：语音模块自己从持久化消息构造对话历史，若再启用通用 Memory 容易重复注入或串会话。
Voice 重点保留流式工具调用能力和安全检查。

**问题：ChatClient 可以安全复用吗？**

回答：ChatClient 本身按构建后的默认配置复用，单次 Prompt 通过 fluent spec 创建请求状态。真正需要
关注的是 Advisor 内部是否持有共享可变状态，例如 ChatMemory，必须用 conversationId 隔离。

## 3. 进阶面试问题

**问题：`ConcurrentHashMap.clear()` 与 `computeIfAbsent()` 是否组成原子 reload？**

回答：不是。线程 A 可能读取旧配置开始构建，线程 B 执行 clear，线程 A 随后把旧客户端写回 Map。
因此 reload 后仍可能缓存旧配置。可在缓存值中加入配置版本，构建结束时比较版本；或整体替换不可变
Map/RegistrySnapshot，而不是原地 clear。

**问题：默认 Provider 切换为什么可能不需要清全部客户端？**

回答：缓存 key 是具体 Provider ID，默认值只影响“空 providerId 解析成哪个 ID”。如果两个 Provider
客户端都已正确缓存，切换默认指针不必重建它们。当前全清简单但会制造重建尖峰。

**问题：启用 MessageChatMemoryAdvisor 有什么隐患？**

回答：Registry 为每个 Provider 构建一个共享 Advisor 和 `MessageWindowChatMemory`。Spring AI 官方
语义要求每次调用传 `ChatMemory.CONVERSATION_ID`；若业务未传会运行时报错，若错误复用 ID 则会话
串扰。当前默认关闭是合理保护，但开启前必须审计所有调用点。

**问题：为什么 `getChatClient` 对未知 Provider 抛 `IllegalArgumentException` 不理想？**

回答：项目规范要求业务异常统一用 `BusinessException`。`IllegalArgumentException` 可能被全局处理成
通用内部错误，丢失 Provider 错误码。数据库与 YAML 分支还应保持同一种异常语义。

**问题：一个 Provider 配置更新时在途请求怎么办？**

回答：在途请求持有旧对象，会自然完成；这是一种 copy-on-use 语义。若旧密钥被立即吊销，在途请求
可能失败，业务层需按幂等性决定重试新版本。不要强行关闭共享 HTTP 客户端导致请求中断。

## 4. 技术延伸问题

**问题：Registry 和依赖注入容器有什么区别？**

回答：Spring Bean 更适合启动时已知、生命周期稳定的对象；Registry 适合运行时 key 驱动、数量和
配置可变的对象。也可以动态注册 Bean，但复杂度和刷新边界更高。当前 Registry 是更直接的选择。

**问题：缓存可以改用 Caffeine 吗？**

回答：可以获得统计、最大容量、过期和异步刷新，但核心一致性仍要靠版本控制。Provider 数量通常
很少，不需要容量淘汰；最重要的是精确失效和避免旧构建写回。

**问题：如何做能力路由？**

回答：为 Provider 声明 `CHAT`、`EMBEDDING`、`TOOLS`、`STREAMING`、`JSON_SCHEMA` 等 capability，
业务请求声明所需能力，Registry 过滤候选并按成本/健康度选择，而不是只凭字符串 ID。

## 5. 改进方案问题

**问题：如何设计无竞态热重载？**

回答：维护 `AtomicLong configVersion`。缓存值保存 `version + client`；变更提交后递增版本。请求读取
时若版本不一致就基于固定配置快照构建，完成后用 CAS 仅在版本仍一致时发布。更简单的是构建完整的
新 RegistrySnapshot 后一次 `AtomicReference.set()`。

**问题：如何避免缓存重建风暴？**

回答：只失效受影响 key；对高流量默认 Provider 可在发布事件后预热 Default/Plain/Voice；使用
single-flight 保证同一版本只构建一次；监控构建耗时和失败率。

**问题：如何改进 Advisor 装配？**

回答：把三种变体定义为明确的 `ClientProfile`，由工厂按 capability 组合 Advisor；Memory 强制传入
conversationId；SimpleLogger 在生产默认关闭并脱敏；SafeGuard 只是输入词表防护，不能替代 Prompt
注入防御和输出审核。

**问题：如何增强类型安全？**

回答：用 `ProviderId` 值对象和 `ClientVariant` 枚举代替字符串拼接 key；缓存 key 使用 record，避免
Provider ID 本身包含 `:plain` 造成碰撞或歧义。

## 6. 故障处理问题

**问题：第一次请求很慢，之后正常，为什么？**

回答：首次请求触发数据库读取、密钥解密、OpenAiApi/Model/Advisor 构建，之后命中缓存。可以观察
Registry 创建日志并对默认 Provider 做启动后预热，但预热失败不应阻止非关键功能启动。

**问题：开启 Memory 后所有请求报参数错误，怎么查？**

回答：检查每次 `chatClient.prompt()` 是否通过 Advisor 参数传入唯一 conversationId。不能只创建
Memory Advisor 而不修改调用方；这是 Spring AI 的明确契约。

**问题：结构化输出突然出现工具调用内容，怎么查？**

回答：确认业务是否误用了 Default 而不是 Plain 客户端；检查缓存 key 是否碰撞；确认调用时是否又
动态添加了 tool callbacks。最终还应使用 StructuredOutputInvoker 做解析和有限修复重试。

**问题：reload 后仍命中旧模型怎么证明是竞态？**

回答：给配置和缓存对象打 version，记录构建开始/结束版本；并发压测中让旧构建阻塞，在其间触发
reload，再观察旧版本是否写回。修复后断言缓存版本永不低于已发布版本。
