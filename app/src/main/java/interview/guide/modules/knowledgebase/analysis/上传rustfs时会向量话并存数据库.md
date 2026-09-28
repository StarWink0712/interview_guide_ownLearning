不需要。LLM 获取知识库内容的路径完全不走 RustFS，而是走一条完全不同的管道。让我用代码说清楚。

## 关键区分：两条完全独立的数据管道

```
管道 A：上传存储（管理员用）
  文件 → Apache Tika 提取文本 → RustFS 存原始文件 → DB 存元数据

管道 B：RAG 问答（用户用）      
  用户提问 → pgvector 向量检索 → 获取文档片段 → 注入 LLM Prompt
```

**管道 B 完全不碰 RustFS**，原因是文件在上传时已经被"预处理"到了向量数据库。

---

## 向量化发生了什么？

看 [KnowledgeBaseVectorService.vectorizeAndStore()](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java#L62-L99)：

```java
public void vectorizeAndStore(Long knowledgeBaseId, String content) {
    // 1. 将文本分块（TokenTextSplitter，每块约 800 tokens）
    List<Document> chunks = textSplitter.apply(List.of(new Document(content)));

    // 2. 为每个 chunk 打上知识库 ID 标记
    chunks.forEach(chunk -> chunk.getMetadata().put("kb_id", knowledgeBaseId.toString()));

    // 3. 分批向量化并存入 pgvector（阿里云 API 限制 batch ≤ 10）
    vectorStore.add(chunks);  // Embedding → pgvector 存储
}
```

上传时的文本内容 `content`（Apache Tika 提取的纯文本）被：
1. **分块**（TokenTextSplitter，~800 tokens/块）
2. **向量化**（调用 Embedding API 转为 1024 维向量）
3. **存入 pgvector**（PostgreSQL 的向量扩展，key 是向量 + kb_id 元数据）

---

## RAG 问答时的检索路径

看 [KnowledgeBaseQueryService](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java#L400-L442) 的检索和回答流程：

```java
private List<Document> retrieveRelevantDocs(...) {
    // 从 pgvector 做语义搜索（COSINE 相似度）
    List<Document> docs = vectorService.similaritySearch(
        candidateQuery,          // 改写后的问题
        knowledgeBaseIds,        // 限定知识库范围
        searchParams.topK(),     // topK 动态调整
        searchParams.minScore()  // 相似度阈值
    );
}

// 检索结果直接拼接为上下文
String context = relevantDocs.stream()
    .map(Document::getText)       // ← 直接拿检索到的文本片段
    .collect(Collectors.joining("\n\n---\n\n"));

// 构建 Prompt 注入 LLM
String userPrompt = buildUserPrompt(context, question);
getChatClient().prompt()
    .system(systemPrompt)
    .user(userPrompt)             // ← 上下文直接嵌入提示词
    .call().content();
```

---

## 完整链路对比

```
❌ 错误理解（低效）：
  用户提问 → 查 DB 元数据 → 拿 storageKey → 去 RustFS 下载原始文件 
  → 全文读取 → 发给 LLM → 很慢很贵

✅ 实际设计（高效）：
  用户提问 → pgvector 语义搜索 → 只返回最相关的几个文本片段 
  → 直接注入 LLM Prompt → 快速精准
```

| 对比维度 | 走 RustFS 读原文件 | 走 pgvector 向量检索 |
|---------|-------------------|---------------------|
| 速度 | 下载文件 → 读取全文（慢） | 向量相似度计算（毫秒级） |
| 精度 | 全文发给 LLM，Token 浪费 | 只取最相关片段，精准上下文 |
| 可扩展 | 文件越大越慢 | 向量索引，恒定速度 |
| 语义理解 | 关键词匹配 | 语义相似度（COSINE） |

---

## 什么时候才会读 RustFS？

只有两种场景：

| 场景 | 触发方法 | 位置 |
|------|---------|------|
| **用户下载原始文件** | `downloadKnowledgeBase` | [KnowledgeBaseController.java#L180-L196](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/KnowledgeBaseController.java#L180-L196) |
| **重新向量化** | `revectorize` | [KnowledgeBaseUploadService.java#L160-L178](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseUploadService.java#L160-L178) |

LLM 问答全程不碰 RustFS，这也是 RAG（检索增强生成）的核心价值——**把文档预处理为向量索引，查询时秒级语义检索，而不是每次从头读文件**。