# RustFS 对象存储

## 1. 为什么文件不能直接存应用服务器本地磁盘？

**回答：** 多实例部署时，如果文件只写在某一台机器，其他实例无法访问；每台机器都保存副本又难以同步并浪费空间。简历和知识库文件还会持续增长，本地磁盘不便扩容和备份。因此项目使用支持 S3 协议的 RustFS 统一保存文件原件，让所有服务实例通过网络访问同一份对象。

## 2. RustFS 和数据库分别保存什么？

**回答：** RustFS 保存简历和知识库的原始二进制文件；数据库只保存业务元数据，例如原文件名、大小、MIME 类型、`storageKey`、`storageUrl`、哈希以及解析后的文本。业务需要下载或重新解析原件时，从数据库取得 key 或 URL，再访问对象存储。

## 3. RustFS 的连接配置如何管理？

**回答：** 配置集中在 `StorageConfigProperties`，映射 `app.storage` 前缀，包括 S3 端点地址、访问密钥对、存储桶名称和区域标识。`FileStorageService` 根据这些配置构建 S3 客户端，业务 Service 不直接散落读取端点或密钥。

## 4. storageKey 的格式是怎么设计的？

**回答：** Key 使用 `业务前缀/日期路径/8位UUID_安全文件名`，例如 `resumes/yyyy/MM/dd/uuid_filename` 或 `knowledgebases/yyyy/MM/dd/uuid_filename`。业务前缀区分简历和知识库，日期便于按时间组织对象，8 位 UUID 降低同名文件冲突，安全文件名保留可读性。

## 5. 中文和特殊字符文件名如何处理？

**回答：** 中文文件名通过 Pinyin4j 转换为无音调小写拼音，再将首字母大写，例如“简历.pdf”转换为 `JianLi.pdf`。特殊字符统一替换为下划线，只保留字母、数字、点号和连字符，避免对象 Key 中出现不兼容字符。

## 6. uploadFile 的完整上传流程是什么？

**回答：** 方法接收 `MultipartFile` 和固定业务前缀，先读取原文件名并通过 `generateFileKey` 构造 key；然后创建 `PutObjectRequest`，设置 bucket、key、contentType 和 contentLength；最后调用 `s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()))` 把输入流上传到 RustFS，成功后返回 `fileKey`。

## 7. fileUrl 是怎么生成的？

**回答：** 上传成功后，调用方把 `fileKey` 传给 `getFileUrl()`。方法通过 `String.format("%s/%s/%s", endpoint, bucket, fileKey)` 拼接端点、存储桶和对象 Key，得到完整访问 URL；最终 `fileKey` 和 `fileUrl` 一起保存到数据库。

## 8. 文件下载流程是什么？

**回答：** `downloadFile()` 先通过 `headObject` 检查目标对象是否存在，确认后构造下载请求并调用 `s3Client.getObjectAsBytes(getRequest).asByteArray()`，从对应存储桶取得完整字节数组。下载结果可以直接返回前端，也可以包装成输入流重新交给 Tika 解析。

## 9. 文件删除流程是什么？

**回答：** 删除前同样先检查对象是否存在，确认后调用 S3 客户端的 `deleteObject`。先判断存在性可以让删除逻辑更明确，也便于对对象不存在和远端删除失败分别记录或转换业务异常。

## 10. 为什么应用启动时要自动检查存储桶？

**回答：** 启动阶段通过 `ensureBucketExists()` 检查配置的 bucket 是否存在，不存在时自动创建。这样应用在新环境部署后不依赖人工预建存储桶，可以避免第一次上传时因为基础资源缺失而失败。

## 11. RustFS 上传异常如何分类？

**回答：** `IOException` 表示应用读取本地上传流时失败，`S3Exception` 表示访问远端 RustFS 的请求失败，例如认证、网络或服务端错误。系统对两类异常分别封装不同错误码和提示信息，便于判断问题发生在本地文件读取还是对象存储调用。

