# AI 面试项目技术题库

本题库根据当前项目的 Java、前端、配置、README 和测试代码重新整理，按照技术栈和技术专题划分，而不是按照简历、文字面试、语音面试等业务模块划分。

业务功能只作为技术落地案例。例如，简历分析用于说明消息队列、文件解析和对象存储；文字面试用于说明 LLM、结构化输出、缓存和事务；语音面试用于说明 WebSocket、并发和流式处理。

## 章节目录

| 章节 | 题数 | 核心内容 |
|---|---:|---|
| [01 项目介绍与系统架构](./01-项目介绍与系统架构.md) | 10 | 模块化单体、分层架构、存储分工、同步异步边界、系统瓶颈与演进 |
| [02 大模型调用与 LLM 网关](./02-大模型调用与LLM网关.md) | 10 | 多 Provider、ChatClient、模型切换、重试降级、上下文、成本与幻觉 |
| [03 Prompt、结构化输出与 Skills](./03-Prompt结构化输出与Skills.md) | 10 | Prompt 模板、JSON 修复重试、plain Client、Skills、Tool Calling 与注入防护 |
| [04 RAG 与知识库](./04-RAG与知识库.md) | 15 | 文档解析、Chunk、Embedding、检索策略、Query Rewrite、幻觉与完整评测体系 |
| [05 消息队列与异步任务](./05-消息队列与异步任务.md) | 15 | Redis Stream、Consumer Group、PEL、幂等、重试、顺序、积压、扩容与组件对比 |
| [06 Redis 缓存、限流与分布式控制](./06-Redis缓存限流与分布式控制.md) | 10 | Key/TTL、Cache-Aside、穿透击穿雪崩、Lua 限流、锁与 Redis 高可用 |
| [07 数据库事务与数据一致性](./07-数据库事务与数据一致性.md) | 10 | PostgreSQL、事务边界、跨存储一致性、Outbox、唯一键、锁、JSON 与对账 |
| [08 WebSocket 与实时语音](./08-WebSocket与实时语音.md) | 10 | WebSocket/WebRTC、PCM、ASR-LLM-TTS、重连、背压、打断与多实例 |
| [09 Java 并发、异步与虚拟线程](./09-Java并发异步与虚拟线程.md) | 10 | 虚拟线程、线程池、CAS、Semaphore、Future、定时任务、取消与上下文传播 |
| [10 文件解析与对象存储](./10-文件解析与对象存储.md) | 10 | MIME、Tika、文本清洗、文件安全、SHA-256、S3、补偿与孤儿清理 |
| [11 Spring Boot 与后端工程设计](./11-Spring-Boot与后端工程设计.md) | 10 | MVC 分层、Result、异常、DTO、配置绑定、AOP 事务、条件装配与调度 |
| [12 安全认证与权限控制](./12-安全认证与权限控制.md) | 10 | 当前鉴权缺口、OIDC/JWT、RBAC、多租户、WebSocket 安全、密钥与数据隐私 |
| [13 可观测性、日志与链路追踪](./13-可观测性日志与链路追踪.md) | 10 | traceId、MDC、AI attempt/invocation、RAG 明细、Micrometer、SLO 与告警 |
| [14 测试、部署与高可用](./14-测试部署与高可用.md) | 10 | JUnit/Mockito、H2、Testcontainers、Compose、健康检查、迁移、灾备与 CI/CD |
| [15 前端流式交互与 SSE](./15-前端流式交互与SSE.md) | 10 | Axios、fetch SSE、事件解析、取消重试、React 渲染、音频前端与 Nginx |

总计：15 个技术章节，160 道题。

## 核心技术放置位置

| 核心技术 | 主要章节 | 关联章节 |
|---|---|---|
| Redis Stream 消息队列 | 第 05 章 | 第 07、09、13 章 |
| Redis 缓存与限流 | 第 06 章 | 第 05、07、12 章 |
| RAG、pgvector 与检索评测 | 第 04 章 | 第 02、10、13 章 |
| PostgreSQL 与数据一致性 | 第 07 章 | 第 05、06、10、14 章 |
| LLM Provider 与 ChatClient | 第 02 章 | 第 03、04、13 章 |
| Prompt、结构化输出与 Skills | 第 03 章 | 第 02、04、12 章 |
| WebSocket、ASR、TTS | 第 08 章 | 第 09、12、15 章 |
| Java 虚拟线程与并发控制 | 第 09 章 | 第 05、08 章 |
| 文件解析与 S3 兼容存储 | 第 10 章 | 第 04、07、12 章 |
| Spring Boot 工程设计 | 第 11 章 | 所有后端章节 |
| 认证授权与数据安全 | 第 12 章 | 第 06、08、10、14 章 |
| 日志、指标与 Trace | 第 13 章 | 第 02、04、05、08 章 |
| 测试、Docker 与高可用 | 第 14 章 | 第 07、08、13 章 |
| React、SSE 与 Nginx | 第 15 章 | 第 04、08 章 |

