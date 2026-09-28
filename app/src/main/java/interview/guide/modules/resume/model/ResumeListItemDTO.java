/**
 * 简历列表项响应 DTO
 *
 * 返回给前端的简历列表条目，包含简历基本信息、最新评分、最近分析时间、
 * 关联面试次数、分析状态和错误信息，用于简历列表页面的展示。
 *
 * 联动关系：
 * - 被 ResumeHistoryService 组装并返回给 ResumeController
 * - 数据来源于 ResumeEntity 和 ResumeAnalysisEntity 的联合查询
 */
package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;

import java.time.LocalDateTime;

/**
 * 简历列表项DTO
 */
public record ResumeListItemDTO(
    Long id,
    String filename,
    Long fileSize,
    LocalDateTime uploadedAt,
    Integer accessCount,
    Integer latestScore,
    LocalDateTime lastAnalyzedAt,
    Integer interviewCount,
    AsyncTaskStatus analyzeStatus,
    String analyzeError
) {}

