# 环境配置状态

由执行环境配置的 Codex 更新此文件。

## 基础环境

- [ ] Java 主版本为 21。
- [ ] Docker 可用。
- [ ] Docker Compose 可用。
- [ ] Gradle Wrapper 可执行。

## 配置文件

- [ ] `.env` 已从 `.env.example` 创建。
- [ ] `.env` 已被 `.gitignore` 忽略。
- [ ] `application.yml` 与 `.env` 端口一致。
- [ ] 测试使用 H2，不依赖本地 PostgreSQL。

## 服务验证

- [ ] `docker compose config` 通过。
- [ ] PostgreSQL 容器启动。
- [ ] PostgreSQL 健康检查通过。
- [ ] `vector` 扩展已创建。

## 项目验证

- [ ] `./gradlew test` 通过。
- [ ] Spring Boot 启动成功。
- [ ] `/api/health` 返回 `ok`。

## 实际版本

```text
Java:
Gradle:
Docker:
Docker Compose:
PostgreSQL:
```

## 执行记录

```text
日期：
执行的命令：
修改的文件：
遇到的问题：
解决方式：
遗留问题：
```

## 下一阶段

环境全部通过后，下一阶段是：

```text
统一响应 Result<T>
ErrorCode
BusinessException
GlobalExceptionHandler
```
