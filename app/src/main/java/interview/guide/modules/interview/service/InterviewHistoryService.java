/**
 * 面试历史服务
 *
 * 负责获取面试会话详情和导出面试报告为 PDF。
 * 详情获取时解析 Entity 中的 JSON 字段（题目、优势、改进建议、参考答案），
 * 构建包含已答/未答题目的完整答案列表，使用 MapStruct 映射为 InterviewDetailDTO。
 *
 * 联动关系：
 * - 被 InterviewController 调用获取面试详情和导出 PDF
 * - 依赖 InterviewPersistenceService 查询会话和答案数据
 * - 依赖 PdfExportService（infrastructure.export）生成 PDF
 * - 依赖 InterviewMapper（infrastructure.mapper）做 Entity → DTO 映射
 */
package interview.guide.modules.interview.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.modules.interview.adaptive.model.AbilityProfileDTO;
import interview.guide.modules.interview.adaptive.model.AdaptiveTraceDTO;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 面试历史服务
 * 获取面试会话详情和导出面试报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewHistoryService {
    // 面试持久化服务，负责查询面试会话和答案数据
    private final InterviewPersistenceService interviewPersistenceService;
    // pdf导出服务，负责将面试会话详情导出为PDF文件
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    // 面试映射器，负责将数据库实体映射为DTO
    private final InterviewMapper interviewMapper;

    /**
     * 获取面试会话详情
     *
     * <p>调用入口：{@code InterviewController.getInterviewDetail}。
     * 先查询会话实体并反序列化报告和自适应字段，再调用
     * {@link #buildAnswerDetailList(List, List)} 对齐全部题目与答案，
     * 最后通过 {@code InterviewMapper} 组装 DTO。
     *
     * @param sessionId 会话业务 ID
     * @return 包含题目、答案、报告和自适应信息的详情 DTO
     * @throws BusinessException 会话不存在时抛出
     */
    public InterviewDetailDTO getInterviewDetail(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();

        // 解析JSON字段
        List<Object> questions = parseJson(session.getQuestionsJson(), new TypeReference<>() {
        });
        List<String> strengths = parseJson(session.getStrengthsJson(), new TypeReference<>() {
        });
        List<String> improvements = parseJson(session.getImprovementsJson(), new TypeReference<>() {
        });
        List<Object> referenceAnswers = parseJson(session.getReferenceAnswersJson(), new TypeReference<>() {
        });
        AbilityProfileDTO abilityProfile = parseJson(
                session.getAbilityProfileJson(),
                new TypeReference<>() {
                });
        List<AdaptiveTraceDTO> adaptiveTrace = parseJson(
                session.getAdaptiveTraceJson(),
                new TypeReference<>() {
                });

        // 解析所有题目（用于构建完整的答案列表）
        List<InterviewQuestionDTO> allQuestions = parseJson(
                session.getQuestionsJson(),
                new TypeReference<>() {
                });

        // 构建答案详情列表（包含所有题目，未回答的也要显示）
        List<InterviewDetailDTO.AnswerDetailDTO> answerList = buildAnswerDetailList(
                allQuestions,
                session.getAnswers());

        // 使用 MapStruct 组装最终 DTO
        return interviewMapper.toDetailDTO(
                session,
                questions,
                strengths,
                improvements,
                referenceAnswers,
                abilityProfile,
                adaptiveTrace,
                answerList);
    }

    /**
     * 构建答案详情列表（包含所有题目）
     *
     * <p>仅由 {@link #getInterviewDetail(String)} 调用。
     * 将数据库答案按 questionIndex 建立索引，再遍历完整题目列表；
     * 已回答题目使用答案实体，未回答题目创建空答案 DTO。
     * 这样提前交卷时前端仍能按原顺序展示全部题目。
     *
     * @param allQuestions 会话的完整题目列表
     * @param answers 数据库中实际存在的答案记录
     * @return 与题目列表顺序一致的完整答案详情
     */
    private List<InterviewDetailDTO.AnswerDetailDTO> buildAnswerDetailList(
            List<InterviewQuestionDTO> allQuestions,
            List<InterviewAnswerEntity> answers) {
        if (allQuestions == null || allQuestions.isEmpty()) {
            // 如果没有题目数据，回退到仅显示已回答的题目
            return interviewMapper.toAnswerDetailDTOList(answers, this::extractKeyPoints);
        }

        // 将答案按 questionIndex 索引
        java.util.Map<Integer, InterviewAnswerEntity> answerMap = answers.stream()
                .collect(java.util.stream.Collectors.toMap(
                        InterviewAnswerEntity::getQuestionIndex,
                        a -> a,
                        (a1, a2) -> a1 // 如果有重复，取第一个
                ));

        // 遍历所有题目，构建完整的答案详情列表
        return allQuestions.stream()
                .map(question -> {
                    InterviewAnswerEntity answer = answerMap.get(question.questionIndex());
                    if (answer != null) {
                        // 用户已回答，使用答案数据
                        return interviewMapper.toAnswerDetailDTO(answer, extractKeyPoints(answer));
                    } else {
                        // 用户未回答，构建空答案
                        return new InterviewDetailDTO.AnswerDetailDTO(
                                question.questionIndex(),
                                question.question(),
                                question.category(),
                                null, // userAnswer
                                question.score() != null ? question.score() : 0, // score
                                question.feedback(), // feedback
                                null, // referenceAnswer
                                null, // keyPoints
                                null // answeredAt
                        );
                    }
                })
                .toList();
    }

    /**
     * 从 JSON 提取 keyPoints
     *
     * <p>由 {@link #buildAnswerDetailList(List, List)} 转换已回答题目时调用，
     * 委托 {@link #parseJson(String, TypeReference)} 解析答案中的关键点 JSON。
     */
    private List<String> extractKeyPoints(InterviewAnswerEntity answer) {
        return parseJson(answer.getKeyPointsJson(), new TypeReference<>() {
        });
    }

    /**
     * 通用 JSON 解析方法
     *
     * <p>由详情组装和关键点提取共同调用。
     * 空值直接返回 {@code null}；格式异常记录错误并降级为 {@code null}，
     * 避免单个历史字段损坏导致整个详情页不可用。
     */
    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JacksonException e) {
            log.error("解析 JSON 失败", e);
            return null;
        }
    }

    /**
     * 导出面试报告为PDF
     *
     * <p>调用入口：{@code InterviewController.exportInterviewPdf}。
     * 查询会话后调用 {@code PdfExportService.exportInterviewReport} 生成字节数组，
     * 导出异常转换为 {@link BusinessException}。
     *
     * @param sessionId 会话业务 ID
     * @return PDF 文件字节
     * @throws BusinessException 会话不存在或 PDF 生成失败时抛出
     */
    public byte[] exportInterviewPdf(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();
        try {
            return pdfExportService.exportInterviewReport(session);
        } catch (Exception e) {
            log.error("导出PDF失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: " + e.getMessage());
        }
    }
}
