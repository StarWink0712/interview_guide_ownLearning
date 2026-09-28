# File 模块 — 综合设计文档

> **模块定位**：`interview.guide.infrastructure.file` 是面试平台的**文件处理基础设施层**，负责文件上传全链路的校验、存储、类型检测、内容解析、文本清洗和去重判断。

---

## 一、模块整体架构

```
                          ┌─────────────────────────────────────┐
                          │           业务 Service 层            │
                          │  ResumeUploadService                 │
                          │  ResumeParseService                  │
                          │  KnowledgeBaseUploadService          │
                          │  KnowledgeBaseParseService           │
                          └──────────────────┬──────────────────┘
                                             │ 依赖注入（6 个基础设施 Service）
                         ┌───────────────────┼───────────────────┐
                         │                   │                   │
         ┌───────────────┼───────────┐      ...          ┌──────┴──────────────┐
         │               ▼           ▼                    ▼                     │
         │  ┌─────────────────────────────┐  ┌──────────────────────────────┐  │
         │  │    FileValidationService    │  │     ContentTypeDetection     │  │
         │  │    ◎ 文件上传前置校验        │  │     Service                  │  │
         │  │     空文件/大小/类型校验     │  │     ◎ MIME 类型精确检测       │  │
         │  └─────────────┬───────────────┘  └──────────────┬───────────────┘  │
         │                │                                 │                  │
         │                │ 输入                            │ 输入              │
         │                ▼                                 ▼                  │
         │  ┌─────────────────────────────────────────────────────────────┐   │
         │  │                    MultipartFile / InputStream              │   │
         │  └─────────────────────────────────────────────────────────────┘   │
         │                                    │                                │
         │                ┌───────────────────┼───────────────────┐           │
         │                ▼                   ▼                   ▼           │
         │  ┌────────────────────┐ ┌────────────────────┐ ┌──────────────┐    │
         │  │  FileHashService   │ │  FileStorageService │ │  Document    │    │
         │  │  ◎ SHA-256 哈希     │ │  ◎ S3 兼容存储      │ │  ParseService│    │
         │  │  文件去重判断       │ │    上传/下载/删除    │ │  ◎ 文档解析  │    │
         │  └────────────────────┘ └────────────────────┘ └──────┬───────┘    │
         │                                                        │           │
         │                                                        ▼           │
         │                                          ┌────────────────────┐    │
         │                                          │ TextCleaningService │    │
         │                                          │ ◎ 语义去噪+格式化   │    │
         │                                          └────────────────────┘    │
         │                                                │                   │
         │                                          ┌─────┴─────┐             │
         │                                          │ 依赖配置   │             │
         │                                          ▼           ▼             │
         │                              ┌────────────┐ ┌─────────────────┐    │
         │                              │NoOpEmbedded│ │ StorageConfig   │    │
         │                              │Document    │ │ Properties      │    │
         │                              │Extractor   │ │ (S3/RustFS 配置) │    │
         │                              └────────────┘ └─────────────────┘    │
         │                                                                    │
         │          ┌─────────────────────────────────────────────┐           │
         │          │              外部依赖                         │           │
         │          │  Apache Tika / AWS S3 SDK / Pinyin4j / JCA  │           │
         │          └─────────────────────────────────────────────┘           │
         └────────────────────────────────────────────────────────────────────┘
```

### 设计理念

**单一职责 + 管道式编排**。每个 Service 只做一件事，业务层通过组合调用形成完整的文件处理管道。类似于 Unix 管道的设计哲学：每个组件小而精，通过标准接口（InputStream/byte[]/String）串联。

---

## 二、各组件关系与交互方式

### 2.1 核心调用链路

