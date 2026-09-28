# listTasks 与 getTask：从前端到数据库再回到页面

> 对应后端：`AsyncTaskController.listTasks`、`AsyncTaskController.getTask`  
> 对应前端：`AsyncTaskPage`、`TaskTable`、`asyncTaskApi`、`request.ts`

## 1. 先看结论

两个查询接口的实际使用情况不同：

| 方法 | 当前是否被页面调用 | 实际用途 |
|---|---|---|
| `listTasks` | 是 | 页面首次加载、筛选、手动刷新、操作完成后刷新列表 |
| `getTask` | 否 | 后端能力和前端 API 封装已经存在，但当前详情面板不发详情请求 |

因此，当前页面的“列表 + 详情”实际只靠一次 `listTasks` 返回完成：表格显示数组，点击某行时
直接把该数组元素设置为 `selectedTask`，右侧详情面板读取这个对象。

---

## 2. listTasks 完整闭环

### 2.1 用户怎样进入调用链

涉及代码：

- `frontend/src/App.tsx`
- `frontend/src/pages/AdminDashboardPage.tsx`
- `frontend/src/components/admin/AdminNav.tsx`
- `frontend/src/pages/AsyncTaskPage.tsx`

逐步调用：

1. 用户点击管理台“异步任务”卡片或顶部导航。
2. React Router 导航到 `/admin/async-tasks`。
3. `App.tsx` 匹配 `<Route path="admin/async-tasks" element={<AsyncTaskPage />} />`。
4. `AsyncTaskPage` 首次渲染，初始化：
   - `tasks = []`
   - `selectedTask = null`
   - `taskType = ''`
   - `status = ''`
5. `useEffect(..., [taskType, status])` 在组件挂载后执行 `loadTasks()`。

`loadTasks` 是列表查询在页面中的统一入口：

```ts
const loadTasks = async () => {
  setLoading(true);
  setError('');
  try {
    const list = await asyncTaskApi.list({ taskType, status });
    setTasks(list);
    // 同步修正当前选中项
  } catch (err) {
    setError(getErrorMessage(err));
  } finally {
    setLoading(false);
  }
};
```

除了首次进入页面，还有四种路径会再次调用它：

| 触发动作 | 调用位置 | 是否自动发生 |
|---|---|---|
| 修改任务类型下拉框 | `setTaskType` → `useEffect` | 是 |
| 修改状态下拉框 | `setStatus` → `useEffect` | 是 |
| 点击“刷新” | 按钮 `onClick={loadTasks}` | 否，用户主动触发 |
| 重试或取消成功 | `handleRetry` / `handleCancel` 中 `await loadTasks()` | 是 |

这里没有定时器，所以页面不会自动轮询更新。

### 2.2 页面怎样构造请求参数

`loadTasks` 调用：

```ts
asyncTaskApi.list({ taskType, status })
```

`frontend/src/api/asyncTask.ts` 中的 `asyncTaskApi.list` 再调用：

```ts
request.get('/api/async-tasks', {
  params: {
    taskType: filters.taskType || undefined,
    status: filters.status || undefined,
  },
});
```

两个下拉框的“全部”值都是空字符串。`filters.xxx || undefined` 会把空字符串变成
`undefined`，Axios 序列化参数时不会发送该参数。因此对应关系是：

| 页面筛选 | 实际 URL |
|---|---|
| 全部类型 + 全部状态 | `/api/async-tasks` |
| `RESUME_ANALYZE` + 全部状态 | `/api/async-tasks?taskType=RESUME_ANALYZE` |
| 全部类型 + `FAILED` | `/api/async-tasks?status=FAILED` |
| `KB_VECTORIZE` + `PROCESSING` | `/api/async-tasks?taskType=KB_VECTORIZE&status=PROCESSING` |

### 2.3 request.ts 怎样真正发出 HTTP 请求

调用继续进入 `frontend/src/api/request.ts`：

```text
asyncTaskApi.list
  → request.get(url, config)
  → Axios instance.get(url, config)
```

Axios 实例配置：

- 开发环境 `baseURL = http://localhost:8080`。
- 生产环境 `baseURL = ''`，使用当前站点同源地址，通常由 Nginx 代理到后端。
- 普通请求超时为 60 秒。

所以开发环境最终请求示例为：

```http
GET http://localhost:8080/api/async-tasks?taskType=KB_VECTORIZE&status=PROCESSING
```

### 2.4 Spring MVC 怎样进入 Controller

后端 `AsyncTaskController` 没有类级别 `@RequestMapping`，完整路径直接写在方法上：

