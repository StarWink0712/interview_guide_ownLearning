## InterviewScheduleRepository 分析

这个接口是面试日程模块的数据访问层，负责所有 `interview_schedule` 表的数据库操作。

---

### 一、整体结构

```java
@Repository                                         // 声明为 Spring Data JPA 仓库
public interface InterviewScheduleRepository
    extends JpaRepository<InterviewScheduleEntity, Long>  // 继承 CRUD + 分页能力
```

继承 `JpaRepository<实体类, 主键类型>` 后，自动获得：
- `save()`、`findById()`、`findAll()`、`deleteById()` 等 18 个基础方法
- 不需要写任何实现代码，JPA 自动生成

---

### 二、四个自定义查询方法

#### 方法 1：按状态 + 时间筛选

```java
List<InterviewScheduleEntity> findByStatusAndInterviewTimeBefore(
    InterviewStatus status, LocalDateTime time);
```

**生成的 SQL**：
```sql
SELECT * FROM interview_schedule
WHERE status = ? AND interview_time < ?
```

**用途**：查找"状态为 PENDING 且面试时间已过期"的日程，配合定时任务自动更新状态。

#### 方法 2：按状态查询

```java
List<InterviewScheduleEntity> findByStatus(InterviewStatus status);
```

**生成的 SQL**：
```sql
SELECT * FROM interview_schedule WHERE status = ?
```

**用途**：前端筛选"只看待面试"或"只看已完成"的面试。

#### 方法 3：按时间范围查询

```java
List<InterviewScheduleEntity> findByInterviewTimeBetween(
    LocalDateTime start, LocalDateTime end);
```

**生成的 SQL**：
```sql
SELECT * FROM interview_schedule
WHERE interview_time BETWEEN ? AND ?
```

**用途**：日历视图展示某一天的面试安排。

#### 方法 4：批量更新状态（`@Modifying` + `@Query`）

```java
@Modifying
@Query("UPDATE InterviewScheduleEntity e SET e.status = :newStatus
        WHERE e.status = :oldStatus AND e.interviewTime < :cutoff")
int updateStatusByStatusAndInterviewTimeBefore(
    @Param("newStatus") InterviewStatus newStatus,
    @Param("oldStatus") InterviewStatus oldStatus,
    @Param("cutoff") LocalDateTime cutoff);
```

这个方法需要特别说明——前面的三个都是查询（SELECT），这个是**更新**（UPDATE），所以需要 `@Modifying` 注解。

---

### 三、命名查询 vs `@Query` 的区别

```java
// 命名查询（方法 1-3）：JPA 自动根据方法名生成 SQL
findByStatusAndInterviewTimeBefore(status, time)
// → SELECT * FROM interview_schedule WHERE status = ? AND interview_time < ?

// @Query 手动写 JPQL（方法 4）：自定义复杂逻辑
@Query("UPDATE InterviewScheduleEntity e SET e.status = :newStatus WHERE ...")
```

| 维度 | 命名查询 | `@Query` 手动写 JPQL |
|------|:-------:|:-------------------:|
| 写代码量 | 少（只需方法名） | 多 |
| 灵活度 | 低（只支持简单条件） | 高（任意 SQL/JQPL） |
| 批量操作 | ❌ 不支持 | ✅ 支持 |
| 可读性 | 方法名太长时难读 | 一眼看出 SQL 逻辑 |

**为什么方法 4 必须用 `@Query`？** 因为命名查询只能做 SELECT，不能做 UPDATE。而且方法 4 是**批量更新**，一次 SQL 更新多条记录，不是先查出再逐条更新，性能好得多。

---

### 四、`@Modifying` 的详细说明

```java
@Modifying  // 告诉 JPA：这不是 SELECT，是 UPDATE/DELETE
@Query("UPDATE InterviewScheduleEntity e SET e.status = :newStatus ...")
int updateStatusByStatusAndInterviewTimeBefore(...)
```

| 不加 `@Modifying` | 加了 `@Modifying` |
|:-----------------:|:----------------:|
| JPA 认为是 SELECT | 知道是 UPDATE/DELETE |
| 执行时报错 | 正常执行 |
| — | 返回 `int` = 影响的行数 |

**返回值的含义**：`int` 表示本次更新影响了多少行。如果返回 0，说明没有符合条件的记录要更新。

---

### 五、四个方法的完整使用场景

```java
// 1. 定时任务：把已过 PENDING 面试自动改为 EXPIRED
@Scheduled(cron = "0 */5 * * * ?")
public void expirePastInterviews() {
    List<InterviewScheduleEntity> expired = repository
        .findByStatusAndInterviewTimeBefore(InterviewStatus.PENDING, LocalDateTime.now());
    expired.forEach(e -> e.setStatus(InterviewStatus.EXPIRED));
    repository.saveAll(expired);
}
// 或者更高效：直接批量更新
repository.updateStatusByStatusAndInterviewTimeBefore(
    InterviewStatus.EXPIRED, InterviewStatus.PENDING, LocalDateTime.now());

// 2. 前端筛选
repository.findByStatus(InterviewStatus.COMPLETED);

// 3. 日历视图 - 查看某天的面试
repository.findByInterviewTimeBetween(startOfDay, endOfDay);
```

---

### 六、`@Repository` vs `@Service` vs `@Component`

| 注解 | 语义 | 本接口用 `@Repository` 的原因 |
|------|------|-----------------------------|
| `@Repository` | **数据访问层** | JPA 仓库的标配，且自动翻译持久化异常 |
| `@Service` | 业务逻辑层 | 不适用（这里是数据访问） |
| `@Component` | 通用组件 | 也能用，但语义不够准确 |

`@Repository` 最重要的作用是将 JPA 抛出的持久化异常（如 `DataIntegrityViolationException`）自动转为 Spring 的 `DataAccessException`，方便被 `GlobalExceptionHandler` 统一处理。不加也能用，但加上更规范。