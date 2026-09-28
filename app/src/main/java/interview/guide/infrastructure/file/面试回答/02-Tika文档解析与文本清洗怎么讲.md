# Tika 文档解析与文本清洗怎么讲

## 1. 一句话定位

`DocumentParseService` 负责把 PDF、DOCX、DOC、TXT、MD 等上传文件解析成纯文本，`TextCleaningService` 负责把 Tika 提取出的原始文本清洗成更适合 AI 分析和 RAG 向量化的输入。

面试中可以这样说：

> AI 和 RAG 不能直接消费 PDF 或 Word 的二进制内容，所以文件上传后必须先做文档解析。这里我用 Apache Tika 做统一解析入口，再加一层文本清洗，尽量把图片路径、控制字符、无意义分隔线等噪声过滤掉，让后续简历分析和知识库向量化拿到更干净的文本。

## 2. 为什么用 Apache Tika

项目要支持多种格式：

```text
PDF
DOC / DOCX
TXT
Markdown
RTF
```

如果每种格式都单独接一个解析器，会有很多分支：

| 格式 | 可能的专用解析器 |
| --- | --- |
| PDF | PDFBox |
| DOCX | Apache POI |
| DOC | POI HWPF |
| TXT/MD | 文本流读取 |

Tika 的价值是提供统一入口：

```text
AutoDetectParser
  -> 自动识别格式
  -> 选择对应 Parser
  -> 输出统一文本
```

面试表达：

> Tika 适合这种多格式上传场景，因为它把格式检测和解析器选择封装起来。业务代码不用关心用户上传的是 PDF 还是 DOCX，统一调用 `parseContent` 就能拿到文本。

## 3. DocumentParseService 的解析流程

对外有三个入口：

| 方法 | 场景 |
| --- | --- |
| `parseContent(MultipartFile file)` | 用户刚上传文件时解析 |
| `parseContent(byte[] fileBytes, String fileName)` | 已经拿到字节数组时解析 |
| `downloadAndParseContent(FileStorageService, storageKey, originalFilename)` | 从 RustFS 下载原文件后重新解析 |

核心私有方法是：

```text
parseContent(InputStream inputStream)
```

内部流程：

```text
AutoDetectParser parser = new AutoDetectParser()
BodyContentHandler handler = new BodyContentHandler(5MB)
Metadata metadata = new Metadata()
ParseContext context = new ParseContext()
context.set(Parser.class, parser)
context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor())
context.set(PDFParserConfig.class, pdfConfig)
parser.parse(inputStream, handler, metadata, context)
return handler.toString()
```

## 4. 为什么禁用嵌入文档解析

Tika 默认可能会解析 PDF 或 Office 文档里的嵌入资源，比如：

1. 图片。
2. 附件。
3. 图标。
4. 临时文件路径。

这些内容对简历分析和知识库问答通常没有价值，反而会污染文本：

```text
image123.png
file:///tmp/tika-xxx/image.png
```

所以代码里注册了：

```text
NoOpEmbeddedDocumentExtractor
```

它的 `shouldParseEmbedded` 永远返回 `false`，表示不解析嵌入文档。

面试表达：

> 我不是只把 Tika 默认跑起来，而是针对 AI 输入质量做了裁剪。嵌入资源经常带来图片文件名和临时路径，这些对模型没有语义价值，所以我通过 `NoOpEmbeddedDocumentExtractor` 禁用了嵌入文档解析。

## 5. PDF 解析做了哪些配置

`PDFParserConfig` 当前配置了两点：

| 配置 | 作用 |
| --- | --- |
| `setExtractInlineImages(false)` | 不提取 PDF 内联图片 |
| `setSortByPosition(true)` | 按页面坐标排序文本，改善多栏布局解析顺序 |

这对简历很有用，因为简历常见两栏布局。如果完全按底层内容流顺序提取，文本顺序可能错乱。按坐标排序可以让提取结果更接近视觉阅读顺序。

## 6. TextCleaningService 清理了什么

