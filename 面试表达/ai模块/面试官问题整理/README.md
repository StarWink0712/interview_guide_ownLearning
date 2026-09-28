# AI 模块面试问题索引

本目录根据上一级目录中的 9 篇 AI 模块笔记整理。原始笔记文件保持不变；每个分类文件按照面试问题逐题整理答案，答案只重组原笔记中的信息，不修改原始笔记。

## 一、AI 公共基础层与 Provider 治理

- 为什么要抽象 AI 公共基础层？
- AI 模块主要解决了哪些共性问题？
- `LlmProviderRegistry` 的职责是什么？
- Provider 配置为什么优先从数据库读取？
- ChatClient 和 EmbeddingModel 为什么使用懒加载缓存？
- 模型调用失败后是否需要清空客户端缓存？
- 临时异常和配置异常分别怎么处理？
- Provider 熔断和备用 Provider 切换怎么设计？
- 切换 Provider 后如何保持多轮对话上下文？
- 为什么 Embedding Provider 不能随意切换？

对应文件：[01-AI公共基础层与Provider治理.md](01-AI公共基础层与Provider治理.md)

## 二、LLM 底层通信与 OpenAiApi

- Spring AI 从 ChatClient 到模型厂商的调用链是什么？
- `OpenAiApi` 的职责是什么？
- 为什么可以用 OpenAI-compatible 协议调用 DashScope？
- 普通调用和流式调用分别使用什么 HTTP 客户端？
- 连接超时和读取超时是怎么配置的？
- Prompt 最终是如何转换成 HTTP JSON 请求的？
- `OpenAiApi` 是否等于 OpenAI 官方服务？

对应文件：[02-LLM底层通信与OpenAiApi.md](02-LLM底层通信与OpenAiApi.md)

## 三、ChatModel 重试与 ObservationRegistry

- 创建 `OpenAiChatModel` 时传入了哪些基础设施对象？
- `RetryTemplate` 处理什么类型的失败？
- 当前重试次数、退避时间和最大间隔是多少？
- `ObservationRegistry` 有什么作用？
- 没有监控组件时为什么使用 `ObservationRegistry.NOOP`？
- `RetryListener` 的两个回调分别记录什么？
- Micrometer 指标与数据库调用日志有什么区别？

对应文件：[03-ChatModel重试与ObservationRegistry.md](03-ChatModel重试与ObservationRegistry.md)

## 四、三类 ChatClient 与 Advisor 链

- 为什么设计 Default、Plain、Voice 三种 ChatClient？
- 三种 ChatClient 分别适用于哪些业务？
- 文字面试出题为什么使用 Plain Client？
- 语音面试为什么使用 Voice Client？
- Default ChatClient 注册了哪些 Advisor？
- `AiProviderObservationAdvisor` 记录什么？
- `ToolCallAdvisor` 如何执行 SkillsTool？
- `MessageChatMemoryAdvisor` 如何维护上下文？
- `SimpleLoggerAdvisor` 和 `SafeGuardAdvisor` 分别做什么？
- Advisor 与 HTTP Interceptor 的职责有什么区别？
- ChatModel、ChatClient 和 OpenAiApi 的层级关系是什么？

对应文件：[04-三类ChatClient与Advisor链.md](04-三类ChatClient与Advisor链.md)

## 五、结构化输出

- `StructuredOutputInvoker` 解决什么问题？
- `BeanOutputConverter` 如何把 JSON 转换成 Java 对象？
- `convertWithRepair` 如何修复 JSON？
- 结构化输出的重试与网络重试有什么区别？
- 为什么结构化输出最好使用 Plain ChatClient？
- 所有 LLM 调用都会经过 StructuredOutputInvoker 吗？

对应文件：[05-StructuredOutputInvoker.md](05-StructuredOutputInvoker.md)

## 六、SkillsTool

- `interviewSkillsToolCallback` 是怎么生成的？
- `AgentUtilsConfiguration`、`AgentUtilsProperties` 和 ResourceLoader 分别负责什么？
- Spring 启动时如何加载 `classpath:skills`？
- SkillsTool 如何注册到 Spring 容器？
- LlmProviderRegistry 如何把 SkillsTool 挂到 ChatClient？

对应文件：[06-SkillsTool装配.md](06-SkillsTool装配.md)

## 七、Prompt 注入安全

- 为什么简历、JD 和知识库文档属于不可信数据？
- Prompt 注入防护分成哪几层？
- `PromptSanitizer` 会过滤哪些内容？
- 为什么使用随机 UUID 数据边界？
- `ANTI_INJECTION_INSTRUCTION` 和 `DATA_BOUNDARY_INSTRUCTION` 有什么区别？
- `SafeGuardAdvisor` 与 PromptSanitizer 有什么区别？
- SafeGuard 命中敏感词后如何处理？

对应文件：[07-Prompt注入安全.md](07-Prompt注入安全.md)

## 八、LLM 调用日志与监控

- LLM 调用日志为什么要分层设计？
- RestClient Interceptor 和 WebClient Filter 分别记录什么？
- RetryListener 在监控链路中的位置是什么？
- `AiProviderObservationAdvisor` 记录什么？
- invocation 主表和 attempt 明细表如何关联？
- 如何通过 invocationId 排查一次完整调用？
- WebSocket 和 WebClient 在语音面试中的职责有什么区别？
- Micrometer 指标如何按 Provider、状态码和异常类型聚合？

对应文件：[08-LLM调用日志与监控.md](08-LLM调用日志与监控.md)

## 九、Prometheus 与 Grafana

- Grafana 是什么？它是否保存监控数据？
- Micrometer、Prometheus 和 Grafana 的关系是什么？
- Grafana 是项目内部组件还是独立中间件？
- 运维大盘可以展示哪些 LLM 指标？
- 前端如何嵌入 Grafana 面板？

对应文件：[09-Prometheus与Grafana.md](09-Prometheus与Grafana.md)
