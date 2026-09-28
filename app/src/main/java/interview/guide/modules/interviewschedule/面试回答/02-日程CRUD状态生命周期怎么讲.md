# 日程 CRUD 与状态生命周期怎么讲

## 1. 一句话定位

`InterviewScheduleService` 管理面试日程的创建、更新、删除、查询和状态变更，`ScheduleStatusUpdater` 负责把过期未处理的待面试日程自动取消。

面试中可以这样说：

> 日程服务不是只做增删改查，它还维护了面试状态生命周期。创建时默认 PENDING，用户可以标记完成、取消或改期；如果面试时间已经过去但仍然是 PENDING，后台定时任务会自动标记为 CANCELLED，并取消相关提醒。

## 2. 创建日程流程

```text
POST /api/interview-schedule
  -> InterviewScheduleController.create
  -> InterviewScheduleService.create
      -> new InterviewScheduleEntity
      -> BeanUtils.copyProperties(request, entity)
      -> normalizeReminderSettings(entity)
      -> entity.status = PENDING
      -> repository.save(entity)
      -> reminderService.synchronizeReminders(saved)
      -> toDTO(saved)
```

关键点：

1. `CreateInterviewRequest` 只代表前端表单，不是 Entity。
2. 新建日程状态强制设置为 `PENDING`。
3. JPA `@PrePersist` 自动填充 `createdAt` 和 `updatedAt`。
4. 保存成功后同步生成提醒计划。
5. 返回 `InterviewScheduleDTO`，不直接返回 Entity。

## 3. 更新日程流程

```text
PUT /api/interview-schedule/{id}
  -> getByIdOrThrow(id)
  -> BeanUtils.copyProperties(request, entity, "id", "status")
  -> normalizeReminderSettings(entity)
  -> repository.save(entity)
  -> reminderService.synchronizeReminders(saved)
  -> toDTO(saved)
```

为什么忽略 `id` 和 `status`：

| 字段 | 原因 |
| --- | --- |
| `id` | 主键由路径参数决定，不能被请求体覆盖 |
| `status` | 状态必须走专门接口，防止客户端绕过业务逻辑 |

面试表达：

> 更新接口只负责更新日程业务字段，比如公司、岗位、时间、会议链接、邮箱提醒等。状态变更走独立接口，这样状态流转更清晰，也避免前端随便在请求体里改状态。

## 4. 删除日程流程

```text
DELETE /api/interview-schedule/{id}
  -> InterviewScheduleService.delete
      -> reminderService.cancelActiveReminders(id, "面试日程已删除")
      -> repository.deleteById(id)
```

设计点：

> 删除日程前先取消活跃提醒，避免日程不存在后仍然有提醒任务被投递。

当前边界：

> `deleteById` 本身没有先查存在性。如果需要统一返回 `INTERVIEW_SCHEDULE_NOT_FOUND`，可以先调用 `getByIdOrThrow` 再删除。

## 5. 查询日程流程

### 按 ID 查询

```text
GET /api/interview-schedule/{id}
  -> getByIdOrThrow(id)
  -> toDTO(entity)
```

不存在时抛：

```text
BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, ...)
```

### 列表查询

`getAll(status, start, end)` 当前是三选一：

```text
start != null && end != null
  -> findByInterviewTimeBetween(start, end)

否则 status != null
  -> findByStatus(InterviewStatus.valueOf(status))

否则
  -> findAll()
```

注意：

1. 如果同时传时间范围和 status，status 会被忽略。
2. 只传 start 或只传 end 时，时间条件不会生效。
3. 当前没有分页和显式排序。

## 6. 状态有哪些

`InterviewStatus` 当前有四个枚举：

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 待面试 |
| `COMPLETED` | 已完成 |
| `CANCELLED` | 已取消 |
| `RESCHEDULED` | 已改期 |

状态更新接口：

```text
PATCH /api/interview-schedule/{id}/status?status=COMPLETED
PUT   /api/interview-schedule/{id}/status?status=COMPLETED
```

同时支持 PATCH 和 PUT，是为了兼容部分前端或表单提交场景。

## 7. 状态变更和提醒如何联动

```text
updateStatus(id, status)
  -> entity.setStatus(status)
  -> repository.save(entity)
  -> 如果 status == PENDING
       reminderService.synchronizeReminders(saved)
     否则
       reminderService.cancelActiveReminders(id, reason)
```

含义：

| 目标状态 | 提醒处理 |
| --- | --- |
| `PENDING` | 重新同步提醒计划 |
| `COMPLETED` | 取消活跃提醒 |
| `CANCELLED` | 取消活跃提醒 |
| `RESCHEDULED` | 取消活跃提醒 |

面试表达：

> 提醒只应该存在于待面试状态。只要日程完成、取消或改期，未发送的提醒就没有意义，所以要取消活跃提醒。恢复为 PENDING 时再重新生成提醒计划。

## 8. 过期日程自动处理

`ScheduleStatusUpdater` 每小时整点执行：

```text
@Scheduled(cron = "0 0 * * * ?")
```

执行流程：

```text
cutoff = LocalDateTime.now()
findByStatusAndInterviewTimeBefore(PENDING, cutoff)
  -> 收集过期 scheduleId
updateStatusByStatusAndInterviewTimeBefore(CANCELLED, PENDING, cutoff)
  -> 一条 JPQL 批量 UPDATE
reminderService.cancelActiveReminders(expiredScheduleIds, reason)
```

为什么用批量更新：

1. 不需要把所有过期记录逐条加载到 JVM。
2. 减少数据库往返次数。
3. 在事务内一次性完成状态修改。

面试表达：

> 过期状态不是靠前端判断，而是后端定时维护。这样即使用户不打开页面，数据库里的状态也会被推进，避免过期面试一直显示“待面试”。

## 9. 当前实现边界

| 边界 | 当前情况 | 优化方向 |
| --- | --- | --- |
| 状态流转 | 任何状态都能改成任意枚举 | 增加状态机，如 COMPLETED 不能改回 PENDING |
| 查询能力 | 列表查询无分页、无排序、无组合筛选 | 加分页、排序和组合条件 |
| 时间模型 | 使用 `LocalDateTime`，无时区字段 | 多地区用户可存时区或统一 UTC |
| 删除语义 | 物理删除 | 可改软删除，保留历史 |
| 批量更新 | JPQL UPDATE 不触发 Entity `@PreUpdate` | 如果需要更新时间戳，JPQL 里显式更新 `updatedAt` |
| 用户隔离 | 没有 userId | 接入用户体系后按用户过滤 |

## 10. 面试回答模板

> 日程 CRUD 这块我把 Controller 和 Service 分得比较清楚。Controller 只负责路由、参数绑定和统一返回；真正的业务逻辑在 `InterviewScheduleService`。创建时把 `CreateInterviewRequest` 拷贝到 Entity，强制设置状态为 PENDING，保存后同步提醒计划，再转成 DTO 返回。更新时会忽略 id 和 status，防止客户端通过请求体篡改主键或绕过状态接口。
>
> 状态管理单独做了接口，支持把日程改成 PENDING、COMPLETED、CANCELLED、RESCHEDULED。提醒和状态联动：只有 PENDING 日程会同步提醒计划，其他状态都会取消活跃提醒。后台还有 `ScheduleStatusUpdater`，每小时批量把面试时间已过但仍为 PENDING 的日程改成 CANCELLED，并取消关联提醒。
>
> 当前边界是状态流转还没有严格状态机，列表查询也没有分页和排序。后续可以加状态机、软删除、用户隔离和组合查询。