Tika 提取出来的文本会进入：

```text
TextCleaningService.cleanText
```

它分两层处理。

第一层：语义去噪。

| 清理项 | 原因 |
| --- | --- |
| 控制字符 | 去掉不可见脏字符，保留换行和制表符 |
| 图片文件名整行 | 去掉 `image123.png` 这类无意义行 |
| 图片 URL | 避免图片链接进入 AI prompt 或向量库 |
| `file://` 路径 | 去掉 Tika 或系统临时路径 |
| 分隔线 | 去掉整行 `---`、`***`、`===` 等低价值内容 |

第二层：格式规范化。

| 处理 | 作用 |
| --- | --- |
| 统一 `\r\n` 和 `\r` 为 `\n` | 避免不同平台换行不一致 |
| 去掉行尾空格和制表符 | 保持文本干净 |
| 压缩连续空行 | 最多保留一个空行，保留段落感 |
| `strip()` | 去掉首尾空白 |

## 7. 为什么文本清洗很重要

对 AI 分析来说，输入质量直接影响输出质量。未清洗文本可能包含：

```text
file:///tmp/tika/image001.png
image23.jpg
--------------------------------
```

这些内容会带来三个问题：

1. 浪费 token。
2. 干扰模型判断。
3. 在 RAG 向量化时产生低质量 embedding。

面试表达：

> 文本清洗其实是 AI 应用里的数据预处理。模型看起来很聪明，但输入里如果混进大量图片路径、临时文件和控制字符，就会影响简历评分和知识库检索效果。所以我在 Tika 后面加了一层清洗作为保险。

## 8. 其他辅助方法怎么讲

`TextCleaningService` 还提供：

| 方法 | 用途 |
| --- | --- |
| `cleanTextWithLimit(text, maxLength)` | 清洗后按最大长度截断 |
| `cleanToSingleLine(text)` | 多行文本转单行，适合列表或摘要显示 |
| `stripHtml(text)` | 去除 HTML 标签和常见实体 |

这些方法不是主链路核心，但体现出文本处理能力被集中封装，而不是散落在各个业务 Service 里。

## 9. 当前实现的边界

| 边界 | 当前情况 | 优化方向 |
| --- | --- | --- |
| 扫描版 PDF | 没有 OCR，提取不到文本时上层会提示无法提取 | 接入 OCR 引擎作为 fallback |
| 超大文本 | `BodyContentHandler` 限制 5MB 提取长度 | 大文档可做分页解析或 chunk 流式处理 |
| 表格结构 | Tika 主要输出文本，表格结构可能丢失 | 对强表格场景可用专门解析器 |
| 图片内容 | 当前主动忽略图片 | 如果图片包含关键信息，需要 OCR 或多模态解析 |
| 清洗规则 | 规则偏简历和知识库文本 | 可按业务场景配置不同清洗策略 |

## 10. 面试回答模板

> 文档解析这块我用了 Apache Tika 的 `AutoDetectParser`，因为项目同时支持 PDF、Word、TXT、Markdown 等格式，Tika 可以统一完成格式识别和文本提取。解析时我没有直接用默认配置，而是做了几个针对 AI 输入质量的优化：用 `BodyContentHandler` 只提取正文并限制最大文本长度；注册 `NoOpEmbeddedDocumentExtractor` 禁用嵌入资源解析，避免图片文件名和临时路径污染正文；PDF 解析关闭内联图片提取，并开启按坐标排序，改善多栏简历的文本顺序。
>
> 提取出的原始文本还会经过 `TextCleaningService` 清洗，去掉控制字符、图片文件名、图片 URL、`file://` 路径和分隔线，再统一换行、去除行尾空格、压缩连续空行。这样简历分析和知识库向量化拿到的是更干净的文本，减少 token 浪费和语义噪声。
>
> 当前边界是 Tika 对扫描版 PDF 无能为力，因为扫描件没有文本层。后续生产级优化可以加 OCR fallback、大文件分段解析，以及按业务场景配置不同清洗规则。

