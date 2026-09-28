# 02 面试日程 CRUD

## 本阶段目标

完成第一个真正的业务模块：

```text
创建日程
查询列表
查询详情
修改日程
修改状态
删除日程
```

这一阶段只做 CRUD，不做 AI 解析面试邀请，也不做定时状态更新。

## 先画清业务

一条面试日程包含：

```text
公司
岗位
面试时间
面试形式
会议链接
轮次
面试官
备注
状态
创建时间
更新时间
```

接口草图：

```text
POST   /api/interview-schedules
GET    /api/interview-schedules
GET    /api/interview-schedules/{id}
PUT    /api/interview-schedules/{id}
PATCH  /api/interview-schedules/{id}/status
DELETE /api/interview-schedules/{id}
```

## 文件创建顺序

严格按以下顺序：

```text
1. InterviewStatus
2. InterviewScheduleEntity
3. InterviewScheduleRepository
4. CreateInterviewScheduleRequest
5. UpdateInterviewStatusRequest
6. InterviewScheduleDTO
7. InterviewScheduleService
8. InterviewScheduleController
9. Service 测试
10. Controller 测试
```

## 1. 状态枚举

路径：

```text
modules/interviewschedule/model/InterviewStatus.java
```

第一版：

```java
public enum InterviewStatus {
  PENDING,
  COMPLETED,
  CANCELLED,
  RESCHEDULED
}
```

不要先写中文状态转换、状态机或自动更新。

## 2. Entity

路径：

```text
modules/interviewschedule/model/InterviewScheduleEntity.java
```

建议字段：

```java
Long id
String companyName
String position
LocalDateTime interviewTime
String interviewType
String meetingLink
Integer roundNumber
String interviewer
String notes
InterviewStatus status
LocalDateTime createdAt
LocalDateTime updatedAt
```

需要掌握的注解：

```text
@Entity
@Table
@Id
@GeneratedValue
@Column
@Enumerated(EnumType.STRING)
@PrePersist
@PreUpdate
```

先完成实体，再启动应用，观察 Hibernate 是否建表。不要同时写 Controller。

检查启动日志或 H2 控制台中的表：

```text
interview_schedule
```

## 3. Repository

路径：

```text
modules/interviewschedule/repository/InterviewScheduleRepository.java
```

第一版：

```java
public interface InterviewScheduleRepository
    extends JpaRepository<InterviewScheduleEntity, Long> {

  List<InterviewScheduleEntity> findByStatus(InterviewStatus status);

  List<InterviewScheduleEntity> findByInterviewTimeBetween(
      LocalDateTime start,
      LocalDateTime end
  );
}
```

这一层只负责数据访问，不写 DTO 转换和业务判断。

先写 `@DataJpaTest` 验证：

```text
save 后能生成 id
findById 能查到
findByStatus 能过滤
```

## 4. 请求对象

路径：

```text
modules/interviewschedule/model/CreateInterviewScheduleRequest.java
```

优先使用 `record`：

```java
public record CreateInterviewScheduleRequest(
    @NotBlank(message = "公司名称不能为空")
    String companyName,

    @NotBlank(message = "岗位不能为空")
    String position,

    @NotNull(message = "面试时间不能为空")
    LocalDateTime interviewTime,

    String interviewType,
    String meetingLink,
    Integer roundNumber,
    String interviewer,
    String notes
) {
}
```

状态更新单独使用：

```text
modules/interviewschedule/model/UpdateInterviewStatusRequest.java
```

```java
public record UpdateInterviewStatusRequest(
    @NotNull(message = "状态不能为空")
    InterviewStatus status
) {
}
```

不要让前端在创建日程时提交 `id`、`createdAt` 或 `status`。

## 5. DTO

路径：

```text
modules/interviewschedule/model/InterviewScheduleDTO.java
```

DTO 可以也是 `record`，包含允许返回给前端的全部字段。

