# MIME 检测与文件校验怎么讲

## 1. 一句话定位

`FileValidationService` 负责上传入口的业务校验，`ContentTypeDetectionService` 负责用 Apache Tika 检测文件真实 MIME 类型。一个判断“能不能传”，一个判断“它到底是什么文件”。

面试中可以这样说：

> 文件上传不能只相信前端传来的文件名和 `Content-Type`。我先做大小和空文件校验，再用 Tika 基于内容检测 MIME 类型，最后按简历或知识库的业务规则判断是否允许上传。

## 2. 为什么不能只看文件扩展名

扩展名很容易伪造：

```text
virus.exe -> 改名成 resume.pdf
unknown.bin -> 改名成 docx
```

HTTP `Content-Type` 也不一定可靠，因为它通常来自浏览器或客户端上传头，可能为空，也可能错误。

所以当前链路是：

```text
MultipartFile
  -> FileValidationService.validateFile
  -> ContentTypeDetectionService.detectContentType
  -> FileValidationService.validateContentType...
```

面试表达：

> 扩展名适合作为辅助信息，不适合作为唯一安全判断。Tika 会读取文件内容和文件名综合判断 MIME 类型，比只看后缀或请求头可靠。

## 3. FileValidationService 校验什么

### 基础属性校验

`validateFile(file, maxSizeBytes, fileTypeName)` 做两件事：

1. 文件不能为空。
2. 文件大小不能超过业务上限。

当前业务上限：

| 业务  | 最大大小 | 调用方                          |
| --- | ---- | ---------------------------- |
| 简历  | 10MB | `ResumeUploadService`        |
| 知识库 | 50MB | `KnowledgeBaseUploadService` |

失败时抛：

```text
BusinessException(ErrorCode.BAD_REQUEST, message)
```

### MIME 白名单校验

简历上传使用：

```text
validateContentTypeByList(contentType, appConfig.getAllowedTypes(), errorMessage)
```

允许类型来自配置：

```text
app.resume.allowed-types
```

它支持部分匹配，比如：

```text
application/pdf
```

可以被包含 `pdf` 的规则匹配。

### MIME + 扩展名组合校验

知识库上传使用：

```text
validateContentType(
  contentType,
  fileName,
  mimeTypeChecker,
  extensionChecker,
  errorMessage
)
```

逻辑是：

```text
先检查 MIME
  -> 通过则允许
MIME 不通过时检查扩展名
  -> 扩展名通过则允许
都不通过
  -> 抛 BAD_REQUEST
```

这样是为了兼容 Markdown，因为部分环境下 `.md` 可能被识别成 `text/plain` 或其他文本类型。

## 4. ContentTypeDetectionService 做什么

它内部持有一个 Tika 实例：

```text
private final Tika tika;
```

支持三种检测入口：

| 方法                                                            | 场景     |
| ------------------------------------------------------------- | ------ |
| `detectContentType(MultipartFile file)`                       | 上传文件检测 |
| `detectContentType(InputStream inputStream, String fileName)` | 流式检测   |
| `detectContentType(byte[] data, String fileName)`             | 字节数组检测 |

`MultipartFile` 版本如果 Tika 检测失败，会降级使用：

```text
file.getContentType()
```

`InputStream` 版本检测失败时返回：

```text
application/octet-stream
```

面试表达：

> 类型检测失败不能让系统直接空指针或误判，所以我做了降级。上传文件检测失败时使用 HTTP 头兜底，普通输入流检测失败时返回通用二进制类型，然后交给后续白名单校验拒绝。

## 5. 常见类型判断方法

`ContentTypeDetectionService` 提供了几个便捷判断：

| 方法               | 判断逻辑                                                 |
| ---------------- | ---------------------------------------------------- |
| `isPdf`          | contentType 包含 `pdf`                                 |
| `isWordDocument` | 包含 `msword` 或 `wordprocessingml`                     |
| `isPlainText`    | 以 `text/` 开头                                         |
| `isMarkdown`     | MIME 包含 markdown，或文件名以 `.md`、`.markdown`、`.mdown` 结尾 |

`FileValidationService` 里也有知识库场景的判断：

```text
isKnowledgeBaseMimeType
```

支持：

```text
pdf
msword
wordprocessingml
text/plain
text/markdown
text/x-markdown
text/x-web-markdown
application/rtf
```

## 6. 简历和知识库校验规则有什么区别

| 维度   | 简历                              | 知识库                             |
| ---- | ------------------------------- | ------------------------------- |
| 大小   | 10MB                            | 50MB                            |
| 类型来源 | 配置文件 `app.resume.allowed-types` | 代码里知识库 MIME 判断 + Markdown 扩展名兜底 |
| 失败错误 | 不支持的文件类型                        | 提示支持 PDF、DOCX、DOC、TXT、MD 等      |
| 业务目标 | 解析简历文本用于 AI 分析                  | 解析文档文本用于向量化和 RAG                |

面试表达：

> 简历和知识库复用了同一个基础校验服务，但业务规则不同。简历类型走配置白名单，便于调整；知识库要兼容 Markdown，所以除了 MIME，还加了扩展名兜底。

## 7. 当前实现的边界

| 边界        | 当前情况                                       | 优化方向              |
| --------- | ------------------------------------------ | ----------------- |
| 安全扫描      | 当前只做类型和大小校验                                | 可接入杀毒扫描、恶意宏检测     |
| MIME 部分匹配 | `validateContentTypeByList` 支持 contains 匹配 | 高安全场景可改精确匹配       |
| 扩展名兜底     | Markdown 可用扩展名放行                           | 可要求文本内容也通过安全检查    |
| 文件名安全     | 存储层会清理对象 key                               | 上传校验层可增加原始文件名长度限制 |
| 空文件       | 只判断 `file.isEmpty()`                       | 可增加解析后文本为空的业务提示   |

## 8. 面试回答模板

> 文件校验我拆成两层。第一层是 `FileValidationService` 做业务校验，比如文件不能为空、大小不能超过限制、MIME 类型必须在允许范围内（在tika检测之后）。第二层是 `ContentTypeDetectionService` 用 Apache Tika 做真实 MIME 检测，不只相信文件后缀或浏览器传来的 `Content-Type`。
>
> 简历上传最大 10MB，类型走配置白名单；知识库最大 50MB，支持 PDF、Word、TXT、Markdown、RTF 等类型。知识库对 Markdown 做了扩展名兜底，因为不同环境下 `.md` 可能被识别成普通文本。校验失败统一抛 `BusinessException(ErrorCode.BAD_REQUEST, ...)`，交给全局异常处理器包装响应。
>
> 这个设计的边界是它还不是完整的文件安全网关，目前没有做病毒扫描、恶意宏检测或内容安全审计。生产环境可以在 MIME 校验后再接入安全扫描和更严格的精确白名单。

