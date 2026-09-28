# LLM 底层通信与 OpenAiApi

## 1. Spring AI 从 ChatClient 到模型厂商的调用链是什么？

**回答：** 项目中的 LLM 底层通信本质上是 HTTPS API 调用。业务层通过 `ChatClient` 发起调用，ChatClient 再委托给 `OpenAiChatModel`，OpenAiChatModel 通过 `OpenAiApi` 把 System Prompt、User Prompt、model、temperature 等参数封装成 OpenAI-compatible JSON，最后请求模型厂商的 `/chat/completions` 接口。

<br />

llm底层是通过spring ai提供的openaiapi与llm进行通信，底层还是https调用，底层openaiapi会把系统提示词，用户提示词，模型，温度等信息封装成json。然后请求llm厂商的接口。

## 2. `OpenAiApi` 的职责是什么？

**回答：** `OpenAiApi` 是 Spring AI 对 OpenAI-compatible 接口的封装，负责把上层 Prompt 和 Messages 组装成兼容格式的 JSON 请求体，通过底层 HTTP 客户端发送到 Provider 的 BaseURL，再把 Provider 返回的 JSON 或流式 Token 解析后交给上层模型和 ChatClient。

<br />

通信底层，负责封装请求信息为json并请求llm的接口。

## 3. 为什么可以用 OpenAI-compatible 协议调用 DashScope？

**回答：** DashScope 提供了 OpenAI 兼容接口，因此项目可以直接使用 Spring AI 的 OpenAI 模块，不需要再单独编写 DashScope SDK 适配层。以后切换 DeepSeek、Kimi 或智谱 GLM 等兼容 Provider 时，只需要修改 BaseURL、API Key 和 model，上层业务代码不用变化。

<br />

<br />

## 4. 普通调用和流式调用分别使用什么 HTTP 客户端？

**回答：** 普通非流式 `call()` 底层走 `RestClient`，语音面试等 `.stream()` 流式调用走 `WebClient`。项目分别给 RestClient 和 WebClient 挂载 Interceptor 和 Filter（两个作用都是记录http请求信息到数据库），用于记录每一次真实 HTTP 请求的状态码、耗时、成功状态和异常信息。、、

<br />

llm可以用call同步调用也可以用stream流式调用，同步调用是底层restclient，而流式是走的webclient。我的项目给这两种请求方式都挂载了拦截器和过滤器，记录每次http请求通信的状态码，耗时等信息用于记录到日志里面评估llm的调用状态。

## 5. 连接超时和读取超时是怎么配置的？

**回答：** 项目通过 `SimpleClientHttpRequestFactory` 配置底层 HTTP Client，连接超时是建立连接最多等待 10 秒，读取超时是等待模型返回最多 5 分钟。这两个超时属于真正访问模型服务时的网络通信配置。

<br />

建立连接最多等待五米，等待模型返回最多等待五分钟，超过了之后就会重试，重试次数也是由llm底层的retrytemplate配置的，重试10次。

## 6. Prompt 最终是如何转换成 HTTP JSON 请求的？

**回答：** 业务代码传入 System Prompt、User Prompt、模型名称和 temperature，Spring AI 会把它们转换成包含 `model`、`messages` 和 `temperature` 等字段的 JSON，然后携带 `Authorization: Bearer <api-key>` 请求 `/chat/completions`。模型返回的 `choices.message.content` 会被解析成 `ChatResponse` 或流式 Token。

<br />

spring ai底层会将其转成json，然后请求llm官方提供的接口。

## 7. `OpenAiApi` 是否等于 OpenAI 官方服务？

**回答：** 不等于。`OpenAiApi` 表示 Spring AI 对 OpenAI 兼容协议的一层封装，不限定请求必须发给 OpenAI 官方服务。只要厂商提供兼容接口，DashScope、DeepSeek、Kimi 等模型都可以使用这一套调用方式。
