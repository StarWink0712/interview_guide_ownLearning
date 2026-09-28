# AgentUtils 与 ApiPathResolver 怎么讲

## 1. 这篇讲哪两个能力

`common.ai` 里除了 Provider 注册和结构化输出，还有两个容易被忽略但面试很好讲的工程点：

```text
AgentUtilsConfiguration / AgentUtilsProperties
  -> 把面试技能文件注册为 LLM 可调用的 SkillsTool

ApiPathResolver
  -> 适配不同 OpenAI-compatible 厂商的 API 路径差异
```

前者体现“模型不只是聊天，还能调用工具”；后者体现“多厂商接入时处理过真实兼容性问题”。

## 2. AgentUtilsConfiguration 的定位

面试可以这样说：

> `AgentUtilsConfiguration` 是工具调用能力的入口。它读取 `app.ai.agent-utils.skills-root` 配置，加载 `resources/skills/**/SKILL.md`，构建 `SkillsTool`，并注册成名为 `interviewSkillsToolCallback` 的 `ToolCallback` Bean。之后 `LlmProviderRegistry` 创建 ChatClient 时会把这个 ToolCallback 注册进去，让模型在对话过程中可以调用预定义的面试技能。

调用关系：

```text
application.yml
  app.ai.agent-utils.skills-root = classpath:skills
        |
        v
AgentUtilsProperties
        |
        v
AgentUtilsConfiguration.interviewSkillsToolCallback()
        |
        v
SkillsTool.builder().addSkillsResource(skillsRootResource).build()
        |
        v
ToolCallback Bean: interviewSkillsToolCallback
        |
        v
LlmProviderRegistry.createChatClient/createVoiceChatClient
        |
        v
ChatClient.defaultToolCallbacks(interviewSkillsToolCallback)
```

## 3. SkillsTool 解决什么问题

普通 LLM 调用：

```text
用户输入 -> 模型生成文本
```

带工具调用：

```text
用户输入
  -> 模型判断需要某个工具
  -> ToolCallAdvisor / ToolCallingManager 执行工具
  -> 工具结果返回给模型
  -> 模型基于工具结果继续生成
```

对面试平台来说，工具可以代表：

- 面试技能说明；
- 出题规则；
- 评分标准；
- 某个技能方向的知识结构；
- 预定义工作流。

面试表达：

> 这样做的好处是技能知识不一定都写死在 Java 代码里，可以通过 `SKILL.md` 文件组织，让模型在需要时调用。Java 侧只负责把技能目录注册成工具能力，技能内容本身可以按资源文件维护。

## 4. ToolCallback 和 ToolCallingManager 怎么区分

这是很容易被问到的点。

| 概念 | 作用 |
|---|---|
| `ToolCallback` | 描述有哪些工具、工具叫什么、参数是什么、怎么被模型看到 |
| `ToolCallingManager` | 当模型真的请求调用工具时，负责执行工具调用 |
| `ToolCallAdvisor` | Advisor 链中的工具调用拦截器，把模型的 tool call 和执行结果串起来 |

面试回答：

> 可以理解为 ToolCallback 是“工具说明书”，ToolCallingManager 是“工具执行器”，ToolCallAdvisor 是“把模型输出里的工具调用请求拦下来并执行”的链路组件。Registry 创建 Default 和 Voice Client 时会注册 ToolCallback；如果配置开启 ToolCallAdvisor 且容器里有 ToolCallingManager，模型才能真正完成工具调用闭环。

## 5. 为什么 Plain Client 不注册 SkillsTool

这点可以和结构化输出联动讲。

> Plain Client 是给结构化 JSON 输出用的，比如出题、评分、自适应决策。这些场景最重要的是返回格式稳定。如果注册 SkillsTool，模型可能中途触发工具调用，响应里混入工具调用消息，导致 `BeanOutputConverter` 解析失败。所以 Plain Client 不注册 SkillsTool，只保留 SafeGuardAdvisor。

Default / Voice 才注册：

```text
Default Client
  -> 可带 SkillsTool + Advisor

Voice Client
  -> SkillsTool + 流式 ToolCallAdvisor

Plain Client
  -> 不带 SkillsTool，专注结构化输出
```

## 6. normalizeSkillsRoot 怎么讲

`AgentUtilsConfiguration` 对配置路径做了规范化：

```text
null / 空白
  -> classpath:skills

Windows 路径反斜杠
  -> 替换成 /

配置到 /SKILL.md 文件
  -> 截断为目录

包含 *
  -> 截断通配符之前部分

末尾多余 /
  -> 移除
```

面试表达：

> 这是一个小但实用的健壮性处理。配置项经常会因为环境或人为填写有不同格式，比如 Windows 路径、通配符、误填到文件级别。我在启动时做规范化和存在性校验，目录不存在直接 fail fast，避免运行到模型调用时才发现工具加载失败。

## 7. ApiPathResolver 的定位

面试可以这样说：

> `ApiPathResolver` 是一个 OpenAI-compatible API 适配器。Spring AI 的 `OpenAiApi` 默认会在 baseUrl 后拼 `/v1/chat/completions` 和 `/v1/embeddings`。但很多厂商的 baseUrl 本身已经带版本号，比如 DashScope 是 `/compatible-mode/v1`，Kimi 是 `/v1`，GLM 可能是 `/api/coding/paas/v4`。如果不适配，就会拼成 `/v1/v1/chat/completions` 或 `/v4/v1/chat/completions`，请求直接 404。

