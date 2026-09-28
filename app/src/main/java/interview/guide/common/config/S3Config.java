/**
 * S3 客户端配置（用于 RustFS）
 *
 * 基于 StorageConfigProperties 构建 S3Client Bean，使用路径风格访问（forcePathStyle），
 * 兼容 RustFS 等 S3 兼容存储服务。
 *
 * 联动关系：
 * - 依赖 StorageConfigProperties 读取 app.storage 配置
 * - 产出 S3Client Bean，被 FileStorageService 使用
 */
package interview.guide.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * S3 客户端配置（用于 RustFS）
 *
 * 基于 StorageConfigProperties 构建 S3Client Bean，使用路径风格访问（forcePathStyle），
 * 兼容 RustFS 等 S3 兼容存储服务。
 *
 * 联动关系：
 * - 依赖 StorageConfigProperties 读取 app.storage 配置
 * - 产出 S3Client Bean，被 FileStorageService 使用
 */
@Configuration
@RequiredArgsConstructor
public class S3Config {

    /** RustFS 存储配置属性 */
    private final StorageConfigProperties storageConfig;

    /**
     * 构建 S3Client Bean。
     *
     * <p>核心配置说明：
     * <ul>
     *   <li><b>endpointOverride</b>：覆盖默认的 AWS S3 端点，指向自定义 S3 兼容服务（MinIO/RustFS）</li>
     *   <li><b>region</b>：S3 区域标识，RustFS 通常使用 us-east-1</li>
     *   <li><b>credentialsProvider</b>：静态凭证提供者，使用配置的 accessKey/secretKey</li>
     *   <li><b>forcePathStyle(true)</b>：<b>关键配置</b>，强制使用路径风格 URL 访问
     *       （{@code http://endpoint/bucket/key}）而非虚拟主机风格
     *       （{@code http://bucket.endpoint/key}）。
     *       若不启用此选项，SDK 会尝试 DNS 解析 {@code bucket.endpoint}，
     *       导致 MinIO/RustFS 等本地服务无法正常工作</li>
     * </ul>
     *
     * <p>路径风格 vs 虚拟主机风格示例：
     * <pre>
     * 路径风格（forcePathStyle=true）：GET http://localhost:9000/interview-guide/myfile.pdf
     * 虚拟主机风格（默认）：        GET http://interview-guide.localhost:9000/myfile.pdf ← DNS 解析失败
     * </pre>
     *
     * @return 配置完成的 S3Client 实例
     */
    @Bean
    public S3Client s3Client() {
        // 使用静态凭证（Access Key + Secret Key）
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
            storageConfig.getAccessKey(),
            storageConfig.getSecretKey()
        );

        return S3Client.builder()
            // 覆盖默认 AWS 端点，指向自定义 S3 兼容服务
            .endpointOverride(URI.create(storageConfig.getEndpoint()))
            // 设置区域标识
            .region(Region.of(storageConfig.getRegion()))
            // 静态凭证认证
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            // 强制路径风格：兼容 MinIO/RustFS 等非 AWS 官方的 S3 兼容存储
            .forcePathStyle(true)
            .build();
    }
}
