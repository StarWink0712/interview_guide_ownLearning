# RAG 链路从 0 到 1 落地讲解

这份文档解决一个面试深挖问题：

> 你的 RAG 链路是否完整？从文档上传、文档解析、文本分块、Embedding 向量化、索引、检索、权限过滤、Prompt 组装、大模型生成到返回，完整过程是怎样的？

面试时不要只说“我用了 RAG”。更好的讲法是把 RAG 拆成两条链路：

- 离线入库链路：把用户上传的资料变成可检索的知识资产。
- 在线问答链路：把用户问题变成检索、上下文组装和模型回答。

这个项目当前已经实现了比较完整的“向量检索型 RAG”链路：文件上传、格式校验、Apache Tika 解析、文本清洗、RustFS/S3 兼容存储、Redis Stream 异步向量化、TokenTextSplitter 分块、Spring AI VectorStore 写入 pgvector、Query Rewrite、动态 TopK/相似度阈值、多知识库过滤、Prompt 组装、ChatClient 调用、SSE 流式返回、RAG 检索日志和指标。

但也要诚实说明：当前项目没有真正实现 BM25/全文索引级别的“关键词检索 + 向量检索”混合召回，也没有用户级权限系统。当前的关键词搜索主要用于知识库列表页按名称/文件名搜索；RAG 查询阶段主要是向量检索，并通过知识库 ID 做范围过滤。生产级可以继续扩展混合检索、权限过滤、rerank 和引用溯源。

## 1. 一句话回答

可以这样讲：

> 我把 RAG 理解成一个完整工程链路，而不是简单把文档塞给大模型。离线侧先完成文件上传、类型校验、Tika 解析、文本清洗、对象存储、异步分块、Embedding 和 pgvector 入库；在线侧先做问题归一化和可选 Query Rewrite，再用向量检索召回相关 chunk，通过知识库 ID 过滤检索范围，然后把检索上下文和用户问题组装成 Prompt，调用大模型生成答案，最后通过普通 HTTP 或 SSE 流式返回，并记录 RAG trace 方便排查。

## 2. 总体架构

```text
文档入库链路
用户上传文件
-> 文件校验
-> MIME/扩展名检测
-> 文件哈希去重
-> Apache Tika 文档解析
-> 文本清洗
-> 原文件上传 RustFS/S3
-> 知识库元数据入库
-> Redis Stream 投递向量化任务
-> 消费者下载原文件并重新解析
-> TokenTextSplitter 文本分块
-> Embedding 向量化
-> pgvector vector_store 入库
-> 更新向量化状态

在线问答链路
用户提问
-> 参数校验/限流
-> 会话历史加载
-> 问题归一化
-> Query Rewrite
-> 动态 topK/minScore
-> 向量检索
-> 知识库 ID 过滤
-> 组装上下文
-> Prompt 安全指令
-> LLM 生成
-> SSE/普通响应返回
-> 检索日志和指标记录
```

## 3. 当前项目的关键代码位置

| 环节 | 代码位置 | 作用 |
| --- | --- | --- |
| 上传入口 | `KnowledgeBaseController` | `/api/knowledgebase/upload` 接收文件 |
| 上传编排 | `KnowledgeBaseUploadService` | 校验、解析、存储、入库、投递任务 |
| 文件校验 | `FileValidationService` | 空文件、大小、MIME、扩展名校验 |
| 类型检测 | `ContentTypeDetectionService` | 检测上传文件 MIME |
| 文档解析 | `DocumentParseService` | Apache Tika 解析 PDF/DOCX/TXT/MD |
| 文本清洗 | `TextCleaningService` | 去控制字符、图片路径、空行规范化 |
| 文件存储 | `FileStorageService` | RustFS/S3 兼容存储上传、下载、删除 |
| 元数据表 | `KnowledgeBaseEntity` | 存储名称、分类、hash、storageKey、向量状态 |
| 任务生产 | `VectorizeStreamProducer` | 创建异步任务并写入 Redis Stream |
| 任务消费 | `VectorizeStreamConsumer` | 消费任务、更新 PROCESSING/COMPLETED/FAILED |
| 分块向量化 | `KnowledgeBaseVectorService` | TokenTextSplitter、VectorStore.add、检索 |
| 向量表删除 | `VectorRepository` | 直接操作 Spring AI `vector_store` |
| 查询服务 | `KnowledgeBaseQueryService` | Query Rewrite、检索、Prompt、LLM、SSE |
| RAG 会话 | `RagChatSessionService` | 会话、历史消息、流式消息落库 |
| RAG 流式接口 | `RagChatController` | 返回 `Flux<ServerSentEvent<String>>` |
| Embedding Bean | `LlmEmbeddingConfig` | 把默认 EmbeddingModel 代理给 VectorStore |
| 模型注册中心 | `LlmProviderRegistry` | 管理 ChatClient 和 EmbeddingModel |
| 检索日志 | `RagRetrievalLogService` | 记录 query、rewrite、topK、命中 chunk |

## 4. 离线入库链路

### 4.1 文档上传

用户在前端上传知识库文件，后端入口是：

