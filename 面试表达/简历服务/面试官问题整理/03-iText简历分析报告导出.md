# iText 简历分析报告导出

## 1. 简历分析报告的 PDF 导出入口是什么？

**回答：** Controller 的 `exportAnalysisPdf` 接收简历 ID，调用 `ResumeHistoryService.exportAnalysisPdf()` 获取导出结果。它直接返回 `ResponseEntity<byte[]>`，Content-Type 为 `application/pdf`，由浏览器下载二进制 PDF。

## 2. PDF 导出为什么不使用统一 Result 包装？

**回答：** `Result<T>` 适合 JSON 业务响应，而 PDF 下载需要把原始字节放在 HTTP Body，并设置文件类型和下载响应头。如果再包一层 JSON，浏览器无法把响应直接当作 PDF 文件，因此 Controller 返回 `ResponseEntity<byte[]>`。

## 3. ExportResult 为什么同时包含字节数组和文件名？

**回答：** `ExportResult` 是一个 record，PDF 字节数组用于响应体，文件名用于构造 `Content-Disposition`。Service 同时返回两者，Controller 不需要了解 iText 文档构建细节，只负责设置 HTTP 下载信息。

## 4. 中文下载文件名如何处理？

**回答：** Controller 对文件名进行 UTF-8 URL 编码，再写入 `Content-Disposition` 响应头并声明附件下载。这样浏览器在处理中文简历报告名称时，不会因为响应头字符集限制出现乱码。

## 5. 导出前会做哪些业务校验？

**回答：** `ResumeHistoryService` 先通过 `ResumePersistenceService` 查询简历，不存在就抛出 `RESUME_NOT_FOUND`；然后查询**最新一条**分析记录，没有结果就抛出 `RESUME_ANALYSIS_NOT_FOUND`。只有简历和分析数据都存在时，才调用 PDF 服务。

## 6. 为什么使用 iText 8 而不是 HTML 转 PDF？

**回答：** `PdfExportService` 直接使用 iText 8 API 构建标题、段落、表格、颜色和列表，不依赖浏览器渲染或 HTML 模板。这样文档结构和样式由后端代码确定，生成过程可控，也不需要额外的 HTML 转换引擎。

itext8可以直接生成pdf是不需要依靠浏览器渲染html的。

## 7. PDF 中文字体是怎么解决的？

**回答：** 服务从 classpath 加载朱雀仿宋 TTF 文件，通过 `PdfFontFactory` 创建字体并强制嵌入 PDF。字体随文档一起输出，用户设备不需要预装同名字体，也能正确显示中文，避免跨设备乱码。

## 8. sanitizeText 为什么要过滤 emoji？

**回答：** 当前嵌入的字体不一定包含 emoji 或部分特殊 Unicode 字形，iText 渲染时可能报错或产生异常字符。`sanitizeText` 在创建段落前通过正则过滤这些字符，提高导出稳定性。

## 9. PDF 报告的整体排版结构是什么？

**回答：** 文档顶部是居中的“简历分析报告”大标题，接着展示文件名和上传时间等基本信息；然后突出显示综合评分，下面用表格展示五个维度得分；最后依次输出简历摘要、优势亮点和改进建议。

## 10. 综合评分如何通过颜色区分？

**回答：** 综合评分使用 18 号加粗字体。分数大于等于 80 显示绿色，大于等于 60 且低于 80 显示黄色，低于 60 显示红色，让用户快速判断简历整体质量区间。

## 11. 五维评分表如何设计？

**回答：** 表格分别展示项目经验满分 40、技能匹配度满分 20、内容完整性满分 15、结构清晰度满分 15、表达专业性满分 10，合计 100。它与 LLM 的 `ResumeAnalysisResponse` 评分结构保持一致。

## 12. 优势和改进建议如何组织？

**回答：** 优势亮点以列表形式展示。改进建议按照优先级和分类组织，并将问题描述与具体建议分开显示，使用户不仅知道简历哪里有问题，也能看到可执行的优化方式。

## 13. 为什么 PDF 生成不需要写临时文件？

**回答：** iText 直接把文档写入内存中的 `ByteArrayOutputStream`，关闭文档后一次性取得 byte 数组并返回。整个过程不落本地磁盘，不需要管理临时文件和清理任务，各请求使用独立内存流，因此并发安全性也更好。
