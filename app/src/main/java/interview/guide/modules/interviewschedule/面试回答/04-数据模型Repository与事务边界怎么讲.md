# 数据模型、Repository 与事务边界怎么讲

## 1. 一句话定位

面试日程模块的数据模型分两类：`interview_schedule` 保存日程本体，`interview_reminder` 保存提醒任务。Service 层负责事务编排和 DTO 转换，Repository 层只做 JPA 数据访问。

面试中可以这样说：

> 日程和提醒是两个生命周期相关但职责不同的模型。日程表示“什么时候面试、面什么公司”，提醒表示“什么时候通过什么渠道提醒用户”。我把它们拆成两张表，可以让提醒拥有独立状态、重试次数、已读时间和错误原因。

## 2. 日程数据模型

`InterviewScheduleEntity` 映射：

```text
interview_schedule
```

核心字段：

| 字段 | 作用 |
| --- | --- |
| `id` | 自增主键 |
| `companyName` | 公司名，必填 |
| `position` | 岗位，必填 |
| `interviewTime` | 面试时间，必填 |
| `interviewType` | ONSITE / VIDEO / PHONE，当前用 String |
| `meetingLink` | 会议链接或会议信息 |
| `roundNumber` | 面试轮次，默认 1 |
| `interviewer` | 面试官 |
| `notes` | 备注 |
| `reminderEnabled` | 是否开启提醒 |
| `reminderEmail` | 邮件提醒地址 |
| `status` | PENDING / COMPLETED / CANCELLED / RESCHEDULED |
| `createdAt` / `updatedAt` | JPA 生命周期自动维护 |

`@PrePersist` 和 `@PreUpdate`：

```text
新增前填 createdAt 和 updatedAt
更新前刷新 updatedAt
```

注意：

> JPQL 批量更新不会触发实体生命周期回调，所以 `ScheduleStatusUpdater` 的批量状态更新不会自动刷新 `updatedAt`。如果产品需要准确更新时间，需要在 JPQL 里显式设置。

## 3. 请求、响应和实体如何隔离

| 类型 | 用途 |
| --- | --- |
| `CreateInterviewRequest` | 前端创建/更新表单请求体 |
| `InterviewScheduleEntity` | JPA 持久化实体 |
| `InterviewScheduleDTO` | 返回给前端的响应 DTO |
| `ParseRequest` | 邀约解析请求体 |
| `ParseResponse` | 邀约解析响应，内部 data 是 `CreateInterviewRequest` |

转换方式：

```text
CreateInterviewRequest -> InterviewScheduleEntity
  BeanUtils.copyProperties

InterviewScheduleEntity -> InterviewScheduleDTO
  BeanUtils.copyProperties
```

面试表达：

> Entity 不直接返回前端，Controller 返回 DTO。当前字段基本同名，所以用 BeanUtils 足够；如果后续字段映射复杂，比如要组合提醒数量、用户信息或公司 logo，可以升级为 MapStruct。

## 4. 提醒数据模型

`InterviewReminderEntity` 映射：

```text
interview_reminder
```

核心字段：

| 字段 | 作用 |
| --- | --- |
| `scheduleId` | 关联日程 |
| `channel` | EMAIL / IN_APP |
| `timing` | DAY_BEFORE / HOUR_BEFORE |
| `status` | PENDING / QUEUED / PROCESSING / SENT / FAILED / CANCELLED |
| `recipient` | 邮件收件人，站内为空 |
| `title` / `content` | 提醒文案 |
| `remindAt` | 应触发时间 |
| `sentAt` | 投递完成时间 |
| `readAt` | 站内提醒已读时间 |
| `retryCount` | 重试次数 |
| `lastError` | 失败原因或取消原因 |

唯一约束：

```text
schedule_id + channel + timing
```

索引：

```text
status, remind_at
channel, read_at, remind_at
```

这些索引用于：

1. 快速扫描到期提醒。
2. 快速查询站内提醒和未读数。

## 5. Repository 负责什么

