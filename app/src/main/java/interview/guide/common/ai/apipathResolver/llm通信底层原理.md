对，总结就是：

```
Spring AI（openai 模块） → HTTPS → OpenAI 兼容 API 格式 → 阿里云 DashScope
```

- **协议**：HTTPS
- **格式**：OpenAI 兼容的 JSON（`/chat/completions` 接口）
- **实际调用的模型**：阿里云的通义千问（qwen-plus）
- **好处**：哪天想换 Kimi、DeepSeek、智谱 GLM，只需改 `base-url` 和 `api-key`，代码一行不用动

<br />

**`SimpleClientHttpRequestFactory`** 是 Spring 框架提供的一个类，位于 `org.springframework.http.client` 包下。

它的作用相当于一个 **"HTTP 请求的配置模板"**。你把超时时间等参数设好之后，Spring 每次发起 HTTP 请求时就会按照这个模板来创建连接。具体来说：

- `setConnectTimeout(10000)` — 告诉底层："建立 TCP 连接最多等 10 秒，连不上就报错"
- `setReadTimeout(300000)` — 告诉底层："连接建立后，等服务端返回数据最多等 5 分钟"

这里的流程是：

```
SimpleClientHttpRequestFactory（配置超时参数）
        ↓ 传入
RestClient.Builder（Spring 的 HTTP 客户端构造器）
        ↓ 传入
OpenAiApi.Builder（Spring AI 的 OpenAI API 构造器）
        ↓ build()
OpenAiApi（最终可用的 API 实例）
```

简单理解：**请求工厂就是一套"造连接"的配方**，里面存了超时等参数。实际发请求时，Spring 按这个配方创建一个新的 HTTP 连接去调用 LLM 接口。

底层通过 **HTTPS + REST API**，协议层面就是普通的 HTTP/HTTPS。

***

### 具体配置

```yaml
# application.yml
dashscope:
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: ${AI_BAILIAN_API_KEY}
```

***

### 通信方式

```
Spring Boot 应用
  │
  │ HTTP POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
  │ Content-Type: application/json
  │ Authorization: Bearer <API_KEY>
  │
  │ Body（OpenAI 兼容格式）:
  │ {
  │   "model": "qwen-plus",
  │   "messages": [
  │     { "role": "system", "content": "你是一个面试官..." },
  │     { "role": "user",   "content": "请根据简历出10道Java面试题" }
  │   ],
  │   "response_format": { "type": "json_object" }
  │ }
  │
  ▼ 阿里云 DashScope 服务器
  │
  │ 处理 Prompt → 模型推理 → 返回 JSON
  │
  ▼
  │ {
  │   "choices": [{
  │     "message": {
  │       "content": "{ \"questions\": [...] }"
  │     }
  │   }]
  │ }
```

***

### 简单说就是

**一次普通的 HTTPS 请求**。Java 代码里用 Spring AI 框架封装的 HTTP 客户端，把 Prompt 打包成 JSON 发到阿里云的 API 地址，那边的大模型处理完再返回 JSON 结果。

和其他 HTTP API（比如提交答案的 `POST /api/interview/sessions/{id}/answers`）在协议层面没有本质区别——都是 HTTP + JSON，只是接收端不是自己的后端，而是阿里云的 LLM 服务器。

### 为什么说它是"iOS 兼容"？

`/compatible-mode/v1` 这个路径意味着阿里云的 API 完全兼容 OpenAI 的接口格式。所以 Spring AI 的 `openai` 模块可以直接对接，不需要专门写 DashScope 适配代码。配置里也预留了 Kimi、DeepSeek、智谱 GLM 等其他兼容 OpenAI 格式的 Provider，换个 `base-url` + `api-key` 就能切。
