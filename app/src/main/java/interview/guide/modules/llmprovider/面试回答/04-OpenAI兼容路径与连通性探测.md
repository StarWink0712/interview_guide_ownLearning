# OpenAI 兼容路径与连通性探测

## 1. 模块基础讲解

模块通过 Spring AI `OpenAiChatModel`/`OpenAiEmbeddingModel` 接入多个厂商，前提是对方提供 OpenAI
兼容协议。`ApiPathResolver` 解决最常见的 baseUrl 重复版本问题：

- baseUrl 末尾是 `/v1`、`/v3`、`/v4` 等版本段时，显式设置短路径
  `/chat/completions` 和 `/embeddings`。
- baseUrl 不带版本段时，沿用 Spring AI 默认 `/v1/chat/completions` 和 `/v1/embeddings`。
- 先去掉尾部 `/`，避免双斜杠。

例如：

```text
https://dashscope.../compatible-mode/v1
  + /chat/completions
  = https://dashscope.../compatible-mode/v1/chat/completions

http://localhost:1234
  + Spring AI 默认 /v1/chat/completions
  = http://localhost:1234/v1/chat/completions
```

Provider 连通性测试不复用 ChatClient，而是直接构建 `RestClient`，使用 5 秒连接、10 秒读取超时，
发送 `max_tokens=1`、要求回复 OK 的最小请求。候选路径优先 `{base}/chat/completions`，base 不带
版本时再尝试 `{base}/v1/chat/completions`。

ASR 测试更浅，只解析 WebSocket URL 后执行 TCP connect，不能证明 WebSocket 握手、认证、模型名
或音频协议可用。

## 2. 基础面试问题

**问题：为什么 baseUrl 已带 `/v1` 会出现重复路径？**

回答：客户端 SDK 常把 `/v1/chat/completions` 当默认相对路径。如果用户把 `/v1` 也放进 baseUrl，
直接拼接就变成 `/v1/v1/chat/completions`。Resolver 根据末尾版本段改用短路径。

**问题：连通性测试为什么必须带模型名？**

回答：只能连通域名并不代表模型可调用。最小推理请求同时验证 DNS/TLS、认证、API 路径、模型权限
和基本协议兼容性。

**问题：测试返回成功是否代表所有业务一定成功？**

回答：不代表。它没有验证流式响应、工具调用、长上下文、JSON 输出、Embedding、配额和高并发，
只能证明最小非流式 Chat 请求可用。

**问题：为什么 ASR TCP 成功不等于 ASR 可用？**

回答：TCP 只证明目标 host:port 可达，没有完成 TLS/WebSocket Upgrade、Authorization、模型会话和
音频帧交互。它应命名为“网络可达性测试”，不能表述为完整配置验证。

## 3. 进阶面试问题

**问题：当前版本识别正则有什么边界？**

回答：只识别末尾 `/v\d+[a-zA-Z0-9]*`。`/v1beta` 能识别，但 `/v1-beta`、末尾查询参数、
`/openai/deployments/x` 等厂商路径不会按预期处理。通用兼容层应允许显式配置 chatPath 和
embeddingPath，而不是只靠启发式。

**问题：连通性测试为什么存在 SSRF 风险？**

回答：管理员可配置任意 baseUrl，服务端随后主动请求该地址。如果接口未鉴权，攻击者可探测
localhost、云元数据地址和内网服务，还可能把 Authorization Header 发送到恶意主机。必须限制协议、
DNS/IP、端口和重定向，并保护管理 API。

**问题：错误正文返回前端有什么风险？**

回答：当前截取上游响应前 200 字符并放进 `ProviderTestResult.message`。上游可能回显内部 URL、
账号、请求片段或调试信息。日志带堆栈是有用的，但用户响应应映射为安全错误类别和 correlationId。

**问题：为什么测试逻辑和真实客户端路径可能漂移？**

回答：测试用自己的 URL 候选拼接，真实调用走 `ApiPathResolver + OpenAiApi`。两套算法修改不同步时，
会出现“测试成功、业务 404”。已有 HTTP 回归测试覆盖 `/v1`、无版本和 `/v3`，但最好复用同一个
EndpointDescriptor。

**问题：在读锁中探测网络有什么后果？**

回答：探测最长阻塞数秒，期间配置写锁无法获取，管理更新被无谓阻塞。应先复制配置快照，释放锁，
再做外部调用。

## 4. 技术延伸问题

**问题：如何支持非 OpenAI 兼容厂商？**

回答：定义 `ProviderAdapter` 接口，为 OpenAI、Anthropic、Ollama 等实现各自模型工厂；配置中增加
protocol/type。Registry 按类型选择 Adapter，而不是所有厂商强行塞进 OpenAiChatModel。

**问题：生产健康检查应该主动还是被动？**

回答：二者结合。被动指标来自真实请求的延迟、429、5xx；主动探测低频验证端点，但会消耗配额。
路由决策以滑动窗口健康度为主，避免一次探测失败就切流。

**问题：如何测试路径兼容层？**

回答：用本地 `HttpServer`/MockWebServer 捕获真实请求路径，参数化覆盖尾斜杠、`/v1`、`/v1beta`、
`/api/v3`、自定义路径、重定向和 Embedding。不要只单测字符串拼接。

## 5. 改进方案问题

**问题：如何消除测试与真实路径漂移？**

回答：让 `ApiPathResolver` 返回结构化 `ResolvedApiEndpoint(baseUrl, chatPath, embeddingPath)`；
OpenAiApi 和健康探测都使用它。配置可覆盖路径，启发式只作为默认值。

**问题：如何落地 SSRF 防护？**

回答：只允许 `https`，本地开发 Provider 通过显式 allowlist 开放 `http`；解析 DNS 后拒绝 loopback、
link-local、private、multicast 和云元数据地址；禁止重定向或逐跳重新校验；限制端口；设置请求大小、
超时和并发；管理 API 强制 RBAC 与审计。

**问题：连通性测试结果如何设计更专业？**

回答：返回阶段化结果：DNS、TCP/TLS、HTTP、AUTH、MODEL、PROTOCOL；包含安全的错误码、耗时、
目标模型和 requestId，不回传原始响应正文。Embedding 和 Voice 单独提供能力测试。

**问题：如何减少测试请求成本？**

回答：保持最小 token，设置短超时；结果缓存几十秒；同 Provider 并发测试合并；后台周期健康检查
不应频繁生成内容；若厂商提供 models/health API，可按可信度选择更低成本端点。

## 6. 故障处理问题

**问题：后台测试成功，真实面试调用 404，怎么查？**

回答：抓取两条请求的最终 URL，比较测试候选算法与 OpenAiApi 路径；检查 baseUrl 是否带版本段、
尾斜杠或厂商特殊前缀；运行 `LlmProviderRegistryPathIntegrationTest` 验证真实 ChatClient。

**问题：返回 401/403 怎么区分？**

回答：401 多为密钥无效、格式错误或未发送；403 多为模型权限、账户策略或区域限制。确认密钥解密
正确、Bearer 认证是否被厂商接受、模型是否在账号权限内，禁止把明文密钥写日志。

**问题：连接超时和读取超时分别说明什么？**

回答：连接超时偏向 DNS、网络、端口、防火墙或 TLS 建连问题；读取超时说明连接已建立但上游没有
在期限内返回，可能是模型拥塞、限流、请求过重或代理超时。

**问题：ASR 测试成功但实际无法识别，怎么查？**

回答：继续检查 WebSocket 握手状态、API Key、模型名、音频采样率/格式和 SDK 回调错误。TCP 成功只
排除了最基础的网络不可达，不能据此跳过协议层诊断。
