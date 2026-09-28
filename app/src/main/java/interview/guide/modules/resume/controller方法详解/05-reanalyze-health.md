# reanalyze & health 方法详解

> 所属类：`ResumeController` | 基路径：`/api/resumes`  
> 委托：`ResumeUploadService`（reanalyze）

---

## reanalyze — 重新分析简历

### 方法签名

```java
@PostMapping("/api/resumes/{id}/reanalyze")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
@RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
public Result<Void> reanalyze(@PathVariable Long id)
```

### 前端触发

用户在简历分析状态为 `FAILED` 或希望刷新分析结果时，点击"重新分析"按钮：

```typescript
const handleReanalyze = async (id: number) => {
    await resumeApi.reanalyze(id);
    // 进入轮询等待分析完成
    pollAnalysisStatus(id);
};
```

### 后端调用链

```
POST /api/resumes/{id}/reanalyze
  → @RateLimit(GLOBAL, 2/s) + @RateLimit(IP, 2/s)
  → uploadService.reanalyze(id)
      │
      ├── 1. 查询简历实体
      │     └── persistenceService.findById(id)
      │     └── 不存在 → throw BusinessException(RESUME_NOT_FOUND)
      │
      ├── 2. 获取简历文本
      │     ├── 优先使用数据库已存储的 resumeText
      │     ├── 若为空 → 从 RustFS 下载原始文件重新解析
      │     │     └── parseService.downloadAndParseContent(storageKey, filename)
      │     └── 仍为空 → throw BusinessException(RESUME_PARSE_FAILED)
      │
      ├── 3. 预处理（ResumePersistenceService.prepareReanalyze）
      │     ├── 保存简历文本到数据库
      │     ├── 重置分析状态为 PENDING
      │     └── 清除旧的错误信息
      │
      └── 4. 发送分析任务
            └── analyzeStreamProducer.sendAnalyzeTask(resumeId, resumeText)
            └── 异步消费者处理：
                  ├── AI 分析简历内容
                  ├── 更新分析状态为 PROCESSING
                  ├── 保存分析结果到 ResumeAnalysisEntity
                  └── 更新状态为 COMPLETED 或 FAILED
```

### 返回值

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

返回 `Result<Void>`，数据体为 `null`。前端通过后续轮询 `getResumeDetail` 获取最新分析状态。

### 核心业务问题

**解决的问题**：AI 分析可能因网络抖动、LLM 超时等原因失败（状态 = `FAILED`），用户需要手动触发重试。也支持用户修改简历后重新上传，对已有简历重新触发分析。

**设计要点**：
- **限流严格**：GLOBAL 2/s + IP 2/s，比上传接口（5/s）更严格，防止频繁重试消耗 AI 资源
- **文本获取策略**：优先复用数据库已存的文本，避免重复解析；若文本丢失（如重新上传文件覆盖），从对象存储下载原始文件重新解析
- **异步处理**：与上传接口一致，重新分析也通过 Redis Stream 异步执行，立即返回，前端轮询状态
- **状态重置**：调用 `prepareReanalyze` 将分析状态重置为 `PENDING`，清除旧错误信息

---

## health — 健康检查

### 方法签名

```java
@GetMapping("/api/resumes/health")
public Result<Map<String, String>> health()
```

### 后端调用链

```
GET /api/resumes/health
  → 直接返回固定 JSON（无 Service 层委托）
```

### 返回值

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "status": "UP",
    "service": "AI Interview Platform - Resume Service"
  }
}
```

### 核心业务问题

为负载均衡器、Kubernetes 存活探针或监控系统提供一个轻量级端点，验证简历服务是否正常运行。方法不依赖任何外部服务（数据库、Redis、LLM），仅返回固定字符串，确保即使依赖服务故障也能响应，避免健康检查误判导致 Pod 被错误重启。

---

## 异常处理（reanalyze）

| 场景 | 处理 |
|------|------|
| 简历 ID 不存在 | `BusinessException(RESUME_NOT_FOUND)` |
| 无法获取简历文本 | `BusinessException(RESUME_PARSE_FAILED)` |
| 限流触发 | `RateLimitExceededException`（8001） |

---

## 总结

`reanalyze` 和 `health` 是两个辅助性方法。`reanalyze` 是简历分析的**手动重试入口**，当 AI 分析因 LLM 调用失败进入 `FAILED` 状态后，用户可触发重新分析。它的限流比上传接口更严格（2 次/秒 vs 5 次/秒），因为重试消耗 AI 资源且通常不应频繁触发。文本获取策略采用"数据库优先、对象存储兜底"的二级降级方案，兼顾性能与可靠性。与上传接口一样采用异步模式，立即返回空数据体，前端通过轮询获取最新状态。`health` 是简历模块唯一的健康检查端点，不依赖任何外部服务，仅返回固定字符串，为基础设施层（K8s、负载均衡）提供存活探测能力。设计上刻意不做数据库连通性检查，避免因短暂数据库故障导致 Pod 被误杀。
