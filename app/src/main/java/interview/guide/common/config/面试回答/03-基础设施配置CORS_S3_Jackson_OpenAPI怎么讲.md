# CORS、S3、Jackson、OpenAPI 基础设施配置怎么讲

## 1. 这几个配置类的共同点

`CorsConfig`、`S3Config`、`JacksonConfig`、`OpenApiConfig` 的共同点是：

> 它们不是业务 Service，而是把框架或第三方 SDK 需要的对象注册到 Spring 容器里，供全局复用。

面试中可以这样归纳：

| 配置类 | 注册/配置的对象 | 支撑什么能力 |
| --- | --- | --- |
| `CorsConfig` | `CorsFilter` | React 前端跨域调用后端 API |
| `S3Config` | `S3Client` | RustFS/S3 文件上传下载 |
| `JacksonConfig` | `ObjectMapper` | JSON 序列化/反序列化 |
| `OpenApiConfig` | `OpenAPI` | Swagger/OpenAPI 接口文档 |

## 2. CORS 配置怎么讲

### 2.1 业务背景

前端是 React/Vite，开发环境常见地址：

- `http://localhost:5173`
- `http://localhost:5174`

后端是 Spring Boot，默认：

- `http://localhost:8080`

协议、域名、端口任一不同，浏览器就认为是跨域。

所以后端需要配置 CORS。

### 2.2 当前实现

`CorsConfig`：

- 从 `CorsProperties.allowedOrigins` 读取允许来源。
- 用逗号拆分并 trim。
- 允许方法：`GET`、`POST`、`PUT`、`DELETE`、`PATCH`、`OPTIONS`。
- 允许所有请求头。
- `allowCredentials=true`，支持 Cookie / Authorization。
- `maxAge=3600`，预检请求缓存 1 小时。
- 只注册到 `/api/**`。

面试回答：

> 我们通过 `CorsFilter` 对 `/api/**` 配置跨域规则，允许来源从配置读取，而不是写死在代码里。这样开发环境可以放行 Vite 端口，生产环境可以通过环境变量换成正式域名。开启 `allowCredentials` 是为了支持带认证信息的请求，同时用明确 origins 避免和通配符冲突。

### 2.3 为什么不能随便 `allowedOrigins("*")`

当 `allowCredentials=true` 时，浏览器安全规范不允许响应：

```http
Access-Control-Allow-Origin: *
Access-Control-Allow-Credentials: true
```

所以必须配置明确来源。

面试回答：

> 如果要允许携带 Cookie 或 Authorization，就不能简单用 `*` 放开所有来源。项目用配置化白名单是更安全的做法。

### 2.4 CORS 的边界

当前 HTTP API 是 `/api/**`，但语音面试 WebSocket 在：

```text
/ws/voice-interview/{sessionId}
```

WebSocket 跨域不走 `CorsFilter` 的 `/api/**`，而是在 `WebSocketConfig` 里单独调用 `setAllowedOrigins()`。

面试回答：

> HTTP API 和 WebSocket 的跨域配置不完全一样。`CorsConfig` 管 `/api/**`，语音面试 WebSocket 由 `WebSocketConfig` 单独配置，但它们共用 `CorsProperties`，这样允许来源保持一致。

## 3. S3/RustFS 配置怎么讲

### 3.1 业务背景

项目需要存储：

- 简历原始文件。
- 知识库上传文档。
- 后续可能还有报告、音频等文件。

如果直接存本地磁盘：

- 多实例部署时文件不共享。
- 容器重建可能丢文件。
- 扩展和备份麻烦。

所以项目通过 S3 兼容协议接 RustFS/MinIO。

### 3.2 `StorageConfigProperties`

绑定：

```yaml
app:
  storage:
    endpoint:
    access-key:
    secret-key:
    bucket:
    region:
```

### 3.3 `S3Config`

创建 AWS SDK 的 `S3Client`：

```java
S3Client.builder()
  .endpointOverride(URI.create(storageConfig.getEndpoint()))
  .region(Region.of(storageConfig.getRegion()))
  .credentialsProvider(StaticCredentialsProvider.create(credentials))
  .forcePathStyle(true)
  .build();
```

面试回答：

> S3Config 的作用是把 RustFS 伪装成标准 S3 客户端。业务层只使用 `S3Client` 和 `FileStorageService`，不关心底层是 RustFS、MinIO 还是 AWS S3。

### 3.4 `forcePathStyle(true)` 是重点

S3 有两种访问风格：

| 风格 | URL 示例 |
| --- | --- |
| 路径风格 | `http://localhost:9000/interview-guide/a.pdf` |
| 虚拟主机风格 | `http://interview-guide.localhost:9000/a.pdf` |

RustFS/MinIO 本地开发通常不支持虚拟主机风格，因为 `interview-guide.localhost` DNS 不一定能解析。

所以配置：

```java
.forcePathStyle(true)
```

面试回答：

> 这是对 S3 兼容存储非常关键的配置。AWS 官方 S3 常用虚拟主机风格，但本地 RustFS/MinIO 更适合路径风格。如果不打开，SDK 可能会访问 `bucket.endpoint`，导致 DNS 解析失败。

### 3.5 S3Config 和 FileStorageService 的边界

不要把 S3Config 讲成完成了所有文件逻辑。

准确边界是：

- `S3Config`：创建客户端。
- `StorageConfigProperties`：保存连接参数。
- `FileStorageService`：负责上传、下载、删除、文件 key 生成、bucket 检查。

