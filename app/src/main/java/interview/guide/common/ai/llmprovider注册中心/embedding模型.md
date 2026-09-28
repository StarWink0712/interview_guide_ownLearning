l

对，完全正确。整个关系可以简化为：

```
        同一厂商 (baseUrl + apiKey)
              │
    ┌─────────┴─────────┐
    ▼                   ▼
 向量模型              聊天模型
 text-embedding-v3     qwen-plus
    │                   │
    ▼                   ▼
 /embeddings        /chat/completions
    │                   │
    ▼                   ▼
 上传时：文本→向量     聊天时：问题→回答
 检索时：问题→向量
    │                   ▲
    └───────────────────┘
          RAG 桥接：
    向量搜到文档 → 喂给 LLM 回答
```

两条管道完全独立，只在 RAG 场景下协作：向量模型负责"找到相关文档"，聊天模型负责"基于文档生成回答"。

## EmbeddingModel 详解

### 什么是 Embedding（向量化）？

EmbeddingModel 的作用是把**文本变成一串数字**（向量），让数学能"理解"语义。

```
"Java 多线程编程"  →  [0.12, -0.34, 0.56, 0.78, ...]  ← 1024 个浮点数
"Java 并发开发"    →  [0.11, -0.32, 0.58, 0.76, ...]  ← 和上面很近（语义相似）
"今天天气很好"     →  [0.87, 0.23, -0.45, ...]         ← 和上面很远（语义不同）
```

语义相近的文本 → 向量在数学空间里距离近；语义无关的 → 距离远。

***

### 在本项目中的作用：知识库 RAG 检索

EmbeddingModel 用于知识库的两大场景：

```
┌─ 场景1：文档入库 ─────────────────────────────┐
│                                                │
│  用户上传 PDF/文档                             │
│      ↓                                         │
│  文档切分成多个文本块                           │
│      ↓                                         │
│  EmbeddingModel 把每个文本块 → 向量            │
│      ↓                                         │
│  向量存入 PostgreSQL (pgvector 插件)           │
│                                                │
└────────────────────────────────────────────────┘

┌─ 场景2：语义搜索 ─────────────────────────────┐
│                                                │
│  用户提问："HashMap 底层原理是什么？"           │
│      ↓                                         │
│  EmbeddingModel 把问题 → 向量                  │
│      ↓                                         │
│  pgvector 余弦相似度搜索，找距离最近的文档块    │
│      ↓                                         │
│  返回相关文档块 → 喂给 LLM → 生成带上下文的回答│
│                                                │
└────────────────────────────────────────────────┘
```

**简单理解**：ChatClient 负责"聊"，EmbeddingModel 负责"搜"。RAG 模式就是先用 Embedding 搜出相关资料，再把资料和问题一起给 ChatClient 回答。

***

### 代码流程解析

对应你选中的代码（634-677 行），一步步来看：

#### 步骤 1：加载配置

```java
ProviderSnapshot config = loadProviderOrThrow(providerId);
```

与 ChatModel 一样，从数据库或配置文件取 baseUrl/apiKey。

#### 步骤 2：校验是否支持 Embedding

```java
if (!config.supportsEmbedding() || isBlank(config.embeddingModel())) {
    throw new BusinessException(...);
}
```

不是所有厂商都提供 Embedding 服务，必须在配置中明确声明 `supportsEmbedding: true` 并填写 `embeddingModel`。

#### 步骤 3：防止配错模型（关键校验）

```java
if (looksLikeChatModel(config.embeddingModel())) {
    // 推荐正确的 Embedding 模型名
    String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId);
    throw new BusinessException(...);
}
```

**这是一个很实用的防错机制**。用户在配 `embeddingModel` 时可能误填聊天模型（如 `qwen-plus`），但聊天模型不能做向量化。

```
❌ embeddingModel: qwen-plus           ← 这是聊天模型，不能用
✅ embeddingModel: text-embedding-v3   ← 这才是向量模型

RECOMMENDED_EMBEDDING_MODELS:
  dashscope → text-embedding-v3
  glm/zhipu → embedding-3
  baidu     → Embedding-V1
  minimax   → embo-01
```

#### 步骤 4：构建 OpenAiApi（与 ChatModel 共享逻辑）

```java
OpenAiApi openAiApi = ApiPathResolver.buildOpenAiApi(config.baseUrl(), config.apiKey());
```

同一个 `baseUrl`，但 EmbeddingModel 走的是 `/embeddings` 端点而非 `/chat/completions`。

#### 步骤 5：配置 Embedding 选项

```java
OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
        .model(config.embeddingModel())      // 如 text-embedding-v3
        .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
        .build();
```

`dimensions` 是向量维度，默认 1024。维度越高语义表达能力越强，但存储和计算成本也越高。

#### 步骤 6：创建 EmbeddingModel

```java
return new OpenAiEmbeddingModel(
        openAiApi,
        MetadataMode.EMBED,       // 元数据处理模式
        options,
        RetryUtils.DEFAULT_RETRY_TEMPLATE,  // 失败重试
        observationRegistry != null ? ...   // 监控指标
);
```

***

### 代理模式：为什么有 LlmEmbeddingConfig？

`LlmProviderRegistry` 返回的 EmbeddingModel 是**动态实例**（可能运行时切换 Provider），无法直接注册为静态 Spring Bean。`LlmEmbeddingConfig` 用匿名类做了一层代理：

```java
@Bean
public EmbeddingModel embeddingModel(LlmProviderRegistry registry) {
    return new EmbeddingModel() {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return registry.getDefaultEmbeddingModel().call(request);  // 每次实时委托
        }
        @Override
        public float[] embed(Document document) {
            return registry.getDefaultEmbeddingModel().embed(document);
        }
    };
}
```

这样 Spring AI 的 VectorStore 等组件通过 `@Autowired EmbeddingModel` 注入时，实际调用的是 Registry 中随时可能变更的实例，Provider 切换后无需重启。

***

### EmbeddingModel vs ChatModel 对比总结

| <br />         | ChatModel           | EmbeddingModel        |
| -------------- | ------------------- | --------------------- |
| **输入**         | 自然语言对话              | 待向量化的文本               |
| **输出**         | 自然语言回复              | 1024 维浮点数数组           |
| **API 端点**     | `/chat/completions` | `/embeddings`         |
| **模型示例**       | `qwen-plus`         | `text-embedding-v3`   |
| **用途**         | 面试对话、出题、评分          | 知识库向量化、语义搜索           |
| **SkillsTool** | 可挂载                 | 不涉及                   |
| **Advisors**   | 可配置                 | 不涉及                   |
| **缓存**         | `clientCache`       | `embeddingModelCache` |

