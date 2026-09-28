# testProvider：从“测试连接”按钮到远端模型的完整调用链

该接口不是简单 ping，也不是只检查端口。它会拿 Provider 当前保存的 `baseUrl + apiKey + model`，向 OpenAI 兼容的 `chat/completions` 接口发送一个最小聊天请求。

```text
POST /api/llm-provider/{id}/test
```

---

## 1. Controller 入口

```java
@PostMapping("/{id}/test")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
public Result<ProviderTestResult> testProvider(@PathVariable String id) {
  return Result.success(configService.testProvider(id));
}
```

GLOBAL 10/s 表示所有用户和 IP 共享这个 Controller 方法的每秒配额。每次测试会产生真实远端请求，可能产生少量模型调用成本。

---

## 2. 前端触发与页面状态

每个 Provider 卡片的“测试”按钮把 ID 传给 `handleTest(id)`：

```text
点击测试
  → setTestingId(id)
      → 当前卡片显示加载态
  → 删除该 id 的旧测试结果
  → llmProviderApi.test(id)
      → POST /api/llm-provider/{id}/test
  → 成功解析 DTO
      → setTestResults({ ...prev, [id]: result })
  → finally setTestingId(null)
```

页面根据 `ProviderTestResult.success` 决定绿色或红色提示，并显示 `message`。

测试结果只保存在当前 React state，不会写数据库；刷新页面后消失。

---

## 3. Controller 前的公共链路

```text
浏览器 POST
  → Axios（全局超时 60 秒）
  → Spring MVC
  → RateLimitAspect.around()
      → Redis Key:
        ratelimit:{LlmProviderController:testProvider}:global
      → Lua 滑动窗口检查 10 次/秒
  → Controller.testProvider(id)
```

路径中的 ID 原样传给 Service，不做 trim 或大小写转换。

---

## 4. Service 先取得可运行配置

```text
LlmProviderConfigService.testProvider(id)
  → rwLock.readLock().lock()
  → 根据模式取得 ProviderRuntimeConfig
      ├─ 数据库：getProviderRuntimeConfigOrThrow(id)
      └─ Legacy：toRuntimeConfig(getLegacyProviderConfigOrThrow(id))
  → doTestProvider(config, id)
  → finally 释放读锁
```

### 4.1 数据库模式

```text
providerRepository.findById(id)
  → 不存在：BusinessException(PROVIDER_NOT_FOUND)
  → 存在：ApiKeyEncryptionService.decrypt(nonce, ciphertext)
  → ProviderRuntimeConfig(
      baseUrl,
      明文apiKey,
      model,
      embeddingModel,
      embeddingDimensions,
      supportsEmbedding,
      temperature
    )
```

只有 `baseUrl`、明文 `apiKey`、`model` 会参与这次聊天测试。Embedding 字段不会被测试。

### 4.2 Legacy 模式

```text
properties.providers.get(id)
  → 不存在：PROVIDER_NOT_FOUND
  → ProviderConfig 转 ProviderRuntimeConfig
```

Key 直接来自内存配置对象，不经过数据库解密。

---

## 5. 候选 URL 怎样生成

`buildConnectivityTestUrls(baseUrl)` 先调用 `ApiPathResolver.stripTrailingSlashes()` 去掉末尾 `/`，然后构造：

```text
候选 1：{normalizedBaseUrl}/chat/completions
```

如果 Base URL 自身不包含 API 版本路径，再追加：

```text
候选 2：{normalizedBaseUrl}/v1/chat/completions
```

使用 `LinkedHashSet` 去重并保留顺序。

示例：

| 配置的 baseUrl | 候选请求 |
|---|---|
| `https://api.example.com/v1` | `https://api.example.com/v1/chat/completions` |
| `https://api.example.com` | 先 `/chat/completions`，失败后 `/v1/chat/completions` |
| `https://api.example.com/` | 先去末尾 `/`，避免生成双斜杠 |

是否“包含版本”由 `ApiPathResolver.baseUrlContainsVersion()` 判断，不是无条件请求两次。

---

## 6. 真正发出的 HTTP 请求

`doTestProvider()` 创建独立 `RestClient`：

```text
连接超时：5 秒
读取超时：10 秒
默认请求头：Authorization: Bearer {解密后的API Key}
```

请求体由 `buildConnectivityTestRequestBody(model)` 生成：

```json
{
  "model": "当前Provider的聊天模型",
  "messages": [
    {
      "role": "user",
      "content": "Reply with OK only."
    }
  ],
  "max_tokens": 1
}
```

每个候选地址依次执行：

```java
restClient.post()
    .uri(URI.create(targetUrl))
    .body(requestBody)
    .retrieve()
    .toEntity(String.class);
```

这里是真实的同步网络调用。Service 在整个网络等待期间一直持有 `rwLock` 的读锁。

---

## 7. 成功条件的精确定义

