# updateStatus：手动状态更新与定时过期处理完整链路

手动接口：

```text
PATCH /api/interview-schedule/{id}/status?status=COMPLETED
PUT   /api/interview-schedule/{id}/status?status=COMPLETED
```

后台还有一条不经过 Controller 的自动链路：每小时把已过期的 PENDING 日程批量改成 CANCELLED。

---

## 1. 状态枚举

```java
public enum InterviewStatus {
  PENDING,
  COMPLETED,
  CANCELLED,
  RESCHEDULED
}
```

对应页面语义：

| 状态 | 含义 |
|---|---|
| `PENDING` | 待面试 |
| `COMPLETED` | 已完成 |
| `CANCELLED` | 已取消 |
| `RESCHEDULED` | 已改期 |

当前业务没有单独的 `EXPIRED`，定时过期统一写成 CANCELLED。

---

## 2. 前端手动触发

`InterviewListItem` 只在 PENDING 卡片底部显示两个按钮：

```text
“标记为已完成” → onStatusChange(COMPLETED)
“取消面试”     → onStatusChange(CANCELLED)
```

调用链：

```text
InterviewListItem
  → ScheduleList.onStatusChange(id, status)
  → InterviewSchedulePage.handleStatusChange()
  → useInterviewSchedule.updateStatus()
  → interviewScheduleApi.updateStatus(id, status)
  → PATCH /{id}/status，request body=null，query param=status
```

前端当前没有把状态改为 RESCHEDULED 或重新改回 PENDING 的按钮，但 API 本身允许。

Hook 已接入 `interviewScheduleApi`，页面动作会真实调用状态接口。

---

## 3. Controller 为什么同时支持 PATCH 和 PUT

```java
@RequestMapping(
    path = "/{id}/status",
    method = {RequestMethod.PATCH, RequestMethod.PUT})
public Result<InterviewScheduleDTO> updateStatus(
    @PathVariable Long id,
    @RequestParam InterviewStatus status)
```

Spring 把 query 参数直接转换为枚举：

```text
status=COMPLETED → InterviewStatus.COMPLETED
```

前端实际使用 PATCH，PUT 只是额外兼容入口。

状态值必须大小写和枚举完全一致。非法值在 Controller 参数转换阶段失败，不会进入 Service。当前全局异常处理器没有专门处理 `MethodArgumentTypeMismatchException`，因此这类错误可能落入通用 INTERNAL_ERROR，而不像 `getAll()` 内部 `valueOf()` 那样稳定映射为 BAD_REQUEST。

---

## 4. Service 手动更新链

```java
@Transactional
public InterviewScheduleDTO updateStatus(
    Long id, InterviewStatus status) {
  InterviewScheduleEntity entity = getByIdOrThrow(id);
  entity.setStatus(status);
  return toDTO(repository.save(entity));
}
```

完整过程：

```text
开启事务
  → repository.findById(id)
      ├─ 不存在：BusinessException 9001
      └─ 存在：Entity
  → entity.setStatus(target)
  → repository.save(entity)
  → Entity 转 DTO
  → 事务提交/flush 时执行 @PreUpdate 和 JPA UPDATE
  → Result.success(dto)
```

数据库最终只修改 status 和 updatedAt，其余业务字段保持不变。与通用更新相同，DTO 在提交 flush 前构造，所以返回的 `updatedAt` 可能还是旧值；重新查询后才是 `@PreUpdate` 写入的新时间。

---

## 5. 当前没有状态流转规则

Controller 注释举例 PENDING → COMPLETED/CANCELLED，但 Service 没有校验当前状态：

```text
PENDING     → 任意状态
COMPLETED   → PENDING/CANCELLED/RESCHEDULED
CANCELLED   → COMPLETED/PENDING/RESCHEDULED
RESCHEDULED → 任意状态
```

只要 query 参数是合法枚举，后端都会保存。这里是“枚举字段更新接口”，还不是严格状态机。

同样也没有：

- 完成时间或取消原因字段。
- 操作日志。
- 用户权限检查。
- 乐观锁避免并发状态覆盖。

---

## 6. 返回页面的设计闭环

