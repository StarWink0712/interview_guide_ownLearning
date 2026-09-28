# getById 与 getAll：日程查询完整调用链

接口：

```text
GET /api/interview-schedule/{id}
GET /api/interview-schedule?status=...&start=...&end=...
```

`getById` 精确查询单条，`getAll` 根据参数选择时间范围、状态或全量查询。

---

## 1. 前端 API 封装

```typescript
getById(id)
  → GET /api/interview-schedule/{id}

getAll(params)
  → GET /api/interview-schedule
      ?status=PENDING
      &start=2026-06-01T00:00:00
      &end=2026-06-30T23:59:59
```

当前 `frontend/src` 没有调用 `getById()`。编辑弹窗直接使用列表/日历事件中的完整对象回填。

`getAll()` 由 `useInterviewSchedule.fetchInterviews()` 在页面挂载和写操作后真实调用。

---

## 2. getById 的 Controller 链

```java
@GetMapping("/{id}")
public Result<InterviewScheduleDTO> getById(@PathVariable Long id) {
  InterviewScheduleDTO dto = scheduleService.getById(id);
  return Result.success(dto);
}
```

调用过程：

```text
GET /api/interview-schedule/15
  → Spring 把路径 "15" 转为 Long
  → Controller.getById(15L)
  → scheduleService.getById(15L)
  → getByIdOrThrow(15L)
  → repository.findById(15L)
  → SELECT ... FROM interview_schedule WHERE id=15
```

找到后：

```text
InterviewScheduleEntity
  → toDTO(entity)
  → BeanUtils.copyProperties(entity, dto)
  → Result.success(dto)
  → Axios 解包 InterviewSchedule
```

---

## 3. getById 不存在时

统一辅助方法：

```java
repository.findById(id)
    .orElseThrow(() -> new BusinessException(
        ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND,
        "面试日程不存在: " + id));
```

闭环：

```text
无记录
  → BusinessException(code=9001)
  → GlobalExceptionHandler
  → HTTP 200 + Result.error(9001, "面试日程不存在: 15")
  → Axios reject Error(message)
```

路径不是数字时，Long 参数转换在进入方法前失败；当前全局处理器没有专门的 `MethodArgumentTypeMismatchException` 分支，可能落入通用 INTERNAL_ERROR，而不是 9001。

---

## 4. getAll 的 Controller 参数绑定

```java
@GetMapping
public Result<List<InterviewScheduleDTO>> getAll(
    @RequestParam(required=false) String status,
    @RequestParam(required=false)
    @DateTimeFormat(iso=ISO.DATE_TIME) LocalDateTime start,
    @RequestParam(required=false)
    @DateTimeFormat(iso=ISO.DATE_TIME) LocalDateTime end)
```

时间参数使用 ISO 日期时间，例如：

```text
2026-06-20T00:00:00
```

它是 `LocalDateTime`，没有 `Z` 或 `+08:00` 时区语义。

---

## 5. 查询模式优先级

Service 代码是严格的 if/else-if/else，不会组合条件。

### 5.1 时间范围优先

```text
start != null && end != null
  → repository.findByInterviewTimeBetween(start, end)
```

生成 Spring Data JPA 范围查询。即使同时传 `status=PENDING`，status 也会被忽略。

方法名 `Between` 通常对应边界包含语义，最终以 JPA 生成 SQL 为准。

当前不校验：

- start 是否早于 end。
- 范围是否过大。
- 是否需要按时间排序。

### 5.2 状态查询

当时间参数没有同时存在，且 status 非 null：

```text
InterviewStatus.valueOf(status)
  → repository.findByStatus(enum)
```

枚举只接受完全一致的大写值：

```text
PENDING
COMPLETED
CANCELLED
RESCHEDULED
```

`pending`、空字符串或未知值会抛 `IllegalArgumentException`，全局异常处理器返回 BAD_REQUEST。

### 5.3 全量查询

```text
没有完整时间范围，status 也为 null
  → repository.findAll()
```

只传 start 或只传 end 时，单边时间参数会被忽略并进入状态/全量分支，不会报“时间参数不完整”。

---

## 6. Repository 到 DTO

无论哪个分支，Service 最后统一执行：

```java
entities.stream()
    .map(this::toDTO)
    .collect(Collectors.toList());
```

每个 Entity 被复制为 DTO，避免直接返回 JPA Entity。

当前没有：

- `Pageable` 分页。
- `Sort` 参数。
- Service 层显式排序。
- 只查询所需字段的 projection。

数据量增长后，全量查询会一次加载所有记录。

---

## 7. 页面理想展示链

```text
useEffect
  → fetchInterviews(params)
  → interviewScheduleApi.getAll(params)
  → GET /api/interview-schedule
  → List<InterviewScheduleDTO>
  → setInterviews(data)
```

日历视图再进行前端转换：

```text
过滤 interviewTime 无效项
  → start = interviewTime
  → end = start + 30 分钟
  → 生成 react-big-calendar event
```

数据库没有“面试时长”字段。日历统一显示 30 分钟，是前端渲染规则，不是后端返回值。

列表视图按 `interviews.map()` 原顺序显示，前端也未排序。

---

## 8. 当前实际页面链

```text
useEffect
  → fetchInterviews()
  → setLoading(true)
  → interviewScheduleApi.getAll(params)
  → setInterviews(后端数据)
  → setLoading(false)
```

因此日历上的公司、岗位、ID 和时间来自数据库查询结果。

---

## 9. 与定时状态更新的关系

`ScheduleStatusUpdater` 每小时可能把数据库中的 PENDING 改成 CANCELLED。只有重新调用 `getAll()`，页面才会看到变化。

系统没有 SSE/WebSocket 主动推送日程状态；页面重新挂载或写操作后查询时会看到更新。

---

## 10. 完整闭环

```text
单条：
调用方 → GET /{id} → findById
  → 不存在抛 9001
  → 存在 Entity 转 DTO → Result → 调用方

列表：
调用方 → GET /
  → 完整 start/end 优先
  → 否则 status
  → 否则 findAll
  → Entity 列表逐条转 DTO
  → Result → 调用方

当前页面：
useEffect/fetchInterviews → 进入上述列表链 → 渲染数据库数据
```