## 相比旧题库的调整

### 新增并独立成章

- 消息队列与异步任务：集中覆盖 Redis Stream、PEL、重试、顺序、积压、扩容和 Kafka/RabbitMQ 对比。
- Java 并发、异步与虚拟线程：集中覆盖虚拟线程、线程池、CAS、Semaphore、Future、取消和上下文传播。
- Spring Boot 与后端工程设计：集中覆盖 MVC 分层、配置绑定、AOP、DTO、异常和生命周期。
- 前端流式交互与 SSE：集中覆盖 fetch 流、事件协议、取消、渲染性能和 Nginx。
- Prompt、结构化输出与 Skills：从原大模型章节中拆出，避免 LLM 网关与 Prompt 工程混在一起。

### 删除或合并的重复方向

- “简历上传完整流程”“知识库上传完整流程”不再分别作为业务章节，文件校验、对象存储和补偿统一放入第 10 章。
- “简历异步分析”“知识库向量化”“面试评估”中重复的生产、消费、重试与恢复问题统一放入第 05 章。
- “文字会话缓存”和“Redis 的作用”合并为第 06 章中的 Cache-Aside、TTL 和一致性问题。
- “文字面试并行出题”和“语音并发 TTS”保留为第 09 章的不同并发案例，不再重复解释虚拟线程原理。
- “业务错误处理”“AI 降级”“异步失败”分别放入工程设计、LLM 网关和消息队列章节，避免同一异常流程反复描述。
- “WebSocket 为什么不用 HTTP”“WebSocket 优点”“为什么不用 WebRTC”合并为协议选择与 WebRTC 对比两道不同层次的问题。

## 推荐复习顺序

1. 先学习第 01 章，形成两到三分钟的系统架构介绍。
2. 学习第 02～04 章，掌握 LLM、Prompt 和 RAG 三条 AI 技术主线。
3. 学习第 05～09 章，重点准备消息、Redis、事务、WebSocket 和 Java 并发连续追问。
4. 学习第 10～13 章，补齐文件、Spring 工程、安全和可观测性。
5. 最后学习第 14～15 章，用测试、部署、高可用和前端流式交互回答生产化问题。

## 真实性边界

题库明确区分已实现、部分实现和未实现能力。以下能力不能描述成已经完整落地：

- 尚未实现 Spring Security、登录认证、RBAC、多租户和 WebSocket 会话所有权校验。
- 已接入 SkillsTool 和 Skill 资源，但没有完整 Agent 编排系统。
- 实际语音传输是 WebSocket + Base64 PCM，不是 README 中勾选完成的 WebRTC。
- 已有 Micrometer 埋点，但未完整接入 Actuator、Prometheus 和 Grafana。
- Redis Stream 是至少一次处理语义，after-commit 仍有漏发空窗，没有完整 Outbox 和 Exactly Once。
- RAG 评测目前主要是关键词命中与延迟，不是完整 Recall@K、MRR、NDCG、Faithfulness 或 LLM-as-a-Judge。
- Docker Compose 是单实例可复现环境，不是高可用生产集群。
- 多个语音核心测试和真实 Redis 限流测试仍处于 `@Disabled`。

## 项目使用但未单独成章的技术

- MapStruct、iText PDF 导出、邮件 SMTP、面试日程调度没有单独成章，因为它们在当前项目中的技术深度和题量不足以形成 10 道不重复专题题。
- MapStruct 已放入第 11 章，邮件和调度分布在第 05、07、11、14 章，PDF 导出作为基础设施实现保留在项目代码依据中。
- Redisson 作为 Redis 客户端放入第 06 章，Spring Data JPA 和 pgvector 分别放入第 07、04 章。