`InterviewScheduleRepository`：

```text
findByStatusAndInterviewTimeBefore
findByStatus
findByInterviewTimeBetween
updateStatusByStatusAndInterviewTimeBefore
```

其中批量更新使用：

```text
@Modifying
@Query("UPDATE InterviewScheduleEntity e SET e.status = :newStatus ...")
```

`InterviewReminderRepository`：

```text
findByScheduleIdAndChannelAndTiming
findByScheduleId
findByScheduleIdIn
findDueForUpdate
findVisibleInApp
countUnreadInApp
```

`findDueForUpdate` 使用：

```text
@Lock(LockModeType.PESSIMISTIC_WRITE)
```

作用：

> 多实例或多线程扫描到期提醒时，同一批 PENDING 提醒不会被重复认领。

## 6. 事务边界怎么设计

### 日程写操作

`InterviewScheduleService` 的写方法使用 `@Transactional`：

```text
create
update
delete
updateStatus
```

这些方法只做数据库操作和提醒计划同步，不直接调用 SMTP 或 LLM。

### 邀约解析

`InterviewParseService.parse` 不写数据库，不需要事务。AI 调用在事务外执行。

### 提醒投递

提醒计划生成和状态流转在事务内：

```text
synchronizeReminders
claimDueReminderIds
markProcessing
markSent
markFailed
markCancelled
```

真正的 SMTP 发送发生在消费者的业务处理阶段，不在日程创建事务中。

面试表达：

> 我把“计划写入”和“外部投递”拆开。数据库事务只保证提醒计划和状态变化一致，邮件发送这种外部调用放到异步消费者里，避免长事务和外部服务失败影响核心日程保存。

## 7. 异常和错误码

日程不存在：

```text
BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, ...)
```

提醒不存在：

```text
BusinessException(ErrorCode.INTERVIEW_REMINDER_NOT_FOUND, ...)
```

邮件发送失败：

```text
BusinessException(ErrorCode.INTERVIEW_REMINDER_SEND_FAILED, ...)
```

站内提醒标记已读时，如果提醒不是 IN_APP：

```text
BusinessException(ErrorCode.BAD_REQUEST, "只有站内提醒可以标记已读")
```

## 8. 当前实现边界

| 边界 | 当前情况 | 优化方向 |
| --- | --- | --- |
| 日程和提醒关联 | `scheduleId` 是普通 Long，没有 JPA 外键关联 | 可加数据库外键或软关联校验 |
| 用户隔离 | 两张表都没有 userId | 接入认证后加 userId |
| 查询分页 | 日程和提醒列表都没有分页 | 加 Pageable |
| 日程排序 | Repository 查询无显式排序 | 按 interviewTime 升序或 remindAt 降序 |
| 状态校验 | 状态接口缺少状态机 | 增加状态流转表 |
| 批量更新回调 | JPQL UPDATE 不触发 `@PreUpdate` | 显式更新 updatedAt |

## 9. 面试回答模板

> 数据模型上我把日程和提醒拆成两张表。`interview_schedule` 保存日程本体，包括公司、岗位、面试时间、会议链接、状态、提醒开关和邮箱；`interview_reminder` 保存具体提醒任务，包括渠道、时机、状态、触发时间、发送时间、已读时间、重试次数和错误原因。这样一个日程可以对应多个提醒任务，比如站内提前一天、站内提前一小时、邮件提前一天、邮件提前一小时。
>
> Repository 层只负责数据访问。日程仓库提供状态查询、时间范围查询和批量过期状态更新；提醒仓库提供按日程查提醒、到期任务悲观锁认领、站内提醒列表和未读数查询。Service 层负责事务编排，日程创建、更新、删除、状态变更都在事务内完成，同时同步提醒计划。
>
> 外部调用不会放进日程事务里。邀约 AI 解析不写数据库，所以没有事务；邮件发送通过定时任务和 Redis Stream 异步投递，避免 SMTP 慢或失败拖住日程创建事务。

