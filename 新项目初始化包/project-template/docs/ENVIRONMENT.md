# 本地环境说明

## 端口

```text
Spring Boot: 8081
PostgreSQL: 5433 → 容器 5432
未来 React: 5173
```

这些端口用于避免和参考项目冲突。

## 开发数据库

开发运行使用 Docker PostgreSQL：

```text
镜像: pgvector/pgvector:pg16
数据库: interview_guide_rebuild
用户: postgres
密码: rebuild_password
```

真实值以 `.env` 为准。

## 测试数据库

自动化测试使用 H2 内存数据库：

```text
profile: test
配置: src/test/resources/application-test.yml
```

测试类需要：

```java
@ActiveProfiles("test")
```

这样运行单元测试时不要求 Docker PostgreSQL 已启动。

## `.env`

Spring 通过以下配置读取项目根目录 `.env`：

```yaml
spring:
  config:
    import: optional:file:.env[.properties]
```

`.env` 使用简单的 `KEY=VALUE` 格式，不要在其中写 Shell 命令。

`.env` 不提交，`.env.example` 需要提交。

## 数据持久化

PostgreSQL 数据保存在 Docker 命名卷：

```text
interview_rebuild_postgres_data
```

执行：

```bash
docker compose down
```

不会删除数据卷。

不要随意执行：

```bash
docker compose down -v
```

因为 `-v` 会删除本地数据库数据。

## 常见问题

### 端口被占用

修改 `.env`：

```text
SERVER_PORT=8082
POSTGRES_PORT=5434
```

应用和 Compose 都会读取对应变量。

### Java 版本错误

确认：

```bash
java -version
./gradlew --version
```

两处都应该使用 Java 21。

### 数据库密码修改后仍连接失败

数据库初始化后，修改 `.env` 不会自动修改已有数据卷中的密码。

学习阶段可以选择保留原密码，或明确备份后重建数据卷。
