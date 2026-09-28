之前已经看到过，在 `KnowledgeBaseQueryService` 里通过 `resolveSearchParams` 根据问题长度动态调整：

```java
private SearchParams resolveSearchParams(String question) {
    int compactLength = question.replaceAll("\\s+", "").length();

    if (compactLength <= shortQueryLength) {       // 短问题（如 ≤5字："JVM是什么"）
        return new SearchParams(topkShort, minScoreShort);   // topK更大 + minScore更低
    }
    if (compactLength <= 12) {                      // 中等长度
        return new SearchParams(topkMedium, minScoreDefault); // topK适中
    }
    // 长问题（详细描述）
    return new SearchParams(topkLong, minScoreDefault); // topK更大 + minScore严格
}
```

短问题语义少，所以放宽阈值，长问题描述详细，向量精准，严格阈值保证精度。

思路很简单：**短问题语义信息少，向量分布更模糊，放宽阈值提高召回**；**长问题描述详细，向量更精准，用严格阈值保证精度**。

具体参数从 `application.yml` 配置读取：

```yaml
app:
  knowledgebase:
    query:
      search:
        short-query-length: 5        # 短问题阈值（≤5字）
        topk-short: 8                # 短问题 topK
        topk-medium: 6               # 中等长度 topK
        topk-long: 10                # 长问题 topK
        min-score-short: 0.5         # 短问题 minScore（低阈值，提高召回）
        min-score: 0.7               # 默认 minScore（严格过滤）
```

`topK` 控制"最多取几条返回给 LLM"，太少了漏信息，太多了浪费 Token 成本。`minScore` 控制"相似度低于多少就不算命中"，太低了噪声多，太高了可能零命中。两段式分级属于经验规则，不是精确的最优解，但足够实用。

<br />

其实就是在改写完query后，对query长度进行统计，如果是长问题就用长topk，短问题比如小于5个字就用小topk8；minscore分数也会因为问题长度去改变。

<br />

基本正确，有两个小细节：

**1. 短问题用更低的 minScore，而不是短问题用短 minScore。**

短问题的向量质量差，所以 minScore 设低（比如 0.5），放宽门槛，宁可多召回一些相关度不那么高的文档，也不能漏。长问题反过来，描述详细向量精准，minScore 用默认值（0.7），严格过滤噪声。

**2. 不是"小 topK 8"，是分档独立配置的。**

| 问题长度    | 配置项           | 值  | 策略           |
| :------ | :------------ | :- | :----------- |
| ≤5 字    | `topk-short`  | 8  | 宽召回，虽然短但尽量多捞 |
| 6\~12 字 | `topk-medium` | 6  | 适中           |
| >12 字   | `topk-long`   | 10 | 长问题信息量大，多取几条 |

短问题用 8 是因为虽然问题短，但语义稀疏，多捞几条能提高覆盖。中等问题 6 条够，长问题 10 条是因为描述详细、检索精准，取多了也不会有太多噪声。
