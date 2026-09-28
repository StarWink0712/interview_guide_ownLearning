我明白了，就是我先上传一些知识库，然后验证这些知识库能否起作用。用一个测试用例，包含问题，知识库id，期望关键词。看知识库查找能否找到这些关键词
是的，你的理解完全正确。整个流程就是：

**准备阶段**：上传文档到知识库 → 系统自动向量化

**评测阶段**：

1. 创建评测集
2. 添加用例 = 问题 + 指定在哪些知识库里搜 + 期望搜到的关键词
3. 执行评测 → 系统对每个用例做向量检索，用 `contains` 检查关键词是否出现在召回文档里
4. 看结果 → 哪些用例命中了、哪些没命中，整体召回率如何

本质就是一个**向量检索的回归测试工具**——每次调整了知识库内容或检索参数后，跑一遍评测就能知道检索质量是变好了还是变差了。

比如你说的场景，上传 Spring、MyBatis 相关的面试题文档到不同知识库，然后用这个评测服务来验证：用户问这类问题时，向量检索能不能精准找到包含正确答案的文档。
以下是一个完整的真实例子：

## 场景：评测知识库对 Spring 面试题的检索质量

### 第一步：创建评测集

调用 `POST /api/aiobservability/rag-eval/datasets`

```json
{
  "name": "Spring基础面试题回归测试",
  "description": "验证知识库对 Spring 核心概念相关问题的检索召回质量"
}
```

返回一个 `datasetId = 1` 的评测集。

### 第二步：添加评测用例

调用 `POST /api/aiobservability/rag-eval/datasets/1/cases`，添加多个用例：

**用例 A：**
```json
{
  "question": "Spring 的 IoC 和 DI 有什么区别？",
  "knowledgeBaseIds": [1, 3],
  "expectedKeywords": ["控制反转", "依赖注入", "容器", "Bean"]
}
```

**用例 B：**
```json
{
  "question": "Spring Boot 的自动配置原理是什么？",
  "knowledgeBaseIds": [1],
  "expectedKeywords": ["AutoConfiguration", "spring.factories", "条件注解"]
}
```

**用例 C：**
```json
{
  "question": "MyBatis 是如何实现分页的？",
  "knowledgeBaseIds": [2],
  "expectedKeywords": ["PageHelper", "RowBounds", "拦截器"]
}
```

### 第三步：执行评测

调用 `POST /api/aiobservability/rag-eval/datasets/1/runs`

```json
{
  "topK": 5,
  "minScore": 0.5
}
```

系统自动对每个用例执行向量检索：

```
用例 A（IoC 和 DI 的区别）：
  → 在知识库 1、3 中向量搜索 question
  → 检出 4 个文档片段：
    文档1: "控制反转（IoC）是一种设计思想，DI 是它的实现方式..."
    文档2: "Spring 容器负责管理 Bean 的生命周期..."
    文档3: "依赖注入有三种方式：构造器注入、Setter 注入..."
    文档4: "Spring AOP 通过动态代理实现横切关注点..."
  → 关键词匹配：
    ✓ "控制反转" → 文档1 命中
    ✓ "依赖注入" → 文档1、文档3 命中
    ✓ "容器"     → 文档2 命中
    ✓ "Bean"     → 文档2 命中
  → 结果：命中 ✓（4/4 关键词匹配），耗时 87ms

用例 B（自动配置原理）：
  → 检出 3 个文档片段
  → 关键词匹配：
    ✓ "AutoConfiguration" → 命中
    ✓ "spring.factories"  → 命中
    ✗ "条件注解"           → 未找到（文档只写到 @Conditional 英文形式）
  → 结果：命中 ✓（2/3 关键词匹配），耗时 73ms

用例 C（MyBatis 分页）：
  → 检出 2 个文档片段
  → 关键词匹配：
    ✗ "PageHelper" → 未找到
    ✗ "RowBounds"  → 未找到
    ✗ "拦截器"     → 未找到
  → 结果：未命中 ✗（0/3 关键词匹配），耗时 56ms
```

### 第四步：查看结果

调用 `GET /api/aiobservability/rag-eval/runs/1`

```json
{
  "runId": 1,
  "datasetId": 1,
  "status": "COMPLETED",
  "strategyJson": "{\"topK\":5,\"minScore\":0.5}",
  "totalCases": 3,
  "matchedCases": 2,
  "avgLatencyMs": 72,
  "caseResults": [
    {
      "caseId": 1,
      "question": "Spring 的 IoC 和 DI 有什么区别？",
      "matchedKeywordCount": 4,
      "expectedKeywordHit": true,
      "hitCount": 4,
      "latencyMs": 87,
      "retrievedPreviewJson": "[{\"knowledgeBaseId\":1,\"score\":0.92,\"text\":\"控制反转（IoC）是一种...\"},...]"
    },
    {
      "caseId": 2,
      "question": "Spring Boot 的自动配置原理是什么？",
      "matchedKeywordCount": 2,
      "expectedKeywordHit": true,
      "hitCount": 3,
      "latencyMs": 73
    },
    {
      "caseId": 3,
      "question": "MyBatis 是如何实现分页的？",
      "matchedKeywordCount": 0,
      "expectedKeywordHit": false,
      "hitCount": 2,
      "latencyMs": 56
    }
  ]
}
```

### 结论

- **召回率 = 2/3 = 66.7%**，说明知识库 2 中 MyBatis 分页相关文档不够丰富
- **平均延迟 = 72ms**，性能良好
- 用例 C 需要排查是知识库缺内容，还是向量检索召回不准，可针对性地补充文档或调整检索参数（调低 minScore、增大 topK）后重新评测