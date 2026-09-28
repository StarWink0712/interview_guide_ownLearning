/**
 * 文字面试答案评估服务
 *
 * DTO 适配器角色，将 InterviewQuestionDTO 转换为通用 QaRecord，
 * 调用 UnifiedEvaluationService 执行 AI 评估，再将 EvaluationReport 转回 InterviewReportDTO。
 * 同时从 InterviewSkillService 获取评估参考基线注入 Prompt。
 * 这是一个 DTO 适配器和编排服务：自身不直接构造 LLM 请求，
 * 但会委托 UnifiedEvaluationService 执行实际 AI 调用。
 *
 * 联动关系：
 * - 被 EvaluateStreamConsumer（异步评估）和 InterviewSessionService.generateReport()（同步评估）调用
 * - 依赖 UnifiedEvaluationService（common.evaluation）执行实际评估逻辑
 * - 依赖 InterviewPersistenceService 查询会话信息
 * - 依赖 InterviewSkillService 构建评估参考基线
 */
package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.CategoryScore;
import interview.guide.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import interview.guide.modules.interview.model.InterviewReportDTO.ReferenceAnswer;
import interview.guide.modules.interview.skill.InterviewSkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文字面试答案评估服务
 * 职责：DTO 适配器，将 InterviewQuestionDTO 转为通用 QaRecord，调用 UnifiedEvaluationService
 */
@Service
public class AnswerEvaluationService {

    //log是日志记录器
    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);

    //unifiedEvaluationService是统一评估服务
    private final UnifiedEvaluationService unifiedEvaluationService;
    //persistenceService是面试持久化服务
    private final InterviewPersistenceService persistenceService;
    //skillService是面试技能服务
    private final InterviewSkillService skillService;

    /**
     * 注入通用评估、持久化和技能参考服务。
     *
     * <p>由 Spring 创建本服务时调用；构造过程不访问数据库或 LLM。
     */
    public AnswerEvaluationService(UnifiedEvaluationService unifiedEvaluationService,
                                   InterviewPersistenceService persistenceService,
                                   InterviewSkillService skillService) {
        this.unifiedEvaluationService = unifiedEvaluationService;
        this.persistenceService = persistenceService;
        this.skillService = skillService;
    }

    /**
     * 评估完整面试并生成报告
     *
     * <p>由 {@code EvaluateStreamConsumer.processBusiness} 的异步路径和
     * {@code InterviewSessionService.generateReport} 的同步路径调用。
     * 该方法把题目转换为 {@code QaRecord}，查询会话技能参考，
     * 再调用 {@code UnifiedEvaluationService.evaluate} 执行 LLM 评估，
     * 最后通过 {@link #toInterviewReportDTO(EvaluationReport)} 转换报告。
     *
     * @param chatClient 当前会话选择的 LLM 客户端
     * @param sessionId 会话业务 ID
     * @param resumeText 可选简历文本
     * @param questions 包含用户答案的完整题目列表
     * @return 文字面试报告 DTO
     * @throws BusinessException 评估失败时保留原业务异常或转换为评估失败异常
     */
    public InterviewReportDTO evaluateInterview(ChatClient chatClient, String sessionId, String resumeText,
                                                 List<InterviewQuestionDTO> questions) {
        log.info("开始评估面试: {}, 共{}题", sessionId, questions.size());

        try {
            // 转为通用问答记录，qaRecords是面试题目列表，每个元素是一个QaRecord，包含问题索引、问题、分类、用户答案
            List<QaRecord> qaRecords = questions.stream()
                .map(q -> new QaRecord(q.questionIndex(), q.question(), q.category(), q.userAnswer()))
                .toList();

            // 构建评估参考基线,调用面试技能服务，根据会话id查询技能id，再根据技能id构建参考参考基线
            String referenceContext = skillService.buildEvaluationReferenceSectionSafe(
                persistenceService.findBySessionId(sessionId)
                    .map(s -> s.getSkillId())
                    .orElse(null)
            );

            // 调用通用评估服务
            EvaluationReport report = unifiedEvaluationService.evaluate(
                chatClient, sessionId, qaRecords, resumeText, referenceContext
            );

            // 转为文字面试专用 DTO
            return toInterviewReportDTO(report);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("面试评估失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试评估失败：" + e.getMessage());
        }
    }

    /**
     * 转换通用评估报告为文字面试专用 DTO
     *
     * <p>仅由 {@link #evaluateInterview(ChatClient, String, String, List)}
     * 在通用评估成功后调用，负责转换分类评分、逐题结果和参考答案，
     * 不访问数据库或 LLM。
     */
    private InterviewReportDTO toInterviewReportDTO(EvaluationReport report) {
        return new InterviewReportDTO(
            report.sessionId(),
            report.totalQuestions(),
            report.overallScore(),
            report.categoryScores().stream()
                .map(cs -> new CategoryScore(cs.category(), cs.score(), cs.questionCount()))
                .toList(),
            report.questionDetails().stream()
                .map(qe -> new QuestionEvaluation(qe.questionIndex(), qe.question(), qe.category(),
                    qe.userAnswer(), qe.score(), qe.feedback()))
                .toList(),
            report.overallFeedback(),
            report.strengths(),
            report.improvements(),
            report.referenceAnswers().stream()
                .map(ra -> new ReferenceAnswer(ra.questionIndex(), ra.question(),
                    ra.referenceAnswer(), ra.keyPoints()))
                .toList()
        );
    }
}
