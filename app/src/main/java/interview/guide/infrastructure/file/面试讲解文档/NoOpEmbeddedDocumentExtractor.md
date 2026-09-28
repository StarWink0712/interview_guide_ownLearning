# NoOpEmbeddedDocumentExtractor — 空操作嵌入文档提取器技术讲解

## 一、流程图

```
              ┌────────────────────────────────┐
              │   DocumentParseService          │
              │   准备解析文档（PDF/DOCX等）      │
              └──────────────┬─────────────────┘
                             │
                             ▼
              ┌────────────────────────────────┐
              │   创建 ParseContext              │
              │   context.set(                  │
              │     EmbeddedDocumentExtractor   │
              │     .class,                     │
              │     new NoOpEmbedded            │
              │     DocumentExtractor()         │
              │   )                             │
              └──────────────┬─────────────────┘
                             │
                             ▼
              ┌────────────────────────────────┐
              │   Tika AutoDetectParser         │
              │   解析过程中遇到嵌入资源时        │
              │   调用 EmbeddedDocumentExtractor │
              └──────────────┬─────────────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
    shouldParseEmbedded   (返回 false)    parseEmbedded
    (metadata)                          (空实现)
    → 打印 debug 日志                    → 不做任何操作
    "Skip embedded:                      （永远不会被调用）
     resourceName"
    → return false
                             │
                             ▼
              ┌────────────────────────────────┐
              │  Tika 跳过此嵌入资源             │
              │  不输出图片文件名、临时路径等     │
              │  噪声内容到最终文本               │
              └────────────────────────────────┘
```

## 二、执行步骤详解

1. **注册到解析上下文**：`DocumentParseService` 在构建 `ParseContext` 时注入：
   ```java
   context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());
   ```

2. **Tika 回调 `shouldParseEmbedded`**：当解析器在文档中遇到嵌入资源（图片、附件、OLE 对象等）时，调用此方法询问"是否要解析这个嵌入资源"。此时：
   - 记录 `debug` 级别日志 `"Skip embedded document: {resourceName}"`
   - 始终返回 `false`

3. **Tika 响应**：收到 `false` 后，Tika 跳过该嵌入资源的解析，不会将其文件名、临时路径、图片链接等噪声内容加入最终提取的文本。

4. **`parseEmbedded` 空实现**：由于 `shouldParseEmbedded` 始终返回 `false`，Tika 永远不会调用此方法。实现为空（留作接口契约满足）。

---

## 三、结构化详细总结

### 功能用途

实现 Tika 的 `EmbeddedDocumentExtractor` 接口，通过**始终拒绝解析嵌入资源**来阻止 Tika 将图片文件名、临时文件路径等噪声内容混入最终提取的文本。这是保证 `DocumentParseService` 输出干净文本的关键组件。

### 核心算法

- **策略模式**：Tika 将"是否解析嵌入文档"的决策权开放给调用方。`NoOpEmbeddedDocumentExtractor` 实现了一个"永远不解析"的固定策略。
- **防噪声机制**：如果不替换默认的嵌入提取器，Tika 解析 PDF 时可能输出类似 `embedded:image1.png`、`file:///tmp/tika-xxx.tmp` 的字符串，污染 LLM 的输入质量。

### 关键实现细节

| 细节 | 说明 |
|------|------|
| `shouldParseEmbedded` 始终返回 `false` | 最简单且最安全的策略——不解析任何嵌入资源 |
| `metadata.get("resourceName")` | 使用字符串常量而非 `TikaCoreProperties.RESOURCE_NAME_KEY`，兼容不同 Tika 版本 |
| `@Slf4j` + `log.debug` | debug 级别记录，不影响生产日志量，排查问题时开启 |
| `parseEmbedded` 空实现 | 满足接口契约，实际不会被调用 |

### 潜在优化点

- 当前策略是全部拒绝。如果未来有场景需要解析特定类型的嵌入资源（如图片中的文字 OCR），可改为**条件性拒绝**——检查 `metadata` 中的资源类型，只拒绝图片，允许文本附件。
- `resourceName` 使用字符串常量 `"resourceName"`，如果 Tika 版本升级后变更此 key，日志将变为 `null`。可升级为使用 `TikaCoreProperties` 常量。

---

## 四、一段话总结

NoOpEmbeddedDocumentExtractor 是 Tika 文档解析管线中的一个精巧的"噪声过滤器"，它实现了 Tika 的 EmbeddedDocumentExtractor 接口，核心逻辑极其简洁：shouldParseEmbedded 方法在打印一条 debug 级别日志后始终返回 false，parseEmbedded 方法留为空实现——因为前者的返回值保证了后者永远不会被调用。这个看似简单的设计背后蕴含了策略模式的典型应用：Tika 将"是否解析嵌入资源"的决策权通过接口开放给调用方，DocumentParseService 在构建 ParseContext 时注入此实现，替换 Tika 默认的嵌入文档提取器。效果上，PDF 中嵌入的图片文件名、临时文件路径（形如 file:///tmp/tika-xxx.tmp）等噪声内容将不再混入最终提取的文本，从根本上保证了输送给 LLM 的文本质量。使用字符串常量 "resourceName" 而非 TikaCoreProperties 常量来读取资源名称的设计决策，则体现了对跨 Tika 版本兼容性的考量——不同版本的 Tika 常量名称可能发生变化，而字符串硬编码虽不优雅，却能保证稳定的日志输出。
