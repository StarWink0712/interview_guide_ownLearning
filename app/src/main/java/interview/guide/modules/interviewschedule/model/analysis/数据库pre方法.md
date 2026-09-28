## `@PrePersist` 和 `@PreUpdate` 的作用

### 一句话解释

**在 JPA 自动保存/更新数据之前，先把时间戳填好。**

```java
@PrePersist          // 插入（INSERT）之前自动执行
protected void onCreate() {
    createdAt = LocalDateTime.now();   // 设置创建时间
    updatedAt = LocalDateTime.now();   // 设置更新时间（刚创建时两者相同）
}

@PreUpdate           // 更新（UPDATE）之前自动执行
protected void onUpdate() {
    updatedAt = LocalDateTime.now();   // 只更新"更新时间"
}
```

### 这样设计的好处

**没有这两个方法时**，每次插入和更新都要手动写：

```java
// Service 层，每个新增方法都要写
InterviewScheduleEntity entity = new InterviewScheduleEntity();
entity.setCompanyName("字节跳动");
entity.setCreatedAt(LocalDateTime.now());   // 手动设
entity.setUpdatedAt(LocalDateTime.now());   // 手动设

// 每个更新方法也要写
entity.setUpdatedAt(LocalDateTime.now());   // 又手动设
```

**用了这两个方法之后**，Service 层完全不用管时间：

```java
// Service 层，只管业务字段
InterviewScheduleEntity entity = new InterviewScheduleEntity();
entity.setCompanyName("字节跳动");
entity.setPosition("Java 后端");
scheduleRepository.save(entity);  // JPA 自动调用 @PrePersist，时间戳自动填好
```

### 完整执行流程

```
repository.save(entity)
    ↓
JPA 检查是否有 @PrePersist 方法
    ↓
有 → 调用 onCreate()
    → createdAt = "2026-05-21 15:30:00"
    → updatedAt = "2026-05-21 15:30:00"
    ↓
执行 INSERT SQL
```

```
entity.setPosition("高级 Java 后端");
repository.save(entity)
    ↓
JPA 检查是否有 @PreUpdate 方法
    ↓
有 → 调用 onUpdate()
    → updatedAt = "2026-05-21 16:00:00"（自动更新为当前时间）
    ↓
执行 UPDATE SQL
```

### 加上 `updatable = false` 的联动效果

```java
@Column(name = "created_at", updatable = false)  // 数据库层面也禁止修改
private LocalDateTime createdAt;
```

这里有**双重保护**：
- `@PrePersist` → 插入时自动设（Java 层）
- `updatable = false` → 就算有人手动调 `setCreatedAt()`，生成的 UPDATE SQL 也不包含这个列（数据库层）

### 对比其他时间戳方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| `@PrePersist` + `@PreUpdate`（本项目） | 灵活，可在方法中写任意逻辑 | 每个 Entity 都要写一遍 |
| `@CreationTimestamp` + `@UpdateTimestamp`（Hibernate 提供） | 一行注解搞定 | 不够灵活（只能设当前时间） |
| 数据库 `DEFAULT CURRENT_TIMESTAMP` | 最底层，无需 Java 代码 | 不经过 Java 层，Java 对象中拿不到时间 |