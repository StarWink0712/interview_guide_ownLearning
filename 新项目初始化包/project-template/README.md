# Interview Guide Rebuild

这是一个从零手写 AI 面试平台的练习项目。

当前阶段只负责建立稳定的后端开发环境，不开发业务功能。

## 当前技术栈

```text
Java 21
Spring Boot 4.0.1
Gradle 8.14
Spring Web MVC
Validation
Spring Data JPA
PostgreSQL 16 + pgvector
H2（测试）
JUnit 5
```

## 第一次启动

```bash
./scripts/setup-local.sh
./scripts/run-backend.sh
```

验证：

```bash
curl http://localhost:8081/api/health
```

预期：

```text
ok
```

## 常用命令

```bash
# 检查 Java、Docker 和端口
./scripts/check-prerequisites.sh

# 启动 PostgreSQL
./scripts/start-deps.sh

# 停止 PostgreSQL，不删除数据卷
./scripts/stop-deps.sh

# 运行配置检查和测试
./scripts/verify.sh

# 启动后端
./scripts/run-backend.sh
```

## 配置文件

第一次执行 `setup-local.sh` 时，会把 `.env.example` 复制为 `.env`。

`.env` 包含本地密码，因此已加入 `.gitignore`，不要提交。

## 给 Codex 的指令

在新线程中说：

```text
请读取 AGENTS.md、NEXT_AGENT_TASK.md 和 SETUP_STATUS.md，
按照文档完成环境配置、验证并更新状态。先不要开发业务功能。
```

环境配置完成后，再开始公共骨架和面试日程 CRUD。