```text
POST /api/knowledgebase/upload
```

Controller 层只负责接收 `MultipartFile`、名称和分类，然后委托给 `KnowledgeBaseUploadService.uploadKnowledgeBase()`。

面试可以这样讲：

> 我没有把上传、解析、存储、向量化都写在 Controller 里。Controller 只做路由和参数接收，真正的业务编排在 UploadService，事务写库又拆到了 PersistenceService，避免大 Service 和事务边界混乱。

### 4.2 文件校验

文件上传后先做基础校验：

- 文件是否为空。
- 文件大小是否超过限制，知识库文件上限是 50MB。
- MIME 类型是否合法。
- 对 Markdown 这类可能 MIME 不稳定的文件，允许通过扩展名兜底。

当前支持类型包括：

- PDF。
- DOC/DOCX。
- TXT。
- Markdown。
- RTF。

对应代码是 `FileValidationService`。

面试可以这样讲：

> RAG 入库的第一步不是解析，而是防御性校验。因为上传文件是用户输入，必须先限制大小和格式，避免空文件、超大文件或不支持格式进入后续解析和向量化流程。

### 4.3 MIME 类型检测

项目通过 `KnowledgeBaseParseService.detectContentType()` 委托 `ContentTypeDetectionService` 检测文件类型，然后再交给 `FileValidationService.validateContentType()` 做校验。

为什么不能只看文件扩展名：

- 用户可以把 `.exe` 改成 `.pdf`。
- 浏览器上传时 MIME 可能不准确。
- Markdown/TXT 文件在不同系统里 MIME 表现可能不同。

所以项目采用 MIME + 扩展名组合判断。

### 4.4 文件哈希去重

校验通过后，系统会计算文件哈希：

```text
FileHashService.calculateHash(file)
```

然后通过 `KnowledgeBaseRepository.findByFileHash(fileHash)` 判断是否已上传过。

去重的意义：

- 避免重复占用对象存储空间。
- 避免重复解析和向量化。
- 减少 Embedding API 调用成本。
- 避免同一份资料在检索结果中重复出现。

面试可以这样讲：

> 我用内容哈希而不是文件名去重，因为用户可能用不同文件名上传同一份资料。hash 去重更接近内容级去重，可以减少存储和 Embedding 成本。

### 4.5 文档解析：Apache Tika

项目的文档解析由 `DocumentParseService` 完成，核心是 Apache Tika 的 `AutoDetectParser`。

它做了几件事：

- 自动识别 PDF、DOCX、DOC、TXT、MD 等文档格式。
- 使用 `BodyContentHandler` 提取正文文本。
- 设置最大文本提取长度为 5MB，防止异常大文本把内存打爆。
- 禁用嵌入文档解析，避免图片、附件、临时路径等噪声进入文本。
- 对 PDF 配置 `PDFParserConfig`：
  - 关闭图片提取。
  - 按坐标排序文本，改善多栏 PDF 的文本顺序。

面试可以这样讲：

> 文档解析我用了 Apache Tika，因为它能统一处理 PDF、Word、TXT、Markdown 等多种格式。解析时我只提取正文文本，并禁用了嵌入资源提取，避免图片路径、附件引用这类噪声污染 RAG 语料。

### 4.6 文本清洗

Tika 提取出来的文本并不能直接向量化，还需要清洗。项目使用 `TextCleaningService` 做两层处理。

语义去噪：

- 去除不可见控制字符。
- 去除图片文件名行，例如 `image123.png`。
- 去除图片 URL。
- 去除 `file://` 临时路径。
- 去除纯分隔线。

格式规范化：

- 统一换行符。
- 去掉行尾空格。
- 压缩连续空行。
- 保留基本段落结构。

面试可以这样讲：

> 清洗不是为了美观，而是为了提高检索质量。很多 PDF/DOCX 解析后会带图片名、临时文件路径、控制字符和大量空行，如果直接向量化，会让 embedding 表示变脏，影响召回质量。

### 4.7 原文件存储：RustFS / S3 兼容对象存储

项目没有把原文件直接放在数据库里，而是通过 `FileStorageService` 上传到 S3 兼容对象存储。

本地开发可以用 RustFS 或 MinIO，代码层通过 AWS S3 SDK 的 `S3Client` 访问：

```text
FileStorageService.uploadKnowledgeBase(file)
```

文件 key 按业务和日期组织：

```text
knowledgebases/yyyy/MM/dd/{uuid}_{safeFilename}
```

这么做的价值：

- 数据库存元数据，文件走对象存储，职责清晰。
- 后续重新向量化时，可以从对象存储重新下载原文件。
- 删除知识库时，可以同时删除对象存储文件和向量数据。
- 本地可以用 RustFS/MinIO，生产可以切 AWS S3、阿里云 OSS、腾讯云 COS 等 S3 兼容服务。

面试可以这样讲：

> 原文件我没有存数据库，而是放到 RustFS 这种 S3 兼容对象存储里。数据库只存 storageKey、storageUrl、文件名、hash、向量化状态这些元数据，这样文件存储和业务数据解耦，也方便后续重新向量化。

