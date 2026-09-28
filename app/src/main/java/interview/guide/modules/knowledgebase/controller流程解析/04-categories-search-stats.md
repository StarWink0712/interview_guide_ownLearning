# 分类、搜索与统计接口完整调用链

## 1. 覆盖接口

| 方法 | 接口 | 当前前端调用 |
|---|---|---|
| `getAllCategories` | `GET /api/knowledgebase/categories` | 管理页 |
| `getByCategory` | `GET /api/knowledgebase/category/{category}` | 管理页 |
| `getUncategorized` | `GET /api/knowledgebase/uncategorized` | API 已封装，页面未调用 |
| `updateCategory` | `PUT /api/knowledgebase/{id}/category` | 管理页内联编辑 |
| `search` | `GET /api/knowledgebase/search?keyword=...` | 管理页、问答页 |
| `getStatistics` | `GET /api/knowledgebase/stats` | 管理页 |

管理页每次 `loadData` 会并行请求统计、当前列表/搜索/分类结果、分类集合。有向量化中的项目时，
这组请求每 5 秒重复一次。

## 2. 获取分类集合

```text
knowledgeBaseApi.getAllCategories
  → GET /api/knowledgebase/categories
  → Controller listService.getAllCategories
  → Repository.findAllCategories
```

JPQL 查询只返回非 null 的不同分类，并按分类名排序。空字符串通常在保存时已转换为 null，因此
不会作为独立分类返回。

响应经 `Result.success` 和 Axios 拦截器变成 `string[]`，用于管理页筛选器。

## 3. 按分类和未分类查询

普通分类：

```text
knowledgeBaseApi.getByCategory(category)
  → encodeURIComponent(category)
  → GET /api/knowledgebase/category/{category}
  → listService.listByCategory(category)
  → repository.findByCategoryOrderByUploadedAtDesc
```

未分类：

```text
knowledgeBaseApi.getUncategorized
  → GET /api/knowledgebase/uncategorized
  → listService.listByCategory(null)
  → repository.findByCategoryIsNullOrderByUploadedAtDesc
```

两个查询都通过 MapStruct 转成列表 DTO，也都没有分页或向量状态过滤。当前页面没有调用
`getUncategorized`；管理页的 `selectedCategory=null` 同时也被用作“全部”，所以没有通过这个
接口单独筛选未分类项。

## 4. 更新分类

管理页内联编辑：

```text
editingCategoryValue.trim()
  → 空字符串转 null
  → knowledgeBaseApi.updateCategory(id, category)
  → PUT /api/knowledgebase/{id}/category
  → body { category }
```

Controller 用 `Map<String,String>` 读取 `body.get("category")`，没有专用 Request record 或
Bean Validation。

Service 在事务中：

```text
Repository.findById
  → 不存在：BusinessException(KNOWLEDGE_BASE_NOT_FOUND)
  → 空白分类转 null
  → Repository.save
```

成功后页面退出编辑状态并重新并行加载列表、分类和统计。

## 5. 关键词搜索

```text
keyword 为空
  → listService.listKnowledgeBases()

keyword 非空
  → trim
  → Repository.searchByKeyword
  → LOWER(name) LIKE %keyword%
     OR LOWER(originalFilename) LIKE %keyword%
  → uploadedAt DESC
```

前端使用 `encodeURIComponent`，避免关键词破坏 URL。查询结果经 Mapper 返回。

搜索没有分页，也没有全文检索索引语义；前后通配 `%keyword%` 在数据量大时通常难以使用普通
B-Tree 索引。

### 问答页的状态过滤漏洞

问答页初次加载调用：

```text
getAllKnowledgeBases(sortBy, COMPLETED)
```

但用户搜索时改为调用通用 `search(keyword)`，后端搜索不限制 `vectorStatus`。因此搜索结果可能
重新出现 `PENDING`、`PROCESSING` 或 `FAILED` 的知识库，用户可选中尚不可检索的记录。这与
页面“只显示向量化完成知识库”的初始逻辑不一致。

## 6. 统计信息

Controller 调用 `listService.getStatistics`，Service 构造 `KnowledgeBaseStatsDTO`：

```text
totalCount
  → KnowledgeBaseRepository.count()

totalQuestionCount
  → RagChatMessageRepository.countByType(USER)

totalAccessCount
  → KnowledgeBaseRepository.sumAccessCount()

completedCount
  → countByVectorStatus(COMPLETED)

processingCount
  → countByVectorStatus(PROCESSING)
```

这是 5 次独立数据库查询，没有统一快照事务。高并发更新时，各字段可能来自略有差异的时刻。

`totalQuestionCount` 特意统计 RAG 会话中的用户消息，因此一次多知识库提问只算一次。但无会话
`/query` 和 `/query/stream` 不保存 `RagChatMessageEntity`，这些请求不会进入总提问数；同时它们
仍会增加每个知识库自身的 `questionCount`。所以全局总数不等于各知识库 questionCount 之和。

`processingCount` 不包含 `PENDING`，统计 DTO 也没有失败数。

## 7. 异常如何回到页面

普通成功都走 `Result.success`。更新不存在 ID 时由全局异常处理器返回业务错误；管理页 catch
只写 `console.error`，不会显示用户可见提示。列表、分类、搜索或统计任一 Promise 失败，会让
整个 `Promise.all` 进入 catch，三组 state 都不更新。

## 8. 当前实现边界

- 分类、搜索都无分页。
- 更新分类使用原始 Map，没有长度/字符约束。
- 未分类 API 当前未被页面使用。
- 问答页搜索会绕过 `COMPLETED` 状态过滤。
- 统计通过 5 次查询拼装，不是同一快照。
- 全局提问数只统计持久化 RAG 会话消息，与知识库自身 questionCount 的口径不同。

## 流程总结

这六个辅助接口被管理页和问答页同时使用。分类管理方面：`GET /api/knowledgebase/categories` 通过 JPQL `SELECT DISTINCT category ... WHERE category IS NOT NULL` 返回所有不重复的分类名列表，供管理页渲染筛选下拉框；`GET /api/knowledgebase/category/{category}` 和 `GET /api/knowledgebase/uncategorized` 分别按具体分类名和 IS NULL 条件查询对应知识库，均按时上传时间降序排列并通过 MapStruct 转换返回；`PUT /api/knowledgebase/{id}/category` 在事务中 `findById` 后设置 category（空字符串转为 null 表示清除分类）然后 `save`，前端内联编辑后刷新三组数据。搜索方面：`GET /api/knowledgebase/search?keyword=...` 将关键词 trim 后走 Repository 的 JPQL 查询 `LOWER(name) LIKE %keyword% OR LOWER(originalFilename) LIKE %keyword%` 实现大小写不敏感的模糊匹配，关键词为空时直接返回全部列表作为降级。统计方面：`GET /api/knowledgebase/stats` 通过五次独立数据库查询组装 `KnowledgeBaseStatsDTO`——`count()` 总数、`countByType(USER)` 从 RAG 聊天消息表统计全局提问数、`sumAccessCount()` 累计访问次数、`countByVectorStatus(COMPLETED/PROCESSING)` 分别统计已完成和进行中的向量化任务数。需要注意全局提问数只统计持久化 RAG 聊天消息，不走会话的 `/query` 和 `/query/stream` 的提问不计入。
