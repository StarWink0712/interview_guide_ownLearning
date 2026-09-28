# ApiPathResolver 模块实现详解

## 一、模块概述

ApiPathResolver 模块是面试平台中负责 **OpenAI API 实例构建与路径智能适配** 的工具类。它将 LLM 厂商的 baseUrl 和 apiKey 封装为 Spring AI 的 `OpenAiApi` 实例，并通过版本号检测机制自动调整 API 请求路径，解决了不同 LLM 厂商 API 路径格式不一致的兼容性问题。

模块由单个 Java 源文件构成：

| 文件 | 职责 |
|------|------|
| [ApiPathResolver.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/ai/ApiPathResolver.java) | 工具类：构建 OpenAiApi、路径规范化、版本号检测 |

涉及的核心依赖文件：

- `OpenAiApi`（Spring AI）：OpenAI 兼容 API 的 HTTP 客户端封装
- `SimpleClientHttpRequestFactory`（Spring）：HTTP 请求工厂，管理连接和读取超时
- `RestClient.Builder`（Spring）：REST 客户端构造器，统一 HTTP 请求配置

### 1.1 整体实现流程

ApiPathResolver 模块的整体实现流程是一个从原始配置参数到可用的 HTTP API 客户端的构建闭环，涉及超时配置、HTTP 请求模板装配、API 构建器参数注入以及智能路径适配四个核心阶段。当调用方（如 `LlmProviderRegistry`）需要为某个 LLM 厂商创建 API 实例时，首先通过便捷方法 `buildOpenAiApi(baseUrl, apiKey)` 传入厂商地址和密钥，该方法内部使用预定义的常量 `DEFAULT_CONNECT_TIMEOUT`（10000 毫秒）和 `DEFAULT_READ_TIMEOUT`（300000 毫秒）作为超时默认值，委托给完整的四参数方法 `buildOpenAiApi(baseUrl, apiKey, connectTimeout, readTimeout)` 执行构建。在完整方法中，第一步创建 `SimpleClientHttpRequestFactory` 实例并分别调用 `setConnectTimeout()` 和 `setReadTimeout()` 设置 TCP 连接建立超时和等待响应数据的读取超时，该请求工厂本质上是一个 HTTP 连接的配置模板，Spring 每次发起请求时都会按照这个模板创建连接；第二步通过 `RestClient.builder().requestFactory(requestFactory)` 将配置好超时的请求工厂绑定到 REST 客户端构造器中，使后续所有通过该 REST 客户端发出的 HTTP 请求都遵循统一的超时策略；第三步调用 `OpenAiApi.builder()` 开启构建器模式，依次通过 `.baseUrl(baseUrl)` 设置目标服务器地址、`.apiKey(apiKey)` 设置认证密钥、`.restClientBuilder(restClientBuilder)` 注入已配好超时的 REST 客户端；第四步是模块的核心算法——智能路径适配，调用 `baseUrlContainsVersion(baseUrl)` 方法检测厂商地址末尾是否包含版本号路径（如 `/v1`、`/v2beta`），该方法内部依次调用 `stripTrailingSlashes()` 去除末尾斜杠后，使用正则表达式 `TRAILING_VERSION`（即 `/v\d+[a-zA-Z0-9]*$`）匹配路径末尾的版本号片段，若匹配成功说明 baseUrl 已自带版本号，则调用 `apiBuilder.completionsPath("/chat/completions").embeddingsPath("/embeddings")` 设置短路径格式，避免最终请求 URL 出现 `/v1/v1/chat/completions` 这样的重复路径；若未匹配到版本号，则保持 Spring AI 默认的长路径格式 `/v1/chat/completions`。最后第五步调用 `apiBuilder.build()` 完成构建，返回一个配置完整的 `OpenAiApi` 实例。该实例内部封装了 `RestClient`（管理 HTTP 连接和请求构造）、认证头（`Authorization: Bearer sk-xxx`）以及具体的 API 端点路径，调用方通过该实例即可透明地向 LLM 厂商发送聊天补全或向量嵌入请求，无需关心底层协议细节。本模块被 `LlmProviderRegistry` 在构建 `OpenAiChatModel` 和 `OpenAiEmbeddingModel` 时调用，也被 `LlmProviderConfigService` 用于 Provider 连通性测试，是连接配置层与模型层的桥梁组件。

