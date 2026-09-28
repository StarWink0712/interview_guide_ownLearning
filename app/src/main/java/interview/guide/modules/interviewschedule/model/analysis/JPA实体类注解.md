## InterviewScheduleEntity 注解详解

这是面试日程的 **JPA 实体类**，一个 Entity 对应数据库中的一张表。

1. 映射 ： @Entity + @Table + @Column → 告诉 JPA "Java 这个类和哪个表、哪一列对应"
2. 约束 ： nullable = false 、 updatable = false 、 GenerationType.IDENTITY → 告诉数据库"这个字段怎么存、能否为空、主键怎么生成"
   不过还有第三层—— 行为 ： @PrePersist 、 @PreUpdate → 告诉 JPA "存之前自动执行什么方法"

这三层合在一起，就是 JPA 的全部能力：
这个 Java 类 = 表结构定义 + 数据约束规则 + 生命周期行为，三个都在 Entity 里一次搞定

***

### 一、表级注解

```java
@Entity                     // 告诉 JPA：这是一个数据库实体类
@Table(name = "interview_schedule")  // 指定对应的表名（不写的话默认用类名）
@Data                       // Lombok：自动生成 getter/setter/toString/equals/hashCode
```

- `@Entity` → JPA 自动为这个类建表、管理增删改查
- `@Table` → 指定表名为 `interview_schedule`（不写则表名默认为 `interview_schedule_entity`）

***

### 二、字段注解

#### 1. `@Id` + `@GeneratedValue` — 主键与自增策略

```java
@Id                                 // 标记为主键
@GeneratedValue(strategy = GenerationType.IDENTITY)  // 自增策略
private Long id;
```

| `GenerationType` 策略 | 说明                                                    | 适用场景          |
| ------------------- | ----------------------------------------------------- | ------------- |
| `IDENTITY`          | **数据库自增**：MySQL 的 AUTO\_INCREMENT，PostgreSQL 的 SERIAL | ✅ 最常用，本项目使用   |
| `SEQUENCE`          | 序列生成：Oracle/PostgreSQL 的 SEQUENCE                     | 批量插入性能更优      |
| `TABLE`             | 模拟序列的表方案                                              | 不推荐，性能差       |
| `AUTO`              | JPA 自动选择                                              | 不可控，不同数据库行为不同 |

**含义**：新增记录时不用手动设 id，数据库自动生成下一个值。

#### 2. `@Column` — 列映射

```java
@Column(name = "company_name", nullable = false)
private String companyName;

@Column(name = "created_at", updatable = false)
private LocalDateTime createdAt;
```

| `@Column` 参数       | 作用         | 示例含义                                                     |
| ------------------ | ---------- | -------------------------------------------------------- |
| `name`             | 指定数据库列名    | 字段 `companyName` → 列 `company_name`（下划线风格）               |
| `nullable`         | 是否允许为空     | `nullable = false` → 数据库该列 NOT NULL                      |
| `updatable`        | 是否允许更新     | `updatable = false` → 创建后不允许修改该列                         |
| `columnDefinition` | 自定义 SQL 类型 | `columnDefinition = "TEXT"` → 用 TEXT 类型代替默认 VARCHAR(255) |

**为什么不写** **`@Column`** **也可以？**

```java
private String interviewer;  // 没写 @Column
```

不写 `@Column` 时 JPA 自动推断：列名 = 驼峰转下划线（`interviewer` → `interviewer`），类型 = 默认（String → VARCHAR(255)）。

#### 3. `@Enumerated` — 枚举映射

```java
@Enumerated(EnumType.STRING)        // 存字符串 "PENDING" 而不是数字 0
@Column(nullable = false)
private InterviewStatus status = InterviewStatus.PENDING;
```

| `EnumType` | 数据库存什么        | 优点               | 缺点            |
| ---------- | ------------- | ---------------- | ------------- |
| `STRING`   | `"PENDING"`   | 可读性强，加枚举值不影响既有数据 | 占用空间稍大        |
| `ORDINAL`  | `0`, `1`, `2` | 占用空间小            | 插入新枚举值会导致序号错乱 |

**本项目用** **`STRING`**，因为面试状态（PENDING/COMPLETED/CANCELLED）的可读性比空间节省更重要。

#### 4. `@PrePersist` + `@PreUpdate` — 自动时间戳

```java
@PrePersist  // 插入前自动执行
protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
}

@PreUpdate   // 更新前自动执行
protected void onUpdate() {
    updatedAt = LocalDateTime.now();
}
```

所以 `createdAt` 和 `updatedAt` **不需要在 Service 层手动设置**，JPA 会自动完成：

```java
// Service 中只需
InterviewScheduleEntity entity = new InterviewScheduleEntity();
entity.setCompanyName("字节跳动");
scheduleRepository.save(entity);
// → JPA 自动调用 onCreate() → createdAt 和 updatedAt 都自动设好
```

***

### 三、完整映射对应表

| Java 字段         | 数据库列             |      类型      |           约束           |
| --------------- | ---------------- | :----------: | :--------------------: |
| `id`            | `id`             |    BIGINT    |   PK, AUTO\_INCREMENT  |
| `companyName`   | `company_name`   | VARCHAR(255) |        NOT NULL        |
| `position`      | `position`       | VARCHAR(255) |        NOT NULL        |
| `interviewTime` | `interview_time` |   TIMESTAMP  |        NOT NULL        |
| `interviewType` | `interview_type` | VARCHAR(255) |           可空           |
| `meetingLink`   | `meeting_link`   |     TEXT     |           可空           |
| `roundNumber`   | `round_number`   |      INT     |          默认 1          |
| `interviewer`   | `interviewer`    | VARCHAR(255) |           可空           |
| `notes`         | `notes`          |     TEXT     |           可空           |
| `status`        | `status`         | VARCHAR(255) | NOT NULL, 默认 'PENDING' |
| `createdAt`     | `created_at`     |   TIMESTAMP  |     NOT NULL, 不可更新     |
| `updatedAt`     | `updated_at`     |   TIMESTAMP  |        NOT NULL        |

***

### 四、一句话总结

| 注解                                  | 一句话解释               |
| ----------------------------------- | ------------------- |
| `@Entity`                           | 这是个数据库表对应的 Java 类   |
| `@Id` + `@GeneratedValue(IDENTITY)` | 主键，数据库自动生成          |
| `@Column(name="xxx")`               | Java 字段名 → 数据库列名    |
| `@Column(nullable=false)`           | 该字段不能为空             |
| `@Column(updatable=false)`          | 创建后不许改（如 createdAt） |
| `@Column(columnDefinition="TEXT")`  | 用 TEXT 类型存长文本       |
| `@Enumerated(STRING)`               | 枚举存字符串而不是数字         |
| `@PrePersist` / `@PreUpdate`        | 插入/更新前自动执行的方法       |