```java
@GetMapping("/api/async-tasks")
public Result<List<AsyncTaskDTO>> listTasks(
    @RequestParam(value = "taskType", required = false) AsyncTaskType taskType,
    @RequestParam(value = "status", required = false) AsyncTaskStatusDTO status
) {
  return Result.success(asyncTaskService.listTasks(taskType, status));
}
```

Spring MVC 在调用方法前完成参数绑定：

- 没有参数时传入两个 `null`。
- `taskType=KB_VECTORIZE` 转成 `AsyncTaskType.KB_VECTORIZE`。
- `status=PROCESSING` 转成 `AsyncTaskStatusDTO.PROCESSING`。
- 枚举名称区分大小写；前端通过联合类型和固定下拉选项保证发送合法值。

Controller 本身不做过滤、不访问数据库，只把绑定后的两个值传给
`AsyncTaskService.listTasks(taskType, status)`。

### 2.5 Service 怎样选择 Repository 方法

`AsyncTaskService.listTasks` 使用只读事务：

```java
@Transactional(readOnly = true)
public List<AsyncTaskDTO> listTasks(
    AsyncTaskType taskType,
    AsyncTaskStatusDTO status
)
```

它根据是否为 `null` 选择四个 Spring Data 派生查询之一：

| `taskType` | `status` | Repository 方法 |
|---|---|---|
| 有值 | 有值 | `findByTaskTypeAndStatusOrderByCreatedAtDesc` |
| 有值 | `null` | `findByTaskTypeOrderByCreatedAtDesc` |
| `null` | 有值 | `findByStatusOrderByCreatedAtDesc` |
| `null` | `null` | `findAllByOrderByCreatedAtDesc` |

例如组合筛选会执行：

```java
taskRepository.findByTaskTypeAndStatusOrderByCreatedAtDesc(taskType, status)
```

`AsyncTaskRepository` 继承 `JpaRepository<AsyncTaskEntity, Long>`。Spring Data 根据方法名生成
查询，`OrderByCreatedAtDesc` 保证新任务排在前面。

当前查询没有 `Pageable`、页码或数量限制。选择“全部”时会把数据库中所有任务加载到内存，
随后全部序列化给前端。

### 2.6 Entity 怎样变成 DTO

Repository 返回 `List<AsyncTaskEntity>`。Service 对每条记录执行：

```java
.stream()
.map(this::toDTO)
.toList();
```

`AsyncTaskService.toDTO` 手工构造 `AsyncTaskDTO` record，映射：

```text
AsyncTaskEntity
  → id / taskType / businessId
  → streamKey / messageId / status
  → retryCount / maxRetryCount / nextRetryAt
  → lastError / payloadJson / parentTaskId
  → activeFlag / createdAt / updatedAt
  → AsyncTaskDTO
```

Entity 没有直接暴露给 Controller，符合项目“禁止直接返回 Entity”的分层规则。

### 2.7 后端怎样包装响应

Service 返回 `List<AsyncTaskDTO>` 后，控制权回到 Controller：

```java
Result.success(asyncTaskService.listTasks(taskType, status))
```

成功响应结构：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 42,
      "taskType": "KB_VECTORIZE",
      "businessId": "9",
      "streamKey": "knowledgebase:vectorize:stream",
      "messageId": "1710000000000-0",
      "status": "PROCESSING",
      "retryCount": 0,
      "maxRetryCount": 3,
      "lastError": null,
      "activeFlag": true,
      "createdAt": "2026-06-19T10:00:00",
      "updatedAt": "2026-06-19T10:00:01"
    }
  ]
}
```

HTTP 状态是 200，`code` 是响应体内的业务状态码。

### 2.8 前端怎样解包并渲染

Axios 响应拦截器执行：

```text
response.data 是 Result
  → 检查 code
  → code === 200
  → response.data = result.data
  → request.get 的 .then(res => res.data)
  → asyncTaskApi.list 得到 AsyncTask[]
  → loadTasks 得到 list
```

然后 `loadTasks` 更新两个核心状态：

1. `setTasks(list)`：`TaskTable` 根据数组渲染 ID、类型、业务 ID、状态、重试次数和更新时间。
2. `setSelectedTask(...)`：
   - 如果原来选中的任务仍在新列表中，替换为新查询返回的对象；
   - 如果因筛选不再存在，改选第一条；
   - 列表为空时设为 `null`。

右侧详情面板直接读取 `selectedTask`，展示 `streamKey`、`messageId`、错误和 `payloadJson`。
至此，`listTasks` 从页面触发到数据库再回到页面的同步闭环完成。

### 2.9 listTasks 失败闭环

查询阶段发生未预期异常时：

```text
Repository / Service 抛异常
  → GlobalExceptionHandler.handleException
  → Result.error(INTERNAL_ERROR, ...)
  → Axios 拦截器发现 code !== 200
  → reject Error(message)
  → loadTasks.catch
  → setError(getErrorMessage(err))
  → finally setLoading(false)
  → 页面显示错误提示，并停止 loading 动画
