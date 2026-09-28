/**
 * 知识库文件解析服务
 *
 * 委托给基础设施层的 DocumentParseService 处理文件内容提取，
 * 支持 PDF、DOCX、DOC、TXT、MD 等格式。同时提供 MIME 类型检测和
 * 从 RustFS 存储下载文件后解析的能力。
 *
 * 联动关系：
 * - 被 KnowledgeBaseUploadService 调用（上传时解析文件内容和检测类型）
 * - 被 KnowledgeBaseUploadService.revectorize() 调用（重新向量化时从存储下载并解析）
 * - 委托 DocumentParseService 执行实际的文件解析
 * - 委托 ContentTypeDetectionService 检测文件 MIME 类型
 * - 委托 FileStorageService 下载存储文件
 */
package interview.guide.modules.knowledgebase.service;

import interview.guide.infrastructure.file.ContentTypeDetectionService;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库解析服务
 * 委托给通用的 DocumentParseService 处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseParseService {

    //文档解析服务，负责解析上传的知识库文件，提取文本内容
    private final DocumentParseService documentParseService;
    //文件类型检测服务，负责检测上传的知识库文件的 MIME 类型
    private final ContentTypeDetectionService contentTypeDetectionService;
    //文件存储服务，负责从 RustFS 存储下载文件
    private final FileStorageService storageService;

    /**
     * 解析上传的知识库文件，提取文本内容
     *
     * @param file 上传的文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 提取的文本内容
     */
    public String parseContent(MultipartFile file) {
        log.info("开始解析知识库文件: {}", file.getOriginalFilename());
        return documentParseService.parseContent(file);
    }

    /**
     * 解析字节数组形式的文件内容
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（用于日志）
     * @return 提取的文本内容
     */
    public String parseContent(byte[] fileBytes, String fileName) {
        log.info("开始解析知识库文件（从字节数组）: {}", fileName);
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
        log.info("从存储下载并解析知识库文件: {}", originalFilename);
        return documentParseService.downloadAndParseContent(storageService, storageKey, originalFilename);
    }

    /**
     * 检测文件的MIME类型
     */
    public String detectContentType(MultipartFile file) {
        return contentTypeDetectionService.detectContentType(file);
    }
}
