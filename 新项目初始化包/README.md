# 新项目初始化包

这是一个用于创建“AI 面试平台手写版”的最小项目模板。

模板版本与当前参考项目对齐：

```text
Java 21
Spring Boot 4.0.1
Gradle 8.14
PostgreSQL 16 + pgvector
JUnit 5
H2 测试数据库
```

这些是参考项目当前使用的固定版本，不表示互联网上的最新版本。

## 这个包包含什么

```text
新项目初始化包/
├── install.sh                  # 把模板安装到空目录
├── README.md                   # 当前说明
└── project-template/           # 将被复制到新项目
    ├── AGENTS.md
    ├── NEXT_AGENT_TASK.md
    ├── SETUP_STATUS.md
    ├── build.gradle
    ├── settings.gradle
    ├── gradlew
    ├── gradlew.bat
    ├── gradle/wrapper/
    ├── docker-compose.yml
    ├── .env.example
    ├── scripts/
    ├── docs/
    └── src/
```

## 推荐安装方式

在当前仓库根目录执行：

```bash
./新项目初始化包/install.sh "/你的/新项目/绝对路径"
```

安装脚本只接受空目录，默认不会覆盖已有文件。

安装成功后：

```bash
cd "/你的/新项目/绝对路径"
./scripts/setup-local.sh
```

然后启动后端：

```bash
./scripts/run-backend.sh
```

访问：

```text
http://localhost:8081/api/health
```

预期响应：

```text
ok
```

## 让 Codex 在新项目中继续配置

进入新项目线程后，可以直接说：

```text
请读取 AGENTS.md、NEXT_AGENT_TASK.md 和 SETUP_STATUS.md，
按照文档完成环境配置、验证并更新状态。先不要开发业务功能。
```

这样下一次工作会限定在环境配置，不会提前开始复制业务代码。

## 为什么没有默认加入高级依赖

模板暂时没有启用：

- Spring AI
- Redis/Redisson
- Apache Tika
- S3/RustFS
- pgvector VectorStore
- WebSocket
- MapStruct
- iText

数据库镜像已经支持 pgvector，但应用暂时不依赖向量存储。

后续每进入一个阶段，再按照 `docs/DEPENDENCY_PHASES.md` 加入对应依赖。

## 端口规划

为了能和当前参考项目同时运行：

| 服务 | 手写项目 | 当前参考项目 |
|---|---:|---:|
| Spring Boot | 8081 | 8080 |
| PostgreSQL | 5433 | 5432 |
| React | 5173 | 可能为 5173/5174 |

如果端口仍有冲突，可以修改新项目的 `.env`。
