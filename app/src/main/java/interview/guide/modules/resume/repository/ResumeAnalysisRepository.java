/**
 * 简历评测结果 Repository
 *
 * 提供 ResumeAnalysisEntity 的数据访问层，支持按简历查询评测记录（按时间倒序）、
 * 查询最新评测记录等操作。
 *
 * 联动关系：
 * - 被 ResumePersistenceService 调用，用于保存和查询分析结果
 * - 管理 ResumeAnalysisEntity 实体
 * - 关联 ResumeEntity 通过 resumeId 字段
 */
package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 简历评测Repository
 */
@Repository
public interface ResumeAnalysisRepository extends JpaRepository<ResumeAnalysisEntity, Long> {
    
    /**
     * 根据简历查找所有评测记录
     */
    List<ResumeAnalysisEntity> findByResumeOrderByAnalyzedAtDesc(ResumeEntity resume);
    
    /**
     * 根据简历ID查找最新评测记录
     */
    ResumeAnalysisEntity findFirstByResumeIdOrderByAnalyzedAtDesc(Long resumeId);
    
    /**
     * 根据简历ID查找所有评测记录
     */
    List<ResumeAnalysisEntity> findByResumeIdOrderByAnalyzedAtDesc(Long resumeId);
}
