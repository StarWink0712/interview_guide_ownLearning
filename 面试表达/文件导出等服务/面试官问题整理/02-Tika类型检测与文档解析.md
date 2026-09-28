# Tika 类型检测与文档解析

## 1. 为什么不能只根据文件名后缀判断文件类型？

**回答：** 文件名后缀和 HTTP `Content-Type` 都可以被用户修改，不能代表文件真实内容。`ContentTypeDetectionService` 注入 Apache Tika 的检测器，调用 `tika.detect(inputStream, fileName)` 读取文件魔数字节并结合文件名判断真实 MIME 类型，从而识别实际是 PDF 还是 Word，避免伪造后缀绕过校验。

## 2. 为什么选择 Apache Tika 作为统一解析入口？

**回答：** 项目支持 PDF、Word、TXT、Markdown 等多种格式，如果每种格式接一个解析器，会产生大量条件分支。Tika 的 `AutoDetectParser` 可以自动识别格式并选择对应解析器，最后统一输出 String 文本，一套实现同时服务简历分析和知识库向量化。

## 3. LLM 为什么不能直接处理上传的 PDF 或 Word？

**回答：** 当前业务调用的 LLM 主要消费文本 Prompt，不能直接把 PDF、Word 的原始二进制当成普通文本理解。因此文件上传后先通过 Tika 提取正文，再通过文本清洗去掉噪声，最后把干净文本用于简历分析、问题生成或知识库向量化。

## 4. DocumentParseService 的解析流程是什么？

**回答：** 方法先把 `MultipartFile` 或下载得到的字节数组转换为 `InputStream`，创建 `AutoDetectParser`、接收正文的 `BodyContentHandler`、保存文件属性的 `Metadata` 和解析配置 `ParseContext`。配置完成后调用 `parser.parse(inputStream, handler, metadata, context)`，最后通过 `handler.toString()` 取得纯文本，再交给 `TextCleaningService`。

## 5. BodyContentHandler 在解析中有什么作用？

**回答：** `BodyContentHandler` 用来接收 Tika 解析出的正文内容，不保存解析器产生的其他无关结构。项目为它设置最大 5MB 的文本长度限制，避免异常文档生成过大的正文占用内存；解析完成后调用 `toString()` 获得正文字符串。

## 6. ParseContext 中为什么显式放入 Parser.class？

**回答：** 项目在 `ParseContext` 中显式指定 `Parser.class`，让解析上下文明确使用当前自动检测解析器，增强不同文档解析时的健壮性。与此同时，PDF 配置和嵌入文档提取策略也通过 ParseContext 传给 Tika。

## 7. 为什么要使用 NoOpEmbeddedDocumentExtractor？

**回答：** 文档中可能包含图片和其他嵌入资源，默认提取可能把图片文件名、临时路径等内容混入正文，既增加 token，又给 LLM 和向量检索带来语义噪声。注册 `NoOpEmbeddedDocumentExtractor` 后，Tika 不再解析这些嵌入资源，只保留需要的主体文本。

## 8. PDFParserConfig 做了哪些优化？

**回答：** PDF 解析关闭内联图片提取，避免图片相关信息污染正文；同时开启 `setSortByPosition(true)`，根据文字坐标重新排序。简历经常使用左右多栏布局，按位置排序能改善默认抽取顺序，让文本更接近人类阅读顺序。

## 9. 上传文件和从 RustFS 下载的文件如何复用同一套解析？

**回答：** 刚上传时，系统直接把 `MultipartFile` 转成 `InputStream`；从 RustFS 重新处理时，先下载为字节数组，再把字节数组包装成输入流。两种入口最终都调用同一个 Tika 解析方法，因此类型识别、PDF 配置、正文提取和清洗行为保持一致。

## 10. Tika 解析扫描版 PDF 有什么限制？

**回答：** 扫描版 PDF 通常只有图片，没有可直接提取的文本层，Tika 的普通文本解析无法识别图片中的文字。这是当前实现的明确边界；生产级优化可以增加 OCR fallback，在发现正文为空或过短时转入图像文字识别。

## 11. 文档解析后为什么还要做文本清洗？

**回答：** Tika 负责尽可能提取内容，但输出中仍可能存在控制字符、图片文件名、URL、临时路径和无意义分隔线。直接交给 LLM 或 Embedding 会浪费 token，并干扰语义。`TextCleaningService` 在解析后统一去噪和规范格式，让简历分析与知识库向量化使用更干净的输入。

## 12. TextCleaningService 的语义去噪规则有哪些？

**回答：** 第一层通过正则删除控制字符、整行类似 `image123.png` 的图片文件名、HTTP/HTTPS 图片链接、`file://` 临时文件路径，以及 `---`、`===` 等无意义分隔线，避免这些非正文内容进入后续 AI 流程。

## 13. TextCleaningService 如何规范文本格式？

**回答：** 第二层把不同系统的换行符统一为 `\n`，移除每行末尾空格，并压缩连续空行，最多保留一个空行，在减少无效字符的同时保留段落结构。另外提供独立的 `stripHtml` 方法，用于移除 HTML 标签以及 `&nbsp;`、`&amp;` 等实体字符。

## 14. 文档解析还有哪些可继续优化的方向？

**回答：** 除了为扫描 PDF 增加 OCR fallback，还可以对大文件采用分段解析，避免一次处理过多文本；同时根据简历和知识库的不同需求配置独立清洗规则，在减少噪声的同时避免误删有业务价值的内容。

