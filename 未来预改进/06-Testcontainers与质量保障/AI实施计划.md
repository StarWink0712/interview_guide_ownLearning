# AI 实施计划：Testcontainers 与质量保障

## 一、执行前提

请先阅读：

- `app/build.gradle`
- `app/src/test/resources/application-test.yml`
- 现有 `app/src/test/java/` 下的测试。

注意：

- 不要破坏现有 H2 测试。
- Testcontainers 测试可以单独使用 tag 或 profile。
- 如果本地没有 Docker，测试应该能被跳过或明确失败原因。

## 二、第一阶段：增加依赖

修改：

- `app/build.gradle`

增加测试依赖：

- Testcontainers JUnit Jupiter。
- PostgreSQL Testcontainers。
- Redis 可用 `GenericContainer`。
- MinIO 可用 `GenericContainer`。

注意使用 Gradle 现有版本管理方式，尽量加入 `gradle/libs.versions.toml`。

## 三、第二阶段：新增测试基类

新增：

```text
app/src/test/java/interview/guide/testsupport/BaseIntegrationTest.java
```

职责：

- 启动 PostgreSQL pgvector 容器。
- 启动 Redis 容器。
- 启动 MinIO 容器。
- 通过 `@DynamicPropertySource` 注入配置。

如果 pgvector 镜像启动慢，可以先只做 Redis + PostgreSQL，MinIO 后续补。

## 四、第三阶段：Redis Stream 集成测试

新增：

```text
app/src/test/java/interview/guide/common/async/RedisStreamIntegrationTest.java
```

测试：

- create group。
- stream add。
- read group。
- ack。
- retry message。

如果已经实现 pending 恢复，增加 pending 恢复测试。

## 五、第四阶段：对象存储集成测试

新增：

```text
app/src/test/java/interview/guide/infrastructure/file/FileStorageServiceIntegrationTest.java
```

测试：

- 上传简历文件。
- 获取 URL。
- 下载文件。
- 删除文件。

需要确保测试 bucket 自动创建。

## 六、第五阶段：RAG 最小集成测试

新增：

```text
app/src/test/java/interview/guide/modules/knowledgebase/KnowledgeBaseRagIntegrationTest.java
```

为了避免真实调用外部 Embedding：

- 第一版可以 Mock EmbeddingModel 或 VectorStore。
- 第二版再接真实测试 embedding provider。

测试目标：

- 文档分块。
- metadata 包含 kb_id。
- similaritySearch 按 kbId 过滤。
- 无命中返回空列表。

## 七、第六阶段：结构化输出测试增强

新增或扩展：

```text
app/src/test/java/interview/guide/common/ai/StructuredOutputInvokerTest.java
```

测试：

- 合法 JSON 解析成功。
- Markdown 包裹 JSON 时失败并重试。
- 字符串未转义引号时本地修复。
- 超过重试次数抛 BusinessException。

## 八、第七阶段：测试分组

可增加 JUnit Tag：

- `@Tag("integration")`
- `@Tag("container")`

Gradle 任务可选：

```text
test
integrationTest
```

第一版如果不想改 Gradle 任务，可先用命名约定和文档说明。

## 九、验收标准

完成后必须满足：

- 普通单元测试仍可运行。
- 至少有一个 Testcontainers 集成测试能启动真实 Redis。
- 至少有一个 PostgreSQL/pgvector 相关测试能运行。
- 文件存储测试能覆盖上传和下载。
- 测试失败原因清晰，不依赖手动启动 docker-compose。

