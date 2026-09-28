# FileStorageService — 文件存储服务技术讲解

## 一、流程图

```
              ┌──────────────────────────────┐
              │        业务 Service            │
              │  ResumeUploadService          │
              │  KnowledgeBaseUploadService   │
              └──────────────┬───────────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
    uploadResume()   uploadKnowledgeBase()  downloadFile()
              │              │              │
              └──────┬───────┘              │
                     │                      │
                     ▼                      ▼
            ┌────────────────┐    ┌────────────────────┐
            │  uploadFile()  │    │  fileExists() 先校验 │
            │  通用上传方法    │    │  → 不存在抛异常      │
            └───────┬────────┘    │  → 存在则下载        │
                    │             └────────────────────┘
                    ▼
          ┌─────────────────────┐
          │  generateFileKey()   │
          │  生成存储路径+文件名  │
          └─────────┬───────────┘
                    │
          ┌─────────┼──────────┐
          │         │          │
          ▼         ▼          ▼
    prefix/      日期路径    UUID_安全文件名
 "resumes"/    yyyy/MM/dd   8位UUID_拼音文件名
 "knowledgebases"
                    │
                    ▼
          ┌─────────────────────┐
          │  sanitizeFilename() │
          │  文件名安全清洗       │
          └─────────┬───────────┘
                    │
          ┌─────────┼──────────┐
          │         │          │
          ▼         ▼          ▼
    convertToPinyin()  sanitizeChar()  capitalize()
    汉字 → 拼音        特殊字符 → _    首字母大写(大驼峰)
          │
          ▼
    ┌──────────────────────────────┐
    │    PutObjectRequest          │
    │    .bucket(config)           │
    │    .key(fileKey)             │
    │    .contentType(mime)        │
    │    .contentLength(size)      │
    └──────────────┬───────────────┘
                   │
                   ▼
    ┌──────────────────────────────┐
    │   s3Client.putObject()       │
    │   RustFS / S3 兼容存储       │
    └──────────────────────────────┘

    删除路径：
    fileKey → fileExists()? → 否 → skip
                  ↓ 是
           s3Client.deleteObject()
                → 成功/失败
```

## 二、执行步骤详解

1. **业务入口方法**（`uploadResume`、`uploadKnowledgeBase`）：区分业务前缀 `"resumes"` 和 `"knowledgebases"`，委托给通用 `uploadFile()` 方法。

2. **文件键生成（`generateFileKey`）**：
   - 格式：`{prefix}/{yyyy/MM/dd}/{8位UUID}_{安全文件名}`
   - 日期路径：`LocalDateTime.now().format("yyyy/MM/dd")`，按日期分区便于管理
   - UUID：取前 8 位，在短小和唯一性之间取得平衡
   - 安全文件名：通过 `sanitizeFilename` 清洗原始文件名

3. **文件名清洗（`sanitizeFilename → convertToPinyin`）**：
   - 汉字转为**无音调小写拼音**（Pinyin4j 库），再首字母大写形成大驼峰
   - 非汉字字符调用 `sanitizeChar()` 过滤：只保留 `a-z`、`A-Z`、`0-9`、`.`、`_`、`-`，其余替换为 `_`
   - 目的：避免 S3 兼容存储出现 URL 编码问题或特殊字符报错

4. **文件上传（`uploadFile`）**：
   - 构建 `PutObjectRequest`，传入 bucket、key、contentType、contentLength
   - `RequestBody.fromInputStream(file.getInputStream(), file.getSize())` 流式上传
   - 返回 `fileKey` 供后续数据库持久化

5. **文件下载（`downloadFile`）**：
   - 先 `fileExists()` 检查，不存在抛 `BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED)`
   - 使用 `getObjectAsBytes()` 返回 `byte[]`

6. **文件删除（`deleteFile`）**：
   - 空键直接跳过
   - 先 `fileExists` 检查，不存在跳过（幂等）
   - 再执行 `deleteObject`

7. **桶管理（`ensureBucketExists`）**：
   - 应用启动时调用，检查桶是否存在
   - 不存在则自动创建（适用于内网 RustFS 自运维场景）

---

## 三、结构化详细总结

### 功能用途

基于 **S3 兼容协议（RustFS）** 实现文件存储层，统一管理简历和知识库文件的上传、下载、删除和元信息查询。对外暴露按业务模块命名的语义化方法（`uploadResume`、`uploadKnowledgeBase`），内部统一委托到通用 S3 操作方法。

### 核心算法

- **文件键生成算法**：`prefix + 日期分区 + UUID前缀 + 拼音文件名`，四层结构确保路径可读、可追溯、无冲突。
- **汉字拼音转换**：Pinyin4j 库实现汉字→拼音，配置为**小写无音调**（`HanyuPinyinCaseType.LOWERCASE` + `HanyuPinyinToneType.WITHOUT_TONE`），再通过 `capitalize()` 转大驼峰。
- **安全字符过滤**：仅保留字母数字和少数安全符号，其余替换为 `_`，防止特殊字符破坏 S3 路径。

### 关键实现细节

| 细节 | 说明 |
|------|------|
| `DATE_PATH_FORMAT = "yyyy/MM/dd"` | 静态常量，避免重复创建 DateTimeFormatter |
| UUID 前 8 位 | 碰撞概率约为 2^32 分之一（短 UUID），结合日期前缀后实际碰撞概率极低 |
| `contentType` 设置 | 上传时保留原始 `file.getContentType()`，便于 S3 返回正确的 Content-Type |
| 删除幂等性 | 删除前检查存在性，文件不存在时静默跳过而非报错 |
| `HeadObjectRequest` | `fileExists` 和 `getFileSize` 使用 HEAD 请求，不下载文件体 |

### 潜在优化点

- `downloadFile()` 使用 `getObjectAsBytes()` 一次性加载到内存，对大文件（> 100MB）存在 OOM 风险，可增加流式下载重载。
- `convertToPinyin` 每次创建新的 `HanyuPinyinOutputFormat`，可提取为静态常量避免重复创建。
- 文件键中 `UUID.substring(0,8)` 碰撞风险随文件量增长而增加，可考虑使用完整 UUID 或 Snowflake ID。
- 可增加 S3 预签名 URL 功能，用于前端直传/下载，减少后端带宽压力。

---

## 四、一段话总结

FileStorageService 基于 S3 兼容协议（RustFS）构建了面试平台的文件存储层，对外暴露按业务模块命名的语义化方法（uploadResume、uploadKnowledgeBase、deleteResume、deleteKnowledgeBase），内部统一委托到通用的 uploadFile/deleteFile/downloadFile 私有方法操作 S3 SDK，体现了经典的门面模式设计。文件键生成算法是此服务的核心亮点——它通过四层结构（业务前缀 + yyyy/MM/dd 日期分区 + 8 位 UUID 前缀 + 安全文件名）确保路径既可读又无冲突，其中安全文件名通过 sanitizeFilename 方法实现了完整的汉字转拼音管线：Pinyin4j 库将汉字转为小写无音调拼音，capitalize 方法转大驼峰，sanitizeChar 方法将特殊字符统一替换为下划线，最终生成如 ZhangSanJianLi.pdf 的纯 ASCII 安全文件名，从根本上避免了非 ASCII 路径在 S3 兼容存储中的兼容性问题。此外，文件存在性检查和大小查询使用 HeadObjectRequest 而非下载全文，删除操作具备幂等性（不存在则静默跳过），桶不存在时自动创建以适配内网自运维场景，这些细节共同保证了存储层在生产环境下的健壮性。