---

## 二、模块结构

### 2.1 文件组织结构

```
common/ai/
├── apipathResolver/
│   ├── 技术文档-ApiPathResolver模块实现详解.md    ← 本文档
│   ├── 面试讲解-ApiPathResolver模块面试指南.md
│   ├── api实例构造器.md
│   ├── http请求模版.md
│   └── what is openAIapi?.md
├── ApiPathResolver.java                           ← 核心源文件
└── LlmProviderRegistry.java                       ← 主要调用方
```

### 2.2 依赖关系

```
LlmProviderRegistry/LlmProviderConfigService（调用方）
        │
        ▼
ApiPathResolver.buildOpenAiApi(baseUrl, apiKey)
        │
        ├── SimpleClientHttpRequestFactory  ← 设置超时模板
        │       ↓
        ├── RestClient.Builder              ← 绑定请求工厂
        │       ↓
        ├── OpenAiApi.Builder               ← 设置 baseUrl/apiKey
        │       ↓
        └── baseUrlContainsVersion()        ← 智能路径适配
                ↓
        OpenAiApi (最终实例)                ← 返回给调用方
```

---

## 三、核心组件详解

### 3.1 类声明与设计

```java
public final class ApiPathResolver {
    private ApiPathResolver() {}  // 私有构造器，禁止实例化
}
```

**设计要点**：
- `final class`：不可继承，纯工具类
- `private` 构造器：禁止外部实例化
- 所有方法均为 `public static`：以静态工具方法的方式提供功能

### 3.2 常量定义

```java
private static final int DEFAULT_CONNECT_TIMEOUT = 10000;
private static final int DEFAULT_READ_TIMEOUT = 300000;
private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");
```

| 常量 | 值 | 含义 |
|------|-----|------|
| `DEFAULT_CONNECT_TIMEOUT` | 10000（10 秒） | TCP 连接建立超时，向 LLM 服务器发起连接时最多等待 10 秒 |
| `DEFAULT_READ_TIMEOUT` | 300000（5 分钟） | 读取响应超时，连接建立后等待 LLM 返回数据的最大等待时间 |
| `TRAILING_VERSION` | `/v\d+[a-zA-Z0-9]*$` | 正则，匹配 URL 末尾的版本号（如 `/v1`、`/v2beta`） |

**设计考量**：
- 连接超时设为 10 秒是为了快速检测网络不可达的情况，避免长时间阻塞
- 读取超时设为 5 分钟是因为 LLM 生成长回答可能需要较长时间，需要足够的缓冲
- `Pattern.compile()` 编译为静态常量，避免每次调用时重复编译正则表达式

### 3.3 便捷方法 — buildOpenAiApi(baseUrl, apiKey)

```java
public static OpenAiApi buildOpenAiApi(String baseUrl, String apiKey) {
    return buildOpenAiApi(baseUrl, apiKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
}
```

**作用**：提供两参数简化版入口，自动使用默认超时配置。大多数调用场景使用此方法，无需手动指定超时。

### 3.4 核心方法 — buildOpenAiApi(四参数完整版)

**方法签名**：

```java
public static OpenAiApi buildOpenAiApi(String baseUrl, String apiKey,
    int connectTimeout, int readTimeout)
```

**五个步骤**：

#### 步骤 1：创建 HTTP 请求工厂

```java
SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
requestFactory.setConnectTimeout(connectTimeout);
requestFactory.setReadTimeout(readTimeout);
```

`SimpleClientHttpRequestFactory` 是 Spring 提供的 HTTP 请求工厂，它将超时参数固化为一个配置模板，Spring 每次发起 HTTP 请求时按此模板创建连接。它在内部使用 JDK 的 `HttpURLConnection`。

#### 步骤 2：绑定请求工厂到 RestClient

```java
RestClient.Builder restClientBuilder = RestClient.builder()
    .requestFactory(requestFactory);
```