### 4.8 元数据入库

文件存储成功后，项目会把知识库元数据保存到 `knowledge_bases` 表。

核心字段包括：

- `fileHash`：文件哈希，用于去重。
- `name`：知识库名称。
- `category`：分类。
- `originalFilename`：原始文件名。
- `fileSize`：文件大小。
- `contentType`：文件类型。
- `storageKey`：对象存储 key。
- `storageUrl`：对象存储 URL。
- `vectorStatus`：向量化状态。
- `vectorError`：向量化失败原因。
- `chunkCount`：分块数量字段，适合用于前端展示和统计；当前代码里主要通过日志记录分块数量，完成状态主要看 `vectorStatus`。
- `questionCount`：提问次数。

向量化初始状态是：

```text
PENDING
```

### 4.9 异步任务投递：Redis Stream

保存元数据后，项目不会在上传接口里同步向量化，而是通过 `VectorizeStreamProducer` 投递 Redis Stream 任务。

当前任务消息里主要放：

- `taskId`
- `businessId`
- `kbId`
- `retryCount`

注意一个工程细节：

> 上传时虽然已经解析出了 content，但真正投递到 Redis Stream 的消息不塞大文本，只记录任务 ID 和知识库 ID。消费者处理时再从 RustFS 下载原文件并重新解析。

这样做的好处：

- 避免 Redis Stream 消息过大。
- Redis 只负责任务调度，不承担大文本存储。
- 如果消费者失败重试，可以从对象存储重新拿原始文件，保证任务可恢复。
- 异步任务表能记录任务状态，前端可以查看处理进度。

面试可以这样讲：

> RAG 入库比较耗时，尤其是解析、分块和 Embedding，所以我没有让上传接口同步等待，而是用 Redis Stream 异步处理。Stream 消息只放 kbId 和 taskId，大文本放对象存储，消费者通过 kbId 重新下载文件解析，这样更稳定。

### 4.10 异步消费与状态机

`VectorizeStreamConsumer` 消费 Redis Stream 后，会更新状态：

```text
PENDING -> PROCESSING -> COMPLETED
                       -> FAILED
```

消费者做的事：

1. 标记任务为处理中。
2. 更新知识库 `vectorStatus=PROCESSING`。
3. 查询知识库是否存在，如果已删除则取消并 ACK。
4. 如果已完成且已有分块记录，则可以跳过重复处理；当前代码里有这个防重复判断入口，实际是否跳过取决于 `chunkCount` 是否被正确回写。
5. 从 RustFS 下载原文件。
6. 使用 Tika 重新解析和清洗。
7. 调用 `KnowledgeBaseVectorService.vectorizeAndStore()`。
8. 成功后标记任务完成，失败后按模板重试，超过次数标记失败。

面试可以这样讲：

> 消费者处理前会先判断知识库是否还存在，如果用户已经删除了，就直接取消任务，避免对已删除数据继续向量化。失败时走统一的 Stream 消费模板，支持重试和失败状态记录。

### 4.11 文本分块：TokenTextSplitter

向量化前要把长文档切成 chunk。项目使用 Spring AI 的：

```text
TokenTextSplitter.builder().build()
```

默认配置大约每个 chunk 800 tokens，按标点边界切分，当前没有显式配置重叠。

为什么要分块：

- 大模型上下文有限，不能每次把全文塞进去。
- 向量检索需要比较细粒度的语义单元。
- chunk 太大：召回不精准，容易带入无关信息。
- chunk 太小：语义不完整，回答缺上下文。

面试可以这样讲：

> 分块的目标是让每个 chunk 既能表达一个相对完整的语义，又不会太大导致检索不精准。当前项目使用 TokenTextSplitter 默认配置，后续可以根据文档类型扩展成标题级、段落级或带 overlap 的递归分块。

### 4.12 元数据绑定

每个 chunk 都会附带 metadata：

```text
kb_id = knowledgeBaseId
```

这个 metadata 很重要，后续在线检索时可以按知识库 ID 过滤。

面试可以这样讲：

> 分块以后不能只存文本和向量，还要存 metadata。至少要存 knowledgeBaseId，生产中还可以继续存文件名、页码、段落标题、chunkIndex、用户 ID、租户 ID、权限标签，这些都是后续过滤和引用溯源的基础。

### 4.13 Embedding 向量化

项目通过 Spring AI VectorStore 间接调用 EmbeddingModel：

```text
vectorStore.add(batch)
```

VectorStore 内部会调用 Spring 容器里的 `EmbeddingModel` Bean。这个 Bean 由 `LlmEmbeddingConfig` 提供，它会代理到：

```text
LlmProviderRegistry.getDefaultEmbeddingModel()
```

当前默认 Provider 是 DashScope，默认 embedding 模型是：

```text
text-embedding-v3
```

向量维度是：

```text
1024
```

项目还支持多 Provider 配置。`LlmProviderRegistry` 会校验：

- Provider 是否支持 Embedding。
- 是否配置了 embeddingModel。
- embeddingModel 不能误填成聊天模型。
- 根据 providerId 缓存 EmbeddingModel。