面试回答：

> 配置类只负责装配客户端，业务文件逻辑在 infrastructure/file 里。这样配置层和业务基础设施层职责分开。

## 4. JacksonConfig 怎么讲

### 4.1 它的作用

`JacksonConfig` 注册全局：

```java
@Bean
public ObjectMapper objectMapper() {
  return new ObjectMapper();
}
```

它用于 JSON 序列化和反序列化，例如：

- Controller 请求/响应转换。
- 业务对象转 JSON。
- 面试题目、报告、任务 payload 的 JSON 处理。

### 4.2 面试里怎么讲

可以这样说：

> 项目里显式提供了一个 `ObjectMapper` Bean，让需要 JSON 处理的组件注入同一个对象，而不是到处 new ObjectMapper。这样后续如果要统一时间格式、空值策略、枚举策略或模块注册，可以集中改一处。

### 4.3 必须诚实说明的问题

当前代码有一个不一致点：

- 注释里说的是 `tools.jackson`。
- 但实际 import 是 `com.fasterxml.jackson.databind.ObjectMapper`。

而且当前实现是裸 `new ObjectMapper()`，没有显式注册：

- `JavaTimeModule`
- 时间格式策略。
- 空值策略。
- 枚举策略。

面试里如果被问到，可以这样答：

> 现在这个 ObjectMapper 配置比较基础，更多是提供统一 Bean。代码注释里提到 tools.jackson，但实际使用的是 com.fasterxml.jackson，这个注释需要修正。生产上如果 LocalDateTime、枚举或空值序列化有统一要求，我会改成基于 `Jackson2ObjectMapperBuilder` 或显式注册模块，而不是简单 new。

这个回答比硬说“完全没问题”更可信。

## 5. OpenAPI 配置怎么讲

### 5.1 当前实现

`OpenApiConfig` 注册：

```java
new OpenAPI()
  .info(new Info()
    .title("智能 AI 面试官平台 API")
    .description("简历分析、模拟面试、知识库管理 RESTful API 文档")
    .version("1.0.0"));
```

SpringDoc 配置在 `application.yml`：

```yaml
springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /v3/api-docs
  packages-to-scan: interview.guide.modules
```

### 5.2 面试回答

> OpenApiConfig 主要配置 Swagger 文档的元信息，比如标题、描述和版本。真正的接口路径、请求参数、响应结构由 SpringDoc 扫描 Controller 自动生成。这样前后端联调时可以直接看 `/swagger-ui.html`，减少接口沟通成本。

### 5.3 可优化点

当前描述只写了简历分析、模拟面试、知识库管理，但项目还有：

- 面试日程。
- 语音面试。
- LLM Provider 管理。
- 异步任务管理。

可以补：

- API 文档描述更新。
- JWT / Authorization Header 安全 scheme。
- 按模块 tag 分组。
- 生产环境关闭或加权限。

面试回答：

> OpenAPI 当前主要满足开发联调。生产上我会补安全 scheme，并限制 Swagger 暴露范围，避免内部接口信息直接公开。

## 6. 这几个配置类的设计模式

| 设计点 | 对应类 | 怎么说 |
| --- | --- | --- |
| 工厂方法 | `S3Config.s3Client()` | 根据配置创建 SDK 客户端 Bean |
| 代理模式 | `LlmEmbeddingConfig.embeddingModel()` | Spring 注入代理，运行时委托 Registry |
| 配置对象 | `StorageConfigProperties`、`CorsProperties` | 把 YAML 映射成类型安全对象 |
| 适配思想 | `S3Config.forcePathStyle` | 适配 RustFS/MinIO 这类 S3 兼容服务 |
| 门面/元信息配置 | `OpenApiConfig` | 用一个 Bean 提供文档元信息 |

## 7. 高频追问

### Q1：CORS 是后端安全机制吗？

回答：

> CORS 主要是浏览器安全策略，不是后端认证授权机制。它限制浏览器页面能不能跨源读取响应，但不能替代登录鉴权、Token 校验和权限控制。后端接口仍然需要独立鉴权。

### Q2：为什么 S3Config 不直接上传文件？

回答：

> 配置类职责应该单一，只创建和配置 S3Client。上传、下载、删除属于文件基础设施服务，所以放在 FileStorageService。这样 Bean 装配和业务基础设施逻辑分开。

### Q3：为什么不用本地磁盘存文件？

回答：

> 本地磁盘开发简单，但多实例部署、容器重启、备份迁移都麻烦。对象存储更适合保存用户上传文件，也方便后续扩展到 MinIO、RustFS 或 AWS S3。

### Q4：Swagger 生产环境要不要开放？

回答：

> 一般不建议无保护开放。开发和测试环境可以开放给前后端联调；生产环境要么关闭，要么加认证和网络访问限制。

### Q5：ObjectMapper 为什么需要统一？

回答：

> 如果每个地方自己 new ObjectMapper，时间格式、空值策略、枚举序列化可能不一致。统一 Bean 可以保证全局 JSON 行为一致，也方便后续集中定制。

## 8. 面试表达小结

> CORS、S3、Jackson、OpenAPI 这些配置类体现的是基础设施装配能力：跨域规则、对象存储客户端、JSON 映射器、接口文档元信息都在配置层集中注册，业务层只使用现成 Bean。这样系统更容易做环境切换、统一治理和生产演进。

