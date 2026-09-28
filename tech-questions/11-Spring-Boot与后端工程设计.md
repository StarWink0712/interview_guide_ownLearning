# Spring Boot 与后端工程设计

## 1. 面试官：Spring Boot 4 和 Java 21 在项目中带来了哪些工程能力？

我的回答：

Spring Boot 提供自动配置、Starter、配置绑定和统一生命周期，Java 21 提供虚拟线程、record 与现代语法。我的项目使用 Boot 4 的模块化 webmvc、validation、data-jpa、websocket 和 mail Starter，Java Toolchain 固定 21，并开启 Spring 虚拟线程；请求模型优先用 record，持久化模型仍使用 JPA Entity。这样减少基础设施样板并适合 I/O 密集 AI 场景。Boot 4 与 Spring AI 2.0 均较新，第三方兼容性需要验证，例如项目选择 Redisson 4.0。生产升级时应锁定版本、查看弃用报告并运行完整契约和容器测试。

代码依据：

- `app/build.gradle` — Starter 与 Java Toolchain
- `gradle/libs.versions.toml` — 依赖版本
- `app/src/main/resources/application.yml` — 虚拟线程
- `app/src/main/java/interview/guide/modules/interview/model/CreateInterviewRequest.java` — record 请求

## 2. 面试官：Controller 为什么应该只做路由、校验和委托？

我的回答：

Controller 是协议适配层，若混入业务与事务，HTTP 细节会污染可复用逻辑，也难以单测。我的项目 Controller 主要定义 REST 路径、`@Valid`、`@RequestBody`、`@RateLimit` 和 `Result`，再委托 Service；文件下载和 PDF 因需要 Content-Type、Content-Disposition，会直接返回 `ResponseEntity<byte[]>`，这是协议职责而非业务。复杂上传、面试和 RAG 编排都在 Service。当前少数 Controller 仍有异常包装和响应拼装，可继续抽取。这样未来增加其他入口时可以复用 Service，而不复制 HTTP 控制流。

代码依据：

- `app/src/main/java/interview/guide/modules/interview/InterviewController.java` — 路由与委托
- `app/src/main/java/interview/guide/modules/knowledgebase/KnowledgeBaseController.java` — REST 与 SSE
- `app/src/main/java/interview/guide/modules/resume/ResumeController.java` — 文件响应
- `app/src/main/java/interview/guide/common/annotation/RateLimit.java` — 横切限流

## 3. 面试官：统一 Result 和全局异常处理如何协作，有什么争议？

我的回答：

统一响应让前端始终解析 code、message 和 data，业务异常则携带分域 ErrorCode，由 `GlobalExceptionHandler` 捕获参数校验、限流、AI 网络和未知异常并返回 `Result.error`。我的项目约定所有异常 HTTP 状态仍为 200，Axios 拦截器再根据 body code 判断成功。优点是前后端规则一致，缺点是网关、APM 和缓存只看 HTTP 状态时会误判，429/500 也难以用标准策略处理。我会保留业务 code，但生产 API 同时返回正确 HTTP 400、401、429、500，让协议与业务语义各司其职。

代码依据：

- `app/src/main/java/interview/guide/common/result/Result.java` — 统一响应
- `app/src/main/java/interview/guide/common/exception/BusinessException.java` — 业务异常
- `app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java` — 异常映射
- `frontend/src/api/request.ts` — body code 拦截

## 4. 面试官：DTO、Request、Response、Entity 和 record 在项目中怎样区分？

我的回答：

Entity 表示持久化结构，不应直接暴露；Request/Response 对应接口契约，DTO 用于跨层或聚合数据。我的项目的 JPA Entity 使用 Lombok 与关系注解，创建请求等不可变载体优先使用 record，列表与详情返回单独 DTO；MapStruct 负责 Resume、Interview、KnowledgeBase 和 RagChat 的 Entity 映射。这样数据库字段调整不必直接破坏 API，也能隐藏密钥、JSON 和内部状态。当前部分简单转换仍手写或使用 BeanUtils，结构复杂时容易漏字段。生产上应为 API DTO 建契约测试，并避免 Mapper 中隐含业务判断。

