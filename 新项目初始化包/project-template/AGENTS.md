# AI Interview Platform 编码规范

## 当前阶段

这是从零手写的练习项目。当前只完成环境配置和最小启动验证。

在 `SETUP_STATUS.md` 全部通过前，不要加入 AI、Redis、S3、WebSocket 或业务模块。

## 技术基线

```text
Spring Boot 4.0.1
Java 21
Gradle 8.14
PostgreSQL 16
React 18 + TypeScript + Vite（后续阶段）
```

## 项目结构

按功能分包：

```text
interview.guide/
├── App.java
├── common/
├── infrastructure/
└── modules/
```

业务模块内部遵循：

```text
Controller → Service → Repository
```

## 编码规则

- Java 使用 2 空格缩进，建议每行不超过 100 字符。
- 禁止通配符导入。
- 不可变 Request、Response 和 DTO 优先使用 `record`。
- Entity 使用 `XxxEntity` 后缀。
- 跨层数据使用 `XxxDTO`。
- 请求体使用 `XxxRequest`。
- 前端响应模型使用 `XxxResponse`。
- 禁止直接向前端返回 Entity。
- Controller 只负责路由、校验和 Service 委托。
- `@Transactional` 只放在 Service 层。
- 禁止在数据库事务内调用 LLM、S3 等外部服务。
- 业务异常统一使用 `BusinessException`，禁止直接抛 `RuntimeException`。
- 配置使用 `@ConfigurationProperties`，禁止在 Service 中散落 `@Value`。
- 使用 SLF4J 参数化日志，异常必须作为最后一个参数记录。
- 不硬编码 API Key、数据库密码等敏感信息。
- 每完成一个阶段都运行测试。

## 工作边界

当前允许修改：

```text
Gradle 配置
Spring 配置
Docker Compose
环境变量示例
初始化脚本
最小健康检查
测试配置
```

当前不要实现：

```text
统一响应与异常体系
面试日程 CRUD
简历模块
Spring AI
Redis
RAG
语音面试
React 前端
```

只有用户明确要求进入下一阶段后，才扩展范围。
