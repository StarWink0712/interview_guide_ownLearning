# create：创建面试日程完整调用链

接口：

```text
POST /api/interview-schedule
```

创建接口接收用户确认后的表单，保存 `interview_schedule`，并返回带自增 ID、PENDING 状态和时间戳的 DTO。

---

## 1. 前端的两条表单入口

### 1.1 解析后创建

```text
添加面试
  → 粘贴邀约
  → POST /parse
  → ParseResponse.data 回填 formData
  → 用户确认并编辑
  → 保存
```

### 1.2 手动创建

```text
添加面试
  → 点击“手动输入”
  → 填 companyName/position/interviewTime 等
  → 保存
```

两条路径最终都进入：

```text
InterviewFormModal.handleSubmit()
  → props.onSubmit(formData)
  → InterviewSchedulePage.handleFormSubmit()
  → modalMode=create
  → useInterviewSchedule.createInterview(data)
  → interviewScheduleApi.create(data)
```

Hook 已正确导入 `interviewScheduleApi`，因此该页面链路会真实进入后端。

---

## 2. 前端请求体

```typescript
interface CreateInterviewRequest {
  companyName: string;
  position: string;
  interviewTime: string;
  interviewType?: 'ONSITE' | 'VIDEO' | 'PHONE';
  meetingLink?: string;
  roundNumber?: number;
  interviewer?: string;
  notes?: string;
  reminderEnabled?: boolean;
  reminderEmail?: string;
}
```

表单层使用 HTML `required` 限制公司、岗位和时间；轮次输入设置 `min=1`。这些只是浏览器校验，后端仍必须独立检查。

日期来自 `datetime-local`：

```text
2026-06-20T14:30
```

它没有时区偏移，后端接收为 `LocalDateTime`。

---

## 3. Controller 校验

```java
@PostMapping
public Result<InterviewScheduleDTO> create(
    @Valid @RequestBody CreateInterviewRequest request) {
  InterviewScheduleDTO dto = scheduleService.create(request);
  return Result.success(dto);
}
```

后端请求字段规则：

| 字段 | 后端校验 |
|---|---|
| `companyName` | `@NotBlank` |
| `position` | `@NotBlank` |
| `interviewTime` | `@NotNull` + JSON 时间格式 |
| `interviewType` | 无校验，Java 类型是 String |
| `meetingLink` | 无 URL 校验 |
| `roundNumber` | 无最小/最大值校验，默认 1 |
| `interviewer` | 无长度校验 |
| `notes` | 无长度校验 |
| `reminderEnabled` | 可选，null 在 Service 中归一化为 true |
| `reminderEmail` | `@Email`，为空时只生成站内提醒 |

所以直接调用 API 可以保存未知 `interviewType` 或负轮次，尽管 TypeScript UI 不会主动生成这些值。

校验失败：

```text
MethodArgumentNotValidException
  → GlobalExceptionHandler
  → HTTP 200 + Result.error(BAD_REQUEST, 字段消息)
  → Axios reject
  → Modal submitError 显示后端消息
```

---

## 4. Service 事务与对象转换

```java
@Transactional
public InterviewScheduleDTO create(CreateInterviewRequest request) {
  InterviewScheduleEntity entity = new InterviewScheduleEntity();
  BeanUtils.copyProperties(request, entity);
  entity.setStatus(InterviewStatus.PENDING);
  return toDTO(repository.save(entity));
}
```

逐步过程：

```text
Spring 事务代理开启事务
  → new InterviewScheduleEntity()
      → Java 字段默认 roundNumber=1、status=PENDING
  → BeanUtils.copyProperties(request, entity)
      → 按同名属性复制八个业务字段
  → entity.setStatus(PENDING)
      → 无论请求内容如何都强制待面试
  → repository.save(entity)
      → 新 Entity 使用 EntityManager.persist
      → PostgreSQL INSERT
  → reminderService.synchronizeReminders(entity)
      → 生成 24 小时前和 1 小时前的站内提醒
      → 配置邮箱时同时生成邮件提醒
  → toDTO(savedEntity)
      → new InterviewScheduleDTO
      → BeanUtils.copyProperties(entity, dto)
  → 事务提交
```

请求 DTO 本身没有 status 字段，因此客户端不能在创建时直接指定 COMPLETED/CANCELLED。

---

## 5. JPA 写库细节

Entity 映射：

```text
@Entity
@Table(name="interview_schedule")
@GeneratedValue(strategy=IDENTITY)
```

INSERT 前触发：

```java
@PrePersist
protected void onCreate() {
  createdAt = LocalDateTime.now();
  updatedAt = LocalDateTime.now();
}
```

因此数据库返回后 Entity 已具有：

```text
id          数据库生成
status      PENDING
createdAt   应用服务器当前时间
updatedAt   同一时刻
```

时间戳不是 PostgreSQL 默认值，而是应用 JVM 生成。

---

## 6. 空值与默认值的细节

`roundNumber` 在 Request 和 Entity 中都声明默认 1，但 JSON 显式传 `null` 时：

```text
Jackson request.roundNumber=null
  → BeanUtils 把 null 复制到 entity.roundNumber
  → 数据库列允许 null
```

因此“默认第 1 轮”只在字段缺失/对象初始化保持默认时可靠，显式 null 可以覆盖默认值。

`interviewType`、meetingLink 等可选字段也可以为 null。

---

## 7. 返回 DTO

成功响应的 data：

```json
{
  "id": 15,
  "companyName": "示例公司",
  "position": "Java 后端工程师",
  "interviewTime": "2026-06-20T14:30:00",
  "interviewType": "VIDEO",
  "meetingLink": "https://...",
  "roundNumber": 1,
  "interviewer": "张老师",
  "notes": "准备系统设计",
  "status": "PENDING",
  "createdAt": "...",
  "updatedAt": "..."
}
```

Controller 返回的是 DTO，不直接暴露 Entity。

---

## 8. 正常页面回显设计

设计上的闭环：

```text
create API resolve 新 DTO
  → Hook await fetchInterviews()
  → GET /api/interview-schedule
  → setInterviews(数据库列表)
  → 页面出现新日程
  → Modal 关闭
```

当前 `fetchInterviews()` 会真实查询后端，因此数据库写入成功后，新记录会进入日历/列表 state。

Modal 内 `handleSubmit()` 成功后调用一次 `onClose()`，Page 的 `handleFormSubmit()` 自身也会关闭 Modal，存在重复关闭调用，但状态结果相同。

---

## 9. 失败与事务回滚

| 失败位置 | 结果 |
|---|---|
| 请求 JSON/日期无法解析 | Controller 方法前失败，返回参数错误或全局错误 |
| 必填字段为空 | BAD_REQUEST，不进入 Service |
| JPA INSERT 失败 | 事务回滚，GlobalExceptionHandler 返回 INTERNAL_ERROR |
| DTO 转换异常 | 事务回滚 |
| 前端刷新失败 | 数据库可能已成功创建，页面状态未更新 |

模块没有重复日程检查，同一公司、岗位、时间可以重复插入。

---

## 10. 完整闭环

```text
用户确认表单
  → POST /api/interview-schedule
  → @Valid 校验三项必填
  → Controller.create
  → Service @Transactional
  → Request 同名字段复制到 Entity
  → 强制 PENDING
  → 同步生成邮件/站内提醒计划
  → JPA @PrePersist 填时间戳
  → INSERT interview_schedule
  → Entity 转 DTO
  → Result.success(dto)
  → Axios 解包
  → 重新查询并渲染
```