代码依据：

- `app/src/main/java/interview/guide/modules/resume/model/ResumeEntity.java` — JPA Entity
- `app/src/main/java/interview/guide/modules/interview/model/CreateInterviewRequest.java` — record Request
- `app/src/main/java/interview/guide/modules/resume/model/ResumeDetailDTO.java` — DTO
- `app/src/main/java/interview/guide/infrastructure/mapper/ResumeMapper.java` — MapStruct

## 5. 面试官：为什么使用 ConfigurationProperties，而不是在 Service 中散落 @Value？

我的回答：

`@ConfigurationProperties` 把同一前缀配置绑定成有类型对象，便于默认值、校验、测试和 IDE 提示；散落 `@Value` 会让 Service 隐藏依赖并难以统一修改。我的项目将存储、CORS、LLM Provider、结构化输出、RAG、面试问题、提醒和语音参数分别放在 Properties 类，构造函数注入 Service；敏感值通过环境变量覆盖。这样配置边界清晰。当前部分属性很多且动态 Provider 又有数据库/YAML 双来源，优先级需要严格文档化。生产上会增加 `@Validated`、范围约束和启动时 fail-fast，防止非法维度或空密钥运行。

代码依据：

- `app/src/main/java/interview/guide/common/config/LlmProviderProperties.java` — LLM 配置绑定
- `app/src/main/java/interview/guide/common/ai/StructuredOutputProperties.java` — 结构化配置
- `app/src/main/java/interview/guide/modules/voiceinterview/config/VoiceInterviewProperties.java` — 语音配置
- `app/src/main/resources/application.yml` — 配置来源

## 6. 面试官：Spring AOP 事务为什么不能通过同类内部调用生效？

我的回答：

Spring 默认用代理拦截外部方法调用来开启事务，同一个对象内部 `this.method()` 不经过代理，因此被调方法的 `@Transactional` 不会生效。我的项目通过把数据库写操作拆到独立 `ResumePersistenceService`、`InterviewPersistenceService` 等 Bean，由编排 Service 注入调用，既避免 self-invocation，也缩小事务范围。限流切面同样依赖 Controller 方法经 Spring 代理执行。当前设计符合代理语义，但开发时仍要避免把私有方法标事务。需要复杂事务边界时可用 `TransactionTemplate` 显式表达，而不是依赖调用者记住代理细节。

代码依据：

- `app/src/main/java/interview/guide/modules/resume/service/ResumePersistenceService.java` — `@Transactional`
- `app/src/main/java/interview/guide/modules/resume/service/ResumeUploadService.java` — 跨 Bean 调用
- `app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java` — 会话事务
- `app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java` — AOP 切面

## 7. 面试官：条件装配和应用生命周期在项目中有哪些使用？

我的回答：

条件装配让功能只在配置满足时创建 Bean，生命周期回调负责初始化与释放资源。我的项目用 `@ConditionalOnProperty` 控制提醒 Scheduler、Producer 和 Consumer，dispatch-enabled 默认 false 时不会真实投递；`@PostConstruct` 或初始化方法加载限流 Lua、启动 Stream 消费、预热开场 TTS；`@PreDestroy`/DisposableBean 关闭 executor、ASR 和连接。这样可选功能不会仅靠运行时 if 维持，也减少资源泄漏。当前某些初始化依赖外部 Redis/语音服务，失败策略需明确。生产上会把 readiness 与初始化状态关联，并保证 shutdown 先停止接流量再排空任务。

代码依据：

- `app/src/main/java/interview/guide/modules/interviewschedule/reminder/service/InterviewReminderScheduler.java` — `@ConditionalOnProperty`
- `app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java` — `init()`、`shutdown()`
- `app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java` — Lua 初始化
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — `destroy()`

