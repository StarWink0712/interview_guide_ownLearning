/**
 * 简历上传应用配置属性
 *
 * 映射 app.resume 前缀的配置项，包括上传目录（uploadDir）和允许的文件类型列表（allowedTypes）。
 *
 * 联动关系：
 * - 被 ResumeUploadService 注入，用于校验上传文件类型和确定上传目录
 */
package interview.guide.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 简历上传应用配置属性
 *
 * 映射 app.resume 前缀的配置项，包括上传目录（uploadDir）和允许的文件类型列表（allowedTypes）。
 *
 * 联动关系：
 * - 被 ResumeUploadService 注入，用于校验上传文件类型和确定上传目录
 */
@Component
@ConfigurationProperties(prefix = "app.resume")
public class AppConfigProperties {

    /** 上传目录路径 */
    private String uploadDir;
    /** 允许的文件类型列表 */
    private List<String> allowedTypes;
    
    public String getUploadDir() {
        return uploadDir;
    }
    
    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }
    
    public List<String> getAllowedTypes() {
        return allowedTypes;
    }
    
    public void setAllowedTypes(List<String> allowedTypes) {
        this.allowedTypes = allowedTypes;
    }
}
