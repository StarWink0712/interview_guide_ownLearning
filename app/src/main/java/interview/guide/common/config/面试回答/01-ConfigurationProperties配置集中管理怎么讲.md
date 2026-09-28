# ConfigurationProperties 配置集中管理怎么讲

## 1. 为什么不用到处写 `@Value`

项目规范里明确要求：业务配置集中到 Properties 类，避免 `@Value` 散落在 Service 中。

原因很简单：

如果每个 Service 都这样写：

```java
@Value("${app.storage.bucket}")
private String bucket;
```

会带来几个问题：

- 配置项分散，没人知道一个模块到底需要哪些配置。
- 配置结构不可复用，测试时替换麻烦。
- 缺少类型聚合，复杂配置如 `providers`、`advisors` 很难管理。
- 配置名改动时影响面大，不容易统一重构。

当前项目把配置集中成这些类：

| Properties 类 | 绑定前缀 | 代表配置 |
| --- | --- | --- |
| `AppConfigProperties` | `app.resume` | 简历上传目录、允许 MIME 类型 |
| `CorsProperties` | `app.cors` | 前端来源白名单 |
| `StorageConfigProperties` | `app.storage` | RustFS/S3 endpoint、bucket、accessKey、secretKey、region |
| `LlmProviderProperties` | `app.ai` | 默认 Provider、多模型连接配置、Embedding、Advisor、安全配置 |

面试中可以说：

> 我们没有把配置散落写在业务 Service 里，而是按领域抽成 Properties 类。这样配置结构和业务边界一致，Service 只依赖类型化对象，配置变更也更容易定位。

## 2. Spring Boot 是怎么绑定配置的

以 `StorageConfigProperties` 为例：

```java
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageConfigProperties {
  private String endpoint;
  private String accessKey;
  private String secretKey;
  private String bucket;
  private String region = "us-east-1";
}
```

对应 YAML：

```yaml
app:
  storage:
    endpoint: ${APP_STORAGE_ENDPOINT:http://localhost:9000}
    access-key: ${APP_STORAGE_ACCESS_KEY:minioadmin}
    secret-key: ${APP_STORAGE_SECRET_KEY:minioadmin}
    bucket: ${APP_STORAGE_BUCKET:interview-guide}
    region: ${APP_STORAGE_REGION:us-east-1}
```

Spring Boot 会做 relaxed binding：

- `access-key` 绑定到 `accessKey`。
- `secret-key` 绑定到 `secretKey`。
- `${ENV:default}` 优先读取环境变量，没有时使用默认值。

面试回答：

> `@ConfigurationProperties` 支持按前缀批量绑定，并且支持 kebab-case 到 camelCase 的宽松映射。像 `app.storage.access-key` 会自动绑定到 `accessKey` 字段，配置结构和 Java 对象能保持一致。

## 3. `AppConfigProperties` 怎么讲

`AppConfigProperties` 绑定的是：

```yaml
app:
  resume:
    upload-dir: ${java.io.tmpdir}/ai-interview/resumes
    allowed-types:
      - application/pdf
      - application/msword
      - application/vnd.openxmlformats-officedocument.wordprocessingml.document
      - text/plain
```

它被 `ResumeUploadService` 使用，用于：

- 确定简历上传临时目录。
- 校验上传文件 MIME 类型。

面试回答：

> 简历上传支持 PDF、Word、txt 等文件类型，这些类型不是写死在上传服务里，而是通过 `app.resume.allowed-types` 配置。以后如果要支持 Markdown 或限制某类格式，只需要改配置，不需要改业务代码。

可以补一句边界：

> 当前上传目录和文件类型已经外置，但还可以进一步加 `@Validated`，比如 `allowedTypes` 不能为空，`uploadDir` 不能为空，这样配置错误能在启动阶段发现。

## 4. `CorsProperties` 怎么讲

`CorsProperties` 绑定：

```yaml
app:
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:5174,http://localhost:80}
```

用途有两个：

- `CorsConfig` 用它配置 HTTP API 跨域。
- `WebSocketConfig` 用它配置语音面试 WebSocket 跨域。

面试回答：

> 前端是 React/Vite，开发环境端口通常是 5173 或 5174，后端是 8080，所以浏览器会触发跨域。我们把允许来源放到配置里，而不是在代码里写死某个 localhost。部署到生产环境时，只要改环境变量就可以切换到正式域名。

边界：

> 当前 `allowedOrigins` 是一个逗号分隔字符串，HTTP CORS 里有 trim，WebSocket 里直接 split。更稳的做法是把它定义成 `List<String>`，Spring Boot 直接绑定列表，避免空格和格式问题。

## 5. `StorageConfigProperties` 怎么讲

`StorageConfigProperties` 绑定对象存储参数：

| 字段 | 含义 |
| --- | --- |
| `endpoint` | RustFS/MinIO/S3 地址 |
| `accessKey` | 访问密钥 |
| `secretKey` | 私钥 |
| `bucket` | 存储桶 |
| `region` | 区域，默认 `us-east-1` |

它被两个地方使用：

- `S3Config`：创建 `S3Client`。
- `FileStorageService`：上传、下载、删除文件时读取 bucket。

面试回答：

> 我们文件存储不是绑定本地磁盘，而是通过 S3 兼容协议接 RustFS。连接参数全部放在 `app.storage` 下，`S3Config` 只负责创建客户端，真正的上传下载在 `FileStorageService`。这样以后从 RustFS 换成 MinIO 或 AWS S3，业务代码不用动，只调整 endpoint 和凭证。

