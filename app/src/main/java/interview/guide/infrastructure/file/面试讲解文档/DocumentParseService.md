# DocumentParseService — 通用文档解析服务技术讲解

## 一、流程图

```
              ┌──────────────────────────────────┐
              │         业务 Service 调用          │
              │  ResumeParseService               │
              │  KnowledgeBaseParseService        │
              │  (或自存储下载:                     │
              │   downloadAndParseContent)         │
              └──────────────┬───────────────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
    parseContent        parseContent     downloadAndParse
    (MultipartFile)     (byte[], name)   Content(svc,key,name)
         │                    │                │
         │                    │                ▼
         │                    │        storageService
         │                    │        .downloadFile(key)
         │                    │                │
         └────────────────────┼────────────────┘
                              │
                              ▼
              ┌──────────────────────────────┐
              │    私有核心解析方法             │
              │    parseContent(InputStream)   │
              └──────────────┬───────────────┘
                             │
              ┌──────────────┼──────────────┐
              │ ① 创建解析器   │              │
              ▼                              │
    AutoDetectParser parser                  │
    = new AutoDetectParser()                 │
              │                              │
              ▼                              │
              ┌──────────────────────────────┐
              │ ② 创建内容处理器              │
              │ BodyContentHandler handler    │
              │ = new BodyContentHandler(     │
              │     MAX_TEXT_LENGTH)  ← 5MB   │
              └──────────────┬───────────────┘
                             │
              ┌──────────────┼──────────────┐
              │ ③ 创建元数据  │ ④ 创建上下文  │
              ▼               ▼              │
    Metadata metadata   ParseContext context  │
    = new Metadata()    │                     │
              │         ├─ context.set(        │
              │         │   Parser.class,      │
              │         │   parser)            │
              │         ├─ context.set(        │
              │         │   EmbeddedDoc...     │
              │         │   NoOpExtractor)     │
              │         └─ context.set(        │
              │             PDFParserConfig    │
              │             extractInline      │
              │             Images=false       │
              │             sortByPosition     │
              │             =true)             │
              └──────────────┬───────────────┘
                             │
                             ▼
              ┌──────────────────────────────┐
              │ ⑤ parser.parse(              │
              │   inputStream, handler,      │
              │   metadata, context)          │
              └──────────────┬───────────────┘
                             │
                             ▼
              ┌──────────────────────────────┐
              │ ⑥ handler.toString()         │
              │   返回提取的纯文本             │
              └──────────────┬───────────────┘
                             │
                             ▼
              ┌──────────────────────────────┐
              │  TextCleaningService          │
              │  .cleanText(content)          │
              │  清洗后的干净文本               │
              └──────────────┬───────────────┘
                             │
                             ▼
              ┌──────────────────────────────┐
              │      返回给业务 Service        │
              │      送 AI / RAG 分析          │
              └──────────────────────────────┘
```

## 二、执行步骤详解

1. **入口分发**：三个公开方法分别处理不同数据源——`MultipartFile`（Web 上传）、`byte[]`（从存储下载）、`downloadAndParseContent`（从 S3 下载+解析的复合操作）。

2. **空文件防护**：检查文件是否为空（`isEmpty()` 或 `bytes.length == 0`），空文件直接返回 `""`，避免 Tika 解析空流时抛异常。

3. **私有核心解析方法（`parseContent(InputStream)`）**，分 9 步构建解析管线：

   | 步骤 | 组件 | 配置 |
   |------|------|------|
   | ① | `AutoDetectParser` | Tika 自动检测文档格式，选择合适的解析器 |
   | ② | `BodyContentHandler` | 限制最大输出 5MB（`MAX_TEXT_LENGTH`） |
   | ③ | `Metadata` | 空元数据对象，Tika 自动填充 |
   | ④ | `ParseContext` | 注册三个关键配置 |
   | ⑤ | `parser.parse()` | 执行解析 |
   | ⑥ | `handler.toString()` | 获取提取的纯文本 |