```
文件上传请求
    │
    ├─── 1. FileValidationService.validateFile()
    │       校验空文件、文件大小
    │
    ├─── 2. ContentTypeDetectionService.detectContentType()
    │       基于 Tika 魔数检测真实 MIME 类型
    │
    ├─── 3. FileValidationService.validateContentType()
    │       基于步骤 2 的结果校验文件类型是否合法
    │
    ├─── 4. FileHashService.calculateHash()
    │       计算 SHA-256 哈希，查询数据库判断是否重复
    │
    ├─── 5. FileStorageService.uploadResume() / uploadKnowledgeBase()
    │       上传到 S3 兼容存储（RustFS），返回 fileKey
    │
    └─── 6. DocumentParseService.parseContent()   （异步/后续触发）
             ├── AutoDetectParser 解析文件为纯文本
             │   └── NoOpEmbeddedDocumentExtractor（禁用嵌入资源）
             └── TextCleaningService.cleanText()
                  └── 语义去噪 + 格式规范化 → 送 LLM/RAG
```

### 2.2 组件依赖关系矩阵

| 调用方 | 被调用方 | 关系说明 |
|--------|----------|----------|
| 业务 Service | `FileValidationService` | 上传前置校验 |
| 业务 Service | `ContentTypeDetectionService` | MIME 类型检测 |
| 业务 Service | `FileHashService` | 去重判断 |
| 业务 Service | `FileStorageService` | 文件存储 |
| 业务 Service | `DocumentParseService` | 内容提取 |
| `DocumentParseService` | `TextCleaningService` | 文本清洗（强依赖） |
| `DocumentParseService` | `NoOpEmbeddedDocumentExtractor` | 噪声过滤（装饰器注册） |
| `DocumentParseService` | `FileStorageService` | 下载文件后解析（`downloadAndParseContent`） |

### 2.3 数据流传递

所有组件之间通过 Java 标准类型传递数据，无自定义中间数据结构：

```
MultipartFile  ──→  byte[]  ──→  InputStream  ──→  String  ──→  LLM/AI
     │                │              │                │
     │                │              │                └─ TextCleaningService
     │                │              └─ DocumentParseService
     │                └─ FileStorageService (download)
     └─ FileValidationService / FileHashService / FileStorageService (upload)
```

---

## 三、核心功能与技术亮点

### 3.1 多层安全防护

文件上传链路中设置了三道安全防线：

| 层级 | 防线 | 实现位置 | 防护目标 |
|------|------|----------|----------|
| 第一层 | 基础校验 | `FileValidationService.validateFile()` | 空文件、超大文件 |
| 第二层 | MIME 检测 | `ContentTypeDetectionService` | 文件时类型伪装（魔数） |
| 第三层 | 类型白名单 | `FileValidationService.validateContentType()` | 不允许的文件格式 |

### 3.2 文件名安全化处理（汉字转拼音）

`FileStorageService.sanitizeFilename()` 实现了完整的中文文件名处理管线：

```
原始文件名: "张三简历.pdf"
     ↓ convertToPinyin() — Pinyin4j 库
拼音文件名: "zhangsanjianli.pdf"
     ↓ capitalize() — 大驼峰
最终文件名: "ZhangSanJianLi.pdf"
     ↓ sanitizeChar() — 特殊字符过滤
安全路径名: "resumes/2026/06/17/a1b2c3d4_ZhangSanJianLi.pdf"
```

这是对 S3 兼容存储的关键适配——许多对象存储对非 ASCII 路径存在兼容性问题。

### 3.3 Tika 解析精细化控制

`DocumentParseService` 通过 `ParseContext` 注入三个配置，精确控制 Tika 行为：

1. **显式 Parser 注入**：`context.set(Parser.class, parser)`，避免 Tika 从 ServiceLoader 重复加载
2. **禁用嵌入资源**：`NoOpEmbeddedDocumentExtractor`，策略模式实现，阻止图片文件名污染文本
3. **PDF 坐标排序**：`sortByPosition(true)`，解决简历常见多栏布局的文本乱序问题

### 3.4 正则预编译优化

`TextCleaningService` 中所有正则 Pattern 声明为 `private static final`：