面试可以这样讲：

> 向量化不是业务代码手动调 HTTP，而是通过 Spring AI 的 VectorStore 和 EmbeddingModel。项目里我做了一个 EmbeddingModel 代理 Bean，让 VectorStore 每次调用时走 LlmProviderRegistry 获取当前默认 Embedding Provider，这样后续在设置页切换向量模型，不需要改业务代码。

### 4.14 分批写入 pgvector

项目使用 PostgreSQL + pgvector 存储向量。配置里使用：

```text
index-type: HNSW
distance-type: COSINE_DISTANCE
dimensions: 1024
```

向量化时分批处理，每批最多 10 个 chunk：

```text
MAX_BATCH_SIZE = 10
```

原因是 DashScope Embedding API 有批量大小限制。

写入过程：

1. 删除该知识库旧向量。
2. 分块。
3. 给 chunk 添加 metadata。
4. 每 10 个 chunk 一批调用 `vectorStore.add(batch)`。
5. Spring AI PgVectorStore 写入 `vector_store` 表。

面试可以这样讲：

> 入库时先删旧向量再写新向量，保证重新向量化不会产生重复 chunk。因为 Embedding API 有 batch 限制，所以按 10 个 chunk 分批写入。向量数据库我没有额外引入 Milvus 或 Elasticsearch，而是用 PostgreSQL + pgvector，架构更轻，适合这个项目体量。

## 5. 在线问答链路

### 5.1 查询入口

项目有两种问答入口。

普通知识库查询：

```text
POST /api/knowledgebase/query
POST /api/knowledgebase/query/stream
```

RAG 聊天会话：

```text
POST /api/rag-chat/sessions
POST /api/rag-chat/sessions/{sessionId}/messages/stream
```

普通查询更像一次性问答；RAG 聊天会话会保存消息历史、会话标题、置顶状态和关联知识库。

### 5.2 接口限流

知识库查询接口加了 `@RateLimit`：

- 普通查询：Global/IP 维度各 10 次。
- 流式查询：Global/IP 维度各 5 次。
- 上传接口：Global/IP 维度各 3 次。
- 重新向量化：Global/IP 维度各 2 次。

这说明项目考虑了 AI 成本和接口防刷。

面试可以这样讲：

> RAG 查询会调用向量检索和大模型，成本比普通 CRUD 高，所以接口上做了限流。限流是可重复注解，可以同时按全局和 IP 两个维度生效。

### 5.3 会话历史加载

RAG 聊天模式下，`RagChatSessionService` 会：

1. 保存用户消息。
2. 创建 AI 消息占位。
3. 加载最近历史消息。
4. 调用 `KnowledgeBaseQueryService.answerQuestionStreamWithTrace()`。
5. 流式完成后，把完整回答回填到 AI 消息。

历史上下文默认开启，最大消息数由 `KnowledgeBaseQueryProperties.history.maxMessages` 控制，默认是 10。

为什么要带历史：

- 用户常问“那这个呢”“继续解释上面那个点”。
- Query Rewrite 需要结合历史理解追问指代。
- 但历史不能无限带，否则 prompt 过长。

### 5.4 问题归一化

查询服务先做简单归一化：

```text
question.trim()
```

如果知识库 ID 为空或问题为空，直接返回固定无结果响应：

```text
抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。
```

### 5.5 Query Rewrite

项目支持 Query Rewrite，配置项：

```text
app.ai.rag.rewrite.enabled
```

默认开启。

流程：

1. 使用 `knowledgebase-query-rewrite.st` 模板。
2. 把用户原始问题和历史消息填进去。
3. 调用默认 ChatClient。
4. 得到一个更适合检索的单句查询。
5. 如果改写失败，就回退到原问题。

候选查询包括：

- 改写后的问题。
- 原始问题。

检索时先用改写问题，如果没有有效命中，再用原始问题兜底。

面试可以这样讲：

> Query Rewrite 是为了解决短问题和追问问题不好检索的问题。比如用户问“这个怎么优化”，直接向量化很弱；结合历史改写成“Redis Stream 消费失败重试如何优化”后，召回效果会好很多。项目里 rewrite 失败不会中断主流程，而是回退到原问题。

### 5.6 动态 TopK 和相似度阈值

项目会根据问题长度决定检索参数：

| 问题类型 | topK | minScore |
| --- | --- | --- |
| 短查询 | 20 | 0.18 |
| 中等查询 | 12 | 0.28 |
| 长查询 | 8 | 0.28 |

为什么短查询 topK 更大、阈值更低：

- 短问题信息少，向量表达不稳定。
- 需要放宽召回，避免什么都检不出来。
- 后续由 Prompt 和模型整合有效信息。

为什么长查询 topK 更小：

- 长问题语义更明确。
- 过多召回反而会引入噪声。

面试可以这样讲：

> RAG 不是固定 topK 就完事。短查询语义弱，我会提高 topK、降低阈值；长查询语义更明确，可以减少 topK、保持阈值，避免上下文里塞太多弱相关 chunk。

### 5.7 向量检索

