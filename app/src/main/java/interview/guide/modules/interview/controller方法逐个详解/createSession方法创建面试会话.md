# createSession：创建面试会话完整调用链

> 接口：`POST /api/interview/sessions`  
> Controller：`InterviewController.createSession`  
> 当前前端调用：`InterviewPage.startInterview`

## 1. 这个接口完成什么

它不是简单插入一条会话记录，而是同步完成以下工作：

```text
接收面试配置
  → 全局/IP 双维度限流
  → 查询历史题目
  → 调用 LLM 生成题目
  → 写入 Redis 会话缓存
  → 尝试写入 PostgreSQL
  → 返回包含完整题单的 InterviewSessionDTO
  → 前端初始化聊天式面试界面
```

由于出题是同步 LLM 调用，前端为该请求单独设置了 180 秒超时。

## 2. 前端怎样触发

`App.tsx` 把 `/interview` 和 `/interview/:resumeId` 都交给 `InterviewWrapper`。包装器负责取得
简历文本和页面 state，再渲染 `InterviewPage`。

`InterviewPage` 首次挂载时执行 `useEffect`：

```text
有 sessionIdToResume → resumeExistingSession，调用 getSession
没有 sessionIdToResume → startInterview，调用 createSession
```

新建路径中的请求参数来自页面配置：

```ts
interviewApi.createSession({
  resumeText,
  questionCount,
  resumeId,
  forceCreate: true,
  llmProvider,
  skillId,
  difficulty,
  customCategories,
  jdText,
});
```

当前页面始终发送 `forceCreate: true`，所以 Service 中“复用未完成会话”的分支不会被这个页面
触发。自定义 Skill 时才发送 `customCategories` 和 `jdText`。

`interviewApi.createSession` 调用：

```ts
request.post('/api/interview/sessions', req, { timeout: 180000 })
```

开发环境最终请求发往 `http://localhost:8080/api/interview/sessions`；生产环境使用同源地址。

## 3. Controller 执行前：限流切面

方法上有两条可重复限流注解：

```java
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
@RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
```

两条规则都使用默认窗口 `1 SECONDS`，因此语义是：

- 所有客户端合计每秒最多 5 次；
- 同一个 IP 每秒最多 5 次；
- 两个维度必须同时通过。

`RateLimitAspect.around` 为每个维度生成 Redis Key，执行滑动窗口 Lua 脚本。任一维度耗尽时
抛出 `RateLimitExceededException`，请求不会进入 Controller，随后由全局异常处理器包装为
HTTP 200 + `Result.error(8001, ...)`。

## 4. Controller 做什么

```java
@PostMapping("/api/interview/sessions")
public Result<InterviewSessionDTO> createSession(
    @RequestBody CreateInterviewRequest request
) {
  InterviewSessionDTO session = sessionService.createSession(request);
  return Result.success(session);
}
```

调用顺序：

1. Spring/Jackson 把 JSON 反序列化成 `CreateInterviewRequest` record。
2. Controller 记录题目数量日志。
3. 调用 `InterviewSessionService.createSession(request)`。
4. 用 `Result.success(session)` 包装返回值。

需要注意：`CreateInterviewRequest` 虽然声明了 `@Min`、`@Max`、`@NotBlank`，Controller 参数
没有添加 `@Valid`，所以这些 Jakarta Validation 约束当前不会自动执行。这与项目规范不一致。

## 5. Service 第一步：决定复用还是新建

`InterviewSessionService.createSession` 首先判断：

```java
request.resumeId() != null && !Boolean.TRUE.equals(request.forceCreate())
```

满足时调用 `findUnfinishedSession(resumeId)`：先查 Redis 中的简历→会话映射，再降级查询
PostgreSQL 中最新的 `CREATED`/`IN_PROGRESS` 会话。找到后直接返回旧会话，不再调用 LLM。

当前 `InterviewPage` 传入 `forceCreate=true`，所以真实页面新建流程会跳过该分支。

## 6. Service 第二步：准备会话和历史题

新建路径执行：

1. 使用 UUID 生成去掉连字符后的 16 位 `sessionId`。
2. `skillId` 为空时使用 `InterviewDefaults.SKILL_ID`。
3. `difficulty` 为空时使用默认难度。
4. 调用：

```java
persistenceService.getHistoricalQuestions(skillId, resumeId)
```

历史题查询根据场景选择最近会话：

- 有简历：按 `resumeId + skillId` 查询最近记录；
- 无简历：按 `skillId` 查询最近记录；
- 最终最多整理 60 条历史问题，作为 Prompt 去重上下文。

## 7. Service 第三步：调用 InterviewQuestionService 出题