```java
private static final Pattern IMAGE_URL =
    Pattern.compile("https?://\\S+?\\.(png|jpe?g|gif|bmp|webp)(\\?\\S*)?",
                    Pattern.CASE_INSENSITIVE);
```

在高频文档解析场景下（批量上传知识库），类加载时一次编译，后续调用零编译开销。

### 3.5 流式哈希计算

`FileHashService` 的 `InputStream` 重载使用 8KB 缓冲区分块更新摘要：

```java
byte[] buffer = new byte[BUFFER_SIZE];  // 8192 字节
while ((bytesRead = inputStream.read(buffer)) != -1) {
    digest.update(buffer, 0, bytesRead);
}
```

内存占用恒定 8KB，不受文件大小影响，可计算 GB 级文件的哈希。

---

## 四、设计模式与架构原则应用

### 4.1 策略模式（Strategy）

`NoOpEmbeddedDocumentExtractor` 是策略模式的典型应用。Tika 将"是否解析嵌入文档"的决策权通过 `EmbeddedDocumentExtractor` 接口开放，调用方注入不同的实现来改变行为。当前策略是"全部拒绝"，未来可替换为"条件性解析"（如 OCR 图片场景）。

### 4.2 模板方法模式（Template Method）

`DocumentParseService` 的三个公开方法（`parseContent(MultipartFile)`、`parseContent(byte[], String)`、`downloadAndParseContent(...)`）都是**适配层**，统一将不同数据源转为 `InputStream` 后调用私有核心方法 `parseContent(InputStream)`。核心解析逻辑只写一次，入口多样化。

### 4.3 门面模式（Facade）

`FileStorageService` 对外暴露语义化方法（`uploadResume`、`uploadKnowledgeBase`、`deleteResume`），内部委托给通用私有方法（`uploadFile`、`deleteFile`），面向业务场景的简洁接口后隐藏了 S3 SDK 的复杂性。

### 4.4 单一职责原则（SRP）

每个 Service 的职责边界非常清晰：

| Service | 唯一职责 |
|---------|----------|
| `FileValidationService` | 判断文件是否合法 |
| `ContentTypeDetectionService` | 识别文件格式 |
| `FileHashService` | 计算文件指纹 |
| `FileStorageService` | 存取文件 |
| `DocumentParseService` | 提取文本内容 |
| `TextCleaningService` | 清洗文本质量 |

这种划分使每个类专注于一个维度，便于独立测试、独立修改和面试时清晰讲解。

### 4.5 降级容错

`ContentTypeDetectionService.detectContentType()` 和 `FileValidationService.validateContentType()` 采用多层降级策略：
- Tika 魔数检测失败 → 回退 HTTP Content-Type
- HTTP Content-Type 不可靠 → 回退 `application/octet-stream`
- MIME 校验失败 → 回退文件扩展名校验
- 两层都失败 → 抛异常拒绝

降级链上的每一层都是在"完美检测"和"可用性"之间做平衡。

---

## 五、性能考量与扩展性设计

### 5.1 性能考量

| 考量点 | 当前方案 | 说明 |
|--------|----------|------|
| 正则预编译 | 全 `static final Pattern` | 类加载时编译，后续零开销 |
| 流式哈希 | 8KB 缓冲区分块 | 支持大文件，内存 O(1) |
| Tika 解析上限 | `BodyContentHandler(5MB)` | 防止超大文档撑爆内存 |
| S3 下载 | `getObjectAsBytes()` | 小文件场景够用，大文件可扩展流式 |
| PDF 配置 | `extractInlineImages=false` | 减少图片解析开销 |

### 5.2 扩展性设计

**新增文件格式支持**：
1. 在 `ContentTypeDetectionService` 增加 `is[Format]()` 便捷方法
2. 在 `FileValidationService` 的 `isKnowledgeBaseMimeType()` 增加 MIME 类型判断
3. Tika 内置支持 1000+ 格式，通常无需额外开发

