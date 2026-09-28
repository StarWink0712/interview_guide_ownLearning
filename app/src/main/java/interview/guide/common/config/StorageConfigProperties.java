/**
 * RustFS (S3 兼容) 存储配置属性
 *
 * 映射 app.storage 前缀的配置项，包括 endpoint、accessKey、secretKey、bucket、region。
 *
 * 联动关系：
 * - 被 S3Config 注入，用于构建 S3Client Bean
 * - 被 FileStorageService 注入，用于文件上传/下载/删除操作
 */
package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RustFS (S3 兼容) 存储配置属性
 *
 * 映射 app.storage 前缀的配置项，包括 endpoint、accessKey、secretKey、bucket、region。
 *
 * 联动关系：
 * - 被 S3Config 注入，用于构建 S3Client Bean
 * - 被 FileStorageService 注入，用于文件上传/下载/删除操作
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageConfigProperties {

    /** S3 兼容存储服务端点地址 */
    private String endpoint;
    /** 访问密钥 Access Key */
    private String accessKey;
    /** 访问密钥 Secret Key */
    private String secretKey;
    /** 存储桶名称 */
    private String bucket;
    /** 区域标识，默认 us-east-1 */
    private String region = "us-east-1";
}
