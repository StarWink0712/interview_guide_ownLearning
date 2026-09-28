ApiKeyEncryptionService 代码分析

### 📌 核心作用

**ApiKeyEncryptionService** 是一个 API Key 加密/解密服务，使用 **AES-256-GCM 算法** 对数据库中存储的 Provider API Key 进行加密保护。

***

### 🔍 安全设计分析

#### 为什么需要加密 API Key？

```
未加密的 API Key 风险：
┌─────────────────────────────────────────────────────────────────┐
│                      数据库泄露风险                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   llm_provider 表：                                              │
│   ┌─────────────┬─────────────────────────────────┐             │
│   │ id          │ base_url                         │             │
│   ├─────────────┼─────────────────────────────────┤             │
│   │ dashscope   │ https://dashscope...            │             │
│   │ api_key     │ sk-1234567890abcdef1234567890   │ ❌ 明文存储！ │
│   └─────────────┴─────────────────────────────────┘             │
│                                                                 │
│   数据库被黑客入侵 → API Key 泄露 → 第三方恶意调用 → 产生费用     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

加密存储后的 API Key：
┌─────────────┬─────────────────────────────────┐
│ id          │ base_url                         │
├─────────────┼─────────────────────────────────┤
│ dashscope   │ https://dashscope...            │
│ api_key     │ nonce: "...", ciphertext: "..." │ ✅ 加密存储！ │
└─────────────┴─────────────────────────────────┘
```

#### 加密算法选择：AES-256-GCM

| 特性                            | 说明          | 优势                |
| ----------------------------- | ----------- | ----------------- |
| **AES-256**                   | 对称加密，256位密钥 | 安全性高，无法暴力破解       |
| **GCM (Galois/Counter Mode)** | 认证加密模式      | 提供完整性和认证，防止篡改     |
| **Nonce**                     | 每次加密的随机数    | 防止重放攻击，相同明文产生不同密文 |

***

### 🛠️ 核心方法解析

#### 1. 加密方法 `encrypt()`

```java
public EncryptedValue encrypt(String plainText) {
    try {
        // 1. 生成随机 nonce（每次加密都不同）
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        
        // 2. 初始化 AES-256-GCM 加密器
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, 
                   new GCMParameterSpec(GCM_TAG_BITS, nonce));
        
        // 3. 执行加密
        byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        
        // 4. 返回 Base64 编码的 nonce 和密文
        return new EncryptedValue(
            Base64.getEncoder().encodeToString(nonce),
            Base64.getEncoder().encodeToString(ciphertext)
        );
    } catch (Exception e) {
        throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, 
                                 "加密 Provider API Key 失败", e);
    }
}
```

**加密后的存储格式**：

```
数据库存储：
┌─────────────────────────────────────────────────────────────────┐
│ nonce: "dGhpcyBpcyBhIGNoYW5nZSBsb25nIHRlbXBsYXRl"              │
│ ciphertext: "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"     │
└─────────────────────────────────────────────────────────────────┘
```

#### 2. 解密方法 `decrypt()`

```java
public String decrypt(String nonceBase64, String ciphertextBase64) {
    try {
        // 1. Base64 解码
        byte[] nonce = Base64.getDecoder().decode(nonceBase64);
        byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);
        
        // 2. 初始化解密器
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, 
                   new GCMParameterSpec(GCM_TAG_BITS, nonce));
        
        // 3. 执行解密
        byte[] plainText = cipher.doFinal(ciphertext);
        return new String(plainText, StandardCharsets.UTF_8);
    } catch (Exception e) {
        throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, 
                                 "解密 Provider API Key 失败", e);
    }
}
```

***

### ⚙️ 密钥管理设计

#### 密钥来源解析

```java
private byte[] resolveKeyBytes(String configuredKey) {
    String trimmed = configuredKey.trim();
    
    // 1. 尝试作为 Base64 编码的密钥
    try {
        byte[] decoded = Base64.getDecoder().decode(trimmed);
        if (decoded.length == 32) {  // AES-256 需要 32 字节
            return decoded;
        }
    } catch (IllegalArgumentException ignored) {
        // 继续下一步
    }
    
    // 2. 作为人类可读密钥，通过 SHA-256 派生
    return sha256(trimmed);
}
```

**支持两种密钥格式**：

