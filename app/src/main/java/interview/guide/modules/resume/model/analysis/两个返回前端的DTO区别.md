## 核心区别：列表页 vs 详情页

这是典型的**数据传输量分层设计**——同样的简历，在不同的页面需要的数据量差别很大。

### 字段对比

| 字段 | ResumeListItemDTO<br>（列表页用） | ResumeDetailDTO<br>（详情页用） |
|------|:---:|:---:|
| id | ✅ | ✅ |
| filename | ✅ | ✅ |
| fileSize | ✅ | ✅ |
| uploadedAt | ✅ | ✅ |
| accessCount | ✅ | ✅ |
| contentType | ❌ | ✅ |
| storageUrl | ❌ | ✅ |
| resumeText（解析全文） | ❌ | ✅ |
| **评分** | `latestScore`（一个数字） | `analyses`（完整历史列表） |
| **面试** | `interviewCount`（次数） | `interviews`（完整面试列表） |
| analyzeStatus | ✅ | ✅ |
| analyzeError | ✅ | ✅ |
| lastAnalyzedAt | ✅ | ❌（包含在 analyses 列表里） |

### 为什么不能用一个 DTO？

```
列表页：显示 20 条简历
  每一条只需要：文件名 + 最新评分 + 分析状态
  如果用 DetailDTO → 每条都要拉全文(resumeText) + 全部分析历史 + 全部面试记录
  → 一次请求可能拉回来几 MB 的冗余数据

详情页：点击某一条简历
  需要展示：原文内容 + 历次评分趋势图 + 关联的面试记录
  → 这时候才需要完整数据
```

### 字段含义对应

```
ListItemDTO.latestScore  = DetailDTO.analyses 中最新一条的 overallScore
ListItemDTO.interviewCount  = DetailDTO.interviews 的 size()
```

### 一句话总结

> **ListItemDTO 是"摘要卡片"，只放列表展示必需的聚合信息；DetailDTO 是"完整档案"，含解析原文、历次分析详情、关联面试列表。这避免了列表页拉大量冗余数据，是 API 设计中的常见优化。**