调用入口：

```java
questionService.generateQuestionsBySkill(
    request.llmProvider(),
    skillId,
    difficulty,
    request.resumeText(),
    request.questionCount(),
    historicalQuestions,
    request.customCategories(),
    request.jdText()
)
```

内部完整路径：

```text
resolveSkill(skillId, customCategories, jdText)
  → resolveDifficulty(difficulty)
  → llmProviderRegistry.getPlainChatClient(llmProvider)
  → buildHistoricalSection(historicalQuestions)
```

无简历文本时，只调用 `generateDirectionOnly`。有简历文本时，使用虚拟线程执行器并行执行：

- `generateResumeQuestions`：基于简历经历出题；
- `generateDirectionOnly`：基于 Skill、分类配额、难度和 JD 出题。

两个分支都会渲染 Prompt，并通过：

```text
StructuredOutputInvoker.invoke
  → ChatClient 调用 LLM
  → BeanOutputConverter 解析 QuestionListDTO
  → convertToQuestions
  → capToMainCount
```

有简历时，一个分支失败会降级到另一类题目；两个结果都为空时使用本地兜底题。最终调用
`mergeQuestionBatches` 合并并建立稳定的题目索引。

## 8. Service 第四步：写 Redis 缓存

出题成功后调用：

```java
sessionCache.saveSession(
    sessionId,
    resumeText,
    resumeId,
    questions,
    0,
    SessionStatus.CREATED
);
```

`InterviewSessionCache.saveSession`：

1. 构造会话缓存 Key。
2. 把题目序列化进 `CachedSession`。
3. 通过 `RedisService.set` 保存 24 小时。
4. 如果存在 `resumeId` 且状态未完成，建立“简历 ID → 会话 ID”映射。

Redis 写入失败会直接中断创建流程，不会继续保存数据库。

## 9. Service 第五步：尝试持久化数据库

随后调用：

```java
persistenceService.saveSession(
    sessionId, resumeId, questions.size(), questions,
    llmProvider, skillId, difficulty
);
```

`InterviewPersistenceService.saveSession` 在事务中：

1. 创建 `InterviewSessionEntity`。
2. 把题目列表序列化为 `questionsJson`。
3. 设置索引 0、状态 `CREATED`、Provider、Skill 和难度。
4. 有 `resumeId` 时查询 `ResumeRepository.findById` 并建立关联。
5. 调用 `InterviewSessionRepository.save`。

但 `createSession` 用 `try/catch (Exception)` 包住了数据库保存。保存失败时只记录警告，仍然
返回 Redis 中已经创建的会话。结果是：前端可能成功开始面试，但数据库没有对应记录，后续答案
持久化、历史列表和异步评估都可能失败。

## 10. 返回前端并初始化界面

Service 构造：

```text
InterviewSessionDTO(
  sessionId,
  resumeText,
  questions.size,
  currentQuestionIndex=0,
  questions,
  status=CREATED
)
```

Controller 返回：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": "...",
    "currentQuestionIndex": 0,
    "questions": ["..."],
    "status": "CREATED"
  }
}
```

`request.ts` 拦截器提取 `data` 后，`InterviewPage.startInterview` 调用 `initSession(newSession)`：

- 保存 `session`；
- 取 `questions[0]` 作为当前题；
- 构建第一条 interviewer 消息；
- 结束“正在生成面试题目”加载状态。

至此创建闭环完成。

## 11. 异常怎样回到页面

| 失败位置 | 后端行为 | 前端行为 |
|---|---|---|
| 限流未通过 | `RateLimitExceededException` → `Result.error` | 统一被页面显示为“创建面试失败，请重试” |
| Provider 配置或 LLM 出题失败 | `BusinessException` → 全局异常处理 | 同上，具体后端 message 被页面丢弃 |
| Redis 会话保存失败 | 全局异常处理 | 同上 |
| PostgreSQL 会话保存失败 | Service 吞掉异常并继续成功返回 | 页面无法感知 |

前端 `catch` 没有使用 `getErrorMessage`，所有失败都显示固定文案，真实业务错误只能从日志或网络
响应中查看。

## 12. 当前实现边界

- Controller 缺少 `@Valid`，请求 record 上的校验注解未生效。
- 当前页面固定 `forceCreate=true`，不会复用未完成会话。
- LLM 调用是同步的，接口耗时可能接近前端 180 秒超时。
- Redis 先写、数据库后写，且数据库异常被吞掉，存在“只有缓存、没有持久化记录”的会话。
- 没有数据库事务覆盖“缓存 + 数据库”；两种存储不是原子操作。