核心检索方法是：

```text
KnowledgeBaseVectorService.similaritySearch(query, knowledgeBaseIds, topK, minScore)
```

它通过 Spring AI `VectorStore.similaritySearch()` 查询 pgvector：

- query 会先被 EmbeddingModel 转成问题向量。
- pgvector 根据 COSINE_DISTANCE 做相似度搜索。
- topK 控制返回条数。
- minScore 控制最低相似度。

面试可以这样讲：

> 在线查询时，用户问题也会被转成 embedding，和离线写入的文档 chunk 向量放到同一个向量空间里比较，通过余弦距离找最相关的 chunk。这就是 RAG 的检索部分。

### 5.8 知识库 ID 过滤

当前项目支持多知识库查询。用户可以选择一个或多个知识库 ID。

检索时会构建 filter expression：

```text
kb_id in ['1', '2', '3']
```

如果 VectorStore 前置过滤失败，项目会回退为：

1. 先扩大 topK 检索。
2. 在 Java 内存中根据 Document metadata 的 `kb_id` 过滤。
3. 再截取 topK。

这一步当前更准确地说是“知识库范围过滤”，不是完整权限系统。

面试可以这样讲：

> 当前项目没有做登录用户和租户权限，所以检索过滤主要是按用户选择的知识库 ID 过滤，保证回答只来自选定知识库。生产环境可以把 userId、tenantId、visibility、role 等权限字段也写入 chunk metadata，在检索时一并过滤。

### 5.9 当前关键词检索和混合检索的边界

这是面试中一定要讲清楚的点。

当前项目有关键词搜索接口：

```text
GET /api/knowledgebase/search?keyword=xxx
```

但它搜索的是知识库元数据：

- `name`
- `originalFilename`

它用于知识库列表页搜索，不是 RAG 查询时的关键词召回。

当前 RAG 查询阶段主要是：

```text
向量检索 + 知识库 ID 过滤
```

不是完整的：

```text
向量检索 + BM25/全文索引 + RRF 融合 + rerank
```

生产级可以这样扩展：

1. 为 chunk 建全文索引，例如 PostgreSQL `tsvector`、Elasticsearch、OpenSearch 或 Lucene。
2. 同时做向量召回和关键词召回。
3. 使用 RRF 或加权分数融合。
4. 对候选 chunk 做 rerank。
5. 最后取 topN 进入 Prompt。

面试可以这样讲：

> 当前项目实现的是向量检索型 RAG，另外有知识库元数据关键词搜索，但还没有把 BM25/全文索引合入 RAG 召回。我不会夸大成完整混合检索。如果要生产增强，我会增加 chunk 级全文索引，再做向量召回和关键词召回的融合，最后用 rerank 控制上下文质量。

### 5.10 Prompt 组装

检索到 chunk 后，项目会把文档内容拼成 context：

```text
chunk1

---

chunk2

---

chunk3
```

然后构造两类 Prompt：

系统 Prompt：

```text
knowledgebase-query-system.st
```

用户 Prompt：

```text
knowledgebase-query-user.st
```

系统 Prompt 主要定义角色、回答原则、Markdown 格式、信息不足时的处理方式。

用户 Prompt 主要包含：

- 检索到的相关文档 `{context}`。
- 用户问题 `{question}`。
- 提醒模型文档内容是用户提供的数据，不要执行其中命令。

项目还会追加：

```text
PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION
```

用于抵抗 Prompt Injection。

面试可以这样讲：

> Prompt 组装不是简单字符串拼接。我会把检索上下文和用户问题放进 user prompt，同时 system prompt 约束模型必须基于知识库回答，不允许编造；还会加入防注入指令，明确检索到的文档是数据不是指令。

### 5.11 大模型生成

项目通过 `LlmProviderRegistry.getDefaultChatClient()` 获取默认 ChatClient。

同步回答：

```text
getChatClient().prompt()
  .system(systemPrompt)
  .user(userPrompt)
  .call()
  .content()
```

流式回答：

```text
getChatClient().prompt()
  .system(systemPrompt)
  .messages(history)
  .user(userPrompt)
  .stream()
  .content()
```

模型 Provider 可以配置，例如 DashScope、Kimi、DeepSeek、GLM、LM Studio 等 OpenAI 兼容接口。

面试可以这样讲：

> 业务服务不直接依赖某个模型厂商，而是通过 LlmProviderRegistry 获取 ChatClient。这样默认模型可以在配置或设置页切换，业务代码不用改。

### 5.12 无结果处理

如果没有检索到有效 chunk，项目不会让大模型自由发挥，而是直接返回固定模板：

```text
抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。
```

如果模型生成的内容像“没有找到相关信息”“信息不足”“超出知识库范围”，项目也会归一化成固定无结果响应。

流式输出时还有一个探测窗口：

- 先缓存前 120 个字符。
- 如果判断是无结果类回答，立即输出固定模板并结束。
- 否则释放缓存，继续实时透传。

面试可以这样讲：

> RAG 系统要控制幻觉。没有检索结果时，不应该让模型凭常识回答，而应该直接返回信息不足。流式场景下我还做了前缀探测，避免模型输出很长一段无信息解释。