`RestClient` 是 Spring 6 引入的同步 HTTP 客户端，替代旧版 `RestTemplate`。通过 Builder 模式将配置好的请求工厂绑定进去。

#### 步骤 3：构建 OpenAiApi.Builder

```java
OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
    .baseUrl(baseUrl)
    .apiKey(apiKey)
    .restClientBuilder(restClientBuilder);
```

| 方法 | 含义 | 来源 |
|------|------|------|
| `.baseUrl(baseUrl)` | 设置 LLM 厂商 API 地址 | 方法入参，来自配置文件/数据库 |
| `.apiKey(apiKey)` | 设置 API 认证密钥 | 方法入参，来自配置文件/数据库（可能已解密） |
| `.restClientBuilder(restClientBuilder)` | 注入带超时配置的 HTTP 客户端 | 步骤 2 创建 |

#### 步骤 4：智能路径适配（核心算法）

```java
if (baseUrlContainsVersion(baseUrl)) {
    apiBuilder.completionsPath("/chat/completions")
              .embeddingsPath("/embeddings");
}
```

**问题背景**：不同 LLM 厂商的 baseUrl 格式不统一：

| 厂商 | baseUrl | 包含版本号 |
|------|---------|-----------|
| OpenAI 标准 | `https://api.openai.com/v1` | 是 |
| 阿里云 DashScope | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 是 |
| 本地模型（如 Ollama） | `http://localhost:11434` | 否 |

如果不做适配：baseUrl 含 `/v1` 时，Spring AI 默认拼接 `/v1/chat/completions`，最终 URL 变成 `.../v1/v1/chat/completions`，导致 404。

#### 步骤 5：build() 返回实例

```java
return apiBuilder.build();
```

### 3.5 版本号检测方法 — baseUrlContainsVersion()

```java
public static boolean baseUrlContainsVersion(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
        return false;
    }
    String stripped = stripTrailingSlashes(baseUrl.trim());
    return TRAILING_VERSION.matcher(stripped).find();
}
```

**执行逻辑**：
1. 空值保护：null 或空白字符串直接返回 false
2. 去除末尾斜杠（`/v1/` → `/v1`）
3. 正则匹配 `/v\d+[a-zA-Z0-9]*$`

**匹配示例**：

| 输入 | 匹配结果 | 说明 |
|------|---------|------|
| `https://api.openai.com/v1` | true | 标准版本号 |
| `.../compatible-mode/v1` | true | DashScope 格式 |
| `.../v2beta` | true | 带后缀版本号 |
| `http://localhost:11434` | false | 无版本号 |
| `.../v1/` | true | stripTrailingSlashes 处理 |

### 3.6 斜杠处理方法 — stripTrailingSlashes()

```java
public static String stripTrailingSlashes(String value) {
    if (value == null) {
        return "";
    }
    String result = value.trim();
    while (result.endsWith("/")) {
        result = result.substring(0, result.length() - 1);
    }
    return result;
}
```

`public` 修饰符使得外部也可以复用此方法，体现了良好的工具类设计。

---

## 四、网络层次模型

ApiPathResolver 构建的 `OpenAiApi` 实例在运行时对应如下网络通信层次：

```
应用层：  OpenAiApi（管理 JSON 请求/响应、认证）
          ↓ 调用
HTTP层：  RestClient（构建 HTTP 请求、解析响应）
          ↓ 使用
TCP层：   Socket（IP + 端口，三次握手）
```

`OpenAiApi` 内部封装了 `baseUrl`（目标地址）和 `Authorization: Bearer` 头（认证信息），对外暴露 `chatCompletion()` 和 `embeddings()` 等高级方法，使调用方无需关心底层 Socket 连接的创建与管理。

---

## 五、完整工作流程

### 5.1 构建流程

