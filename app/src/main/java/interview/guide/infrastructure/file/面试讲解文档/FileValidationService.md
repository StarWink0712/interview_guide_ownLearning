# FileValidationService — 文件验证服务技术讲解

## 一、流程图

```
              ┌──────────────────────────────┐
              │        文件上传入口             │
              │  ResumeUploadService /        │
              │  KnowledgeBaseUploadService   │
              └──────────────┬───────────────┘
                             │
                             ▼
              ┌──────────────────────────────┐
              │    第一层：基础属性校验         │
              │     validateFile()            │
              └──────────────┬───────────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
        file.isEmpty()  file.getSize()  二者校验通过
        → 抛异常         > maxSize       → 进入下一层
        "请选择文件"      → 抛异常
                         "超过限制"
                             │
                             ▼
              ┌──────────────────────────────┐
              │    第二层：文件类型校验         │
              └──────────────┬───────────────┘
                             │
          ┌──────────────────┼──────────────────┐
          ▼                                     ▼
┌─────────────────────┐            ┌──────────────────────┐
│ 列表模式校验          │            │ 双重校验模式           │
│ validateContentType  │            │ validateContentType   │
│ ByList()             │            │ (Predicate 版本)      │
└──────────┬───────────┘            └──────────┬───────────┘
           │                                   │
           ▼                                   ▼
┌─────────────────────┐            ┌──────────────────────┐
│  isAllowedType()    │            │ ① mimeTypeChecker    │
│  部分匹配逻辑        │            │    .test(contentType) │
│  "pdf" 匹配         │            │         │             │
│  "application/pdf"  │            │     ┌───┴───┐         │
│                     │            │    成功    失败        │
│                     │            │   返回    │           │
│                     │            │          ▼            │
│                     │            │ ② extensionChecker    │
│                     │            │   .test(fileName)      │
│                     │            │     ┌───┴───┐          │
│                     │            │    成功    失败         │
│                     │            │   返回  → 抛异常       │
└─────────────────────┘            └──────────────────────┘

                           便捷方法（供调用方组合）：
              ┌────────────────────────────────────────┐
              │  isMarkdownExtension(fileName)          │
              │  isKnowledgeBaseMimeType(contentType)   │
              └────────────────────────────────────────┘
```

## 二、执行步骤详解

1. **基础属性校验（`validateFile`）**：
   - 检查 `file.isEmpty()`：空文件直接拒绝，提示"请选择要上传的{文件类型}文件"
   - 检查 `file.getSize() > maxSizeBytes`：超限拒绝，提示"文件大小超过限制"
   - 使用参数化的 `fileTypeName`（如"简历"、"知识库"）定制错误消息

2. **列表模式校验（`validateContentTypeByList`）**：
   - 调用 `isAllowedType(contentType, allowedTypes)` 进行**部分匹配**
   - 匹配逻辑：将 `contentType` 转为小写后，遍历 `allowedTypes` 列表，任意一项满足 `contentType.contains(allowed) || allowed.contains(contentType)` 即通过
   - 例如 `allowedTypes = ["pdf"]` 可以匹配 `"application/pdf"`

3. **双重校验模式（`validateContentType` — Predicate 版本）**：
   - **第一步**：使用 `mimeTypeChecker` 检查 MIME 类型（通过 Tika 检测的结果）
   - **第二步（降级）**：如果 MIME 类型未通过，使用 `extensionChecker` 检查文件扩展名
   - **设计意图**：Tika 对 Markdown 等纯文本格式的 MIME 检测可能不准确，双检机制确保不误杀合法文件
   - 两次检查均失败才抛出业务异常

4. **便捷方法**：
   - `isMarkdownExtension(fileName)`：检查 `.md`、`.markdown`、`.mdown` 后缀
   - `isKnowledgeBaseMimeType(contentType)`：预置知识库支持的格式列表（PDF、Word、TXT、Markdown、RTF）

---

## 三、结构化详细总结

### 功能用途

作为文件上传的**前置校验网关**，在上传链路中提供统一的基础校验能力。核心价值在于将"文件是否合法"的判定逻辑集中管理，避免散落在各业务 Service 中。

### 核心算法

- **部分匹配策略**：`isAllowedType` 使用双向 `contains` 匹配——允许配置列表中写 `"pdf"` 即可匹配完整的 `"application/pdf"`，降低配置复杂度。
- **双重校验降级**：MIME 类型检测（基于文件内容）为主，文件扩展名（基于文件名）为辅。当 Tika 检测结果不明确时，扩展名作为降级策略兜底。

### 关键实现细节

| 细节 | 说明 |
|------|------|
| `Predicate<String>` 参数 | 方法签名使用函数式接口，调用方以 Lambda 传入检查逻辑，高度灵活 |
| `fileTypeName` 参数化 | 避免硬编码"简历""知识库"，同一方法服务不同业务模块 |
| 双向 `contains` 匹配 | `lowerContentType.contains(lowerAllowed) \|\| lowerAllowed.contains(lowerContentType)` |
| `.toLowerCase()` 防护 | 所有比较前转小写，兼容 `PDF`、`Pdf` 等大小写变体 |

### 潜在优化点

- `isAllowedType` 的双向 `contains` 过于宽松，例如 `"image/png"` 配 `["pdf"]` 不会误判，但配 `["age"]` 就会误通过（`"image"` 包含 `"age"`）。建议对配置项加前缀约束（如 `"application/pdf"` 完整匹配）。
- `isKnowledgeBaseMimeType` 中支持的格式硬编码在方法内，可考虑移到配置文件或 `@ConfigurationProperties` 中，方便运维动态调整。
- 可增加文件内容魔数校验（如 PDF 的前 4 字节 `%PDF`），作为第三层防护。

---

## 四、一段话总结

FileValidationService 是文件上传链路的前置校验网关，它采用两层防御架构：第一层通过 validateFile 方法检查文件是否为空以及文件大小是否超限，使用参数化的 fileTypeName 定制不同业务模块（简历、知识库）的错误提示；第二层提供两种类型校验模式——列表模式的 validateContentTypeByList 使用双向 contains 部分匹配策略，允许配置"pdf"即可匹配完整的"application/pdf"，而 Predicate 版本的 validateContentType 则采用双重降级机制，先以 Tika 检测的 MIME 类型为主检查，失败时再回退到文件扩展名校验，确保 Markdown 等 MIME 类型不明确的格式不被误杀。此外还提供了 isMarkdownExtension 和 isKnowledgeBaseMimeType 两个便捷方法供调用方组合使用，将分散在各业务 Service 中的文件合法性判定逻辑集中管理，实现了校验规则的统一维护和复用。
