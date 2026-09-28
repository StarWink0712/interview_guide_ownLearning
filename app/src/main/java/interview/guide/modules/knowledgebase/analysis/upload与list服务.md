原文件存rustrf，然后元数据存sql数据库。

你问的是两层存储架构，让我结合代码说清楚。

## 整体存储架构

文件上传后分两处存储，**不是单一的 key-value 结构**：

```
上传的文件 (PDF/DOCX/TXT)
   ├── 原始文件字节 → RustFS 对象存储 (Key-Value)
   └── 元数据信息   → PostgreSQL 数据库 (关系型表)
```

---

## 一、RustFS 对象存储（Key-Value）

查看 [KnowledgeBaseUploadService.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseUploadService.java#L97-L100) 第 97-100 行：

```java
// 5. 保存文件到RustFS
String fileKey = storageService.uploadKnowledgeBase(file);
String fileUrl = storageService.getFileUrl(fileKey);
```

RustFS 就是 **Key-Value 存储**：key 是 `fileKey`（如 `knowledgebase/abc123-uuid.pdf`），value 是原始文件的二进制内容。`fileUrl` 是对外可访问的下载地址。

---

## 二、PostgreSQL 数据库（关系型表）

查看 [KnowledgeBaseEntity.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/model/KnowledgeBaseEntity.java#L24-L28)，映射到 `knowledge_bases` 表，核心字段：

```java
@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBaseEntity {
    private Long id;              // 主键自增
    private String fileHash;     // SHA-256 哈希（去重用，唯一索引）
    private String name;         // 知识库名称
    private String category;     // 分类
    private String originalFilename;  // 原始文件名
    private Long fileSize;       // 文件大小
    private String contentType;  // 文件类型 (MIME)
    private String storageKey;   // RustFS 的 key ← 关键！不存文件本体
    private String storageUrl;   // RustFS 的下载 URL
    private VectorStatus vectorStatus;  // 向量化状态
    private Integer accessCount; // 访问次数
    private Integer questionCount; // 提问次数
}
```

**数据库只存元数据，不存文件二进制内容**。`storageKey` 是连接数据库和对象存储的桥梁。

---

## 三、List 查询的完整流程

以 [KnowledgeBaseListService.listKnowledgeBases()](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseListService.java#L58-L75) 为例：

```
GET /api/knowledgebase/list
  → listService.listKnowledgeBases(vectorStatus, sortBy)
      │
      ├── 数据库过滤（参数下推到 SQL）
      │     ├── vectorStatus != null
      │     │     → repository.findByVectorStatusOrderByUploadedAtDesc(status)
      │     │       // SQL: SELECT * FROM knowledge_bases WHERE vector_status = ? ORDER BY uploaded_at DESC
      │     └── vectorStatus == null
      │           → repository.findAllByOrderByUploadedAtDesc()
      │             // SQL: SELECT * FROM knowledge_bases ORDER BY uploaded_at DESC
      │
      ├── 内存排序（非 time 维度）
      │     └── size/access/question 降序（Java 层面 stream.sorted）
      │       // 这些排序不在数据库，因为数据量不大
      │
      └── MapStruct 转换
            └── knowledgeBaseMapper.toListItemDTOList(entities)
              // KnowledgeBaseEntity → KnowledgeBaseListItemDTO
```

查看 [KnowledgeBaseRepository.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/repository/KnowledgeBaseRepository.java) 中的查询方法，全部是标准 JPA/JPQL，操作的是数据库表而非 RustFS：

| Repository 方法 | 对应 SQL | 用途 |
|---|---|---|
| `findAllByOrderByUploadedAtDesc()` | `SELECT * FROM knowledge_bases ORDER BY uploaded_at DESC` | 列表（默认排序） |
| `findByVectorStatusOrderByUploadedAtDesc(status)` | `...WHERE vector_status = ? ORDER BY...` | 按状态过滤 |
| `findByCategoryOrderByUploadedAtDesc(cat)` | `...WHERE category = ? ORDER BY...` | 按分类过滤 |
| `searchByKeyword(keyword)` | `...WHERE LOWER(name) LIKE %?% OR LOWER(originalFilename) LIKE %?%` | 模糊搜索 |
| `findAllCategories()` | `SELECT DISTINCT category FROM ... WHERE category IS NOT NULL` | 分类下拉 |
| `findByCategoryIsNullOrderByUploadedAtDesc()` | `...WHERE category IS NULL ORDER BY...` | 未分类列表 |

---

## 四、总结：什么时候访问 RustFS，什么时候访问数据库？

```
┌──────────────────────────────────────────────────────┐
│                    PostgreSQL                         │
│  knowledge_bases 表（元数据）                          │
│  ┌────┬──────┬────────────┬───────────┬───────────┐ │
│  │ id │ name │ storageKey │ fileSize  │ category  │ │
│  ├────┼──────┼────────────┼───────────┼───────────┤ │
│  │ 1  │题库A │ kb/xxx.pdf │ 1024000   │ 技术面试   │ │
│  │ 2  │项目B │ kb/yyy.docx│ 512000    │ null       │ │
│  └────┴──────┴────────────┴───────────┴───────────┘ │
└──────────────────────────────────────────────────────┘
                    ↑ storageKey 是关联桥梁
┌──────────────────────────────────────────────────────┐
│                    RustFS (Key-Value)                 │
│  "kb/xxx.pdf"   → [二进制 PDF 内容]                   │
│  "kb/yyy.docx"  → [二进制 DOCX 内容]                  │
└──────────────────────────────────────────────────────┘
```

| 操作 | 访问 PostgreSQL | 访问 RustFS |
|------|:---:|:---:|
| 获取列表/详情/分类/搜索/统计 | 只查数据库 | 不访问 |
| 下载文件原始内容 | 查 `storageKey` | 用 key 下载 |
| 重新向量化（revectorize） | 查 `storageKey` | 下载文件后重新解析 |

所以 List 查询完全不走 RustFS，只在数据库层面做 SQL 查询 + 内存排序 + MapStruct 转换，**性能瓶颈仅在数据库查询，与对象存储无关**。这就是为什么文件元数据存关系型数据库而非 key-value 的原因——需要按多维度（状态、分类、时间）灵活查询和排序。