/**
 * API Key 加密/解密服务
 *
 * 使用 AES-256-GCM 算法对 Provider 的 API Key 进行加密存储和解密读取。
 * 加密密钥从 LlmProviderProperties.SecurityConfig 获取，支持 Base64 编码的原始密钥
 * 或人类可读密钥（通过 SHA-256 派生）。开发环境未配置密钥时使用回退密钥。
 *
 * 联动关系：
 * - 被 LlmProviderConfigService 依赖，在 Provider 创建/更新时加密 API Key，读取时解密
 * - 被 LlmProviderBootstrapService 依赖，在种子初始化时加密 Provider API Key
 * - 依赖 LlmProviderProperties 获取加密密钥配置
 */
package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ApiKeyEncryptionService {

  // 加密参数
  private static final int NONCE_BYTES = 12;
  // GCM 标签长度，单位：位
  private static final int GCM_TAG_BITS = 128;
  // 加密算法
  private static final String CIPHER = "AES/GCM/NoPadding";
  // 开发环境回退密钥
  // 注意：仅用于开发环境，生产环境应配置密钥
  // 密钥长度：32 字节，与 AES-256 兼容
  private static final String DEV_FALLBACK_KEY =
      "interview-guide-dev-only-provider-api-key-encryption";

  // 加密密钥
  // 从 LlmProviderProperties.SecurityConfig 获取
  private final LlmProviderProperties properties;
  // 随机数生成器
  // 用于生成随机的 nonce 
  // 静态实例，避免每次调用都创建新的实例
  private final SecureRandom secureRandom = new SecureRandom();
  // 加密密钥
  // 从 LlmProviderProperties.SecurityConfig 获取
  private SecretKeySpec secretKey;

  // 构造函数
  // 依赖 LlmProviderProperties 获取加密密钥配置
  public ApiKeyEncryptionService(LlmProviderProperties properties) {
    this.properties = properties;
  }
  // 初始化加密密钥
  // 从 LlmProviderProperties.SecurityConfig 获取配置的密钥
  // 如果未配置，使用回退密钥
  @PostConstruct
  void init() {
    //从 LlmProviderProperties.SecurityConfig 获取配置的密钥
    LlmProviderProperties.SecurityConfig security = properties.getSecurity();
    //如果未配置密钥，使用回退密钥
    String configuredKey = security != null ? security.getApiKeyEncryptionKey() : null;
    if (configuredKey == null || configuredKey.isBlank()) {
      if (security != null && security.isRequireEncryptionKey()) {
        throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
            "APP_AI_CONFIG_ENCRYPTION_KEY 未配置，无法解密 Provider API Key");
      }
      log.warn("APP_AI_CONFIG_ENCRYPTION_KEY is not configured; using development fallback key");
      configuredKey = DEV_FALLBACK_KEY;
    }
    secretKey = new SecretKeySpec(resolveKeyBytes(configuredKey), "AES");
  }
  // 加密 API Key
  // 生成随机 nonce
  // 使用 AES-256-GCM 算法加密
  // 返回 nonce 和加密后的 Base64 编码字符串
  //入参：明文api key，即Provider API Key，plaintext就是Provider API Key
  //出参：加密后的 Base64 编码字符串
  public EncryptedValue encrypt(String plainText) {
    try {
      //加密参数是12字节的随机数
      byte[] nonce = new byte[NONCE_BYTES];
      //生成随机数
      secureRandom.nextBytes(nonce);

      //初始化加密器
      //使用GCM模式，设置标签长度为128位
      Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      //加密明文字符串
      byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

      return new EncryptedValue(
          //将随机数转换为Base64编码的字符串
          Base64.getEncoder().encodeToString(nonce),
          //将加密后的字符串转换为Base64编码的字符串
          Base64.getEncoder().encodeToString(ciphertext)
      );
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          "加密 Provider API Key 失败", e);
    }
  }
  // 解密 API Key
  // 从 Base64 编码的 nonce 和加密字符串中提取原始密钥
  // 使用 AES-256-GCM 算法解密
  // 返回解密后的明文字符串
  public String decrypt(String nonceBase64, String ciphertextBase64) {
    try {
      byte[] nonce = Base64.getDecoder().decode(nonceBase64);
      byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);

      Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      byte[] plainText = cipher.doFinal(ciphertext);
      return new String(plainText, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "解密 Provider API Key 失败，请检查 APP_AI_CONFIG_ENCRYPTION_KEY", e);
    }
  }
  // 解密 API Key
  // 从 Base64 编码的 nonce 和加密字符串中提取原始密钥
  // 使用 AES-256-GCM 算法解密
  // 返回解密后的明文字符串
  // 如果解密失败，抛出 BusinessException
  private byte[] resolveKeyBytes(String configuredKey) {
    String trimmed = configuredKey.trim();
    try {
      byte[] decoded = Base64.getDecoder().decode(trimmed);
      if (decoded.length == 32) {
        return decoded;
      }
    } catch (IllegalArgumentException ignored) {
      // Fall through to SHA-256 derivation for human-readable keys.
    }
    return sha256(trimmed);
  }

  private byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "初始化 Provider API Key 加密密钥失败", e);
    }
  }

  public record EncryptedValue(String nonce, String ciphertext) {
  }
}