只要 `retrieve().toEntity(String.class)` 得到 2xx 响应，就立即返回：

```json
{
  "success": true,
  "message": "连接成功",
  "model": "qwen3.5-flash"
}
```

当前代码没有解析响应 JSON，也没有检查模型是否真的回复了 `OK`。因此它验证的是：

- URL 可以完成 HTTP 请求。
- Bearer Key 至少没有被服务端用错误状态拒绝。
- 模型名和请求格式至少获得 2xx。

它不验证：

- 回复正文是否符合预期。
- 输出质量。
- 流式调用、工具调用、结构化输出是否正常。
- Embedding 模型和维度是否正常。
- Registry 的 Advisor、ToolCallback 组合是否正常，因为测试直接用 RestClient，不走 ChatClient。

---

## 8. 单个候选地址失败时怎样处理

### 8.1 HTTP 非 2xx

`RestClientResponseException` 分支：

```text
读取状态码
  → 读取响应体
  → abbreviate(responseBody)
      ├─ 合并空白字符
      ├─ 空正文变为 [no body]
      └─ 超过 200 字符时截断
  → lastFailureMessage =
    "HTTP {status} on {url}, body={摘要}"
  → 记录 warn + 异常堆栈
  → 尝试下一个候选 URL
```

常见情况包括 401 Key 无效、404 路径错误、429 远端限流、5xx 服务异常。

### 8.2 网络或 URI 等其他异常

```text
lastFailureMessage =
  "{异常类名} on {url}: {异常消息}"
  → 记录 warn + 堆栈
  → 尝试下一个 URL
```

例如连接超时、DNS 失败、TLS 握手失败、URI 非法。

### 8.3 所有候选都失败

返回最后一次失败信息：

```json
{
  "success": false,
  "message": "连接失败: HTTP 401 on ...",
  "model": "当前模型"
}
```

外层还有一次 `catch (Exception)`，用于处理 RestClient 构建、请求体构建等测试初始化异常，同样转换成 `success=false` DTO。

---

## 9. 为什么连接失败不会进入前端 catch

Controller 无论 DTO 中 `success` 是 true 还是 false，都会执行：

```java
Result.success(configService.testProvider(id))
```

所以典型网络失败的响应是：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": false,
    "message": "连接失败: ...",
    "model": "..."
  }
}
```

Axios 看到外层 `code=200`，会正常 resolve；`handleTest()` 把 DTO 写入 `testResults`，页面再根据内层布尔值显示红色。

只有测试开始前的业务错误，例如 Provider 不存在或数据库 Key 无法解密，才会抛 `BusinessException`：

```text
GlobalExceptionHandler → Result.error
  → Axios code !== 200
  → Promise reject
  → handleTest catch
  → 前端自己构造 success=false 的显示对象
```

---

## 10. 锁与性能影响

`testProvider()` 在调用远端网络期间持有读锁：

```text
获得读锁
  → 查配置
  → 候选 URL 1：连接/读取等待
  → 必要时候选 URL 2：继续等待
  → 返回结果
  → 释放读锁
```

多个测试请求可以同时持有读锁，但任何需要写锁的操作会等待全部读锁释放，包括：

- 新增、更新、删除 Provider。
- 修改默认 Provider。
- 更新 ASR/TTS 配置。

因此远端服务缓慢时，设置页的保存操作也可能被阻塞。每个候选地址分别受 5 秒连接超时和 10 秒读取超时约束，整体时间取决于候选数量和失败阶段；前端 Axios 总超时为 60 秒。

---

## 11. 安全与信息边界

- 明文 Key 只用于内存中的 Authorization 请求头，不返回前端。
- 日志记录 Provider ID、baseUrl、targetUrl、model 和远端错误正文摘要，不记录 Authorization 头。
- 远端错误正文摘要会返回给前端，最多 200 字符；如果供应商把敏感信息放进错误正文，页面也会看到该摘要。
- URL 来自管理员配置，Service 会从后端主动访问它；部署时应把 Provider 管理权限和出站网络策略纳入安全边界。

---

## 12. 完整闭环

```text
Provider 卡片点击“测试”
  → SettingsPage.handleTest(id)
  → POST /api/llm-provider/{id}/test
  → Redis 全局限流
  → Controller.testProvider(id)
  → Service 获取读锁
  → Repository 查 Entity / Legacy 查内存 Map
  → 数据库模式解密 API Key
  → 生成 1~2 个 chat/completions URL
  → RestClient 发送 max_tokens=1 的真实请求
      ├─ 任一 2xx → success=true
      └─ 全部失败 → success=false + 最后错误摘要
  → 释放读锁
  → 外层 Result.success(testResult)
  → Axios 解包
  → React 按内层 success 渲染绿色/红色结果
```

它完成了“当前保存配置能否执行最小聊天请求”的闭环，但不等价于整个 Spring AI 业务链路验收。
