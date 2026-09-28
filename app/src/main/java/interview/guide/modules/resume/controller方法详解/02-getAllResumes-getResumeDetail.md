# getAllResumes & getResumeDetail 方法详解

> 所属类：`ResumeController` | 基路径：`/api/resumes`  
> 委托：`ResumeHistoryService`

---

## getAllResumes — 获取简历列表

### 方法签名

```java
@GetMapping("/api/resumes")
public Result<List<ResumeListItemDTO>> getAllResumes()
```

### 前端触发

前端简历管理页面加载时调用，用于渲染简历列表：

```typescript
const resumes = await resumeApi.list();
```

### 后端调用链

```
GET /api/resumes
  → historyService.getAllResumes()
    → resumePersistenceService.findAllResumes()
        → 查询所有 ResumeEntity
    → 逐条组装 ResumeListItemDTO：
        ├── 取最新一次分析评分（overallScore）
        ├── 统计关联的面试会话数量
        └── 包含分析状态（PENDING/PROCESSING/COMPLETED/FAILED）
    → 返回 List<ResumeListItemDTO>
```

### ResumeListItemDTO 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 简历 ID |
| `filename` | String | 原始文件名 |
| `fileSize` | Long | 文件大小（字节） |
| `uploadedAt` | LocalDateTime | 上传时间 |
| `accessCount` | Integer | 访问次数 |
| `latestScore` | Integer | 最近一次分析综合评分 |
| `lastAnalyzedAt` | LocalDateTime | 最近分析时间 |
| `interviewCount` | Integer | 关联的面试会话数量 |
| `analyzeStatus` | AsyncTaskStatus | 分析状态（PENDING/PROCESSING/COMPLETED/FAILED） |
| `analyzeError` | String | 分析失败时的错误信息 |

### 核心业务问题

提供简历管理页面的列表视图，让用户快速浏览所有已上传简历的概览信息。每条记录包含文件名、上传时间、最新评分、面试次数、分析状态等关键字段，无需展开详情即可掌握整体情况。

---

## getResumeDetail — 获取简历详情

### 方法签名

```java
@GetMapping("/api/resumes/{id}/detail")
public Result<ResumeDetailDTO> getResumeDetail(@PathVariable Long id)
```

### 前端触发

用户在简历列表中点击某条记录，进入简历详情页：

```typescript
const detail = await resumeApi.getDetail(resumeId);
```

### 后端调用链

```
GET /api/resumes/{id}/detail
  → historyService.getResumeDetail(id)
    → resumePersistenceService.findById(id)
        → 不存在 → throw BusinessException(RESUME_NOT_FOUND)
    → resumePersistenceService.findAnalysesByResumeId(id)
        → 查询所有分析记录
        → ResumeMapper 转换为 AnalysisHistoryDTO 列表
        → 解析 strengths/suggestions JSON 字段
    → interviewPersistenceService.findByResumeId(id)
        → InterviewMapper 转换为 InterviewHistoryItemDTO 列表
    → 组装 ResumeDetailDTO 返回
```

### ResumeDetailDTO 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 简历 ID |
| `filename` | String | 原始文件名 |
| `fileSize` | Long | 文件大小（字节） |
| `contentType` | String | 文件 MIME 类型 |
| `storageUrl` | String | 对象存储下载地址 |
| `uploadedAt` | LocalDateTime | 上传时间 |
| `accessCount` | Integer | 访问次数 |
| `resumeText` | String | 解析后的纯文本内容 |
| `analyzeStatus` | AsyncTaskStatus | 当前分析状态 |
| `analyzeError` | String | 分析失败时的错误信息 |
| `analyses` | List\<AnalysisHistoryDTO\> | 分析历史记录列表 |
| `interviews` | List\<InterviewHistoryItemDTO\> | 关联的面试历史记录 |

### AnalysisHistoryDTO 内嵌字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 分析记录 ID |
| `overallScore` | Integer | 综合评分 |
| `contentScore` | Integer | 内容质量分 |
| `structureScore` | Integer | 结构清晰度分 |
| `skillMatchScore` | Integer | 技能匹配度分 |
| `expressionScore` | Integer | 表达能力分 |
| `projectScore` | Integer | 项目经验分 |
| `summary` | String | AI 分析摘要 |
| `analyzedAt` | LocalDateTime | 分析时间 |
| `strengths` | List\<String\> | 优势列表 |
| `suggestions` | List\<Object\> | 改进建议列表 |

### 核心业务问题

提供简历的全景视图，将简历基本信息、所有历史分析记录（多维度评分、优势、建议）和关联的面试会话一并返回。用户可直观看到：
- 每次分析的评分变化趋势
- 各维度得分对比
- AI 提出的优势和待改进点
- 基于该简历进行过的所有面试会话

---

## 异常处理

| 场景 | 处理 |
|------|------|
| 简历 ID 不存在 | `BusinessException(RESUME_NOT_FOUND)` |
| strengths/suggestions JSON 解析异常 | 记录错误日志，返回空列表（不中断请求） |

---

## 总结

`getAllResumes` 和 `getResumeDetail` 是简历模块的两个**核心查询接口**，分别服务于列表视图和详情视图。`getAllResumes` 在页面加载时被调用，汇总所有简历的摘要信息，性能层面遍历每份简历查询最新评分和面试计数，数据量不大时响应迅速；若简历数量增长，可考虑通过 JPQL 聚合查询优化 N+1 问题。`getResumeDetail` 提供单份简历的完整视图，一次性返回基本信息、分析历史和面试记录，避免前端多次请求。分析历史中的 `strengths`（优势）和 `suggestions`（建议）以 JSON 字符串形式存储在数据库中，通过 ObjectMapper 反序列化为 Java 集合对象返回，解析失败时捕获异常记录日志并返回空列表，保证接口不会因格式异常的数据中断。两个方法均未配置限流，因为查询请求通常不消耗大量系统资源。
