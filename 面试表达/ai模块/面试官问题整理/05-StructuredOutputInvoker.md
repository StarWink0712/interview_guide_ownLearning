# StructuredOutputInvoker

## 1. `StructuredOutputInvoker` 解决什么问题？

**回答：** 它是出题、评分、JD 解析等结构化输出场景的统一调用器，负责调用模型、把模型返回的 JSON 转换成 Java 对象，并在 JSON 格式不合法时执行本地修复和有限次数重试。业务层只需要准备 ChatClient、System Prompt、User Prompt 和 OutputConverter，再统一交给 `invoke`。

## 2. `BeanOutputConverter` 如何把 JSON 转换成 Java 对象？

**回答：** 业务层先用 Java record 定义目标数据结构，再创建 `BeanOutputConverter`。Converter 会生成结构化格式要求告诉模型应该返回什么 JSON，模型返回字符串后，再由 Converter 和 Jackson 反序列化成对应的 Java 对象。

## 3. `convertWithRepair` 如何修复 JSON？

**回答：** 拿到模型内容后，`convertWithRepair` 先直接通过 BeanOutputConverter 解析；如果 Jackson 解析失败，就尝试在本地修复常见 JSON 格式问题，然后再解析一次。如果仍然失败，就把异常抛给外层重试流程。

## 4. 结构化输出的重试与网络重试有什么区别？

**回答：** 结构化输出重试的核心不是连接失败，而是模型返回内容无法解析成目标 JSON 对象。当前最大尝试次数是两次，也就是首次调用加一次重试；重试时会把上一次错误信息和严格 JSON 要求加入新的 System Prompt。网络超时、连接异常和 Provider 5xx 则由 ChatModel 中的 RetryTemplate 负责。

## 5. 为什么结构化输出最好使用 Plain ChatClient？

**回答：** Plain Client 不注册 SkillsTool，也不加入对话记忆，能够避免模型触发 Tool Call 或带入历史上下文后污染 JSON。结构化调用要求响应能够稳定转换成 DTO，因此出题、评分和 JD 解析等场景最好从 LlmProviderRegistry 获取 Plain Client。

## 6. 所有 LLM 调用都会经过 StructuredOutputInvoker 吗？

**回答：** 不会。它只用于需要结构化返回的场景，不是唯一的 LLM 调用入口。Default ChatClient 的普通自然语言回答、语音流式调用以及 ASR、TTS 等场景可以直接调用，不需要经过 StructuredOutputInvoker。