重点：Controller 不能直接返回 `InterviewScheduleEntity`。

第一遍暂时手写转换方法：

```java
private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
  // 逐字段构造 DTO
}
```

手写一次之后，才更容易理解 MapStruct 解决了什么问题。

## 6. Service

路径：

```text
modules/interviewschedule/service/InterviewScheduleService.java
```

建议按这个顺序实现方法：

```text
create
getById
getAll
update
updateStatus
delete
getByIdOrThrow
toDTO
```

为什么先写 `create`：

```text
Request → Entity → repository.save → DTO
```

它能一次练完整的数据流。

### 事务边界

写操作加：

```java
@Transactional
```

查询可以加：

```java
@Transactional(readOnly = true)
```

Service 中找不到数据时：

```java
throw new BusinessException(
    ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND,
    "面试日程不存在: " + id
);
```

删除前也先查询，避免不同数据库对不存在 ID 的删除行为差异。

## 7. Controller

路径：

```text
modules/interviewschedule/InterviewScheduleController.java
```

Controller 只做：

```text
接收参数
触发校验
调用 Service
包装 Result
```

示例：

```java
@PostMapping
public Result<InterviewScheduleDTO> create(
    @Valid @RequestBody CreateInterviewScheduleRequest request
) {
  return Result.success(scheduleService.create(request));
}
```

不要在 Controller 中：

- 调用 Repository
- 创建 Entity
- 判断业务状态
- 编写事务

## 8. 按接口逐个验证

不要等六个接口全部写完再测试。建议顺序：

### 创建

```bash
curl -X POST http://localhost:8081/api/interview-schedules \
  -H 'Content-Type: application/json' \
  -d '{
    "companyName": "示例公司",
    "position": "Java 后端工程师",
    "interviewTime": "2026-06-20T14:00:00",
    "interviewType": "VIDEO",
    "roundNumber": 1
  }'
```

确认：

```text
code = 0
data.id 不为空
data.status = PENDING
```

### 列表

```bash
curl http://localhost:8081/api/interview-schedules
```

### 详情

```bash
curl http://localhost:8081/api/interview-schedules/1
```

### 参数校验

故意删除 `companyName` 再创建，确认返回 `BAD_REQUEST`。

### 不存在的数据

```bash
curl http://localhost:8081/api/interview-schedules/999999
```

确认返回 `9001`，而不是未知的 `500`。

## 9. 测试重点

### Service 单元测试

使用 Mockito：

```text
创建时默认状态是 PENDING
详情查询成功
ID 不存在时抛 BusinessException
更新时不会修改 id 和创建时间
状态更新成功
删除不存在记录时抛 BusinessException
```

### Controller 测试

使用 MockMvc：

```text
请求合法时返回 code=0
公司为空时返回 code=400
Service 抛业务异常时返回对应业务码
```

## 10. 切换 PostgreSQL

H2 CRUD 全部通过后再切换。

配置：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/interview_guide_rebuild
    username: postgres
    password: password
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
```

再次执行所有接口，确认 PostgreSQL 中的数据真实存在。

## 本阶段必须讲清楚

1. Request、Entity、DTO 分别服务于谁？
2. 为什么 Repository 不返回 DTO？
3. 为什么事务放在 Service？
4. `save` 为什么既能新增也能更新？
5. 为什么不能直接返回 Entity？
6. 一次创建请求经过了哪些类？

## 验收标准

- [ ] 六个 CRUD 接口全部可用。
- [ ] 参数校验生效。
- [ ] 数据不存在时返回业务错误码。
- [ ] Controller 没有业务逻辑。
- [ ] 写操作位于 Service 事务中。
- [ ] 没有直接向前端返回 Entity。
- [ ] H2 和 PostgreSQL 至少各验证一次。
- [ ] 所有测试通过。

全部完成后，进入 [03-React前端闭环.md](03-React前端闭环.md)。
