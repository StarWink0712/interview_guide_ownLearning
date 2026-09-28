# exportAnalysisPdf 方法详解

> 所属类：`ResumeController` | 路径：`GET /api/resumes/{id}/export`  
> 委托：`ResumeHistoryService` → `PdfExportService`

---

## 方法签名

```java
@GetMapping("/api/resumes/{id}/export")
public ResponseEntity<byte[]> exportAnalysisPdf(@PathVariable Long id)
```

---

## 前端触发

用户在简历详情页点击"导出 PDF"按钮：

```typescript
const response = await resumeApi.exportPdf(resumeId);
// response 为二进制流，触发浏览器下载
const url = window.URL.createObjectURL(new Blob([response]));
const link = document.createElement('a');
link.href = url;
link.setAttribute('download', '简历分析报告.pdf');
link.click();
```

---

## 后端完整调用链

```
GET /api/resumes/{id}/export
  → historyService.exportAnalysisPdf(id)
      ├── resumePersistenceService.findById(id)
      │     └── 不存在 → throw BusinessException(RESUME_NOT_FOUND)
      │
      ├── resumePersistenceService.getLatestAnalysisAsDTO(id)
      │     └── 不存在 → throw BusinessException(RESUME_ANALYSIS_NOT_FOUND)
      │
      └── pdfExportService.exportResumeAnalysis(resume, analysis)
            └── iText 8 生成 PDF 字节流
            └── 失败 → throw BusinessException(EXPORT_PDF_FAILED)
  │
  ├── URLEncoder.encode(filename, UTF_8)
  │     └── 处理中文文件名兼容性
  │
  └── ResponseEntity<byte[]>
        ├── Content-Disposition: attachment; filename*=UTF-8''{encoded_filename}
        ├── Content-Type: application/pdf
        └── Body: pdfBytes
```

---

## 响应头设置

| 响应头 | 值 | 说明 |
|--------|-----|------|
| `Content-Disposition` | `attachment; filename*=UTF-8''{encoded_name}` | 触发浏览器下载，UTF-8 编码支持中文文件名 |
| `Content-Type` | `application/pdf` | PDF 文件格式 |

---

## 核心业务问题

**解决的问题**：用户查看 AI 分析结果后，需要将报告下载为 PDF 文件保存或打印。方法将简历基本信息、多维度评分、优势、改进建议等内容通过 iText 8 渲染为可直接下载的 PDF 文档。

**技术要点**：
- **返回值不同于其他方法**：其他方法返回 `Result<T>`（JSON），此方法返回 `ResponseEntity<byte[]>`（二进制流），因为需要设置文件下载响应头
- **文件名编码**：使用 `URLEncoder.encode(filename, UTF_8)` 处理中文文件名，通过 RFC 5987 标准的 `filename*` 参数传递，兼容现代浏览器
- **前置校验**：导出前必须确认简历存在且已有分析记录，否则抛出业务异常

---

## 异常处理

| 场景 | 处理 |
|------|------|
| 简历不存在 | `BusinessException(RESUME_NOT_FOUND)` → 全局异常处理器返回错误 JSON |
| 尚无分析记录 | `BusinessException(RESUME_ANALYSIS_NOT_FOUND)` |
| iText 生成 PDF 失败 | `BusinessException(EXPORT_PDF_FAILED)` |
| 其他未知异常（Controller 层 catch） | 日志记录 `log.error("导出PDF失败: resumeId={}", id, e)`，返回 HTTP 500 |

**注意**：Controller 层 `catch (Exception e)` 是兜底保障，防止 PDF 生成过程中的未知异常导致服务器 500 错误未包装。但优先由 Service 层抛出 `BusinessException` 由全局异常处理器统一处理。

---

## 总结

`exportAnalysisPdf` 是简历模块中唯一返回二进制流的接口，与其余返回 JSON 的接口设计不同。它通过 `ResponseEntity<byte[]>` 直接返回 PDF 字节数组，配合 `Content-Disposition: attachment` 响应头触发浏览器下载行为。文件名使用 RFC 5987 标准的 `filename*` 参数传递，确保中文文件名在主流浏览器中正常显示。导出前进行双重校验——简历实体存在性和分析记录存在性均需满足，任一缺失都抛出对应的业务异常。PDF 生成委托给 `PdfExportService`，底层使用 iText 8 库渲染包含简历基本信息、多维度评分雷达图、优势和建议列表的完整报告。方法本身未配置限流，因为 PDF 导出频率通常不高且是用户主动触发的操作。