**接入新存储后端**：
- `FileStorageService` 基于 S3 兼容协议，替换 `s3Client` 的实现即可切换到 AWS S3、MinIO、阿里云 OSS 等
- 接口语义化方法（`uploadResume` 等）保持不变，业务层无感知

**新增文本清洗规则**：
- 在 `TextCleaningService` 的 `cleanText()` 方法中增加新的正则替换步骤
- 两层架构保证了语义规则和格式规则可以独立扩展

**异步解析**：
- 当前 `DocumentParseService.parseContent()` 是同步方法。如需异步，可将解析逻辑接入现有的 Redis Stream 管道（`AbstractStreamProducer/Consumer`），与向量化等异步任务统一调度。

---

## 六、文件清单与文档索引

| 代码文件 | 讲解文档 |
|----------|----------|
| `ContentTypeDetectionService.java` | [ContentTypeDetectionService.md](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/file/analysis/ContentTypeDetectionService.md) |
| `DocumentParseService.java` | [DocumentParseService.md](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/file/analysis/DocumentParseService.md) |
| `FileHashService.java` | [FileHashService.md](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/file/analysis/FileHashService.md) |
| `FileStorageService.java` | [FileStorageService.md](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/file/analysis/FileStorageService.md) |
| `FileValidationService.java` | [FileValidationService.md](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/file/analysis/FileValidationService.md) |
| `NoOpEmbeddedDocumentExtractor.java` | [NoOpEmbeddedDocumentExtractor.md](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/file/analysis/NoOpEmbeddedDocumentExtractor.md) |
| `TextCleaningService.java` | [TextCleaningService.md](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/file/analysis/TextCleaningService.md) |
| 综合设计（本文档） | [file-module-design.md](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/infrastructure/file/analysis/file-module-design.md) |

---

## 七、面试讲解要点

在面试中阐述此模块时，建议按以下结构组织叙述：

1. **先讲架构**：这是一个文件处理基础设施层，7 个 Service 各司其职，通过管道式编排形成完整链路。
2. **再讲亮点**：魔数检测防伪装、汉字拼音转换、PDF 坐标排序、正则预编译、流式哈希——这些是面试官看重的技术深度。
3. **接着讲设计模式**：策略模式（嵌入提取器）、模板方法（解析入口）、门面模式（存储接口），体现架构思维能力。
4. **最后讲扩展性**：如何新增文件格式、如何切换存储后端、如何接入异步管道，展示前瞻性思考。

---

## 八、一段话总结

file 模块是面试平台整个文件上传处理链路的底层基础设施，它围绕 MultipartFile → byte[] → InputStream → String → AI/LLM 这条数据流转主线，将文件校验、类型检测、去重、存储、解析和清洗六大核心能力拆分为七个独立 Service。模块的设计精髓在于"单一职责 + 管道式编排"：每个 Service 只做一件事且做到极致——FileValidationService 用两层防线（基础校验 + 类型白名单）守住上传入口，ContentTypeDetectionService 用魔数检测防伪装攻击，FileHashService 用流式 SHA-256 实现 O(1) 内存的文件指纹计算，FileStorageService 用汉字转拼音 + S3 兼容协议打通存储层，DocumentParseService 用九步解析管线 + ParseContext 三配置精细控制 Tika 行为，TextCleaningService 用两层正则清洗架构保证 LLM 输入质量，NoOpEmbeddedDocumentExtractor 用策略模式过滤嵌入资源噪声。架构层面融入了策略模式（嵌入提取器）、模板方法模式（解析入口分发）、门面模式（存储接口封装）和降级容错（多层回退链），性能层面通过正则预编译、流式计算、5MB 解析上限等手段确保高并发下的稳定性，扩展层面预留了格式新增、存储切换和异步管道接入等演进空间。整个模块的设计思路非常清晰——就像一条精密的生产流水线，每个工位只负责一道工序，通过标准数据接口串联，最终高效地产出高质量的文本内容供 AI 消费。
