# AI 实施计划：前端管理控制台与测试入口

## 一、执行前提

请先阅读：

- `AGENTS.md`
- `frontend/src/App.tsx`
- `frontend/src/components/Layout.tsx`
- `frontend/src/api/request.ts`
- `frontend/src/pages/SettingsPage.tsx`
- `frontend/src/pages/KnowledgeBaseQueryPage.tsx`
- `frontend/src/pages/InterviewPage.tsx`
- `frontend/src/components/RadarChart.tsx`

设计目标：

- 不做营销页。
- 不做复杂权限系统。
- 优先做测试、调试、演示入口。
- 保持现有 React + TypeScript + TailwindCSS 风格。

## 二、第一阶段：新增前端路由入口

修改：

- `frontend/src/App.tsx`
- `frontend/src/constants/routes.ts`
- `frontend/src/components/Layout.tsx`

新增路由建议：

```text
/admin
/admin/ai-traces
/admin/rag-eval
/admin/async-tasks
/admin/monitoring
/admin/interview-adaptive
```

新增页面：

```text
frontend/src/pages/AdminDashboardPage.tsx
frontend/src/pages/AiTracePage.tsx
frontend/src/pages/RagEvalPage.tsx
frontend/src/pages/AsyncTaskPage.tsx
frontend/src/pages/MonitoringPage.tsx
frontend/src/pages/AdaptiveInterviewInsightPage.tsx
```

如果觉得页面太多，第一版只新增：

- `AdminDashboardPage`
- `AiTracePage`
- `AsyncTaskPage`
- `RagEvalPage`

## 三、第二阶段：新增 API 封装

新增：

```text
frontend/src/api/aiObservability.ts
frontend/src/api/asyncTask.ts
frontend/src/api/ragEval.ts
frontend/src/api/monitoring.ts
```

所有 API 必须使用现有 `request` 封装，除非是 SSE 或特殊流式接口。

根据后端接口定义 TypeScript interface。

如果后端接口尚未实现：

- 先写 interface。
- API 函数可以按计划路径预留。
- 页面显示“后端接口待实现”空状态。

不要写假数据混淆真实测试。

## 四、第三阶段：新增管理通用组件

新增目录：

```text
frontend/src/components/admin/
```

新增组件：

- `AdminNav.tsx`
- `MetricCard.tsx`
- `StatusBadge.tsx`
- `TraceTimeline.tsx`
- `RagChunkList.tsx`
- `TaskTable.tsx`
- `EvalRunResultTable.tsx`
- `ConfirmActionButton.tsx`

组件要求：

- `StatusBadge` 统一展示 PENDING、PROCESSING、COMPLETED、FAILED、DEAD_LETTER。
- `MetricCard` 用于监控数字卡片。
- `ConfirmActionButton` 用于重试、取消、清理等动作。

## 五、第四阶段：实现 AiTracePage

依赖后端接口：

```text
GET /api/ai-observability/invocations
GET /api/ai-observability/invocations/{id}
GET /api/ai-observability/rag-traces/{traceId}
GET /api/ai-observability/rag-traces/recent
```

页面功能：

- 最近 AI 调用表格。
- 按 scenario/provider/status/时间范围筛选。
- 点击某条记录查看详情。
- 如果是 RAG trace，展示：
  - 原问题。
  - 改写问题。
  - TopK/minScore。
  - 命中文档列表。
  - 每个 chunk 的 rank、score、preview。
- 对流式 RAG 回答，优先读取独立 trace event 中的 traceId，不把 traceId 混入回答正文。

流式 trace event 建议：

- 后端 SSE 在正文 token 外额外发送一次 `event: trace`。
- trace event 的 data 只包含 `{ "traceId": "..." }`。
- 前端 SSE 解析器识别 trace event 后保存 traceId，用于跳转或回查 `/api/ai-observability/rag-traces/{traceId}`。
- 普通回答内容仍走 `data:` token，不展示 trace event 原始内容。

验收：

- 发起一次知识库问答后，页面能看到对应 trace。
- 发起一次 RAG 流式问答后，页面能拿到 traceId，且聊天正文不出现 traceId。
- 调整 scenario/provider/status/时间范围后，调用日志表格能正确刷新。