## 8. 面试官：CORS 和 OpenAPI 配置为什么应集中管理？

我的回答：

CORS 与 API 文档属于跨接口协议能力，分散在 Controller 容易出现不一致。我的项目用 `CorsProperties` 绑定允许 Origin，`CorsConfig` 统一作用于 `/api/**`，配置允许的方法、头和 credentials；`OpenApiConfig` 与 springdoc 集中生成模块接口文档，Swagger UI 和 api-docs 路径在 YAML 设置。这样环境切换只修改配置。CORS 不是认证，允许 credentials 时更不能使用任意 Origin；WebSocket Origin 也需单独配置。生产上应限制文档端点访问、按环境关闭 Swagger，并让真实域名白名单由受控配置发布。

代码依据：

- `app/src/main/java/interview/guide/common/config/CorsConfig.java` — CORS 映射
- `app/src/main/java/interview/guide/common/config/CorsProperties.java` — Origin 配置
- `app/src/main/java/interview/guide/common/config/OpenApiConfig.java` — OpenAPI
- `app/src/main/resources/application.yml` — springdoc 配置

## 9. 面试官：项目中的定时任务怎样设计，为什么不能把耗时逻辑都写进 @Scheduled？

我的回答：

Scheduler 应只做扫描和触发，耗时工作交给可恢复执行器，否则一次超时会拖延后续调度。我的项目的提醒 Scheduler 每分钟领取到期记录并写 Redis Stream，邮件由 Consumer 发送；状态更新器每小时用批量 SQL 标记过期日程；Pending 恢复每 60 秒调用 XAUTOCLAIM；语音空闲扫描只检查 Map 和关闭连接。这样调度函数大多短小。当前多实例下每个实例都会触发，提醒用数据库悲观锁认领，其他任务依赖幂等。生产上应监控调度延迟、执行时长，并对需要唯一执行的任务加入锁或 leader 机制。

代码依据：

- `app/src/main/java/interview/guide/modules/interviewschedule/reminder/service/InterviewReminderScheduler.java` — `dispatchDueReminders()`
- `app/src/main/java/interview/guide/modules/interviewschedule/service/ScheduleStatusUpdater.java` — 批量状态更新
- `app/src/main/java/interview/guide/modules/asynctask/service/PendingMessageRecoveryService.java` — Pending 扫描
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — 空闲扫描

## 10. 面试官：如何评价当前 Service 拆分和可测试性？

我的回答：

好的 Service 应围绕单一用例和明确依赖，外部系统通过接口或小组件隔离。我的项目已把简历拆为 Upload、Parse、Grading、Persistence、History、Delete，把知识库拆为 Upload、Vector、Query、Chat 等，并抽出统一 Evaluation、LLM Registry 和 Stream 模板；Mockito 测试可验证补偿、状态和模型交互。仍有 `LlmProviderRegistry`、配置 Service、WebSocket Handler 等超大类，职责包括创建、缓存、协议、观测或状态机，维护成本高。后续会按工厂、路由、状态机、协议编码和仓储边界拆分，同时避免为拆分而制造无业务意义的接口。

代码依据：

- `app/src/main/java/interview/guide/modules/resume/service/` — 简历 Service 拆分
- `app/src/main/java/interview/guide/modules/knowledgebase/service/` — 知识库 Service 拆分
- `app/src/test/java/interview/guide/modules/resume/service/ResumeUploadServiceTest.java` — 编排测试
- `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java` — 当前大类边界

# 本章总结

项目使用 Spring Boot 的配置、AOP、校验、JPA、WebSocket、调度和生命周期能力构建模块化单体，并通过 DTO、PersistenceService 与统一异常保持分层。

工程上的下一步是加强配置校验、HTTP 语义、优雅停机和大类拆分，而不是单纯增加更多框架注解。

# 核心主线

让 Spring 管理协议、配置和生命周期，让业务 Service 聚焦用例，并用清晰代理与事务边界保证行为可测试。
