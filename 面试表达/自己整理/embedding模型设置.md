你项目默认用的是 **阿里云百炼 / DashScope** 的 Embedding：

- Provider：`dashscope`
- Base URL：`https://dashscope.aliyuncs.com/compatible-mode/v1`
- 模型：`text-embedding-v3`
- 向量维度：`1024`

配置位置在 [application.yml (line 117)](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/resources/application.yml:117)：`default-embedding-provider: dashscope`，并且 `dashscope.embedding-model: text-embedding-v3`、`embedding-dimensions: 1024`。pgvector 也配成了 `dimensions: 1024`，两边是一致的。

最大输入长度有两层含义：

- **模型官方规格**：阿里云文档里 `text-embedding-v3` 支持 `1,024（默认）、768、512、256、128、64` 维，单批次最大处理 Token 数是 **8,192 tokens**。见 [阿里云百炼向量化文档](https://help.aliyun.com/zh/model-studio/embedding)。
- **你项目实际喂给 Embedding 的文档 chunk**：知识库向量化前用 `TokenTextSplitter.builder().build()`，Spring AI 默认 `chunkSize = 800` tokens，所以文档入库时通常是 **约 800 tokens/块**，每批最多 10 个 chunk。代码在 [KnowledgeBaseVectorService.java (line 54)](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java:54)。

补一句：运行时 Provider 支持后台动态改，`LlmProviderRegistry` 会优先读数据库里的默认 embedding provider，再回退到 YAML；所以如果你在管理后台改过，实际运行值以数据库为准。



你项目的 RAG 检索参数在 [application.yml (line 185)](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/resources/application.yml:185)：

| 场景     | 判断条件            | topK | minScore |
| -------- | ------------------- | ---- | -------- |
| 短问题   | 去空格后长度 `<= 4` | `20` | `0.18`   |
| 中等问题 | 长度 `5 ~ 12`       | `12` | `0.28`   |
| 长问题   | 长度 `> 12`         | `8`  | `0.28`   |

其他相关参数：

- Query Rewrite：默认开启，`APP_AI_RAG_REWRITE_ENABLED:true`
- 短问题长度阈值：`APP_AI_RAG_SHORT_QUERY_LENGTH:4`
- 这些值都可以通过环境变量覆盖，比如 `APP_AI_RAG_TOPK_SHORT`

代码使用逻辑在 [KnowledgeBaseQueryService.java (line 527)](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java:527)：先去掉空白字符算长度，再选对应的 `topK/minScore`。

另外，如果 pgvector 的 metadata filter 失败，会走兜底检索：先查 `topK * 3`，再在 Java 里按知识库 ID 过滤，最后仍然限制返回 `topK` 条。代码在 [KnowledgeBaseVectorService.java (line 146)](/Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java:146)。



这个项目不是千万级、亿级向量库场景，而是面试平台里的知识库问答和简历/面试业务数据。预期规模大概是几百到几千份文档，向量 chunk 数在几万到几十万级别。即使后续增长到百万级向量，PostgreSQL + pgvector 通过 HNSW 索引和合理过滤也还能支撑一段时间。

但如果做到多租户、大规模知识库、千万级以上向量、高并发检索，或者需要更复杂的召回策略，那就应该考虑 Milvus、Qdrant、Weaviate 这类专用向量数据库。