/**
 * 简历文件解析服务
 *
 * 负责解析上传的简历文件，提取文本内容。支持 MultipartFile 和字节数组两种输入方式，
 * 以及从 RustFS 存储下载后解析。还提供文件 MIME 类型检测功能。
 * 实际解析逻辑委托给基础设施层的 DocumentParseService（基于 Apache Tika）。
 *
 * 联动关系：
 * - 被 ResumeUploadService 调用，解析上传文件和检测文件类型
 * - 依赖 DocumentParseService 执行实际的文档解析
 * - 依赖 ContentTypeDetectionService 检测文件 MIME 类型
 * - 依赖 FileStorageService 从存储下载文件
 */
package interview.guide.modules.resume.service;

import interview.guide.infrastructure.file.ContentTypeDetectionService;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历解析服务
 * 委托给通用的 DocumentParseService 处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeParseService {

    // 依赖注入,文档解析服务，内容类型检测服务，文件存储服务
    private final DocumentParseService documentParseService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final FileStorageService storageService;

    /**
     * 解析上传的简历文件，提取文本内容
     *
     * @param file 上传的文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 提取的文本内容
     */
    public String parseResume(MultipartFile file) {
        log.info("开始解析简历文件: {}", file.getOriginalFilename());
        return documentParseService.parseContent(file);
    }

    /**
     * 解析字节数组形式的简历文件
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（用于日志）
     * @return 提取的文本内容
     */
    public String parseResume(byte[] fileBytes, String fileName) {
        log.info("开始解析简历文件（从字节数组）: {}", fileName);
        return documentParseService.parseContent(fileBytes, fileName);
    }

    /**
     * 从存储下载文件并解析内容
     *
     * @param storageKey       存储键
     * @param originalFilename 原始文件名
     * @return 提取的文本内容
     */
    public String downloadAndParseContent(String storageKey, String originalFilename) {
        log.info("从存储下载并解析简历文件: {}", originalFilename);
        return documentParseService.downloadAndParseContent(storageService, storageKey, originalFilename);
    }

    /**
     * 检测文件的MIME类型
     */
    public String detectContentType(MultipartFile file) {
        return contentTypeDetectionService.detectContentType(file);
    }
}