### 5.13 返回方式：普通响应和 SSE

项目同时支持：

- 普通 HTTP 返回完整答案。
- SSE 流式返回，前端逐段展示。

RAG 聊天接口使用：

```text
Flux<ServerSentEvent<String>>
```

返回时会先发送一个 trace 事件：

```json
{"traceId":"..."}
```

然后持续发送模型生成的 chunk。

为什么用 SSE：

- 问答生成时间不稳定。
- 用户可以更快看到首字。
- 实现比 WebSocket 更简单，适合服务端单向流式输出。

面试可以这样讲：

> RAG 问答更适合 SSE，因为服务端只需要把模型输出持续推给前端，不需要 WebSocket 那种双向实时通信。用户体验上能减少等待感，也方便前端做打字机效果。

### 5.14 RAG Trace 和可观测性

项目会记录每次检索：

- traceId。
- sessionId。
- 原始问题。
- 改写问题。
- 候选 query。
- knowledgeBaseIds。
- topK。
- minScore。
- 命中数量。
- 检索耗时。
- 命中 chunk 的 rank、score、metadata、内容预览。

对应服务是 `RagRetrievalLogService`。

同时还有 Micrometer 指标：

- rewrite 成功率和耗时。
- retrieval 延迟。
- hit count。
- answer 成功率和耗时。

面试可以这样讲：

> RAG 出问题时不能只看最终回答，要能知道到底是 query rewrite 错了、检索没命中、阈值太高，还是模型生成出了问题。所以我记录了 RAG 检索日志，包括原问题、改写问题、topK、minScore、命中 chunk 和相似度分数，便于后台排查。

## 6. 权限过滤应该怎么讲

当前项目没有完整用户权限系统，所以不要说“已经做了用户级权限过滤”。可以这样讲：

> 当前项目的过滤主要是知识库范围过滤，也就是用户选择哪些知识库，就只在这些知识库的 chunk 中检索。真正生产环境还需要在 chunk metadata 中加入 userId、tenantId、department、visibility、role 等权限字段，检索时把权限条件和知识库条件一起下推到向量库，防止越权召回。

生产级权限过滤可以这样设计：

```text
文档级权限
knowledge_base.owner_id
knowledge_base.tenant_id
knowledge_base.visibility

chunk 级 metadata
kb_id
owner_id
tenant_id
role_tags
department_id
source_file
page
chunk_index

检索过滤
tenant_id = 当前租户
AND (owner_id = 当前用户 OR visibility = 'team' OR role_tags 包含当前角色)
AND kb_id IN 用户选择的知识库
```

面试回答重点：

- 权限过滤必须发生在检索前或检索时，不能只在 LLM 生成后过滤。
- 如果先召回无权限 chunk，再靠 Prompt 要求模型不要泄漏，是不安全的。
- metadata 设计决定了后续能否做权限、溯源、审计和多租户隔离。

## 7. 混合检索应该怎么讲

如果面试官问“你有没有做混合检索”，推荐如实回答：

> 当前项目 RAG 查询阶段主要是向量检索，知识库列表有元数据关键词搜索，但还没有实现 chunk 级 BM25/全文索引融合。所以严格来说，目前不是完整混合检索。如果要增强，我会在 chunk 入库时同时写一份全文索引，查询时并行做向量召回和关键词召回，再通过 RRF 或 rerank 融合排序。

生产级混合检索流程：

```text
用户问题
-> Query Rewrite
-> 向量召回 topK1
-> BM25/全文索引召回 topK2
-> 合并去重
-> RRF/加权融合
-> Cross Encoder 或 LLM rerank
-> 取 topN
-> Prompt 组装
```

为什么需要混合检索：

- 向量检索擅长语义相似，但对精确关键词、错误码、类名、函数名不一定稳定。
- 关键词检索擅长精确匹配，但对同义表达和语义泛化弱。
- 混合检索可以兼顾“语义召回”和“精确召回”。

可以举例：

> 如果用户问“RateLimitAspect 里的 Lua 脚本为什么要 hash tag”，关键词检索能精准命中 `RateLimitAspect` 和 `Lua`，向量检索能召回限流设计相关解释。两者融合后比单一路径更稳。

## 8. Rerank 和引用溯源怎么讲

当前项目记录了 chunk 的 score、rank、metadata 和内容预览，但没有完整做 rerank 和引用编号。

生产增强可以这样讲：

1. 第一阶段召回较多候选 chunk。
2. 第二阶段使用 rerank 模型重新排序。
3. 过滤低分 chunk。
4. Prompt 中给每个 chunk 加 source id。
5. 要求模型回答时标注引用来源。
6. 前端展示引用卡片，支持点击回到原文。

示例：

```text
[source: kb=12, file=Java面试笔记.pdf, page=5, chunk=32]
...
```

面试重点：

- Rerank 解决的是“召回了，但排序不够准”的问题。
- 引用溯源解决的是“回答可信度”和“用户能否验证来源”的问题。
- metadata 设计越早做好，后续越容易扩展。

