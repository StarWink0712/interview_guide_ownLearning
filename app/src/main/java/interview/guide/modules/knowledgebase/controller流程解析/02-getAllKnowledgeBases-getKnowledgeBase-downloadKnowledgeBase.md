# 列表、单条详情与文件下载完整调用链

## 1. 接口与真实调用者

| Controller 方法 | 接口 | 当前前端调用 |
|---|---|---|
| `getAllKnowledgeBases` | `GET /api/knowledgebase/list` | 管理页、问答页、RAG 评测页 |
| `getKnowledgeBase` | `GET /api/knowledgebase/{id}` | API 已封装，页面未调用 |
| `downloadKnowledgeBase` | `GET /api/knowledgebase/{id}/download` | 管理页下载按钮 |

## 2. getAllKnowledgeBases：前端触发

`knowledgeBaseApi.getAllKnowledgeBases(sortBy?, vectorStatus?)` 使用 `URLSearchParams` 组装：

```text
/api/knowledgebase/list
/api/knowledgebase/list?sortBy=size
/api/knowledgebase/list?sortBy=time&vectorStatus=COMPLETED
```

调用场景：

- 管理页：按时间/大小/访问/提问排序；有处理中任务时每 5 秒刷新。
- 问答页：固定 `vectorStatus=COMPLETED`，只允许选择可检索知识库。
- RAG 评测页：同样只加载 `COMPLETED`。

## 3. Controller 参数解析

Controller 接收两个可选字符串：

```java
sortBy
vectorStatus
```

`vectorStatus` 非空时执行 `VectorStatus.valueOf(vectorStatus.toUpperCase())`。非法值直接：

```java
return Result.error("无效的向量化状态: " + vectorStatus);
```

这里使用 `Result.error(String)`，业务 code 是通用 500，不是 BAD_REQUEST；HTTP 仍为 200。

合法时调用：

```java
listService.listKnowledgeBases(status, sortBy)
```

## 4. Service 和 Repository 查询/排序

状态过滤：

```text
status != null
  → repository.findByVectorStatusOrderByUploadedAtDesc(status)
status == null
  → repository.findAllByOrderByUploadedAtDesc()
```

时间排序已经在数据库完成。`sortBy` 为 `size`、`access` 或 `question` 时，Service 对已加载的
完整 Entity 列表在内存重新排序；未知值保持时间顺序，不返回参数错误。

最后通过 `KnowledgeBaseMapper.toListItemDTOList` 转成 DTO，Entity 不直接暴露。

当前没有分页；管理页轮询会反复加载、映射并传输全部匹配记录。

## 5. 列表响应回到页面

```text
List<KnowledgeBaseListItemDTO>
  → Result.success
  → Axios 拦截器提取 data
  → KnowledgeBaseItem[]
```

管理页同时并行请求统计和分类，并根据状态展示进度、失败原因、重试和下载按钮。问答页把结果按
分类在前端分组。

## 6. getKnowledgeBase：当前未使用的单条详情

后端链路：

```text
GET /api/knowledgebase/{id}
  → listService.getKnowledgeBase(id)
  → KnowledgeBaseRepository.findById
  → KnowledgeBaseMapper.toListItemDTO
```

存在时返回 `Result.success(dto)`；不存在时 Controller 返回：

```java
Result.error("知识库不存在")
```

同样会得到通用业务 code 500，而不是 `KNOWLEDGE_BASE_NOT_FOUND`。前端虽然定义了
`knowledgeBaseApi.getKnowledgeBase`，当前没有调用点。

## 7. downloadKnowledgeBase：前端 Blob 链

管理页点击下载：

```text
knowledgeBaseApi.downloadKnowledgeBase(id)
  → 独立 axios.get(url, { responseType: 'blob' })
  → URL.createObjectURL(blob)
  → 临时 <a>.download = kb.originalFilename
  → click + revokeObjectURL
```

这里没有使用项目的 `request` Axios 实例。

## 8. 下载后端链路

Controller 先后调用：

```text
listService.getEntityForDownload(id)
  → Repository.findById，取得文件名和 contentType

listService.downloadFile(id)
  → Repository.findById，再查一次同一 Entity
  → 校验 storageKey
  → FileStorageService.downloadFile(storageKey)
  → RustFS 返回 byte[]
```

Controller 构造原始文件响应：

```text
HTTP 200
Content-Disposition: attachment; filename=...; filename*=UTF-8''...
Content-Type: Entity.contentType 或 application/octet-stream
body: byte[]
```

前端最终使用列表中的 `originalFilename` 作为本地下载名。

## 9. 下载异常的特殊风险

Controller 没有 catch，`BusinessException` 会进入全局异常处理器，按项目约定返回：

```text
HTTP 200
Content-Type 通常为 JSON
body = Result.error(...)
```

但前端独立 Axios 固定 `responseType='blob'`，只看 HTTP 200，不解析 `Result.code`。因此知识库
不存在、storageKey 缺失或下载业务错误时，Promise 可能仍成功 resolve，页面把错误 JSON Blob
当成原文件下载。只有真正的 HTTP/网络错误才进入 catch。

## 10. 当前实现边界

- 列表无分页，非时间排序在内存完成。
- 非法状态和单条不存在使用通用 `Result.error(String)`，错误码不准确。
- 单条详情 API 当前未被页面使用。
- 下载重复查询同一 Entity，存在额外数据库访问和两次查询间被删除的竞态。
- Blob 客户端无法识别 HTTP 200 的统一业务错误，可能下载错误 JSON。
- 下载没有增加 `accessCount`；当前访问计数主要由重复上传等其他路径更新。

## 流程总结

列表接口 `GET /api/knowledgebase/list` 被管理页、问答页和 RAG 评测页同时使用，支持两个可选参数：`sortBy`（time/size/access/question）和 `vectorStatus`（PENDING/PROCESSING/COMPLETED/FAILED）。Controller 将 `vectorStatus` 字符串转为枚举（非法值返回错误），然后委托 `KnowledgeBaseListService.listKnowledgeBases` 查询——有状态过滤时走 `findByVectorStatusOrderByUploadedAtDesc`，无过滤时走 `findAllByOrderByUploadedAtDesc`，默认按时间降序在数据库完成；sortBy 为非 time 时对全量结果在内存中降序排列，最后通过 MapStruct 将实体转为 DTO 返回给前端。详情接口 `GET /api/knowledgebase/{id}` 仅做单表 `findById` 后 MapStruct 转换，不存在时返回通用错误，当前暂无页面调用。下载接口 `GET /api/knowledgebase/{id}/download` 是唯一不走 `Result<T>` 包装的接口，Controller 分两步操作：先查实体获取文件名和 contentType，再从 RustFS 通过 storageKey 下载文件字节数组，最终以 `ResponseEntity<byte[]>` 返回，设置 `Content-Disposition: attachment` 和双重 UTF-8 编码的文件名响应头触发浏览器下载行为。需要注意的是前端使用独立 Axios 实例且固定 `responseType='blob'`，业务错误返回的 JSON 可能被当作文件下载。