## 6. `LlmProviderProperties` 怎么讲

这是配置模块最有含金量的 Properties 类。

它绑定：

```yaml
app:
  ai:
    default-provider: dashscope
    default-embedding-provider: dashscope
    embedding-dimensions: 1024
    providers:
      dashscope:
        base-url: ...
        api-key: ${AI_BAILIAN_API_KEY}
        model: ...
        embedding-model: text-embedding-v3
        supports-embedding: true
      deepseek:
        base-url: ...
        api-key: ${PROVIDER_DEEPSEEK_API_KEY:}
        model: ...
        supports-embedding: false
```

核心字段：

| 字段 | 含义 |
| --- | --- |
| `defaultProvider` | 默认聊天模型 Provider |
| `defaultEmbeddingProvider` | 默认向量模型 Provider |
| `embeddingDimensions` | 全局向量维度，当前默认 1024 |
| `providers` | 多 Provider 配置 Map |
| `advisors` | Spring AI Advisor 链配置 |
| `security` | API Key 加密相关配置 |
| `configYamlPath` | 运行时 Provider YAML 配置路径 |
| `configEnvPath` | 运行时 Provider env 配置路径 |

面试回答：

> `LlmProviderProperties` 是多模型能力的配置入口。业务模块不会直接读 API Key，也不会自己 new ChatClient，而是通过 `LlmProviderRegistry` 根据这里的配置创建模型客户端。这样新增一个 Provider 基本就是加一段配置，而不是改所有业务 Service。

## 7. 复杂嵌套配置怎么讲

`LlmProviderProperties` 里有三个内部类：

### 7.1 `ProviderConfig`

表示单个厂商：

- `baseUrl`
- `apiKey`
- `model`
- `embeddingModel`
- `embeddingDimensions`
- `supportsEmbedding`
- `temperature`

适合用 `Map<String, ProviderConfig>`：

```java
private Map<String, ProviderConfig> providers;
```

因为 Provider 数量不是固定的，key 就是 providerId，例如 `dashscope`、`deepseek`。

### 7.2 `AdvisorConfig`

控制 Spring AI 的 Advisor 链：

- 工具调用是否启用。
- 对话记忆是否启用。
- 安全词防护是否启用。
- 日志 Advisor 是否启用。
- PromptSanitizer 是否启用。

面试里可以说：

> Advisor 不是写死启用，而是通过配置控制。比如 MessageChatMemory 默认关闭，避免不同会话串扰；SafeGuard 默认开启，用于基础 Prompt 注入防护。

### 7.3 `SecurityConfig`

配置 API Key 加密：

- `apiKeyEncryptionKey`
- `requireEncryptionKey`

它被 `ApiKeyEncryptionService` 使用。

面试表达：

> Provider 配置不仅仅是模型地址，还涉及 API Key 的存储安全。项目里预留了加密密钥配置，数据库模式下可以对 API Key 加密保存。

## 8. 配置来源和优先级怎么讲

当前代码体现了三类配置来源：

| 来源 | 例子 | 作用 |
| --- | --- | --- |
| `application.yml` | `app.ai.providers.dashscope` | 默认种子配置 |
| 环境变量占位符 | `${AI_BAILIAN_API_KEY}` | 敏感信息和环境差异 |
| 运行时配置文件路径 | `~/.interview-guide/llm-providers.yml`、`.env` | Provider 管理后台写入和重载 |

注意要诚实：

> 我在当前 `application.yml` 中看到的是环境变量占位符和 `config-env-path`，没有只凭这个目录确认根目录 `.env` 是由 `spring.config.import` 直接加载的。README 和项目规范提到 bootRun 会读取 `.env`，这可能由启动脚本或其他配置完成。面试里我会说“敏感值通过环境变量/运行时 env 文件注入”，不要把没确认的加载机制说死。

## 9. 面试官可能追问和回答

### Q1：`@ConfigurationProperties` 和 `@Value` 有什么区别？

回答：

> `@Value` 适合少量简单配置，但多了以后会散落在各个类里。`@ConfigurationProperties` 可以按前缀把一组配置绑定成对象，支持嵌套结构、Map、List，也更适合做校验和测试。这个项目的 Provider、存储、CORS 都是结构化配置，所以用 Properties 类更合适。

### Q2：为什么 Properties 类加 `@Component`？

回答：

> 这样 Spring 扫描后会把它注册成 Bean，同时 `@ConfigurationProperties` 完成绑定，其他 Service 或 Config 类可以通过构造函数注入。另一种写法是 `@EnableConfigurationProperties`，当前项目采用的是更直接的 `@Component` 方式。

### Q3：为什么不把 API Key 写在代码里？

回答：

> API Key 属于敏感信息，不能进代码仓库。项目用环境变量占位符或运行时 env 配置文件注入，并且 Provider 数据库存储模式还支持加密密钥。这样不同环境可以有不同密钥，也降低泄露风险。

### Q4：配置错误什么时候能发现？

回答：

> 目前一部分错误会在使用时暴露，比如 S3 endpoint 错误会在文件存储调用时报错，Provider 配置错误会在连接测试或模型调用时报错。更好的做法是给 Properties 加 `@Validated` 和 `@NotBlank`，让关键配置在启动阶段 fail fast。

## 10. 面试表达小结

> 这个目录里的 Properties 类体现的是配置治理思路：业务代码不直接读 YAML，不硬编码环境参数，而是依赖结构化配置对象。这样既能提高可维护性，也能支持多环境部署、多模型扩展和敏感信息外置。