```text
PATCH resolve updated DTO
  → Hook await fetchInterviews()
  → 重新 GET 列表
  → 卡片状态徽标变化
  → 非 PENDING 后操作按钮消失
```

当前 `fetchInterviews()` 会重新查询数据库，因此状态徽标会反映保存结果。

---

## 7. 自动过期更新如何启动

该链路没有任何前端请求：

```text
App.java
  → @EnableScheduling 开启 Spring Scheduler
  → Spring 扫描 @Scheduled 方法
  → 每小时整点调用
    ScheduleStatusUpdater.updateExpiredInterviews()
```

cron：

```text
0 0 * * * ?
```

表示每小时的 00 分 00 秒。没有显式 `zone`，使用 Scheduler/JVM 默认时区。

---

## 8. 定时器数据库调用

```java
@Scheduled(cron = "0 0 * * * ?")
@Transactional
public void updateExpiredInterviews() {
  int updated = repository
      .updateStatusByStatusAndInterviewTimeBefore(
          CANCELLED,
          PENDING,
          LocalDateTime.now());
  if (updated > 0) {
    log.info(...);
  }
}
```

Repository JPQL：

```java
UPDATE InterviewScheduleEntity e
SET e.status = :newStatus
WHERE e.status = :oldStatus
  AND e.interviewTime < :cutoff
```

等价业务条件：

```text
status=PENDING
且 interviewTime 严格早于本次任务的 now
  → status=CANCELLED
```

一条 SQL 更新全部匹配记录，不先加载 Entity，也不逐条 save。

---

## 9. 批量 UPDATE 的重要副作用

JPQL bulk update 绕过普通 Entity 生命周期：

- 不触发 `InterviewScheduleEntity.@PreUpdate`。
- `updatedAt` 不会随自动 CANCELLED 更新。
- 当前持久化上下文若已有同一 Entity，可能持有旧状态；本方法中没有先加载实体，所以自身影响较小。

因此查询到“状态已取消但 updatedAt 仍是旧时间”可能是定时任务造成的，并非数据库漏写 status。

---

## 10. 手动与自动更新并发

数据库条件限制了自动任务只能修改当时仍为 PENDING 的行：

```text
用户先改 COMPLETED
  → 定时 UPDATE 的 WHERE 不匹配
  → 保持 COMPLETED
```

但两事务并发时仍以数据库执行顺序和隔离级别为准。没有 version 字段做乐观锁。

多实例部署时，每个实例都会运行同一个定时任务。SQL 条件具有一定幂等性：第一个实例改成 CANCELLED 后，后续实例不再匹配 PENDING；但仍会产生重复调度和数据库竞争，没有 ShedLock/分布式调度锁。

---

## 11. 自动取消与提醒模块的关系

过期状态定时器不会自己发送通知，但会取消关联的活动提醒。邮件和站内提醒由独立提醒调度器负责。过期状态任务仍不会：

- 提前一小时提醒。
- 发送邮件、短信或站内通知。
- 向前端推送状态。
- 自动标记 COMPLETED。
- 创建模拟面试会话。

它只在整点执行数据库清理。面试在 10:05 过期，最迟可能到 11:00 才自动变 CANCELLED。

---

## 12. 错误链

### 手动接口

```text
ID 不存在
  → BusinessException 9001
  → Axios reject

非法 status
  → Controller 枚举转换失败
  → 当前可能返回 INTERNAL_ERROR

数据库失败
  → 事务回滚 + INTERNAL_ERROR
```

### 定时任务

Repository 异常会使事务回滚并由调度线程记录异常，不经过 Controller 和 `GlobalExceptionHandler`，前端也不会收到通知。

---

## 13. 完整闭环

```text
手动：
列表按钮 → PATCH/PUT /{id}/status
  → 枚举参数绑定 → findByIdOrThrow
  → setStatus → DTO
  → 提交时 @PreUpdate + UPDATE → 页面理应刷新

自动：
@EnableScheduling → 每小时整点
  → 一条 JPQL bulk UPDATE
  → 过期 PENDING 全部改 CANCELLED
  → 不触发 @PreUpdate，不更新 updatedAt
  → 下次真实列表查询才能看到变化
```
