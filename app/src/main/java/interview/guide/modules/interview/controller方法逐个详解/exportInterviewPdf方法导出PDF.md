# exportInterviewPdf：导出面试 PDF 完整调用链

> 接口：`GET /api/interview/sessions/{sessionId}/export`  
> Controller：`InterviewController.exportInterviewPdf`  
> 当前前端调用：面试记录页、简历详情页

## 1. 前端怎样触发

### 面试记录页

只有文字面试且 `evaluateStatus` 已完成时显示导出按钮。点击后：

```text
handleExport(sessionId)
  → historyApi.exportInterviewPdf(sessionId)
  → 得到 Blob
  → URL.createObjectURL(blob)
  → 创建临时 <a>
  → a.download = 面试报告_{sessionId后8位}.pdf
  → a.click()
  → revokeObjectURL
```

### 简历详情页

流程相同，但下载名为 `面试报告_{sessionId}.pdf`。

## 2. 为什么不用普通 request.get

`historyApi` 需要原始二进制响应：

```ts
request.getInstance().get(url, {
  responseType: 'blob',
  skipResultTransform: true,
})
```

响应不是 `Result<T>` JSON，而是 PDF Blob，所以直接使用 Axios 实例并设置 `responseType`。
响应拦截器看到 Blob 不含 `code` 字段，会原样返回。

## 3. Controller 层

```java
@GetMapping("/api/interview/sessions/{sessionId}/export")
public ResponseEntity<byte[]> exportInterviewPdf(
    @PathVariable String sessionId
)
```

这个方法不返回统一 `Result<T>`，而是直接返回文件响应。

## 4. HistoryService 查询和生成 PDF

Controller 调用：

```text
historyService.exportInterviewPdf(sessionId)
  → interviewPersistenceService.findBySessionId
  → InterviewSessionRepository.findBySessionId
```

会话不存在时，Service 抛出 `BusinessException(INTERVIEW_SESSION_NOT_FOUND)`。

找到后调用：

```java
pdfExportService.exportInterviewReport(sessionEntity)
```

`PdfExportService` 使用会话实体中的题目、答案和报告字段生成 PDF 字节数组。生成失败时
HistoryService 转换为：

```java
BusinessException(
    ErrorCode.EXPORT_PDF_FAILED,
    "导出PDF失败: " + e.getMessage()
)
```

## 5. Controller 构造下载响应

成功后：

```text
文件名 = 模拟面试报告_{sessionId}.pdf
  → URLEncoder.encode(..., UTF_8)
  → HTTP 200
  → Content-Type: application/pdf
  → Content-Disposition:
      attachment; filename*=UTF-8''{编码文件名}
  → body = pdfBytes
```

由于前端通过 Blob 和临时 `<a>` 主动下载，最终本地文件名主要由前端 `a.download` 决定。

## 6. 异常返回与普通接口不同

Controller 用 `try/catch (Exception)` 包围全部逻辑：

```java
catch (Exception e) {
  log.error("导出PDF失败", e);
  return ResponseEntity.internalServerError().build();
}
```

因此即使 Service 抛的是明确的 `INTERVIEW_SESSION_NOT_FOUND` 或 `EXPORT_PDF_FAILED`，也不会进入
`GlobalExceptionHandler`，而是统一变成：

```http
HTTP 500
空响应体
```

前端 catch 后只显示“导出失败，请重试”，无法获得业务错误码和具体原因。

## 7. 当前实现边界

- Controller 捕获所有异常，绕过项目统一 HTTP 200 + `Result.error` 约定。
- 服务端不校验评估是否完成；只要会话存在就尝试导出，报告字段可能为空。
- 前端历史页通过按钮条件限制已完成状态，但直接调用 API 可以绕过。
- 整个 PDF 在内存中生成并以 `byte[]` 返回，大报告会占用较多堆内存，没有流式输出。
- 前端配置中的 `skipResultTransform` 不是 `AxiosRequestConfig` 标准字段，通过类型断言传入；当前
  拦截器实际靠 Blob 没有 `code` 字段来避免 Result 解包。
