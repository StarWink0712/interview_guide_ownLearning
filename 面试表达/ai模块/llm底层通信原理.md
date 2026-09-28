项目里 LLM 底层通信本质上就是一次 HTTPS API 调用。业务层不会直接拼 HTTP 请求，而是通过 Spring AI 的 ChatClient 调用模型。ChatClient 底层会委托给 OpenAiChatModel，OpenAiChatModel 再通过 OpenAiApi 把 system prompt、user prompt、model、temperature 等参数封装成 OpenAI 兼容格式的 JSON，请求到模型厂商的 /chat/completions 接口。
我的项目用的是 OpenAI-compatible 方式对接阿里云 DashScope，比如 baseUrl 是 <https://dashscope.aliyuncs.com/compatible-mode/v1。因为> DashScope 兼容 OpenAI 的请求格式，所以 Spring AI 的 openai 模块可以直接调用，不需要单独写一个 DashScope SDK 适配层。以后如果要切 DeepSeek、Kimi、智谱 GLM，只需要换 provider 的 baseUrl、apiKey 和 model，上层业务代码不用改。
在创建 OpenAiApi 时，我还配置了底层 HTTP Client。普通非流式调用走 RestClient，流式语音面试走 WebClient。我给它们分别挂了 interceptor 和 filter（前者是restclient用的，后者是webclient用的），用来记录每次真实 HTTP 请求的状态码、耗时、是否成功和异常信息。同时通过 SimpleClientHttpRequestFactory 设置连接超时和读取超时，连接超时是建立连接最多等 10 秒，读取超时是等待模型返回最多 5 分钟。

<br />

openaiapi底层通信其实就是把提示词整理成json格式，然后向llm的地址发送http请求。

对，核心理解就是这样。

`OpenAiApi` 底层做的事情可以概括为：

```
Prompt / Messages
  ↓ Spring AI 封装
OpenAI-compatible JSON
  ↓ HTTP/HTTPS 请求
LLM Provider 的接口地址
  ↓
返回 JSON / 流式 token
```

比如普通对话大概会变成这样的请求：

```
POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
Authorization: Bearer <api-key>
Content-Type: application/json
```

请求体类似：

```
{
  "model": "qwen-plus",
  "messages": [
    { "role": "system", "content": "你是一个面试官..." },
    { "role": "user", "content": "请根据这份简历生成面试问题..." }
  ],
  "temperature": 0.2
}
```

然后模型厂商返回：

```
{
  "choices": [
    {
      "message": {
        "content": "模型生成的回答"
      }
    }
  ]
}
```

你可以在面试里这样讲：

> `OpenAiApi` 是 Spring AI 对 OpenAI-compatible 接口的一层封装。业务代码里传的是 system prompt、user prompt、model、temperature 等参数，Spring AI 会把这些内容组装成 OpenAI 兼容的 JSON 请求体，然后通过底层 HTTP 客户端请求配置的 baseUrl，比如 DashScope 的 `/chat/completions` 接口。Provider 返回的也是 JSON 或流式 token，Spring AI 再把它解析成 `ChatResponse` 交给上层 `ChatClient` 使用。

注意一点：`OpenAiApi` 不等于 OpenAI 官方服务，它只是使用 OpenAI 兼容协议。所以 DashScope、DeepSeek、Kimi 这类兼容接口都可以接。
