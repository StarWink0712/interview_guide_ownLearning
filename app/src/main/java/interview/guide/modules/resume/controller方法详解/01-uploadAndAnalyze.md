# uploadAndAnalyze 方法详解

> 所属类：`ResumeController` | 路径：`POST /api/resumes/upload`  
> 委托：`ResumeUploadService` | 限流：GLOBAL 5/s + IP 5/s

---

## 方法签名

```java
@PostMapping(value = "/api/resumes/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
@RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
public Result<Map<String, Object>> uploadAndAnalyze(@RequestParam("file") MultipartFile file)
```

---

## 前端触发

前端简历上传页面选择文件后，通过 `multipart/form-data` 格式提交文件：

```typescript
const formData = new FormData();
formData.append("file", selectedFile);
const result = await resumeApi.upload(formData);
```

上传后根据返回的 `duplicate` 字段判断是否命中重复简历，若 `duplicate=true` 则直接展示历史分析结果，否则根据 `analyzeStatus=PENDING` 进入轮询等待异步分析完成。

---

## 后端完整调用链

```
POST /api/resumes/upload
  → @RateLimit(GLOBAL, 5/s) + @RateLimit(IP, 5/s)
  → uploadService.uploadAndAnalyze(file)
      │
      ├── 1. 文件校验（FileValidationService）
      │     ├── 校验非空、大小 ≤ 10MB
      │     └── 记录文件名、文件大小日志
      │
      ├── 2. 类型检测（ResumeParseService.detectContentType）
      │     └── 校验是否为允许的文件类型（PDF/DOCX/DOC/TXT/MD 等）
      │
      ├── 3. 去重检查（ResumePersistenceService.findExistingResume）
      │     └── 比对文件哈希，检查是否已上传过相同文件
      │     ├── 命中 → 返回历史分析结果（duplicate=true）
      │     └── 未命中 → 继续后续流程
      │
      ├── 4. 文本解析（ResumeParseService.parseResume）
      │     ├── Apache Tika 提取纯文本内容
      │     └── 解析失败 → throw BusinessException(RESUME_PARSE_FAILED)
      │
      ├── 5. 文件存储（FileStorageService.uploadResume）
      │     └── 上传至 RustFS，获取 fileKey 和 fileUrl
      │     └── 存储失败后续入库 → 补偿删除已有文件
      │
      ├── 6. 数据持久化（ResumePersistenceService.saveResume）
      │     ├── 保存 ResumeEntity（analyzeStatus = PENDING）
      │     └── 入库失败 → 补偿删除对象存储文件
      │
      ├── 7. 异步分析（AnalyzeStreamProducer.sendAnalyzeTask）
      │     └── 发送分析任务到 Redis Stream，由消费者异步处理
      │
      └── 8. 返回结果
            └── Map 包含 resumeId、filename、analyzeStatus=PENDING、fileKey、fileUrl
```

---

## 返回值结构

### 新上传（非重复）

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "resume": {
      "id": 123,
      "filename": "简历.pdf",
      "analyzeStatus": "PENDING"
    },
    "storage": {
      "fileKey": "resumes/xxx.pdf",
      "fileUrl": "https://...",
      "resumeId": 123
    },
    "duplicate": false
  }
}
```

### 重复简历

```json
{
  "code": 200,
  "message": "检测到相同简历，已返回历史分析结果",
  "data": {
    "analysis": { /* ResumeAnalysisResponse 完整结构 */ },
    "storage": { "fileKey": "...", "fileUrl": "...", "resumeId": 123 },
    "duplicate": true
  }
}
```

---

## 核心业务问题

**解决的问题**：用户上传简历文件后，系统自动完成文本解析、文件存储、数据库持久化，并异步触发 AI 分析，最终让用户在简历列表中查看评分和建议。

**去重机制**：
- 通过文件哈希比对检测重复上传
- 如果命中已存在的简历，跳过解析、存储、分析流程，直接返回历史分析结果
- 避免重复分析消耗 AI 资源，同时提升响应速度

**异步分析设计**：
- 上传请求同步返回 `analyzeStatus=PENDING`，不阻塞用户等待 AI 分析
- 实际分析任务通过 Redis Stream 异步处理，前端可轮询 `/api/resumes/{id}/detail` 获取最新状态
- 分析状态流转：`PENDING → PROCESSING → COMPLETED | FAILED`

**补偿机制**：
- 文件存储成功后若入库失败，自动删除已上传的对象存储文件
- 保证文件存储与数据库记录的一致性

---

## 异常处理

| 场景 | 处理 |
|------|------|
| 文件为空或超过 10MB | `BusinessException`（文件校验不通过） |
| 不支持的文件格式 | `BusinessException`（"不支持的文件类型: ..."） |
| 无法提取文本内容 | `BusinessException(RESUME_PARSE_FAILED)` |
| 对象存储上传失败 | 异常透传，不落库 |
| 入库失败 | 补偿删除文件 + 异常透传 |
| 限流触发 | `RateLimitExceededException`（8001） |

---

## 总结

`uploadAndAnalyze` 是简历模块的**核心入口方法**，承载了从文件上传到 AI 分析的全链路编排。方法采用同步+异步混合架构：文件校验、文本解析、存储持久化在请求线程同步完成，耗时较长（通常 1-3 秒）；AI 分析通过 Redis Stream 异步解耦，避免阻塞 HTTP 请求。去重机制通过文件哈希快速命中历史记录，既提升了用户体验，又节省了 AI 调用成本。方法同时配置了全局和 IP 两个维度的限流（各 5 次/秒），防止恶意上传消耗系统资源。整个过程包含补偿逻辑，确保文件存储与数据库记录的一致性。
