# AI 实施计划：AI 调用观测与 RAG 评测中心

## 一、执行前提

请先阅读：

- `AGENTS.md`
- `app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java`
- `app/src/main/java/interview/guide/modules/knowledgebase/service/RagChatSessionService.java`

遵守项目规范：

- Controller 只做路由和委托。
- Service 编排业务。
- Repository 只负责数据访问。
- 禁止直接返回 Entity。
- 业务异常使用 `BusinessException(ErrorCode.XXX, message)`。
- 配置集中使用 `@ConfigurationProperties`。

## 二、第一阶段：新增观测数据模型

新增包：

```text
app/src/main/java/interview/guide/modules/aiobservability/
```

新增实体：

- `AiInvocationLogEntity`
- `RagRetrievalLogEntity`
- `RagRetrievedChunkLogEntity`

新增 DTO：

- `AiInvocationLogDTO`
- `RagTraceDTO`
- `RagRetrievedChunkDTO`

新增 Repository：

- `AiInvocationLogRepository`
- `RagRetrievalLogRepository`
- `RagRetrievedChunkLogRepository`

字段建议以 `改进详述.md` 为准。JSON 字段可以先用 `String xxxJson` 存储，降低复杂度。

## 三、第二阶段：新增日志服务

新增：

- `AiInvocationLogService`
- `RagRetrievalLogService`

服务职责：

- 创建 traceId。
- 保存 AI 调用开始和结束信息。
- 保存 RAG 检索参数。
- 保存命中文档片段。
- 查询某个 traceId 的完整链路。

注意：

- 日志保存失败不能影响主业务。
- 日志服务内部 catch 异常后只打 warn。
- 不要在主业务事务里强依赖日志写入成功。

## 四、第三阶段：接入结构化输出调用

改造：

- `StructuredOutputInvoker.invoke(...)`

目标：

- 每次结构化输出调用记录一条 `AiInvocationLogEntity`。
- 记录 `logContext`、attemptCount、success、latencyMs、errorMessage。
- 成功和失败都要落日志。

注意：

- 不要把完整 prompt 默认写入 DB。
- 可以记录 prompt hash 或 prompt 长度。
- 日志失败不能导致 AI 调用失败。

## 五、第四阶段：接入 RAG 检索日志

改造：

- `KnowledgeBaseQueryService.answerQuestion(...)`
- `KnowledgeBaseQueryService.answerQuestionStream(...)`
- `KnowledgeBaseQueryService.retrieveRelevantDocs(...)`

目标：

- 生成 traceId。
- 记录原始问题、改写问题、候选 query、TopK、minScore、knowledgeBaseIds。
- 记录命中文档数量和检索耗时。
- 记录每个命中文档的 rank、metadata、preview。

建议：

- 给 `QueryContext` 增加 traceId 或新增 `RagTraceContext`。
- 如果不想大改方法签名，可以在 `answerQuestionStream` 内创建上下文对象。

## 六、第五阶段：新增查询接口

新增 Controller：

- `AiObservabilityController`

接口建议：

```text
GET /api/ai-observability/invocations
GET /api/ai-observability/invocations/{id}
GET /api/ai-observability/rag-traces/{traceId}
GET /api/ai-observability/rag-traces/recent
```

返回 DTO，不返回 Entity。

## 七、第六阶段：RAG 评测最小版本

新增实体：

- `RagEvalDatasetEntity`
- `RagEvalCaseEntity`
- `RagEvalRunEntity`
- `RagEvalRunCaseResultEntity`

新增服务：

- `RagEvaluationService`

最小功能：

- 创建评测集。
- 添加评测问题。
- 批量运行某个评测集。
- 输出每个 case 的命中 chunk 数、是否包含期望关键词、耗时。

接口建议：

```text
POST /api/ai-observability/rag-eval/datasets
POST /api/ai-observability/rag-eval/datasets/{id}/cases
POST /api/ai-observability/rag-eval/datasets/{id}/runs
GET /api/ai-observability/rag-eval/runs/{runId}
```

## 八、测试要求

至少新增：

- `AiInvocationLogServiceTest`
- `RagRetrievalLogServiceTest`
- `RagEvaluationServiceTest`

如果测试中不方便真实调用 LLM：

- Mock `KnowledgeBaseQueryService`
- Mock `ChatClient`
- 重点验证日志落库、异常不影响主流程、DTO 转换正确。

## 九、验收标准

完成后必须满足：

- RAG 聊天一次后，可以通过 traceId 查到检索参数和命中文档。
- 简历分析或面试出题失败时，可以查到结构化输出失败日志。
- 日志写入失败不会影响主业务。
- 至少有一个 RAG 评测集可以跑通。
- 所有新增 Controller 不直接返回 Entity。

