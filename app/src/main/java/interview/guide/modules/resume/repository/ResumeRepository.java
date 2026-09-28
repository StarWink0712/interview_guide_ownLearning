/**
 * 简历 Repository
 *
 * 提供 ResumeEntity 的数据访问层，支持通过文件哈希查找简历（用于去重）
 * 和检查文件哈希是否已存在。
 *
 * 联动关系：
 * - 被 ResumePersistenceService 调用，用于简历的 CRUD 和去重检查
 * - 被 AnalyzeStreamConsumer 调用，用于校验简历存在性和更新分析状态
 * - 被 AnalyzeStreamProducer 调用，用于发送失败时更新分析状态
 * - 被 ResumeUploadService 调用，用于重新分析时更新状态
 * - 管理 ResumeEntity 实体
 */
package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 简历Repository
 */
@Repository
public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {
    
    /**
     * 根据文件哈希查找简历（用于去重）
     */
    Optional<ResumeEntity> findByFileHash(String fileHash);
    
    /**
     * 检查文件哈希是否存在
     */
    boolean existsByFileHash(String fileHash);
}
