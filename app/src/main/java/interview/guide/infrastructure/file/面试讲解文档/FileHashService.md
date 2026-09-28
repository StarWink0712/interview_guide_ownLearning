# FileHashService — 文件哈希服务技术讲解

## 一、流程图

```
                ┌──────────────────────────┐
                │     调用方（Service层）     │
                │  ResumePersistenceService  │
                │  KnowledgeBaseUploadService│
                └─────────────┬──────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │     FileHashService           │
              │     统一哈希计算入口            │
              └───────────────┬───────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
  ┌───────────────┐   ┌───────────────┐   ┌───────────────┐
  │calculateHash  │   │calculateHash  │   │calculateHash  │
  │(MultipartFile)│   │  (byte[])     │   │(InputStream)  │
  └───────┬───────┘   └───────┬───────┘   └───────┬───────┘
          │                   │                   │
          │ getBytes()        │                   │ 分块读取
          ▼                   │                   ▼
  ┌───────────────┐           │           ┌───────────────┐
  │ 读取全部字节   │           │           │ 循环读 buffer  │
  │ 转为 byte[]    │           │           │ digest.update  │
  └───────┬───────┘           │           └───────┬───────┘
          │                   │                   │
          └───────────────────┼───────────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │     MessageDigest SHA-256     │
              │        digest(data)           │
              └───────────────┬───────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │       bytesToHex(bytes)        │
              │   String.format("%02x", b)     │
              │   返回 64 位十六进制哈希字符串    │
              └───────────────────────────────┘
```

## 二、执行步骤详解

1. **入口分发**：调用方根据持有的数据类型，选择对应的重载方法（`MultipartFile`、`byte[]` 或 `InputStream`）。

2. **MultipartFile 重载**：内部调用 `file.getBytes()` 将文件全部读入内存，再委托给 `byte[]` 版本。适合中小文件（简历通常几十 KB~几 MB）。

3. **byte[] 重载**：直接将整个字节数组传给 `MessageDigest.digest(data)`，一次性计算哈希。最快路径，无 I/O 开销。

4. **InputStream 重载**：使用 8KB 缓冲区（`BUFFER_SIZE = 8192`）循环读取，每次调用 `digest.update(buffer, 0, bytesRead)` 渐进式更新。适合大文件，避免将整个文件一次性加载到内存。

5. **哈希计算核心**：`java.security.MessageDigest.getInstance("SHA-256")`，生成 256 位（32 字节）摘要。

6. **字节转十六进制**：`bytesToHex()` 遍历每个字节，用 `String.format("%02x", b)` 转为两位小写十六进制字符，最终返回 64 字符的十六进制字符串。

7. **异常处理**：`NoSuchAlgorithmException` 理论上不会出现（SHA-256 是 JVM 标准算法），但保留防御性捕获；`IOException` 发生在读流失败时。均抛出 `BusinessException(ErrorCode.INTERNAL_ERROR, ...)`。

---

## 三、结构化详细总结

### 功能用途

为简历和知识库文件提供统一的 **SHA-256 哈希值计算**能力，实现**文件级去重**判断。典型场景：用户重复上传同一份简历或知识库文档时，系统通过比对哈希值检测重复文件，避免存储和向量化资源浪费。

### 核心算法

- **算法选型**：SHA-256（SHA-2 家族），256 位输出长度，碰撞概率极低（2^-128 生日攻击），适合文件指纹用途。
- **三种输入适配**：`MultipartFile`（Web 上传）→ `byte[]`（内存/缓存）→ `InputStream`（大文件流式），逐层委托，避免代码重复。
- **流式计算**：`InputStream` 版本使用固定 8KB 缓冲区分块 `update()`，内存占用恒定为 8KB，不受文件大小影响，支持 GB 级大文件。

### 关键实现细节

| 细节 | 说明 |
|------|------|
| `BUFFER_SIZE = 8192` | 经验值，平衡内存占用与系统调用次数 |
| `StringBuilder(bytes.length * 2)` | 预分配容量 64，避免扩容开销 |
| `String.format("%02x", b)` | 字节转十六进制，`%02x` 确保高位补零 |
| 常量提取 `HASH_ALGORITHM` | 算法变更只需改一处 |

### 潜在优化点

- 对于超小文件（< 8KB），byte[] 重载更高效，`InputStream` 重载多了一次 buffer 分配和循环开销。
- 可考虑引入 LRU 哈希缓存，相同文件短期内重复上传时不重复计算。
- 若需更高性能，可考虑 BLAKE3 等现代哈希算法（但需评估 JVM 生态支持）。

---

## 四、一段话总结

FileHashService 是面试平台文件处理链路中的去重守门员，它通过三个重载方法统一提供 SHA-256 哈希计算能力，分别适配 MultipartFile（Web 上传场景，委托给 byte[] 处理）、byte[]（内存数据，一次性 digest 最快路径）和 InputStream（大文件流式场景，8KB 缓冲区分块 update 实现 O(1) 内存占用）。哈希结果的 bytesToHex 转换采用预分配容量的 StringBuilder 和 String.format("%02x") 保证 64 位十六进制输出，算法常量 HASH_ALGORITHM 和缓冲区大小 BUFFER_SIZE 均提取为静态常量便于统一调整。这个服务的设计亮点在于：接口层面通过方法重载统一了不同数据源的接入方式，实现层面通过流式分块计算使内存占用不受文件大小影响，同时防御性地捕获了 NoSuchAlgorithmException 和 IOException 并统一转为业务异常，完美契合项目的异常处理规范。