4. **ParseContext 三个关键配置**：

   | 配置项 | 值 | 目的 |
   |--------|-----|------|
   | `Parser.class` | `parser` 实例 | 显式指定解析器，增强健壮性 |
   | `EmbeddedDocumentExtractor.class` | `NoOpEmbeddedDocumentExtractor` | 禁用嵌入资源解析 |
   | `PDFParserConfig.class` | `extractInlineImages=false` + `sortByPosition=true` | 关闭图片提取 + 按坐标排序文本 |

5. **拼接 TextCleaningService**：解析后的原始文本调用 `textCleaningService.cleanText(content)` 进行清洗（去除控制字符、图片引用、分隔线，规范化格式），参考 [TextCleaningService](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/file/analysis/TextCleaningService.md)。

6. **异常处理**：`IOException`（IO 异常）、`TikaException`（Tika 解析失败）、`SAXException`（SAX 解析失败）统一捕获，抛出 `BusinessException(ErrorCode.INTERNAL_ERROR, ...)`。

---

## 三、结构化详细总结

### 功能用途

基于 Apache Tika 的 `AutoDetectParser`，将多种格式的文档（PDF、DOCX、DOC、TXT、MD）解析为**纯文本**，供下游 AI 分析（简历评分、RAG 检索、面试评估）使用。对外屏蔽文档格式差异，对内精细控制解析行为以确保输出质量。

### 核心算法

- **自动格式检测**：`AutoDetectParser` 内部读取文件头魔数，自动选择 PDFParser、OPCParser（DOCX）、HTMLParser 等专用解析器，调用方无需关心文档格式。
- **PDF 专用优化**：`sortByPosition(true)` 按 x/y 坐标重排文本，解决多栏布局下文本乱序问题；`extractInlineImages(false)` 关闭图片提取，减少无效输出。
- **管线式处理**：`解析 → 清洗 → 返回`，解析服务专注格式转换，清洗服务专注内容质量，各司其职。

### 关键实现细节

| 细节 | 说明 |
|------|------|
| `MAX_TEXT_LENGTH = 5MB` | `BodyContentHandler` 的字符上限，防止超大文档撑爆内存 |
| `BodyContentHandler` 而非 `ToXMLContentHandler` | 只需纯文本，不需要 HTML/XML 标记 |
| `context.set(Parser.class, parser)` | 显式注入解析器实例，避免 Tika 从 ServiceLoader 重复加载 |
| `sortByPosition` | 对简历等有文本块的 PDF 尤为重要，防止左右分栏的文字穿插输出 |
| 异常转换为业务异常 | 将底层 Tika 异常统一转为 `BusinessException`，符合项目编码规范 |

### 潜在优化点

- Tika 解析大 PDF（> 50MB）时内存占用高且速度慢，可考虑异步处理 + 超时控制（`parser.parse` 在当前线程同步执行）。
- `AutoDetectParser` 每次调用都重新创建，可考虑提取为 Bean（线程安全，Tika 文档明确支持复用）。
- 当前不支持扫描版 PDF（图片型），如需支持可接入 OCR 引擎（Tesseract 等）。
- `downloadAndParseContent` 中先下载全部 `byte[]` 再解析，对大文件可考虑流式下载。

---

## 四、一段话总结

DocumentParseService 是文件处理链路的核心——它将 PDF、DOCX、DOC、TXT、MD 等多格式文档统一转换为纯文本供下游 AI 分析使用，对外提供三个入口方法适应不同数据源（MultipartFile、byte[]、从 S3 下载），内部通过私有核心方法 parseContent(InputStream) 构建了一套九步解析管线。这个管线的精妙之处在于 ParseContext 的三个精细配置：显式注入 Parser 实例避免 ServiceLoader 重复加载、注入 NoOpEmbeddedDocumentExtractor 禁用嵌入资源解析以防止图片文件名和临时路径污染文本、配置 PDFParserConfig 关闭内联图片提取并通过 sortByPosition 按坐标重排文本解决多栏布局的乱序问题。解析完成后自动衔接 TextCleaningService 做语义去噪和格式规范化，形成"解析 → 清洗 → 返回"的管道式处理流程。异常处理方面，IOException、TikaException 和 SAXException 三类底层异常被统一捕获并转为 BusinessException，既保护了上层调用方免于感知 Tika 底层细节，又完全符合项目的异常处理规范。
