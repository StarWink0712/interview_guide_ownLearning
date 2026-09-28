/**
 * 知识库上传服务
 *
 * 编排知识库文件上传的完整业务流程：文件验证 → 类型检测 → 哈希去重 →
 * 文本解析 → RustFS 存储 → 元数据持久化 → 发送向量化任务到 Redis Stream。
 * 同时提供手动重新向量化功能（revectorize），从 RustFS 重新下载文件后重新入队。
 * 向量化采用异步处理，上传完成后状态为 PENDING，前端可轮询获取最新状态。
 *
 * 联动关系：
 * - 被 KnowledgeBaseController 委托处理上传和重新向量化请求
 * - 依赖 KnowledgeBaseParseService 解析文件内容和检测类型
 * - 依赖 KnowledgeBasePersistenceService 处理数据库事务（保存/去重/状态更新）
 * - 依赖 FileStorageService 上传文件到 RustFS
 * - 依赖 FileValidationService 验证文件大小和类型
 * - 依赖 FileHashService 计算文件哈希用于去重
 * - 依赖 VectorizeStreamProducer 发送向量化任务到 Redis Stream
 * - 依赖 KnowledgeBaseRepository 查找已有知识库（去重检查）
 */
package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Optional;

/**
 * 知识库上传服务
 * 处理知识库上传、解析的业务逻辑
 * 向量化改为异步处理，通过 Redis Stream 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseUploadService {
    // 解析服务
    private final KnowledgeBaseParseService parseService;
    // 持久化服务
    private final KnowledgeBasePersistenceService persistenceService;
    // 文件存储服务
    private final FileStorageService storageService;
    // 知识库仓库
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    // 文件验证服务
    private final FileValidationService fileValidationService;
    // 文件哈希服务
    private final FileHashService fileHashService;
    // 向量化流生产者
    private final VectorizeStreamProducer vectorizeStreamProducer;

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    
    /**
     * 上传知识库文件
     * 上传到rustfs是storage服务做的，存sql数据库是persistence做的
     * @param file 知识库文件
     * @param name 知识库名称（可选，如果为空则从文件名提取）
     * @param category 分类（可选）
     * @return 上传结果和存储信息（包含duplicate字段，表示是否为重复上传）
     */
    public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name, String category) {
        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");

        String fileName = file.getOriginalFilename();
        log.info("收到知识库上传请求: {}, 大小: {} bytes, category: {}", fileName, file.getSize(), category);

        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType, fileName);

        // 3. 检查知识库是否已存在（去重）
        String fileHash = fileHashService.calculateHash(file);
        Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
        if (existingKb.isPresent()) {
            log.info("检测到重复知识库: hash={}", fileHash);
            return persistenceService.handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
        }

        // 4. 解析知识库文本（用于向量化）
        String content = parseService.parseContent(file);
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容，请确保文件格式正确");
        }

        // 5. 保存文件到RustFS
        String fileKey = storageService.uploadKnowledgeBase(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("知识库已存储到RustFS: {}", fileKey);

        // 6. 保存知识库元数据到数据库（状态为 PENDING）
        KnowledgeBaseEntity savedKb;
        try {
            savedKb = persistenceService.saveKnowledgeBase(
                //
                file,
                name,
                category,
                fileKey,
                fileUrl,
                fileHash
            );
        } catch (Exception e) {
            compensateUploadedKnowledgeBase(fileKey, e);
            throw e;
        }

        // 7. 发送向量化任务到 Redis Stream（异步处理） 把知识库文本向量化，结果会更新到数据库
        vectorizeStreamProducer.sendVectorizeTask(savedKb.getId(), content);

        log.info("知识库上传完成，向量化任务已入队: {}, kbId={}", fileName, savedKb.getId());

        // 8. 返回结果（状态为 PENDING，前端可轮询获取最新状态）
        return Map.of(
            "knowledgeBase", Map.of(
                "id", savedKb.getId(),
                "name", savedKb.getName(),
                "category", savedKb.getCategory() != null ? savedKb.getCategory() : "",
                "fileSize", savedKb.getFileSize(),
                "contentLength", content.length(),
                "vectorStatus", VectorStatus.PENDING.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl
            ),
            "duplicate", false
        );
    }

    /**
     * 验证文件类型
     */
    private void validateContentType(String contentType, String fileName) {
        fileValidationService.validateContentType(
            contentType,
            fileName,
            fileValidationService::isKnowledgeBaseMimeType,
            fileValidationService::isMarkdownExtension,
            "不支持的文件类型: " + contentType + "，支持的类型：PDF、DOCX、DOC、TXT、MD等"
        );
    }
    
    /**
     * 重新向量化知识库（手动重试）
     * 从 RustFS 重新下载文件并发送向量化任务
     *
     * @param kbId 知识库ID
     */
    public void revectorize(Long kbId) {
        KnowledgeBaseEntity kb = persistenceService.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        log.info("开始重新向量化知识库: kbId={}, name={}", kbId, kb.getName());

        // 1. 下载文件并解析内容
        String content = parseService.downloadAndParseContent(kb.getStorageKey(), kb.getOriginalFilename());
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容");
        }

        // 2. 更新状态为 PENDING（通过单独的 Service 保证事务生效）
        persistenceService.updateVectorStatusToPending(kbId);

        // 3. 发送向量化任务到 Stream
        vectorizeStreamProducer.sendVectorizeTask(kbId, content);

        log.info("重新向量化任务已发送: kbId={}", kbId);
    }

    /**
     * 补偿删除上传失败的知识库文件
     */
    private void compensateUploadedKnowledgeBase(String fileKey, Exception originalError) {
        try {
            storageService.deleteKnowledgeBase(fileKey);
            log.info("保存知识库失败，已补偿删除对象存储文件: fileKey={}", fileKey);
        } catch (Exception deleteError) {
            log.warn("保存知识库失败且补偿删除文件失败: fileKey={}, originalError={}",
                fileKey, originalError.getMessage(), deleteError);
        }
    }
}
