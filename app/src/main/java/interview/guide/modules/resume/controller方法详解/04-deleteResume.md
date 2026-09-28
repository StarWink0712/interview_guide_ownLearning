# deleteResume 方法详解

> 所属类：`ResumeController` | 路径：`DELETE /api/resumes/{id}`  
> 委托：`ResumeDeleteService`

---

## 方法签名

```java
@DeleteMapping("/api/resumes/{id}")
public Result<Void> deleteResume(@PathVariable Long id)
```

---

## 前端触发

用户在简历列表或详情页点击删除按钮，经确认对话框后发起请求：

```typescript
const handleDelete = async (id: number) => {
    if (!window.confirm("确认删除该简历？此操作不可撤销。")) return;
    await resumeApi.delete(id);
    await loadList();  // 刷新列表
};
```

---

## 后端完整调用链

```
DELETE /api/resumes/{id}
  → deleteService.deleteResume(id)
      │
      ├── 1. 查询简历实体
      │     └── persistenceService.findById(id)
      │     └── 不存在 → throw BusinessException(RESUME_NOT_FOUND)
      │
      ├── 2. 删除对象存储文件
      │     └── storageService.deleteResume(resume.getStorageKey())
      │     └── 失败 → 记录 warn 日志，继续后续流程（非阻断）
      │
      ├── 3. 删除关联面试会话
      │     └── interviewPersistenceService.deleteSessionsByResumeId(id)
      │     └── 级联删除：
      │           ├── 面试会话（InterviewSessionEntity）
      │           ├── 答题记录（InterviewAnswerEntity）
      │           └── 评估记录（InterviewEvaluationEntity）
      │
      └── 4. 删除数据库简历记录
            └── persistenceService.deleteResume(id)
            └── 级联删除：
                  └── 分析记录（ResumeAnalysisEntity）
```

---

## 级联删除关系

```
ResumeEntity (简历)
  ├── ResumeAnalysisEntity (AI 分析记录) → 直接删除
  ├── ResumeDuplicateRecordEntity (重复检测记录) → 直接删除
  └── InterviewSessionEntity (面试会话) → interviewPersistenceService.deleteSessionsByResumeId()
        ├── InterviewAnswerEntity (答题记录) → 级联
        └── InterviewEvaluationEntity (评估记录) → 级联
```

---

## 核心业务问题

**解决的问题**：提供简历的完整删除能力，清理与该简历关联的所有数据，包括对象存储文件、数据库分析记录、面试会话及答题评估数据。

**设计要点**：
- **软容错**：对象存储文件删除失败不阻断流程，仅记录 warn 日志。因为核心数据在数据库，文件残留可通过定期清理任务处理
- **硬校验**：简历实体不存在时直接抛出 `BusinessException`，不允许删除不存在的记录
- **级联删除有序**：先删文件（外部存储），再删面试会话（关联数据），最后删简历记录（主数据）。这条顺序确保外部资源先释放，数据库事务一致性由 JPA 级联配置保证
- **不可逆操作**：删除后数据无法恢复，前端应有确认对话框提示用户

---

## 异常处理

| 场景 | 处理 |
|------|------|
| 简历 ID 不存在 | `BusinessException(RESUME_NOT_FOUND)` |
| 对象存储删除失败 | 记录 `log.warn`，继续后续流程 |
| 数据库删除失败 | 事务回滚，异常透传 |

---

## 总结

`deleteResume` 是简历模块中唯一执行**级联物理删除**的方法，不可恢复。删除流程按"外部存储 → 关联数据 → 主数据"的顺序执行：首先清理 RustFS 上的原始文件（失败不阻断），然后通过 `InterviewPersistenceService` 删除该简历关联的所有面试会话及子记录，最后通过 JPA 级联删除简历实体和分析记录。方法未配置限流，因为删除是低频操作。对象存储删除的软容错设计体现了系统健壮性——即使文件删除失败，核心数据仍能正常清理，残留文件可通过后台定时任务兜底清理。前端调用前必须有确认对话框，因为操作不可逆。
