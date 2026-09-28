# 下一次 Codex 任务

## 任务目标

在当前新项目中完成并验证本地开发环境。

不要开始业务功能开发。

## 必须先读取

```text
AGENTS.md
README.md
SETUP_STATUS.md
docs/ENVIRONMENT.md
docs/DEPENDENCY_PHASES.md
```

## 执行步骤

1. 检查当前工作区文件，确认模板复制完整。
2. 检查 Java 21、Docker 和 Docker Compose。
3. 如果 `.env` 不存在，从 `.env.example` 创建。
4. 检查 `build.gradle`、`application.yml` 和测试配置是否一致。
5. 运行 `docker compose config`。
6. 启动 PostgreSQL，并等待健康检查通过。
7. 运行 `./gradlew test`。
8. 运行 Spring Boot，并验证 `GET /api/health` 返回 `ok`。
9. 修复发现的环境或配置问题。
10. 更新 `SETUP_STATUS.md`，记录实际版本、执行结果和遗留问题。

## 修改范围

可以修改：

```text
build.gradle
settings.gradle
gradle/wrapper/
application.yml
application-test.yml
docker-compose.yml
.env.example
scripts/
README 和环境文档
最小启动类与健康检查
```

不要加入：

```text
Spring AI
Redis
S3
Tika
WebSocket
业务 Entity
业务 Service
React 前端
```

## 完成标准

- Java 主版本为 21。
- `docker compose config` 成功。
- PostgreSQL 容器为 healthy。
- `./gradlew test` 通过。
- Spring Boot 可以连接 PostgreSQL 并启动。
- `curl http://localhost:8081/api/health` 返回 `ok`。
- `.env` 不会被 Git 跟踪。
- `SETUP_STATUS.md` 已更新。

## 完成后的回复

说明：

```text
修改了哪些配置
Java、Gradle、Docker 和 PostgreSQL 的实际版本
运行了哪些验证
是否存在遗留问题
下一阶段是否可以开始
```
