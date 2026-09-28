## EmbeddingModel 详解

### 📌 什么是 EmbeddingModel？

**EmbeddingModel** 是 Spring AI 提供的接口（或者说是工具），用于将文本转换为**向量**。

**向量** 是一串数字，如 `[0.123, -0.456, 0.789, ...]`，它能在数学空间中表示文本的语义含义。

---

### 🔄 文本向量化的过程

```
┌─────────────────────────────────────────────────────────────────┐
│                        EmbeddingModel                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   输入文本                        输出向量                        │
│   ┌──────────────────┐           ┌─────────────────────────┐   │
│   │ "什么是 Java 多线程" │  ────►  │ [0.12, -0.34, 0.56, ...] │   │
│   └──────────────────┘           │ 共 1024 个浮点数          │   │
│                                  └─────────────────────────┘   │
│                                                                 │
│   "Java 并发编程"      ────►  [0.11, -0.32, 0.58, ...]        │
│   （语义相似，向量也相似）                                        │
│                                                                 │
│   "今天天气很好"        ────►  [0.87, 0.23, -0.45, ...]        │
│   （语义不同，向量差异大）                                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

### 💡 向量的用途：语义相似度搜索

```
┌─────────────────────────────────────────────────────────────────┐
│                     知识库 RAG 流程                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. 文档入库阶段（向量化）                                        │
│  ┌───────────────┐      ┌─────────────┐      ┌──────────────┐  │
│  │ 知识库文档     │ ───► │EmbeddingModel│ ───► │ PostgreSQL   │  │
│  │ "Java 多线程..."│      │  文本→向量   │      │ pgvector 存储│  │
│  └───────────────┘      └─────────────┘      └──────────────┘  │
│                                                                 │
│  2. 查询阶段（相似度搜索）                                        │
│  ┌───────────────┐      ┌─────────────┐      ┌──────────────┐  │
│  │ 用户问题       │ ───► │EmbeddingModel│ ───► │ 向量相似度    │  │
│  │ "如何实现并发" │      │  问题→向量   │      │ 搜索最相似文档 │  │
│  └───────────────┘      └─────────────┘      └──────────────┘  │
│                                                     ↓          │
│                                              ┌──────────────┐  │
│                                              │ 返回相关文档  │  │
│                                              │ "Java 多线程" │  │
│                                              └──────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

### 🎯 为什么需要缓存 EmbeddingModel？

### 原因 1：创建成本高

```java
// 创建 EmbeddingModel 的过程
private EmbeddingModel createEmbeddingModel(String providerId) {
    // 1. 加载配置（可能涉及数据库查询）
    ProviderSnapshot config = loadProviderOrThrow(providerId);

    // 2. 构建 HTTP 客户端
    OpenAiApi openAiApi = ApiPathResolver.buildOpenAiApi(config.baseUrl(), config.apiKey());

    // 3. 配置选项
    OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
        .model(config.embeddingModel())
        .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
        .build();

    // 4. 创建实例（涉及连接池、重试策略等初始化）
    return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options, ...);
}
```

每次创建都会：

- 初始化 HTTP 连接池
- 配置重试策略
- 设置观察注册表
- 可能触发数据库查询

### 原因 2：频繁使用

```
知识库场景调用频率：
┌─────────────────────────────────────────────────────────────┐
│ 文档上传 → 向量化 → 调用 EmbeddingModel                       │
│ 用户提问 → 问题向量化 → 调用 EmbeddingModel                   │
│ 批量导入 → 每个文档都调用 → N 次调用                          │
└─────────────────────────────────────────────────────────────┘

如果不缓存：
每次调用都重新创建 → 性能浪费 → 响应变慢
```

### 原因 3：线程安全

```java
// 使用 ConcurrentHashMap 保证线程安全
private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();

// computeIfAbsent 是原子操作
public EmbeddingModel getEmbeddingModel(String providerId) {
    return embeddingModelCache.computeIfAbsent(providerId, id -> {
        return createEmbeddingModel(id);  // 只在首次访问时创建
    });
}
```

---

### 📊 缓存策略图解

```
┌─────────────────────────────────────────────────────────────────┐
│                     embeddingModelCache                          │
│                    (ConcurrentHashMap)                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Key                Value                                      │
│   ┌────────────┐     ┌─────────────────────────┐               │
│   │ "dashscope"│ ──► │ OpenAiEmbeddingModel    │               │
│   │            │     │ model: text-embedding-v3│               │
│   │            │     │ dimensions: 1024        │               │
│   └────────────┘     └─────────────────────────┘               │
│                                                                 │
│   ┌────────────┐     ┌─────────────────────────┐               │
│   │ "openai"   │ ──► │ OpenAiEmbeddingModel    │               │
│   │            │     │ model: text-embedding-3 │               │
│   │            │     │ dimensions: 1536        │               │
│   └────────────┘     └─────────────────────────┘               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

首次访问 "dashscope"：
  cache 中无 → 创建新实例 → 存入 cache → 返回

再次访问 "dashscope"：
  cache 中有 → 直接返回（跳过创建）
```

---

### 🔄 缓存刷新机制

```java
// 配置变更时清空缓存
public void reload() {
    int size = clientCache.size() + embeddingModelCache.size();
    clientCache.clear();
    embeddingModelCache.clear();  // 清空 Embedding 缓存
    log.info("Cache cleared ({} entries). Next access will re-create clients.", size);
}

// 调用时机：
// 1. 用户通过 API 修改了 Provider 配置
// 2. 切换了默认 Embedding Provider
// 3. 手动触发配置刷新
```

---

### 📝 实际使用示例

```java
@Service
public class KnowledgeBaseService {

    private final LlmProviderRegistry registry;

    // 文档向量化
    public void vectorizeDocument(String content) {
        EmbeddingModel embeddingModel = registry.getDefaultEmbeddingModel();

        // 调用 EmbeddingModel 将文本转为向量
        EmbeddingResponse response = embeddingModel.embedForResponse(
            List.of(content)
        );

        float[] vector = response.getResult().getOutput();  // 获取向量
        // 存储到 pgvector...
    }

    // 相似度搜索
    public List<Document> searchSimilar(String query) {
        EmbeddingModel embeddingModel = registry.getDefaultEmbeddingModel();

        // 将查询文本转为向量
        float[] queryVector = embeddingModel.embed(query);

        // 在数据库中搜索相似向量
        return documentRepository.findSimilar(queryVector, 10);
    }
}
```

---

### 💡 总结

| 问题 | 答案 |
| --- | --- |
| **EmbeddingModel 是什么？** | 将文本转换为向量的模型，用于语义搜索和 RAG |
| **向量有什么用？** | 在数学空间中表示文本语义，相似文本的向量距离近 |
| **为什么缓存？** | 避免重复创建、提高性能、保证线程安全 |
| **何时刷新缓存？** | Provider 配置变更时调用 `reload()` |
| **用什么数据结构？** | `ConcurrentHashMap` 保证并发安全 |

简单来说，**EmbeddingModel 是知识库功能的基础**，缓存是为了避免重复创建昂贵的对象，提升系统性能。