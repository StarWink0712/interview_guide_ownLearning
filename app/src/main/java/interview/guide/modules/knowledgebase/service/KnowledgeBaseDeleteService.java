/**
 * 知识库删除服务
 *
 * 负责知识库的完整删除流程，按顺序执行：解除 RAG 会话关联 → 删除向量数据 →
 * 删除 RustFS 文件 → 删除数据库记录。向量数据和文件删除失败不阻断流程，
 * 仅记录警告日志，确保数据库记录最终被删除。
 *
 * 联动关系：
 * - 被 KnowledgeBaseController.deleteKnowledgeBase() 调用
 * - 依赖 KnowledgeBaseRepository 查找和删除知识库记录
 * - 依赖 RagChatSessionRepository 解除会话与知识库的关联
 * - 委托 KnowledgeBaseVectorService.deleteByKnowledgeBaseId() 删除向量数据
 * - 委托 FileStorageService.deleteKnowledgeBase() 删除 RustFS 文件
 */
package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库删除服务
 * 负责知识库的删除操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseDeleteService {
    
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatSessionRepository sessionRepository;
    private final KnowledgeBaseVectorService vectorService;
    private final FileStorageService storageService;
    
    /**
     * 删除知识库
     * 包括：RAG会话关联、向量数据、RustFS文件、数据库记录
     */
    public void deleteKnowledgeBase(Long id) {
        // 1. 获取知识库信息
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
        
        // 2. 删除所有RAG会话中的知识库关联（必须先删除关联，否则外键约束会阻止删除）
        List<RagChatSessionEntity> sessions = sessionRepository.findByKnowledgeBaseIds(List.of(id));
        for (RagChatSessionEntity session : sessions) {
            session.getKnowledgeBases().removeIf(kbEntity -> kbEntity.getId().equals(id));
            sessionRepository.save(session);
            log.debug("已从会话中移除知识库关联: sessionId={}, kbId={}", session.getId(), id);
        }
        if (!sessions.isEmpty()) {
            log.info("已从 {} 个会话中移除知识库关联: kbId={}", sessions.size(), id);
        }
        
        // 3. 删除向量数据
        try {
            vectorService.deleteByKnowledgeBaseId(id);
        } catch (Exception e) {
            log.warn("删除向量数据失败，继续删除知识库: kbId={}, error={}", id, e.getMessage());
        }
        
        // 4. 删除RustFS中的文件（FileStorageService 已内置存在性检查）
        try {
            storageService.deleteKnowledgeBase(kb.getStorageKey());
        } catch (Exception e) {
            log.warn("删除RustFS文件失败，继续删除知识库记录: kbId={}, error={}", id, e.getMessage());
        }
        
        // 5. 删除知识库记录（在事务中）
        knowledgeBaseRepository.deleteById(id);
        log.info("知识库已删除: id={}", id);
    }
}