## 六、第五阶段：实现 AsyncTaskPage

依赖后端接口：

```text
GET /api/async-tasks
GET /api/async-tasks/{id}
POST /api/async-tasks/{id}/retry
POST /api/async-tasks/{id}/cancel
```

页面功能：

- 任务列表。
- 按任务类型和状态筛选。
- 查看失败原因。
- 对 FAILED/DEAD_LETTER 任务点击重试。
- 对 PENDING/PROCESSING 任务可选取消。

验收：

- 上传简历或知识库后，可以看到任务状态。
- 模拟失败任务后，可以点击重试。

## 七、第六阶段：实现 RagEvalPage

依赖后端接口：

```text
POST /api/ai-observability/rag-eval/datasets
POST /api/ai-observability/rag-eval/datasets/{id}/cases
POST /api/ai-observability/rag-eval/datasets/{id}/runs
GET /api/ai-observability/rag-eval/runs/{runId}
GET /api/ai-observability/rag-eval/datasets
GET /api/ai-observability/rag-eval/datasets/{id}
```

页面功能：

- 评测集列表。
- 新建评测集。
- 添加问题 case。
- 选择策略运行评测。
- 查看运行结果。
- 支持选择多个历史运行进行对比。
- 展示不同 TopK/minScore/rewrite 策略下的命中率、平均命中 chunk 数、平均延迟。
- 用表格优先展示对比结果，后续可升级为趋势图。

策略参数：

- rewrite enabled。
- topK。
- minScore。
- knowledgeBaseIds。

验收：

- 能创建一个评测集。
- 能添加至少一个问题。
- 能运行并看到结果。
- 同一评测集能选择多次运行结果进行对比。
- 对比结果能看出不同 TopK/minScore 策略的命中率和延迟差异。

## 八、第七阶段：实现 MonitoringPage

依赖后端接口：

```text
GET /api/monitoring/business-summary
```

页面功能：

- AI 调用成功率。
- RAG 平均命中数。
- 异步任务失败数。
- 最近失败列表。
- 语音面试延迟摘要。
- AI 调用日志聚合指标：
  - 按 scenario 统计调用量、失败率、平均耗时。
  - 按 provider/model 统计调用量、失败率、平均耗时。
  - 展示最近结构化输出失败记录，可跳转到 invocation 详情。
- RAG 评测摘要：
  - 最近评测运行数。
  - 平均关键词命中率。
  - 平均检索延迟。
  - 可跳转到对应 RagEvalPage 查看运行详情。
- 后续如接入 Micrometer/链路追踪，可在 MonitoringPage 放置 trace/metrics 外部链接。

第一版只需要卡片 + 表格，不要求复杂图表。

## 九、第八阶段：实现 AdaptiveInterviewInsightPage

依赖后端接口后续定义。

页面功能：

- 选择一次面试会话。
- 展示能力画像雷达图。
- 展示自适应决策时间线。
- 展示追问原因。

可复用：

- `RadarChart.tsx`
- `ScoreProgressBar.tsx`

## 十、第九阶段：把管理入口放到 Layout

修改：

- `Layout.tsx`

新增导航项：

- 管理控制台

注意：

- 不要影响普通用户主流程。
- 如果导航太拥挤，可以放到“设置”附近或二级菜单。

## 十、前后端对齐要求

每个页面开始实现前，先确认：

- 后端接口路径。
- 请求参数。
- 响应 DTO。
- 错误码。
- 空状态。
- loading 状态。
- 失败提示。

所有类型放在对应 API 文件中，不要在页面里散落重复 interface。

## 十一、测试要求

至少进行：

- `pnpm build`
- 手动打开各页面检查无空白。
- 对已有接口页面进行真实请求测试。

如项目后续引入前端测试，可新增：

- `StatusBadge` 组件测试。
- API mock 测试。
- 表格空状态测试。

## 十二、验收标准

完成后必须满足：

- 管理控制台入口可访问。
- 后端实现 AI trace 后，前端能查看 trace。
- 后端实现 async task 后，前端能查看和重试任务。
- 后端实现 RAG eval 后，前端能创建评测并运行。
- 页面 loading、empty、error 状态完整。
- TypeScript build 通过。