```

---

## 3. getTask 完整闭环

### 3.1 当前前端并没有触发它

`frontend/src/api/asyncTask.ts` 定义了：

```ts
get(id: number): Promise<AsyncTask> {
  return request.get(`/api/async-tasks/${id}`);
}
```

但当前代码库里没有 `asyncTaskApi.get(...)` 的调用。`TaskTable` 点击行执行的是：

```ts
onClick={() => onSelect(task)}
```

父组件传入 `onSelect={setSelectedTask}`，因此点击详情的真实链路只有：

```text
点击表格行
  → TaskTable.onClick
  → onSelect(task)
  → AsyncTaskPage.setSelectedTask(task)
  → React 重新渲染右侧详情
```

它不会经过 Controller、Service 或数据库。旧文档中“点击任务行后请求详情”和“业务前端每两秒
调用 getTask 轮询”的描述均不符合当前实现。

### 3.2 如果调用 getTask，HTTP 链路是什么

未来页面显式调用 `asyncTaskApi.get(42)`，或其他客户端请求以下地址：

```http
GET /api/async-tasks/42
```

完整链路为：

```text
调用方
  → asyncTaskApi.get(42)
  → request.get('/api/async-tasks/42')
  → Axios GET
  → AsyncTaskController.getTask(42)
  → AsyncTaskService.getTask(42) [只读事务]
  → getEntity(42)
  → AsyncTaskRepository.findById(42)
  → toDTO(entity)
  → Result.success(dto)
  → Axios 拦截器提取 data
  → Promise<AsyncTask>
```

Controller 方法：

```java
@GetMapping("/api/async-tasks/{id}")
public Result<AsyncTaskDTO> getTask(@PathVariable Long id) {
  return Result.success(asyncTaskService.getTask(id));
}
```

`@PathVariable Long id` 由 Spring 把路径字符串 `"42"` 转成 `Long 42L`。Controller 委托
Service，不直接操作 Repository。

### 3.3 Service 和 Repository 做什么

Service 方法：

```java
@Transactional(readOnly = true)
public AsyncTaskDTO getTask(Long id) {
  return toDTO(getEntity(id));
}
```

其中 `getEntity` 是单任务查询的统一入口：

```java
private AsyncTaskEntity getEntity(Long id) {
  return taskRepository.findById(id)
      .orElseThrow(() ->
          new BusinessException(ErrorCode.NOT_FOUND, "异步任务不存在"));
}
```

存在时调用同一个 `toDTO` 转换方法；不存在时不会返回 `null`，而是进入统一业务异常链。

### 3.4 任务不存在怎样闭环

```text
AsyncTaskRepository.findById(id) 返回 Optional.empty
  → getEntity 抛 BusinessException(NOT_FOUND, "异步任务不存在")
  → GlobalExceptionHandler.handleBusinessException
  → HTTP 200 + { code: 404, message: "异步任务不存在", data: null }
  → Axios 拦截器 reject new Error("异步任务不存在")
  → 调用方 catch 并展示错误
```

如果直接在浏览器或 API 工具中调用，看到的是 HTTP 200；是否失败必须看响应体 `code`。

---

## 4. 两个方法的职责边界

### Controller 层

- 负责 URL 映射和参数绑定。
- 调用对应 Service 方法。
- 用 `Result.success` 包装成功数据。
- 不编写查询分支，不直接调用 Repository。

### Service 层

- `listTasks` 决定选择哪个 Repository 查询。
- `getTask` 统一处理“任务不存在”。
- 通过 `toDTO` 隔离 Entity 和前端模型。
- 使用 `@Transactional(readOnly = true)` 声明只读查询。

### Repository 层

- `findById` 提供主键查询。
- 四个派生查询支持列表过滤并按创建时间倒序。

### 前端层

- `asyncTaskApi` 只负责接口路径和参数。
- `request.ts` 负责 Axios 调用与 `Result` 解包。
- `AsyncTaskPage` 负责 loading、error、筛选、列表和选中项状态。
- `TaskTable` 只负责展示和把被点击的任务交给父组件。

## 5. 一句话复述完整流程

`listTasks` 的真实闭环是：管理员进入或操作异步任务页，`loadTasks` 通过 `asyncTaskApi.list`
和 Axios 发起 GET，Controller 绑定可选枚举参数并委托 Service，Service 选择 Repository 派生
查询，将 Entity 转成 DTO，Controller 包装 `Result`，前端拦截器提取 `data`，页面更新表格与
详情选中项；`getTask` 的后端闭环已经完整，但当前页面没有调用它，详情展示实际复用列表数据。
