设计了一个专门用于文件处理的一个模块，主要服务于简历上传和知识库上传（但是简历上传还有调用llm解析，知识库还有转向量存数据库的逻辑，和这个模块无关）。包括文件大小校验，MIME类型检测，SHA-256去重，Apache Tika文本解析，文本清洗，以及基于S3兼任协议的Rustfs对象存储。上传链路里，业务模块先调用 `FileValidationService` 校验空文件和大小，再通过 `ContentTypeDetectionService` 用 Tika 检测真实 MIME 类型。然后用 `FileHashService` 计算文件内容 hash 做去重。新文件会交给 `DocumentParseService` 用 Tika 提取文本，解析时禁用嵌入文档提取，并配置 PDF 按坐标排序和关闭图片提取。提取出的文本再经过 `TextCleaningService` 去掉图片路径、控制字符、分隔线等噪声。最后通过 `FileStorageService` 上传到 RustFS，生成 `resumes/yyyy/MM/dd/uuid_filename` 或 `knowledgebases/yyyy/MM/dd/uuid_filename` 这样的对象 key。

<br />

详解：文件存储RustFS。

用这个rustfs存储是因为简历等文件肯定不能直接存在本地的，如果要多实例部署服务端，那如果存储文件只放在一个本地端，其他端无法共享，如果每一个端都发一份，很难同步并且很占空间。知识库和简历存储的文件较大，本地磁盘也不方便扩容和备份，所以用RustFS对象存储客户端，存储文件原件，数据库中存储原件的元数据，storagekey（存储的标识key），还有访问的url，这样想访问文件原件时，可以直接存数据库中获取相关的信息。storagekey设计是 前缀（resume或knowledgebase）+日期+八位uuid+文件名称，中文文件名就转变成无声调拼音。

```
前端上传文件
    ↓
后端接收MultipartFile和业务前缀
    ↓
获取原文件名：简历.pdf
    ↓
生成日期：2026/07/17
    ↓
生成UUID：a3f2b01c
    ↓
拼接fileKey
    ↓
resumes/2026/07/17/a3f2b01c_简历.pdf
    ↓
调用putObject上传
    ↓
RustFS以该fileKey保存对象
    ↓
上传成功后返回fileKey
```

具体的上传方法uploadFile，传入文件和固定前缀，还有存储桶的配置（配置文件中设置，包含s3端点服务地址，访问密钥，存储桶的名称还有区域标识），先获取文件原文件名，然后根据上面说的日期等信息构造filekey，之后就调用putobject方法，上传文件到s3兼任存储中。上传成功了就返回filekey。然后上传方再调用getFileUrl方法，传入之前拿到的filekey，再获取其url，url是文件的网络访问地址。

下载文件时，从对应的存储桶中，下载成字节数组。

```Java
 PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            // 上传文件到 S3 兼容存储桶
            s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            log.info("文件上传成功: {} -> {}", originalFilename, fileKey);
            return fileKey;
```

`fileKey`：文件在某个 Bucket 中的唯一逻辑标识，例如：

```
resumes/2026/07/17/a3f2b01c_简历.pdf

```

完整定位对象通常是：

```
bucketName + fileKey

```

- `fileUrl`：在 `fileKey` 前面加上对象存储的访问端点、Bucket 等信息形成的 HTTP 地址，例如：

```
http://localhost:9000/interview-files/resumes/2026/07/17/a3f2b01c_简历.pdf

```

因此关系是：

```
endpoint + bucket + fileKey = fileUrl

```

但要注意，`fileUrl`并不是文件在某台服务器、某块磁盘上的真实物理位置。RustFS可能把文件拆成多个分片，存储在不同磁盘或节点上，应用不需要知道这些物理细节。

另外，能否直接通过 `fileUrl`获取文件，取决于访问权限：

- Bucket公开：普通 `fileUrl`通常可以直接访问；
- Bucket私有：直接访问可能返回 `403`，需要后端代理下载，或者根据 `bucket + fileKey`生成临时的预签名URL。

可以最终总结为：

> `fileKey`是对象在RustFS中的唯一逻辑标识，后端通过它调用S3 SDK操作文件；`fileUrl`是基于Endpoint、Bucket和fileKey生成的网络访问地址，前端可以通过它访问文件，但私有对象通常需要预签名URL或后端鉴权代理。

用 RustFS 对象存储是因为简历和知识库等文件不能直接存本地——多实例部署时文件只放一台机器其他实例无法访问，每台都存一份又难以同步且浪费空间，而且文件较大、本地磁盘不方便扩容备份，所以采用 RustFS（S3 兼容）存储文件原件，数据库中只存元数据，包括 `storageKey`（文件标识键）和 `storageUrl`（访问 URL），需要访问原件时通过 key 或 url 就能定位到文件。`storageKey` 的设计格式为 `前缀（resumes 或 knowledgebases）/日期路径（yyyy/MM/dd）/8位UUID_安全文件名`，其中中文文件名通过 Pinyin4j 转换为无音调小写拼音再首字母大写（如"简历.pdf" → `JianLi.pdf`），特殊字符统一替换为下划线，只保留字母数字和点号、连字符。RustFS 的连接配置集中在 `StorageConfigProperties` 中，映射 `app.storage` 前缀，包含 S3 端点地址、访问密钥对、存储桶名称和区域标识。具体上传时调用 `uploadFile` 方法，传入 `MultipartFile` 和固定前缀，先获取原文件名并通过 `generateFileKey` 构造 `fileKey`，然后构建 `PutObjectRequest` 设置存储桶、key、contentType 和 contentLength，再通过 `s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()))` 将文件上传到 S3 兼容存储，上传成功后返回 `fileKey`。调用方再调用 `getFileUrl` 方法，传入 `fileKey`，内部通过 `String.format("%s/%s/%s", endpoint, bucket, fileKey)` 拼接出完整访问 URL，最终 `fileKey` 和 `fileUrl` 一起存入数据库。下载文件时调用 `downloadFile`，先通过 `headObject` 检查文件是否存在，确认存在后调用 `s3Client.getObjectAsBytes(getRequest).asByteArray()` 从对应存储桶下载为字节数组返回。删除文件同样先检查存在性，确认后调用 `deleteObject` 删除。启动时还会通过 `ensureBucketExists` 自动检查存储桶是否存在，不存在则创建，避免手动运维。上传过程中的异常分为两类：`IOException` 表示本地读取文件失败，`S3Exception` 表示远端 RustFS 请求失败，分别封装不同的错误码和提示信息。

<br />

更核心的问题：rustfs的分布式配置，

<br />

