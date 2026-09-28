# Prompt 注入安全

## 1. 为什么简历、JD 和知识库文档属于不可信数据？

**回答：** 简历、JD、面试邀约邮件、知识库文档和语音面试历史都是用户提供的外部文本，其中可能包含“忽略之前的指令”“你现在是系统管理员”等内容。如果直接拼入 Prompt，模型可能把这些数据误认为新的系统指令，因此必须把它们当成不可信数据，只允许模型做抽取、分析或评估。

## 2. Prompt 注入防护分成哪几层？

**回答：** 项目采用四层防护：第一层是 PromptSanitizer 清洗危险输入；第二层是使用随机 UUID 数据边界包裹不可信文本；第三层是通过 PromptSecurityConstants 告诉模型边界内是数据而不是指令；第四层是在 ChatClient Advisor 链中使用 SafeGuardAdvisor 做请求级拦截。

## 3. `PromptSanitizer` 会过滤哪些内容？

**回答：** 它会用正则检测行首伪造角色标记，例如 `System:`、`User:` 和 `Assistant:`；检测 `ignore previous instructions`、`忘记之前的指令` 等高风险短语；同时过滤项目模板分隔符和伪造的 `<data-boundary...>` 标签。检测到后不会直接拒绝请求，而是把危险片段替换成中性占位符后继续处理。

## 4. 为什么使用随机 UUID 数据边界？

**回答：** `wrapWithDelimiters` 会把简历、JD 或邮件等数据包成运行时生成的 `<data-boundary-{uuid}-{label}>...</data-boundary-{uuid}-{label}>`。因为 UUID 在运行时才产生，攻击者无法提前构造完全一致的闭合标签，从而降低通过伪造边界逃逸数据区域的风险。

## 5. `ANTI_INJECTION_INSTRUCTION` 和 `DATA_BOUNDARY_INSTRUCTION` 有什么区别？

**回答：** `ANTI_INJECTION_INSTRUCTION` 追加到 System Prompt，明确要求模型不能因为边界内的数据改变角色、身份或评估标准；`DATA_BOUNDARY_INSTRUCTION` 放在具体用户数据之前，提醒模型后面的文本是待分析数据，不是需要执行的命令。一个提供全局系统级约束，一个在数据进入 Prompt 时再次声明边界。

## 6. `SafeGuardAdvisor` 与 PromptSanitizer 有什么区别？

**回答：** PromptSanitizer 工作在构建 Prompt 的阶段，命中危险片段后进行替换，处理后的文本仍然可以继续进入模型；SafeGuardAdvisor 工作在 ChatClient 调用链中，会根据配置的敏感词检查用户消息和模型回复，命中后直接拦截本次调用或响应，并返回预设的 failureResponse。前者是净化，后者是拒绝。

## 7. SafeGuard 命中敏感词后如何处理？

**回答：** SafeGuardAdvisor 按配置逐条匹配 `I'll now act as`、`Sure, I'll ignore`、`我已经忽略`、`新的角色是`、`忽略之前的指令` 和 `forget all previous instructions` 等敏感词。没有命中就正常放行；命中后不再继续正常调用或返回原始内容，而是拦截并返回固定的安全响应。