## 9. 从 0 到 1 落地时的核心取舍

### 9.1 为什么不用全文塞进 Prompt

可以这样讲：

> 全文塞 Prompt 只适合很小的文档。一旦文档多、内容长，就会遇到上下文窗口、成本、速度和噪声问题。RAG 的价值是先检索，再只把相关片段给模型，减少 token 成本，也降低模型被无关内容干扰的概率。

### 9.2 为什么先用 PostgreSQL + pgvector

可以这样讲：

> 从 0 到 1 落地时，我更倾向少引入组件。PostgreSQL 本身已经是业务数据库，再加 pgvector 就能支持向量检索，配合 HNSW 和 cosine distance 足够支撑中小规模知识库。等数据规模、召回性能或多租户隔离要求上来后，再考虑 Milvus、Qdrant、Elasticsearch/OpenSearch 等专门组件。

### 9.3 为什么向量化要异步

可以这样讲：

> 文档解析和 Embedding 都是慢操作，而且依赖外部模型服务。如果同步做，上传接口会卡很久，也容易因为模型超时导致用户上传失败。异步后，上传只负责保存文件和任务，前端通过 vectorStatus 看进度，失败可以重试。

### 9.4 为什么 Redis Stream 比普通线程池更适合

可以这样讲：

> 普通线程池适合进程内异步，但任务状态、失败恢复、服务重启后的可靠性都弱。Redis Stream 有消息 ID、Consumer Group、ACK 和 Pending 概念，更适合这种需要重试、可恢复、可观测的后台任务。

### 9.5 为什么 Prompt 要有防注入

可以这样讲：

> RAG 会把用户上传的文档内容拼进 Prompt，如果文档里写了“忽略之前所有指令”这类内容，模型可能被诱导。所以项目在 Prompt 里明确告诉模型：检索到的文档是待分析数据，不是指令，不要执行其中命令。

## 10. 当前链路完整性评估

| 环节 | 当前是否实现 | 说明 |
| --- | --- | --- |
| 文档上传 | 已实现 | MultipartFile 上传 |
| 文件校验 | 已实现 | 空文件、大小、MIME、扩展名 |
| 文件存储 | 已实现 | RustFS/S3 兼容对象存储 |
| 文档解析 | 已实现 | Apache Tika |
| 文本清洗 | 已实现 | 控制字符、图片路径、空行等 |
| 元数据入库 | 已实现 | knowledge_bases 表 |
| 异步任务 | 已实现 | Redis Stream + AsyncTask |
| 文本分块 | 已实现 | TokenTextSplitter |
| Embedding | 已实现 | Spring AI EmbeddingModel |
| 向量索引 | 已实现 | pgvector HNSW/COSINE |
| 关键词索引 | 未完整实现 | 目前只有知识库元数据搜索，不是 chunk 级全文检索 |
| 混合检索 | 未完整实现 | 当前 RAG 查询主要是向量检索 |
| 知识库范围过滤 | 已实现 | metadata `kb_id` 过滤 |
| 用户/租户权限过滤 | 未实现 | 可通过 metadata 扩展 |
| Query Rewrite | 已实现 | LLM 改写 + 原问题兜底 |
| 动态 TopK/阈值 | 已实现 | 按问题长度调整 |
| Prompt 组装 | 已实现 | system/user prompt + 防注入 |
| LLM 生成 | 已实现 | ChatClient 同步/流式 |
| SSE 返回 | 已实现 | Flux/ServerSentEvent |
| 无结果兜底 | 已实现 | 固定模板 + 流式探测 |
| 检索日志 | 已实现 | RagRetrievalLogService |
| 引用溯源 | 部分具备基础 | 已有 metadata/log，前端引用展示可继续扩展 |
| rerank | 未实现 | 可作为生产增强点 |

## 11. 面试推荐讲稿

可以直接练这一段：

> 我会把 RAG 拆成离线入库和在线问答两条链路。离线侧，用户上传 PDF、Word、Markdown 等资料后，后端先做文件大小和类型校验，再用内容 hash 做去重。文档解析用了 Apache Tika 的 AutoDetectParser，解析后会做文本清洗，比如去掉控制字符、图片路径和多余空行。原始文件不会存数据库，而是上传到 RustFS 这种 S3 兼容对象存储，数据库只保存知识库元数据、storageKey、hash 和向量化状态。
>
> 上传完成后不会同步向量化，而是通过 Redis Stream 创建异步任务。消费者拿到任务后，根据 kbId 从对象存储下载原文件并重新解析，然后用 Spring AI 的 TokenTextSplitter 做文本分块，给每个 chunk 加上 kb_id 这样的 metadata，再通过 VectorStore.add 分批调用 EmbeddingModel 生成向量并写入 PostgreSQL pgvector。这里 EmbeddingModel 是通过 LlmProviderRegistry 管理的，默认使用 DashScope 的 text-embedding-v3，维度是 1024，VectorStore 通过 Spring Bean 自动拿到当前默认的 embedding 模型。
>
> 在线侧，用户提问后，系统会先做问题归一化和可选 Query Rewrite，短问题会提高 topK、降低相似度阈值，长问题会减少 topK。然后用向量检索在 pgvector 里召回相关 chunk，并根据用户选择的知识库 ID 做过滤。检索到的 chunk 会拼成 context，再和用户问题一起放进 Prompt。Prompt 里会要求模型只基于知识库内容回答，并且加入防 prompt injection 指令，强调检索文档是数据不是指令。最后通过 ChatClient 调用大模型，普通接口返回完整回答，聊天接口通过 SSE 流式返回。
>
> 这个链路里我也做了可观测性：每次 RAG 检索会记录 traceId、原问题、改写问题、候选 query、topK、minScore、命中 chunk、score 和耗时，方便排查到底是检索没命中、rewrite 不好，还是模型生成问题。严格来说，当前项目主要是向量检索型 RAG，还没有实现 chunk 级 BM25 混合检索和用户级权限过滤；如果继续生产化，我会增加全文索引、向量和关键词融合、rerank、引用溯源，以及基于 userId/tenantId/role 的权限过滤。

