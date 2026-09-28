# ContentTypeDetectionService — 文件内容类型检测服务技术讲解

## 一、流程图

```
              ┌──────────────────────────────┐
              │        上传文件请求             │
              │  MultipartFile / InputStream  │
              │  / byte[]                     │
              └──────────────┬───────────────┘
                             │
          ┌──────────────────┼──────────────────┐
          │                  │                  │
          ▼                  ▼                  ▼
  detectContentType   detectContentType   detectContentType
  (MultipartFile)     (InputStream,       (byte[], fileName)
                       fileName)
          │                  │                  │
          ▼                  │                  │
  file.getInputStream()      │                  │
          │                  │                  │
          └──────────────────┼──────────────────┘
                             │
                             ▼
              ┌──────────────────────────────┐
              │        Tika.detect()         │
              │  基于文件内容读取魔数字节       │
              │  文件名作为辅助参考             │
              └──────────────┬───────────────┘
                             │
              ┌──────────────┼──────────────┐
              │ 成功          │ 失败          │
              ▼               ▼              │
       返回 MIME 类型    降级策略：           │
                         file.getContentType()│
                         或 "application/     │
                         octet-stream"        │
                             │
                             ▼
              ┌──────────────────────────────┐
              │      业务层类型判断方法         │
              └──────────────┬───────────────┘
                             │
    ┌────────────┬───────────┼───────────┬────────────┐
    ▼            ▼           ▼           ▼            ▼
  isPdf()   isWordDoc()  isPlainText() isMarkdown()  (可扩展)
    
  "application/pdf"     "text/plain"    "text/markdown"
  .contains("pdf")       .startsWith    .contains
                          ("text/")      ("markdown")
```

## 二、执行步骤详解

1. **入口选择**：根据调用方持有的数据类型，选择三个重载之一——`MultipartFile`（Web 上传）、`InputStream`（流处理）、`byte[]`（字节数组）。

2. **核心检测（`tika.detect()`）**：
   - Apache Tika 读取文件**起始字节（魔数/Magic Bytes）**，而非文件扩展名
   - 例如 PDF 文件魔数为 `%PDF`（ASCII），Tika 识别后返回 `"application/pdf"`
   - 文件名作为**辅助参数**传入，在魔数无法区分时提供额外线索

3. **异常降级**：
   - `MultipartFile` 版本：IO 异常时回退到 `file.getContentType()`（HTTP 头部的 Content-Type）
   - `InputStream` 版本：IO 异常时回退到 `"application/octet-stream"`（未知二进制）
   - **设计考量**：降级后仍返回数据，不阻塞主流程，依赖后续 `FileValidationService` 二次校验

4. **类型判断方法**：提供四个便捷判断方法，供业务 Service 直接调用：

| 方法 | 检测逻辑 |
|------|----------|
| `isPdf()` | `contentType.toLowerCase().contains("pdf")` |
| `isWordDocument()` | 包含 `"msword"` 或 `"wordprocessingml"` |
| `isPlainText()` | `contentType.toLowerCase().startsWith("text/")` |
| `isMarkdown()` | 双重检测——MIME 类型包含 `"markdown"` / `"x-markdown"`，或文件扩展名匹配 `.md`/`.markdown`/`.mdown` |

---

## 三、结构化详细总结

### 功能用途

基于 **Apache Tika** 的 `detect()` 方法，通过读取文件内容的**魔数字节**精确识别 MIME 类型，比 HTTP `Content-Type` 头部和文件扩展名更可靠。解决的核心问题是**防止文件类型伪装攻击**（例如将可执行文件改名为 `.pdf` 上传）。

### 核心算法

- **魔数检测**：Tika 内部维护了一个魔数匹配规则库，读取文件最开头若干字节与规则库比对，确定 MIME 类型。
- **多层降级**：Tika 检测失败 → 回退 HTTP Content-Type → 回退 `application/octet-stream`，逐层保底，不影响可用性。
- **`isMarkdown` 双重检测**：Markdown 文件的 MIME 类型在不同系统上表现不一致（`text/plain`、`text/markdown`、`text/x-markdown`），因此同时检查 MIME 和扩展名。

### 关键实现细节

| 细节 | 说明 |
|------|------|
| `Tika` 实例化 | 无参构造 `new Tika()`，内置默认检测器配置，轻量级 |
| `inputStream` 用 try-with-resources | 确保流被正确关闭 |
| `.toLowerCase()` | 所有比较前转小写，兼容大小写变体 |
| `isMarkdown` 取文件名参数 | 独立于其他判断方法，因为 Markdown 的 MIME 类型过于多样化 |

### 潜在优化点

- `isMarkdown()` 中硬编码了扩展名列表，与 `FileValidationService.isMarkdownExtension()` 重复，可考虑统一到一处。
- Tika 实例可考虑使用 Spring 管理的单例 Bean，或使用 `TikaConfig` 自定义配置（如排除不需要的检测器以提高速度）。
- 可增加对 Excel（`xlsx`、`xls`）和图片格式的类型判断方法，与知识库功能对齐。

---

## 四、一段话总结

ContentTypeDetectionService 是文件安全的第一道技术防线，它的核心价值在于通过 Apache Tika 的 detect() 方法读取文件内容的魔数字节（Magic Bytes）来精确识别 MIME 类型，从根本上防止文件类型伪装攻击——即便攻击者将可执行文件改名伪装成 .pdf 上传，Tika 也会通过文件头字节识别出真实类型。该服务提供了三个重载入口分别适配 MultipartFile、InputStream 和 byte[] 三种数据源，每个入口都内置了降级策略：Tika 检测成功返回精确 MIME 类型，IO 异常时 MultipartFile 版本回退到 HTTP Content-Type 头部，InputStream 版本回退到 application/octet-stream，逐层保底不阻塞主流程。在类型判断层面，提供了 isPdf、isWordDocument、isPlainText 和 isMarkdown 四个便捷方法，其中 isMarkdown 采用了独特的双重检测机制（同时检查 MIME 类型和文件扩展名），因为在不同操作系统和客户端上 Markdown 文件的 MIME 类型表现极不一致，仅靠 Tika 检测容易漏判。所有比较操作均通过 toLowerCase() 预处理，保证大小写兼容。
