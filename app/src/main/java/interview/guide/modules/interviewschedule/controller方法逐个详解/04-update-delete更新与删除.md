# update 与 delete：更新和删除日程完整调用链

接口：

```text
PUT    /api/interview-schedule/{id}
DELETE /api/interview-schedule/{id}
```

`update` 覆盖日程业务字段但不改变状态；`delete` 直接物理删除数据库记录。

---

## 1. update 的前端入口

更新可能来自三种页面动作。

### 1.1 编辑弹窗

```text
点击列表“编辑”或日历事件
  → handleEditClick(interview)
  → modalMode=edit
  → selectedInterview=列表中的完整对象
  → InterviewFormModal 用 initialData 回填
  → 保存
  → handleFormSubmit(data)
  → updateInterview(selectedInterview.id, data)
```

当前没有调用 `GET /{id}` 重新确认详情，编辑数据直接来自前端列表 state。

### 1.2 日历拖动

```text
ScheduleCalendar onEventDrop
  → Page.handleEventDrop(data)
  → 从 interviews 查原对象
  → 只替换 interviewTime
  → 其余字段完整回传
  → updateInterview(id, request)
```

### 1.3 日历 resize

`handleEventResize()` 同样只把新的 start 写入 `interviewTime`。后端没有 duration 字段，所以拖长/缩短并不会保存时长；日历下次仍按固定 30 分钟渲染。

Hook 已接入真实 API，这些动作会进入后端更新链。

---

## 2. update Controller 校验

```java
@PutMapping("/{id}")
public Result<InterviewScheduleDTO> update(
    @PathVariable Long id,
    @Valid @RequestBody CreateInterviewRequest request) {
  InterviewScheduleDTO dto = scheduleService.update(id, request);
  return Result.success(dto);
}
```

它复用创建请求 DTO，所以是全量业务字段更新，不是 PATCH：

- 公司、岗位、时间每次都必须提供。
- 可选字段若传 null，会覆盖 Entity 旧值为 null。
- status 不在请求 DTO 中，必须走状态专用接口。

---

## 3. update Service 完整链

```java
@Transactional
public InterviewScheduleDTO update(
    Long id, CreateInterviewRequest request) {
  InterviewScheduleEntity entity = getByIdOrThrow(id);
  BeanUtils.copyProperties(request, entity, "id", "status");
  return toDTO(repository.save(entity));
}
```

逐步执行：

```text
开启数据库事务
  → repository.findById(id)
      ├─ 不存在：BusinessException 9001
      └─ 存在：返回受持久化上下文管理的 Entity
  → BeanUtils.copyProperties(request, entity, "id", "status")
      → 覆盖八个同名业务字段
      → 请求对象本来就没有 id/status，忽略列表属于额外防御
  → repository.save(entity)
      → 合并/保持 managed Entity
  → Entity 转 DTO
  → 事务提交/flush 时执行 @PreUpdate 和 SQL UPDATE
```

`createdAt` 不会被修改，status 保持原状态。由于 `toDTO()` 在事务提交前执行，而 `save()` 不等于 `saveAndFlush()`，`@PreUpdate` 可能到提交 flush 时才运行；因此本次 PUT 返回 DTO 中的 `updatedAt` 可能仍是旧值，随后重新查询才会得到数据库中的新时间。

---

## 4. update 的覆盖语义

这不是“只更新非 null 字段”。例如旧记录：

```json
{
  "meetingLink": "https://old",
  "interviewer": "张经理",
  "notes": "准备算法"
}
```

请求中这些字段为 null 时，`BeanUtils.copyProperties` 会把旧值清空。

页面当前总是从完整 `interview` 对象组装请求，能尽量保留字段；其他调用方必须按全量 PUT 语义传完整数据。

与创建相同，后端不验证 `interviewType` 枚举、URL、轮次范围，也不检查时间是否在未来。

---

## 5. update 返回页面

理想闭环：

```text
PUT resolve updated DTO
  → Hook fetchInterviews()
  → GET 数据库列表
  → React state 更新
  → 日历/列表显示新数据
```

当前链路会在 PUT 成功后重新查询数据库列表并刷新页面。

---

## 6. delete 的前端入口

列表卡片和编辑弹窗都可触发删除：

```text
点击删除
  → handleDeleteClick(id)
  → 打开 ConfirmDialog
  → 用户确认
  → handleConfirmDelete()
  → useInterviewSchedule.deleteInterview(id)
  → interviewScheduleApi.delete(id)
  → DELETE /api/interview-schedule/{id}
```

对话框文案明确这是不可撤销操作。

---

## 7. delete Controller 与 Service

```java
@DeleteMapping("/{id}")
public Result<Void> delete(@PathVariable Long id) {
  scheduleService.delete(id);
  return Result.success(null);
}
```

```java
@Transactional
public void delete(Long id) {
  repository.deleteById(id);
}
```

调用链：

```text
DELETE /{id}
  → Long 路径绑定
  → Service 开启事务
  → JpaRepository.deleteById(id)
  → DELETE interview_schedule
  → 提交
  → Result.success(data=null)
```

这是物理删除，没有 `deleted` 标记、回收站或审计记录。

---

## 8. delete 不存在 ID 的语义

与 get/update/status 不同，delete 没有先调用 `getByIdOrThrow()`：

```text
delete(id)
  → 直接 repository.deleteById(id)
```

因此它不保证返回模块错误码 9001。具体 Spring Data JPA 版本可能把不存在记录当作无操作，也可能在底层抛数据访问异常；若抛出，当前会由全局兜底映射为 INTERNAL_ERROR，而不是 `INTERVIEW_SCHEDULE_NOT_FOUND`。

源码注释所说的“不存在一定抛 EmptyResultDataAccessException”不应视为稳定业务契约。

---

## 9. 删除后的前端 state

```typescript
await interviewScheduleApi.delete(id);
setInterviews(current => current.filter(i => i.id !== id));
```

删除不像其他写操作那样重新 fetch，而是直接过滤当前闭包中的数组。

这里使用函数式 state 更新，避免闭包中的旧数组覆盖较新的状态。

---

## 10. 异常与回滚

| 操作 | 场景 | 结果 |
|---|---|---|
| update | ID 不存在 | 9001，事务回滚 |
| update | 必填字段无效 | Controller 前 BAD_REQUEST |
| update | 数据库错误 | INTERNAL_ERROR，事务回滚 |
| delete | 数据库删除错误 | INTERNAL_ERROR，事务回滚 |
| 页面 | API 成功后刷新失败 | DB 可能成功，页面显示错误或旧数据 |

Controller 没有限流，也没有乐观锁/version。两个客户端同时编辑同一记录时，后提交的全量 PUT 会覆盖先提交的数据。

---

## 11. 完整闭环

```text
更新：
编辑/拖动 → PUT /{id} → @Valid
  → findByIdOrThrow → 全量覆盖字段 → DTO
  → 事务提交时 @PreUpdate + UPDATE → 页面刷新

删除：
确认框 → DELETE /{id}
  → deleteById → 物理删除
  → Result<Void> → 本地过滤

当前前端：
更新后真实 fetch 数据库列表；删除后函数式移除本地记录
```
