     
## MIME 是什么

**MIME**（Multipurpose Internet Mail Extensions，多用途互联网邮件扩展）最初是为解决电子邮件只能传输 ASCII 文本的限制而设计的，后来扩展为互联网上标识文件内容类型的通用标准。简单说，MIME 类型（也叫 Content-Type）就是一个标签，告诉计算机"这个数据到底是什么格式"。

在你这边的代码里，[ContentTypeDetectionService.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/file/ContentTypeDetectionService.java) 使用 **Apache Tika** 通过读取文件内容（而非扩展名）来精确检测 MIME 类型，比 HTTP 头部的 `Content-Type` 更可靠。

---

## MIME 类型的结构

MIME 类型的格式为：**`主类型/子类型`**，例如 `application/pdf`、`text/plain`。

---

## MIME 主要种类

### 1. **text（文本类）**

| MIME 类型 | 说明 |
|---|---|
| `text/plain` | 纯文本（.txt） |
| `text/html` | HTML 网页 |
| `text/css` | 样式表 |
| `text/csv` | CSV 表格 |
| `text/markdown` | Markdown 文件（.md） |
| `text/javascript` | JS 脚本 |

你代码中的判断方法 `isPlainText()`（第 101-103 行）就是检测 `text/` 开头的类型。

### 2. **application（应用程序/二进制类）**

这是最大的一类，涵盖各种文档和二进制格式：

| MIME 类型 | 说明 |
|---|---|
| `application/pdf` | PDF 文档 |
| `application/msword` | Word .doc |
| `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | Word .docx |
| `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | Excel .xlsx |
| `application/json` | JSON 数据 |
| `application/xml` | XML 数据 |
| `application/zip` | ZIP 压缩包 |
| `application/octet-stream` | 通用二进制流（未知类型默认值） |

你代码中的 `isPdf()`（第 85-87 行）检测 `pdf` 关键字，`isWordDocument()`（第 92-96 行）检测 `msword` 和 `wordprocessingml`。

### 3. **image（图片类）**

| MIME 类型 | 说明 |
|---|---|
| `image/jpeg` | JPEG 图片 |
| `image/png` | PNG 图片 |
| `image/gif` | GIF 动图 |
| `image/svg+xml` | SVG 矢量图 |
| `image/webp` | WebP 图片 |

### 4. **audio（音频类）**

| MIME 类型 | 说明 |
|---|---|
| `audio/mpeg` | MP3 音频 |
| `audio/wav` | WAV 音频 |
| `audio/ogg` | OGG 音频 |

### 5. **video（视频类）**

| MIME 类型 | 说明 |
|---|---|
| `video/mp4` | MP4 视频 |
| `video/webm` | WebM 视频 |

### 6. **multipart（多部分类）**

用于包含多种数据组合的场景：

| MIME 类型 | 说明 |
|---|---|
| `multipart/form-data` | 表单提交（含文件上传） |
| `multipart/mixed` | 邮件附件混合体 |

---

## 总结

在你的项目中，[ContentTypeDetectionService](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/file/ContentTypeDetectionService.java) 负责对上传的简历、知识库文档进行精确的 MIME 类型检测，核心方法是 `tika.detect()`（基于文件内容字节），目前覆盖了 **PDF、Word（doc/docx）、纯文本、Markdown** 四种文档类型的判断。如果后续需要支持更多格式（如 Excel、图片），可以参考上面的分类扩展对应的 `isXxx()` 方法。