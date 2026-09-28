## JD = Job Description（职位描述/招聘需求）

就是企业在招聘网站上发的那个职位信息：

```
【Java高级开发工程师】
岗位职责：
1. 负责电商支付系统的架构设计
2. 参与高并发场景下的性能优化
3. 带领 5 人团队完成核心模块开发

任职要求：
1. 5年以上Java开发经验
2. 精通Spring Cloud微服务架构
3. 熟悉MySQL优化、Redis集群、Kafka消息队列
4. 有大型分布式系统经验优先
```

---

## JD 解析做了什么？

用户把这段招聘文本粘贴进来，AI 自动提取出**这个岗位需要考察哪些分类**，然后生成一套针对性的面试题。

```
用户粘贴 JD 文本
        │
        ▼
    parseJd(jdText)
        │  调用 LLM
        ▼
    AI 输出：
    [
      { key: "JAVA_BASIC",   label: "Java基础",    priority: "CORE" },
      { key: "SPRING_CLOUD", label: "Spring Cloud", priority: "CORE" },
      { key: "MYSQL",        label: "MySQL优化",    priority: "CORE" },
      { key: "REDIS",        label: "Redis集群",    priority: "CORE" },
      { key: "KAFKA",        label: "Kafka",        priority: "CORE" }
    ]
        │
        ▼
    buildCustomSkill(categories, jdText)
        │  匹配本地参考文件
        ▼
    出题时：按这些分类生成面试题
```

---

## 你选中的代码段在做什么？

`buildCustomSkill` 就是把 AI 解析出的分类**和本地已有的参考文件做匹配**：

```java
RefMapping refMapping = categoryRefIndex.get(safeKey);
if (refMapping != null) {
    // LLM 可能返回了错误的 ref，用本地映射纠正
    // 比如 LLM 说 ref = "mysql-basic.md"
    // 但本地实际应该是 "mysql.md"
    return new SkillCategoryDTO(..., refMapping.ref(), refMapping.shared());
}
```

**为什么要纠正？** 因为 LLM 可能猜错了参考文件名，或者返回了一个不存在的文件。`categoryRefIndex` 是启动时从真实文件系统构建的索引，用它兜底保证后续出题时能正确加载参考资料。

---

## 这个功能解决了什么问题？

传统面试都是固定的"Java 后端"、"前端"几个方向。JD 解析让你可以**针对具体公司的招聘要求定制面试**——对方岗位要求 Kafka，你就多考 Kafka；对方强调系统设计，你就重点考系统设计。