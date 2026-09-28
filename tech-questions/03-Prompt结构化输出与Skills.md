# Prompt、结构化输出与 Skills

## 1. 面试官：项目为什么把 Prompt 放在资源文件中，而不是写在 Java 字符串里？

我的回答：

Prompt 是可迭代的业务资产，和控制流代码具有不同变化频率。我的项目把简历评分、面试出题、自适应评估和语音角色提示词放在 `resources/prompts` 的 `.st` 文件中，通过 `PromptTemplate` 加载并传入变量；Properties 保存模板路径，Service 只负责编排。这样 Prompt 更易审阅、对比和按场景复用，也避免大段字符串污染 Java。当前模板没有统一版本号、灰度发布和在线回滚，修改后通常随应用发布。生产上我会记录 promptVersion 到调用日志，并通过测试集比较新旧版本后再放量。

代码依据：

- `app/src/main/resources/prompts/` — Prompt 模板目录
- `app/src/main/java/interview/guide/modules/resume/service/ResumeAnalysisProperties.java` — 模板路径配置
- `app/src/main/java/interview/guide/modules/resume/service/ResumeGradingService.java` — `PromptTemplate`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionProperties.java` — 出题模板配置

## 2. 面试官：项目如何让大模型返回可解析的结构化 JSON？

我的回答：

结构化输出是把模型自由文本约束为目标类型，便于后续业务安全使用。我的项目为目标 record 创建 `BeanOutputConverter`，把 converter 的格式说明追加到 system prompt，再由 plain ChatClient 调用模型；返回文本交给 converter 转为 Java 对象，之后还会对分数、列表和空字段做业务归一化。简历评分、问题生成、单轮评估和自适应决策都复用这一方式。它比手写 JSON 字符串解析一致，但仍是提示词约束，不等于 Provider 原生 schema 保证。后续如果模型支持 JSON Schema，我会优先启用原生 structured output，并保留转换校验。

代码依据：

- `app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java` — `invoke()`
- `app/src/main/java/interview/guide/modules/resume/service/ResumeGradingService.java` — `BeanOutputConverter`
- `app/src/main/java/interview/guide/modules/interview/adaptive/TurnEvaluationService.java` — `outputConverter`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java` — 题目结构化输出

## 3. 面试官：JSON 解析失败为什么需要重试，项目怎样构造修复请求？

我的回答：

模型可能返回 code fence、多余解释、缺字段或未转义引号，即使语义正确也无法反序列化。我的项目的 `StructuredOutputInvoker` 捕获转换异常，保留经过截断的错误原因；如果开启 repair prompt，就在下一次 system prompt 中强调只输出严格 JSON，并注入上次错误帮助模型定向修正，最大尝试次数由 `StructuredOutputProperties` 控制。每次 attempt 和最终 invocation 都记录指标与日志。这样重试针对格式问题，而不是简单重复同一请求。边界是额外调用增加费用和延迟，业务必须设置小上限，不能对确定性 schema 不匹配无限重试。

代码依据：

- `app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java` — 修复重试循环
- `app/src/main/java/interview/guide/common/ai/StructuredOutputProperties.java` — 重试配置
- `app/src/main/resources/application.yml` — `structured-*`
- `app/src/test/java/interview/guide/common/ai/StructuredOutputInvokerTest.java` — 重试测试

## 4. 面试官：本地 JSON 修复能解决什么问题，为什么不能过度依赖？

我的回答：

本地修复适合确定且可逆的格式瑕疵，可以省下一次模型请求。我的项目在 converter 失败后会尝试处理 JSON 字符串中部分未转义引号等情况，再重新解析；如果仍失败才进入模型修复重试。这样对常见模型输出噪声有韧性，并有单测覆盖。但本地算法不知道业务语义，错误地“修好”字符串可能让无效数据通过，也无法补齐缺失对象或纠正类型。生产上我会把修复限定在严格白名单，修复后仍执行 Bean Validation 和业务范围校验，同时记录原始响应哈希与修复类型用于评估。

代码依据：

- `app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java` — 本地修复逻辑
- `app/src/test/java/interview/guide/common/ai/StructuredOutputInvokerTest.java` — 引号修复测试
- `app/src/main/java/interview/guide/modules/interview/adaptive/TurnEvaluationService.java` — 结果归一化

## 5. 面试官：为什么结构化输出要使用 plain ChatClient？

我的回答：

结构化场景要求模型响应只包含目标 JSON，而工具调用、会话记忆或日志 Advisor 可能插入额外消息并改变输出。我的项目专门提供 `getPlainChatClient`，它保留 Provider、模型、HTTP 重试和观测，却不注册 SkillsTool 与对话记忆；`TurnEvaluationService` 等结构化服务明确使用它。默认 ChatClient 则服务需要工具或普通对话的场景。这个隔离比在每个调用点临时关闭 Advisor 更可靠。当前仍需防止模型自身输出解释文本，所以 plain 不是格式保证；它只是减少外部干扰，最终仍由 converter、重试和业务校验把关。

代码依据：

- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java` — `getPlainChatClient()`
- `app/src/main/java/interview/guide/modules/interview/adaptive/TurnEvaluationService.java` — plain 客户端调用
- `app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java` — 最终解析

## 6. 面试官：项目如何防御 Prompt Injection，当前方案有哪些局限？

我的回答：

Prompt Injection 是用户内容伪装成系统指令，诱导模型忽略原任务或调用不当工具。我的项目用 `PromptSanitizer` 检测和过滤常见角色标记、忽略指令语句与伪造边界标签，再用明确 delimiter 包裹简历、JD 和问题；RAG 与邀请解析还追加固定安全约束，声明检索内容只是数据。这样能降低直接攻击概率。当前主要是正则和提示词层防护，既可能误伤也能被变体绕过，更不能替代授权。生产上还需工具白名单、参数校验、最小权限、敏感输出检测和对抗样本回归。

代码依据：

- `app/src/main/java/interview/guide/common/ai/PromptSanitizer.java` — `sanitize()`
- `app/src/main/java/interview/guide/common/ai/PromptSecurityConstants.java` — 安全约束
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java` — RAG Prompt
- `app/src/main/java/interview/guide/modules/interviewschedule/service/InterviewParseService.java` — 邀请解析

## 7. 面试官：项目中的 Skill 是如何加载并参与出题的？

我的回答：

Skill 把某一技术方向的说明、分类权重和参考资料组织成可复用资源。我的项目由 `InterviewSkillService` 扫描 `resources/skills` 下的 `SKILL.md`、`skill.meta.yml` 和 references，解析 front matter、分类与文档并缓存；创建面试时根据技能与题目总数计算各分类配额，`InterviewQuestionService` 选择相应参考内容、历史题和难度注入 Prompt。另一条路径由 `AgentUtilsConfiguration` 把同一根目录注册成 `SkillsTool`。确定性注入保证出题一定使用选中的资料，不依赖模型是否主动调用工具。

代码依据：

- `app/src/main/java/interview/guide/modules/interview/skill/InterviewSkillService.java` — `loadSkills()`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java` — 分类出题
- `app/src/main/java/interview/guide/common/ai/AgentUtilsConfiguration.java` — `skillsTool()`
- `app/src/main/resources/skills/` — Skill 资源

## 8. 面试官：Skills 和 Tool Calling 有什么区别，项目是否实现了完整 Agent？

我的回答：

Skill 更像可按需加载的知识与操作说明，Tool Calling 是模型输出结构化函数调用，由应用真正执行外部动作；完整 Agent 还要包含规划、状态、循环、权限和终止条件。我的项目已经注册 spring-ai-agent-utils 的 `SkillsTool`，默认 ChatClient 可获得工具能力，也直接读取 Skill 资料用于出题。但没有多步规划器、Agent 状态机、工具审批、多 Agent 协作或完整执行轨迹。因此我会称它为 Skill 与工具接入，不会宣称实现了完整 Agent。若扩展，应先限定只读工具、参数 schema、超时和审计，再增加受控循环。

代码依据：

- `app/src/main/java/interview/guide/common/ai/AgentUtilsConfiguration.java` — `SkillsTool`
- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java` — `buildToolCallAdvisor()`
- `app/src/main/java/interview/guide/modules/interview/skill/InterviewSkillService.java` — Skill 读取
- `app/build.gradle` — agent-utils 依赖

## 9. 面试官：为什么 Skill 不能一次全部塞进上下文，Skill 多了应如何召回？

我的回答：

一次加载所有 Skill 会消耗上下文、增加延迟和费用，还会让相似指令互相干扰。我的项目当前由用户显式选择 skillId，并按分类只加载相关 `SKILL.md` 与 reference，题目分配也限制在选中方向；这在技能规模较小时简单可靠。项目尚未实现面向大量 Skill 的语义动态召回，也没有 Skill embedding 索引。继续扩展时，我会先用元数据按岗位、语言和场景过滤，再对 Skill 摘要做向量或关键词召回，选 Top-N 后按需读取完整内容，并记录召回结果用于评测，避免让模型在几百个工具描述中盲选。

代码依据：

- `app/src/main/java/interview/guide/modules/interview/skill/InterviewSkillService.java` — `getSkill()`
- `app/src/main/java/interview/guide/modules/interview/skill/InterviewSkillService.java` — `calculateQuestionAllocation()`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java` — reference 选择
- `app/src/main/resources/skills/` — 当前技能规模

## 10. 面试官：如何测试和版本化 Prompt、结构化输出与 Skill？

我的回答：

这类资产不能只靠人工试聊，应把格式、业务质量和回归分别验证。我的项目已经对 `StructuredOutputInvoker` 的成功、重试和修复做单测，对自适应评估用 Mockito 固定模型结果，Skill 加载与实际出题通过 Service 路径使用；RAG 还有独立评测数据集。当前缺少统一 Prompt 版本、黄金样本、Skill 召回准确率和模型升级对比。生产上我会给 Prompt/Skill 内容计算版本哈希，调用日志保存版本，CI 先跑 schema 与快照测试，再在固定问题集上比较正确率、稳定性、token 和延迟，最后灰度发布。

代码依据：

- `app/src/test/java/interview/guide/common/ai/StructuredOutputInvokerTest.java` — 结构化输出测试
- `app/src/test/java/interview/guide/modules/interview/adaptive/TurnEvaluationServiceTest.java` — Prompt 调用测试
- `app/src/main/java/interview/guide/modules/aiobservability/service/RagEvaluationService.java` — 评测框架
- `app/src/main/java/interview/guide/modules/aiobservability/service/AiInvocationLogService.java` — 调用日志

# 本章总结

Prompt 通过资源模板管理，结构化输出通过 plain ChatClient、BeanOutputConverter、本地修复和模型重试提高可靠性；Skill 则以资源扫描、分类配额和可选工具方式接入。

项目尚未形成 Prompt/Skill 版本治理、大规模动态召回或完整 Agent，生产化重点是可评测、可审计和最小权限。

# 核心主线

把 Prompt 和 Skill 当作可版本化资产，用受控客户端与严格转换把概率模型输出收敛为可用业务数据。