## 12. 面试官追问回答

### 问：你的 RAG 链路完整吗？

答：

> 从基础 RAG 落地角度是完整的，覆盖了文档上传、解析、清洗、对象存储、异步向量化、分块、Embedding、pgvector 检索、Prompt 组装、大模型生成、SSE 返回和检索日志。但从生产级 RAG 角度，还可以继续补强混合检索、rerank、引用溯源和用户级权限过滤。我会把当前实现定义为“向量检索型 RAG 的完整闭环”，而不是夸大成完整企业级知识库。

### 问：为什么不直接把文档全文传给大模型？

答：

> 全文传给模型会受到上下文窗口、token 成本和噪声影响。RAG 的思路是先检索相关片段，只把和问题有关的 chunk 放进 Prompt，这样回答更聚焦、成本更低，也更容易做来源追踪。

### 问：为什么要做 Query Rewrite？

答：

> 用户问题经常很短或者依赖上下文，比如“这个怎么优化”。直接检索效果不好。Query Rewrite 会结合历史把它改写成更具体的检索句，提高召回质量。项目里 rewrite 失败会回退到原问题，不影响主流程。

### 问：为什么要分块？

答：

> 分块是为了在语义完整性和检索精度之间平衡。chunk 太大容易带入无关内容，chunk 太小又缺上下文。当前项目用 TokenTextSplitter 默认配置，生产中可以按标题、段落、代码块等语义结构进一步优化。

### 问：为什么用 Redis Stream 做向量化？

答：

> 向量化耗时且依赖外部 Embedding 服务，不适合同步阻塞上传接口。Redis Stream 支持 Consumer Group、ACK、重试和 pending 消息恢复，比普通线程池更适合可恢复的后台任务。

### 问：有没有混合检索？

答：

> 当前 RAG 查询主要是向量检索，知识库列表有元数据关键词搜索，但还没有实现 chunk 级 BM25 混合召回。所以我会如实说目前不是完整混合检索。后续可以为 chunk 增加全文索引，并行做向量召回和 BM25 召回，再用 RRF 或 rerank 融合。

### 问：权限过滤怎么做？

答：

> 当前项目主要按知识库 ID 过滤检索范围，没有完整用户权限系统。生产上应该把 userId、tenantId、role、visibility 等信息写入 chunk metadata，在向量检索时下推过滤条件，确保无权限的 chunk 不会被召回。权限不能只靠 Prompt 约束。

### 问：怎么排查 RAG 回答不准？

答：

> 我会分层排查：先看文档是否解析正确，再看分块是否合理，再看 query rewrite 是否偏了，再看 topK/minScore 是否导致召回不足，再看命中 chunk 的 score 和内容，最后才看模型生成。项目里有 RAG 检索日志，可以看到原问题、改写问题、命中 chunk 和相似度分数。

## 13. 你可以重点背的 5 个关键词

1. 入库链路：上传、解析、清洗、存储、异步向量化。
2. 检索链路：Query Rewrite、动态 topK、pgvector 相似度搜索、知识库过滤。
3. 生成链路：context 拼装、Prompt 防注入、ChatClient 生成、SSE 返回。
4. 工程保障：Redis Stream、状态机、失败重试、限流、Trace 日志。
5. 生产增强：混合检索、权限过滤、rerank、引用溯源、多租户。

## 14. 最短口径

如果只给你 30 秒，可以这样说：

> 这个项目的 RAG 是一条完整的向量检索链路。文档上传后先做校验、hash 去重，用 Apache Tika 解析并清洗文本，原文件存 RustFS/S3，元数据入库后通过 Redis Stream 异步向量化。消费者重新下载文件、分块、调用 EmbeddingModel 生成向量并写入 pgvector。用户提问时会做 Query Rewrite、动态 topK 和相似度阈值控制，通过 pgvector 召回指定知识库的 chunk，组装 Prompt 后调用大模型，最后通过 SSE 流式返回，并记录检索 trace。当前主要是向量检索型 RAG，生产级可以继续补混合检索、权限过滤、rerank 和引用溯源。
