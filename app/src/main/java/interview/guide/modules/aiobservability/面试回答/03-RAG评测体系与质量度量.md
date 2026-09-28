# RAG 评测体系与质量度量

## 1. 模块基础讲解

RAG 评测由 `RagEvaluationService` 实现，它是一个面向检索质量的轻量评测框架。使用方式是先创建评测集，再添加评测用例，最后执行一次评测运行。

数据模型是四层：

```text
RagEvalDataset：评测集
  -> RagEvalCase：评测用例
    -> RagEvalRun：一次评测运行
      -> RagEvalRunCaseResult：每个用例的结果
```

每个用例包含：

```text
question：测试问题
knowledgeBaseIds：在哪些知识库中检索
expectedKeywords：期望检索结果中出现的关键词
```

执行评测时，系统对每个 case 调用 `KnowledgeBaseVectorService.similaritySearch()`，把返回的文档文本拼成语料，用 `String.contains()` 判断期望关键词是否出现。最终统计：

```text
matchedCases / totalCases：关键词命中率
avgLatencyMs：平均检索延迟
```

要强调：当前评测是检索回归测试，不是完整答案质量评测。

## 2. 基础面试问题

### Q1：RAG 评测集是什么？

评测集是一个问题集合，用来稳定衡量某批知识库的检索效果。比如可以建一个“Java 八股知识库评测集”，里面放 IoC、AOP、事务、JVM 等问题，每个问题配上期望关键词和知识库 ID。

它的价值是把“感觉效果不错”变成“固定测试集上命中率是多少”。

### Q2：一个评测用例包含什么？

包含三部分：

1. `question`：要测试的问题。
2. `knowledgeBaseIds`：限定从哪些知识库检索。
3. `expectedKeywords`：希望检索结果中出现的关键词。

例如：

```text
question = "Spring IoC 是什么？"
knowledgeBaseIds = [10]
expectedKeywords = ["控制反转", "容器", "依赖注入"]
```

### Q3：评测是怎么判断命中的？

它把检索出的所有文档文本拼接起来，转成小写，然后判断期望关键词是否出现在语料中。只要命中至少一个关键词，就认为这个 case 的 `expectedKeywordHit=true`。

如果 expectedKeywords 为空，则当前逻辑是只要检索到文档就算命中。

### Q4：评测结果怎么看？

看三个核心指标：

1. `totalCases`：总用例数。
2. `matchedCases`：命中关键词的用例数。
3. `avgLatencyMs`：平均检索耗时。

每个 case 还会记录 `hitCount`、`matchedKeywordCount`、`retrievedPreviewJson` 和错误信息。

## 3. 进阶面试问题

### Q1：关键词命中率有什么优点和缺点？

优点：

1. 实现简单，成本低。
2. 可解释，失败时能看到缺了哪些关键词。
3. 不依赖额外 LLM，稳定可重复。
4. 适合作为检索回归测试。

缺点：

1. 无法识别同义表达。
2. 关键词出现不代表答案一定正确。
3. 无法评估最终回答的忠实度和完整性。
4. 对关键词设计质量依赖较高。

所以它适合做第一层检索质量门槛，而不是最终质量结论。

### Q2：为什么单个 case 失败不中断整体评测？

`evaluateCase()` 捕获单个用例异常，保存错误结果，然后继续执行后续用例。这样一次评测运行能最大化产出结果，不会因为一个坏 case 或某个知识库异常导致整套评测中断。

这也是批量评测系统常见设计：局部失败要隔离，整体报告要尽量完整。

### Q3：`RunRagEvalRequest` 的 topK 和 minScore 有什么意义？

它允许一次评测覆盖不同检索策略。比如可以比较：

```text
topK=3, minScore=0.3
topK=8, minScore=0.2
topK=12, minScore=0.15
```

通过 matchedCases 和 avgLatencyMs 的变化，可以判断召回率和性能之间的平衡。

### Q4：为什么 `runDataset()` 当前同步执行有风险？

如果评测集只有几十个用例，同步执行简单直接。但如果用例数很多，或者向量检索慢，HTTP 请求会长时间阻塞，可能超时，也不方便查看运行进度。

生产级评测更适合接入异步任务模块，把 run 状态从 RUNNING 异步推进到 COMPLETED/FAILED。

## 4. 技术延伸问题

### Q1：如何升级为答案级评测？

可以在检索后继续生成答案，并增加评估维度：

1. answer relevance：回答是否回答了问题。
2. faithfulness：回答是否忠实于检索上下文。
3. completeness：是否覆盖关键点。
4. citation correctness：引用来源是否支持结论。
5. refusal correctness：无资料时是否正确拒答。

评估方式可以是人工标注、规则评估、LLM-as-judge 或混合方案。

### Q2：如何做 Recall@K / MRR / nDCG？

需要给每个 case 标注相关文档或 chunk ID，而不是只标关键词。

指标示例：

```text
Recall@K：前 K 个结果是否覆盖所有相关 chunk
MRR：第一个相关结果排名的倒数
nDCG：考虑相关性等级和排序位置的综合指标
```

当前模型没有标注标准相关 chunk，所以暂时只能做关键词命中。

### Q3：如何做评测版本管理？

可以记录：

1. embedding model。
2. chunk 策略。
3. topK/minScore。
4. query rewrite 开关和 prompt 版本。
5. 知识库版本。
6. 代码版本或 git commit。

这样才能解释“为什么这次评测比上次好/差”。

## 5. 改进方案问题

### Q1：当前 RAG 评测优点是什么？

1. 数据模型完整，有 dataset/case/run/result。
2. 运行参数 `topK/minScore` 会保存到 `strategyJson`。
3. 单 case 失败不会中断整体评测。
4. 保存检索结果预览，便于排查失败 case。
5. 用例关键词会清洗、去空、去重。
6. 已有测试覆盖创建用例和关键词命中计算。

### Q2：当前不足是什么？

1. 只评估检索，不评估最终答案。
2. 关键词匹配不能识别语义等价。
3. 同步执行，不适合大评测集。
4. `RagEvalRunStatus.FAILED` 顶层状态没有充分使用。
5. 评测集没有版本和标签。
6. 没有和线上 Trace 失败样本自动联动。

### Q3：怎么把线上问题沉淀成评测集？

可以在 RAG Trace 页面增加“加入评测集”按钮：

1. 把用户 question 带入 case。
2. 选择知识库 ID。
3. 根据正确答案人工补 expectedKeywords。
4. 下次修改 chunk 策略或检索参数后自动回归。

这能把线上坏 case 转成长期质量资产。

## 6. 故障处理问题

### Q1：评测命中率很低怎么排查？

1. 看每个失败 case 的 retrievedPreviewJson。
2. 判断是没检到文档，还是检到了但不含关键词。
3. 检查 knowledgeBaseIds 是否正确。
4. 调整 topK/minScore 重跑。
5. 检查知识库是否完成向量化。
6. 检查 expectedKeywords 是否过窄或表达不一致。

### Q2：评测运行很慢怎么办？

1. 减少单次评测用例数。
2. 降低 topK。
3. 检查 pgvector 索引和数据库负载。
4. 改为异步执行。
5. 并行执行 case，但要控制数据库和 embedding/vector 查询压力。

### Q3：某个 case 报错但 run 仍然 COMPLETED，合理吗？

当前逻辑是合理的：单个 case 错误被记录在 `errorMessage`，整体 run 仍然完成，因为评测流程跑完了。不过更细致的状态可以增加 `PARTIAL_FAILED`，区分全部成功和部分用例失败。