| 格式            | 示例                                                 | 说明                       |
| ------------- | -------------------------------------------------- | ------------------------ |
| **Base64 编码** | `NzQ3MzU4Y2EtZjVmYi00Y2Y1LTg2YzktNTI3NDk1ODViMzEz` | 直接使用，长度必须是 32 字节         |
| **人类可读**      | `my-secret-key`                                    | 自动通过 SHA-256 派生出 32 字节密钥 |

#### 开发环境回退机制

```java
if (configuredKey == null || configuredKey.isBlank()) {
    if (security != null && security.isRequireEncryptionKey()) {
        throw new BusinessException(..., "APP_AI_CONFIG_ENCRYPTION_KEY 未配置，无法解密");
    }
    log.warn("APP_AI_CONFIG_ENCRYPTION_KEY is not configured; using development fallback key");
    configuredKey = DEV_FALLBACK_KEY;  // "interview-guide-dev-only..."
}
```

**环境适应**：

- **生产环境**：必须配置加密密钥
- **开发环境**：使用默认回退密钥

***

### 🔄 在项目中的使用场景

#### 1. 创建/更新 Provider

```java
// LlmProviderConfigService 中
@Service
public class LlmProviderConfigService {

    private final ApiKeyEncryptionService encryptionService;

    public LlmProviderEntity createProvider(CreateProviderRequest request) {
        // 1. 加密 API Key
        ApiKeyEncryptionService.EncryptedValue encrypted = 
            encryptionService.encrypt(request.getApiKey());
        
        // 2. 存储加密后的值
        LlmProviderEntity entity = LlmProviderEntity.builder()
            .id(request.getId())
            .baseUrl(request.getBaseUrl())
            .apiKeyNonce(encrypted.nonce())        // 存储 nonce
            .apiKeyCiphertext(encrypted.ciphertext()) // 存储密文
            .build();
        
        return providerRepository.save(entity);
    }
}
```

#### 2. 使用 Provider

```java
// LlmProviderRegistry 中
public ChatClient getChatClient(String providerId) {
    // 1. 从数据库读取加密数据
    LlmProviderEntity entity = providerRepository.findById(providerId).orElseThrow();
    
    // 2. 解密 API Key
    String apiKey = encryptionService.decrypt(
        entity.getApiKeyNonce(), 
        entity.getApiKeyCiphertext()
    );
    
    // 3. 使用 API Key 创建 ChatClient
    OpenAiApi api = ApiPathResolver.buildOpenAiApi(entity.getBaseUrl(), apiKey);
    // ...
}
```

#### 3. 种子数据导入

```java
// LlmProviderBootstrapService 中
private void seedProviders() {
    providers.forEach((id, config) -> {
        // 加密种子配置中的 API Key
        ApiKeyEncryptionService.EncryptedValue encrypted = 
            encryptionService.encrypt(config.getApiKey());
        
        // 构建实体（加密存储）
        LlmProviderEntity entity = LlmProviderEntity.builder()
            .id(id)
            .baseUrl(config.getBaseUrl())
            .apiKeyNonce(encrypted.nonce())
            .apiKeyCiphertext(encrypted.ciphertext())
            .build();
        
        providerRepository.save(entity);
    });
}
```

***

### 🛡️ 安全设计亮点

1. **每次加密结果不同**：不同 nonce 确保相同明文产生不同密文
2. **完整性校验**：GCM 模式验证数据是否被篡改
3. **密钥派生**：支持人类可读密钥，自动 SHA-256 派生
4. **环境适应**：开发/生产环境不同策略
5. **错误处理**：失败时抛出业务异常，避免信息泄露

***

### 💡 总结

| 问题       | 答案                                 |
| -------- | ---------------------------------- |
| **核心作用** | 加密存储、解密读取 Provider API Key，防止数据库泄露 |
| **加密算法** | AES-256-GCM（现代标准，安全性高）             |
| **密钥管理** | 支持 Base64 和人类可读两种格式，自动派生           |
| **项目作用** | 保护 LLM 服务商的 API Key 安全             |
| **使用场景** | 创建 Provider、查询 Provider、种子数据导入     |
| **安全设计** | 每次加密不同、完整性校验、错误处理                  |

**简单来说**：这个服务就像一个"保险柜"，把 API Key 这个"贵重物品"安全地存起来，需要用时再取出来，防止数据库泄露导致 API Key 被盗用。
