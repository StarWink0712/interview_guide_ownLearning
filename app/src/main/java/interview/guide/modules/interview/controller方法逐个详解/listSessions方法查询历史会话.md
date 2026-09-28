# listSessions：查询文字面试历史完整调用链

> 接口：`GET /api/interview/sessions`  
> Controller：`InterviewController.listSessions`  
> 当前前端调用：`InterviewHistoryPage`、`InterviewHubPage`

## 1. 两个真实前端入口

### 面试记录页

`InterviewHistoryPage.loadAll` 并行加载文字面试和语音面试：

```text
loadAll
  → Promise.all
      → loadTextInterviews
          → interviewApi.listSessions
      → loadVoiceInterviews
```

页面首次挂载时调用一次。如果列表中存在 `PENDING` 或 `PROCESSING` 的评估任务，页面启动
3 秒定时器，持续调用 `loadAll(true)`，直到没有评估中的记录。

### 面试中心

`InterviewHubPage.loadRecentInterviews` 在初始化时调用 `listSessions`，与语音会话合并、按时间
倒序排序，只展示最近 5 条。这个页面不轮询。

## 2. 前端 API 层

```ts
interviewApi.listSessions()
  → request.get<TextSessionMeta[]>('/api/interview/sessions')
```

`TextSessionMeta` 只包含列表需要的轻量字段，不包含题目、答案或大段报告 JSON。

两个页面都对错误做了降级：

- 历史页 `loadTextInterviews` catch 后返回 `[]`；
- 面试中心 `.catch(() => [])`。

因此文字面试查询失败时页面可能表现为“没有文字记录”，不会向用户显示后端错误。

## 3. Controller 层

```java
@GetMapping("/api/interview/sessions")
public Result<List<SessionListItemDTO>> listSessions() {
  List<SessionListItemDTO> items = persistenceService.findAll()
      .stream()
      .map(SessionListItemDTO::from)
      .toList();
  return Result.success(items);
}
```

这个方法比其他 Controller 多做了一步 Entity→DTO 转换，但没有业务判断。

## 4. Service 和 Repository 查询

```text
InterviewPersistenceService.findAll
  → InterviewSessionRepository.findAllByOrderByCreatedAtDesc
  → PostgreSQL 查询 interview_sessions
  → List<InterviewSessionEntity>
```

结果按 `createdAt DESC` 排列。当前没有分页、条数上限、状态筛选或类型筛选，所有文字面试会话
都会一次加载并返回。

## 5. Entity 怎样转换成列表 DTO

Controller 对每条 Entity 调用 `SessionListItemDTO.from`，映射：

| 字段 | 页面用途 |
|---|---|
| `sessionId` | 跳转详情、继续、删除和导出 |
| `skillId` | 转换成面试模板名称 |
| `difficulty` | 难度信息 |
| `resumeId` | 重新面试、关联简历 |
| `totalQuestions` | 列表显示题目数；null 转 0 |
| `status` | 会话是否仍可继续 |
| `evaluateStatus` | 是否需要轮询、是否可看得分/导出 |
| `evaluateError` | 失败提示 |
| `overallScore` | 得分展示和统计 |
| `createdAt` / `completedAt` | 排序和时间展示 |

题目 JSON、优势、建议、答案集合都没有返回，避免列表响应过大。Entity 没有直接暴露给前端。

## 6. 响应回到历史页

```text
List<SessionListItemDTO>
  → Result.success(items)
  → HTTP 200
  → Axios 拦截器提取 data
  → TextSessionMeta[]
```

历史页把每条文字会话转换为统一的 `UnifiedInterviewItem`，再与语音会话合并，按创建时间倒序。
它根据状态：

- 显示“继续面试”；
- 显示评估生成中/失败/得分；
- 评估完成后开放 PDF 导出；
- 计算总记录数、已评估数和平均分。

轮询时 `itemsEqual` 只比较 ID、会话状态、评估状态和总分，没变化就保留原 state，减少渲染。

## 7. 评估轮询闭环

```text
最后一题提交/提前交卷
  → evaluateStatus=PENDING
  → 页面进入 /interviews
  → listSessions 返回 PENDING
  → 每 3 秒重新调用 listSessions
  → Consumer 更新 PROCESSING
  → Consumer 保存报告并更新 COMPLETED + overallScore
  → 下次 listSessions 返回最新状态和得分
  → 页面停止轮询
```

这里轮询的是列表接口，不是 `getReport` 或异步任务详情接口。

## 8. 当前实现边界

- 无分页，数据持续增长后会一次查询、映射和传输全部会话。
- 前端把查询异常降级为空数组，用户无法区分“无记录”和“加载失败”。
- 历史页评估期间每 3 秒查询全部文字和语音记录，数据量大时成本较高。
- Controller 自己负责 DTO 映射；若要保持分层一致，可下沉到 Service/Mapper。
- `findAll` 没有显式只读事务，但当前只执行一个 Repository 查询。
