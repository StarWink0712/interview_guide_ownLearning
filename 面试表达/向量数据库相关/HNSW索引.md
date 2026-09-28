HNSW（Hierarchical Navigable Small World，分层可导航小世界图）是专门用于**向量近邻搜索**的索引。

在你的 RAG 知识库中，每个文档分块都会被转换成一个 1024 维向量。用户提问也会转换成同维度向量，系统需要找出最相似的文档块。

如果没有索引，数据库需要把查询向量和所有文档向量逐一计算相似度：

```text
查询向量 → 与全部文档向量比较 → 排序 → 取 Top-K
```

数据量大时会很慢。HNSW 会把向量组织成一张多层图：

```text
上层：节点少，用于快速定位大致区域
  ↓
中层：逐步缩小搜索范围
  ↓
底层：节点密集，找到最相似的向量
```

它有点像导航：

- 上层类似高速公路，快速接近目标区域。
- 下层类似城市道路，精确寻找附近节点。
- 不需要遍历数据库中的全部向量。

在 pgvector 中通常类似这样建立索引：

```sql
CREATE INDEX idx_document_chunk_embedding
ON document_chunk
USING hnsw (embedding vector_cosine_ops);
```

查询时：

```sql
SELECT *
FROM document_chunk
ORDER BY embedding <=> :queryVector
LIMIT :topK;
```

其中：

- `embedding` 是 1024 维文档向量。
- `<=>` 表示余弦距离。
- `LIMIT` 对应 Top-K。
- HNSW 是近似最近邻搜索（ANN），可能牺牲少量召回率换取显著的查询速度提升。

常见参数包括：

- `m`：每个节点建立多少条连接；越大通常召回率越高，但索引更大。
- `ef_construction`：建索引时的搜索范围；越大索引质量越好，但构建更慢。
- `hnsw.ef_search`：查询时考察的候选节点数量；越大结果更准确，但查询更慢。

面试时可以这样概括：

> HNSW 是 pgvector 支持的一种基于多层小世界图的近似最近邻索引。它将文档分块的 1024 维向量组织成图结构，查询时从稀疏上层快速定位，再逐层细化，在不全表扫描的情况下找到与问题向量最相似的 Top-K 文档块，从而提升 RAG 的检索效率。