```
调用方传入 baseUrl, apiKey
        │
        ▼
buildOpenAiApi(baseUrl, apiKey)        ← 便捷入口，使用默认超时
        │
        ▼
buildOpenAiApi(baseUrl, apiKey,        ← 完整方法
               connectTimeout,
               readTimeout)
        │
        ├─ 步骤1：new SimpleClientHttpRequestFactory()
        │           setConnectTimeout(10000)
        │           setReadTimeout(300000)
        │
        ├─ 步骤2：RestClient.builder()
        │           .requestFactory(requestFactory)
        │
        ├─ 步骤3：OpenAiApi.builder()
        │           .baseUrl(baseUrl)
        │           .apiKey(apiKey)
        │           .restClientBuilder(restClientBuilder)
        │
        ├─ 步骤4：baseUrlContainsVersion(baseUrl)
        │           ├─ stripTrailingSlashes()
        │           └─ TRAILING_VERSION.matcher().find()
        │           ├── true  → 设置短路径
        │           └── false → 使用默认长路径
        │
        └─ 步骤5：apiBuilder.build()
                │
                ▼
        返回 OpenAiApi 实例
```

### 5.2 调用链中的位置

```
配置层（YAML/数据库）
        │
        ▼
LlmProviderRegistry.loadProviderOrThrow()
        │ 读取 baseUrl, apiKey
        ▼
ApiPathResolver.buildOpenAiApi(baseUrl, apiKey)   ← 本模块
        │ 返回 OpenAiApi
        ▼
new OpenAiChatModel(openAiApi, options, ...)      ← 聊天模型
new OpenAiEmbeddingModel(openAiApi, ...)           ← 向量模型
```

---

## 六、输入输出规范

### 6.1 方法签名

| 方法 | 输入 | 输出 | 说明 |
|------|------|------|------|
| `buildOpenAiApi(b,a)` | `baseUrl: String`, `apiKey: String` | `OpenAiApi` | 两参数便捷版 |
| `buildOpenAiApi(b,a,c,r)` | 上述 + `connectTimeout: int`, `readTimeout: int` | `OpenAiApi` | 四参数完整版 |
| `baseUrlContainsVersion(url)` | `baseUrl: String` | `boolean` | 版本号检测 |
| `stripTrailingSlashes(value)` | `value: String` | `String` | 末尾斜杠清理 |

### 6.2 输入约束

| 参数 | 约束 | 示例 |
|------|------|------|
| `baseUrl` | 非空字符串，含协议头 | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `apiKey` | 非空字符串 | `sk-xxxxxxxxxxxxxxxx` |
| `connectTimeout` | 正整数，单位毫秒 | `10000` |
| `readTimeout` | 正整数，单位毫秒 | `300000` |

---

## 七、异常处理

| 方法 | 异常处理策略 |
|------|------------|
| `baseUrlContainsVersion()` | null/空白 → 返回 false（安全默认值） |
| `stripTrailingSlashes()` | null → 返回空字符串 `""` |
| `buildOpenAiApi()` | 不捕获异常，将构建失败直接抛出给调用方处理 |

**设计理念**：本模块不吞异常。构建 OpenAiApi 失败属于严重错误，应向上层传播，由 `LlmProviderRegistry` 统一处理。

---

## 八、设计优点

1. **单类工具设计**：`final class` + `private` 构造器 + `public static` 方法，干净利落，无状态
2. **智能路径适配**：使用正则匹配版本号并自动切换 API 路径格式，用一个简单算法解决了多厂商兼容问题
3. **超时参数沉淀**：连接和读取超时用常量管理，可集中调整而无需到处修改
4. **Builder 模式链式调用**：OpenAiApi 构建过程清晰，从底层 HTTP 配置到顶层 API 参数逐层装配
5. **错误快速传播**：不吞异常，让上游 `LlmProviderRegistry` 统一记录和转换异常

---

## 九、潜在改进方向

1. **重试配置**：当前超时后直接失败，可考虑在 RestClient 层面加入重试策略（如指数退避）
2. **连接池支持**：`SimpleClientHttpRequestFactory` 每次新建连接，高并发场景可升级为支持连接池的实现
3. **路径配置外部化**：当前 `completionsPath` 和 `embeddingsPath` 硬编码，可支持从配置读取
4. **超时参数可配置化**：当前常量为编译期固定值，可考虑支持从 `application.yml` 读取超时配置