## 8. ApiPathResolver 的核心逻辑

入口：

```java
ApiPathResolver.buildOpenAiApi(baseUrl, apiKey)
```

内部：

```text
1. 创建 SimpleClientHttpRequestFactory
   connectTimeout = 10s
   readTimeout = 5min

2. 创建 RestClient.Builder
   绑定 requestFactory

3. 创建 OpenAiApi.Builder
   设置 baseUrl、apiKey、restClientBuilder

4. 判断 baseUrl 是否以版本段结尾
   正则：/v\d+[a-zA-Z0-9]*$

5. 如果 baseUrl 已带版本号
   completionsPath = /chat/completions
   embeddingsPath = /embeddings

6. 如果 baseUrl 不带版本号
   使用 Spring AI 默认路径 /v1/chat/completions
```

例子：

```text
baseUrl = https://api.openai.com
请求路径 = /v1/chat/completions

baseUrl = https://api.moonshot.cn/v1
请求路径 = /v1/chat/completions
不是 /v1/v1/chat/completions

baseUrl = https://dashscope.aliyuncs.com/compatible-mode/v1
请求路径 = /compatible-mode/v1/chat/completions
不是 /compatible-mode/v1/v1/chat/completions

baseUrl = https://open.bigmodel.cn/api/coding/paas/v4
请求路径 = /api/coding/paas/v4/chat/completions
不是 /api/coding/paas/v4/v1/chat/completions
```

## 9. 为什么 readTimeout 设置 5 分钟

面试回答：

> LLM 响应可能比较慢，尤其是长简历分析、复杂面试评估或弱网环境。如果读取超时太短，会导致模型其实还在生成但客户端提前断开。连接超时可以短一些，比如 10 秒；读取超时要相对长一些，这里默认 5 分钟。

也可以补充：

> 对语音面试这种实时场景，业务层会更多使用流式响应和自己的节奏控制，不完全依赖这个同步读取时间。

## 10. 真实 bug 背景怎么讲

你可以这样说成一个“我排查过的问题”：

> 之前管理后台测试 Provider 连通性是自己拼 URL，所以能成功；但真正模拟面试调用走的是 Spring AI `OpenAiApi`，它默认又拼了一层 `/v1`，导致 DashScope compatible-mode 的地址变成 `/compatible-mode/v1/v1/chat/completions`，线上表现就是 Provider 测试成功但业务调用 404。后来我抽了 `ApiPathResolver`，并补了路径回归测试，覆盖 baseUrl 带 `/v1`、不带版本号、以及 `/api/v3` 这类场景。

对应测试：

```text
ApiPathResolverTest
LlmProviderRegistryPathIntegrationTest
```

其中集成测试用本地 `HttpServer` 记录实际收到的 path，验证不会出现重复 `/v1`。

## 11. 这个设计体现了什么工程能力

可以从三个角度讲：

### 11.1 兼容性

> 不同厂商虽然都叫 OpenAI-compatible，但 baseUrl 规范不完全一致。适配层把差异集中处理，业务层不用知道这些细节。

### 11.2 可测试性

> 路径适配逻辑是纯函数为主，容易写单元测试；真实 HTTP 路径又用本地 HttpServer 做回归测试，避免只测 builder 不测实际请求路径。

### 11.3 可维护性

> 如果以后新增厂商，只要它仍然兼容 OpenAI API，大概率只需要配置新的 baseUrl、apiKey 和 model。路径差异由 ApiPathResolver 统一处理。

## 12. 高频追问与回答

### 问：SkillsTool 是不是必须的？

答：

> 不是。它只适合需要模型主动调用工具或引用技能说明的场景。结构化输出场景反而不应该带 SkillsTool，所以我做了 Default、Plain、Voice 三种 Client 区分。

### 问：工具调用会不会很危险？

答：

> 工具调用确实需要边界控制。当前项目里工具来源是 `resources/skills` 下的受控技能文件，不是让模型任意执行系统命令。后续如果接入外部工具，还需要做权限控制、参数校验、审计日志和工具白名单。

### 问：为什么 skillsRoot 配置不用 `@Value`？

答：

> 项目规范要求配置集中到 `@ConfigurationProperties`，所以我用 `AgentUtilsProperties` 绑定 `app.ai.agent-utils.skills-root`。这样配置有类型、有默认值，也便于后续扩展更多 Agent 工具配置。

### 问：ApiPathResolver 为什么只判断结尾版本段？

答：

> 因为只有 baseUrl 末尾是版本段时，才说明厂商把版本号作为 API 根路径的一部分。如果版本号出现在中间但后面还有 path，例如 `/v1/proxy`，就不能简单认为它是最终版本根路径，否则会误改请求路径。

### 问：如果厂商不是 OpenAI-compatible 怎么办？

答：

> 当前 Registry 主要面向 OpenAI-compatible 厂商。如果后续接入非兼容协议，应该在 Provider 层继续抽象，增加不同 ChatModel 构建策略，而不是强行塞进 `OpenAiChatModel`。

