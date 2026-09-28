**用 Java record 定义数据结构 → BeanOutputConverter 自动生成格式指令告诉 AI → AI 返回 JSON 字符串 → Jackson 反序列化成 Java 对象**。`convertWithRepair` 在这个流程上加了一层保险——如果 Jackson 解析失败，先试试本地修复 JSON 字符串再解析，不行再抛异常触发外层重试

核心就是structured的invoke方法（只接受plain类型的chatclient，只不过项目中有的地方有错误传入了default，真用default的地方不需要用这个结构化调用器，直接拿content结构就行了），当service业务层需要出题和简历评估等操作时，先通过llm注册中心获取到相应的chatclient，然后生成好相应的提示词还有上下文以及convert转换器（json转换为java对象），传入给invoke方法。

`StructuredOutputInvoker` 是结构化输出场景的统一调用器。业务层比如出题、评分、JD 解析，会先通过 `LlmProviderRegistry` 获取合适的 `ChatClient`，最好是 Plain Client，然后渲染 system prompt、user prompt，并创建 `BeanOutputConverter`，最后交给 `invoke`。

`invoke` 内部会先记录开始时间，用于计算调用耗时；然后把业务上下文规范化成指标标签，比如转小写、去空白、清理特殊字符和多余下划线；接着在 system prompt 里追加防注入指令。之后进入重试循环，每次通过 `chatClient.prompt().system(...).user(...).call().content()` 调用模型。

拿到模型返回内容后，会通过 `convertWithRepair` 转换成 Java 对象。它会先直接用 `BeanOutputConverter` 解析 JSON，如果失败，会尝试本地修复常见 JSON 格式问题，再解析一次。如果本次失败且还没达到最大尝试次数，就构造新的 retry system prompt，把上一次错误信息和严格 JSON 输出要求加进去，再调用一次模型，也就两次，并且是因为不满足结构化解析才重试的。

<br />

<br />

<br />

监控上分两层：每一次尝试失败都会记录 attempt 指标；但完整调用结果、业务级 AI 指标和数据库 AI 调用日志，只在最终成功或所有重试耗尽后记录一次。数据库日志里会保存 `traceId`、场景、provider、model、promptHash、attemptCount、success、latencyMs、errorCode、errorMessage`等信息，主要用于事后排查和审计，可以在后台 AI Trace 页面按时间、provider、场景、成功失败状态或`traceId\` 查看。Micrometer 指标会记录调用次数、耗时、重试次数和失败次数，主要用于实时监控和告警，生产环境可以接 Prometheus/Grafana 查看调用量、失败率、延迟趋势和重试次数。

如果所有尝试都失败，当前实现会记录失败指标和 AI 调用日志，然后抛出 `BusinessException`。后续可以继续增强，比如 provider 级熔断、备用 provider 切换，以及把 provider 标记为短时间不可用。因为业务上下文本来由本地系统管理，所以切换 provider 时可以重新组装 prompt 发给备用模型。（这些都不用了，不需要用到了，统一的日志记录重新写了）

<br />

最核心的是这个重试不是连接失败的重试，而是无法解析出json对象的重试！所以这里的指标记录也是没有意义的，只讲一下简短的流程就行了。

<br />

结构化输出调用器并不是唯一的llm调用入口，只是需要结构化返回的才走这一步，default还有asrtts等是直接调用即可，
