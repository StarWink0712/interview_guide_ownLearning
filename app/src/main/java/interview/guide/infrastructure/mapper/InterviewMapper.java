/**
 * 面试模块对象映射器
 *
 * 使用 MapStruct 自动生成面试相关 Entity 与 DTO 之间的转换代码。
 * 核心职责包括：InterviewAnswerEntity → QuestionEvaluation、InterviewAnswerEntity → AnswerDetailDTO、
 * InterviewSessionEntity → InterviewDetailDTO、InterviewReportDTO → InterviewSessionEntity（更新映射）、
 * InterviewSessionEntity → InterviewHistoryItemDTO。
 * JSON 字段（questionsJson、strengthsJson、improvementsJson 等）需在 Service 层手动处理。
 *
 * 联动关系：
 * - 被 InterviewHistoryService 调用（面试历史列表、详情、报告的 DTO 转换）
 * - 被 ResumeHistoryService 调用（简历详情中面试记录的 DTO 转换）
 * - 依赖 InterviewSessionEntity、InterviewAnswerEntity、InterviewReportDTO、InterviewDetailDTO 等面试模块模型
 */
package interview.guide.infrastructure.mapper;

import interview.guide.modules.interview.adaptive.model.AbilityProfileDTO;
import interview.guide.modules.interview.adaptive.model.AdaptiveTraceDTO;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewHistoryItemDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import org.mapstruct.*;

import java.util.List;
import java.util.function.Function;

/**
 * 面试模块对象映射器
 * 使用 MapStruct 自动生成面试相关 Entity 与 DTO 之间的转换代码
 * 注意：JSON 字段需要在 Service 层手动处理
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface InterviewMapper {

    /**
     * @Mapper(componentModel = "spring", unmappedTargetPolicy =
     *                        ReportingPolicy.IGNORE) 是 MapStruct 的核心注解，两个参数含义：
     * 
     *                        ### componentModel = "spring"
     *                        告诉 MapStruct 生成的实现类要注册为 Spring Bean （加上 @Component
     *                        注解），这样可以通过 @Autowired 注入使用。
     * 
     *                        生成的代码大致如下：
     * 
     *                        ```
     * @Component
     *            public class InterviewMapperImpl implements InterviewMapper {
     *                // 自动生成的转换方法...
     *            }
     *            ```
     *            如果不写这个参数，MapStruct 默认生成普通的 new 方式调用（
     *            Mappers.getMapper(XxxMapper.class) ），无法享受 Spring 的依赖注入。
     * 
     *            ### unmappedTargetPolicy = ReportingPolicy.IGNORE
     *            指定当 目标 DTO 的某些字段在源 Entity 中找不到对应映射时 ，不报错也不警告，直接忽略。
     * 
     *            例如： ResumeEntity 有 10 个字段， ResumeListItemDTO 只有 6 个字段匹配，如果没有
     *            IGNORE ，MapStruct 编译时就会报错：
     * 
     *            ```
     *            Unmapped target property: "xxx"
     *            ```
     *            加了 IGNORE 后，不匹配的字段静默跳过，由 Service 层手动处理（比如 JSON 字段 strengthsJson
     *            等）。
     * 
     *            一句话总结 ：让 MapStruct 生成 Spring 托管的转换 Bean，且遇到无法自动映射的字段时不要报错。
     * @param entity
     * @return
     */

    // ========== QuestionEvaluation 映射 ==========

    /**
     * 将面试答案实体转换为问题评估详情
     */
    @Mapping(target = "questionIndex", source = "questionIndex", qualifiedByName = "nullIndexToZero")
    @Mapping(target = "question", source = "question")
    @Mapping(target = "category", source = "category")
    @Mapping(target = "userAnswer", source = "userAnswer")
    @Mapping(target = "score", source = "score", qualifiedByName = "nullScoreToZero")
    @Mapping(target = "feedback", source = "feedback")
    InterviewReportDTO.QuestionEvaluation toQuestionEvaluation(InterviewAnswerEntity entity);

    /**
     * 批量转换面试答案实体
     */
    List<InterviewReportDTO.QuestionEvaluation> toQuestionEvaluations(List<InterviewAnswerEntity> entities);

    // ========== AnswerDetailDTO 映射 ==========

    /**
     * InterviewAnswerEntity 转换为 AnswerDetailDTO
     * 注意：keyPoints 需要从 JSON 解析后传入
     */
    @Mapping(target = "keyPoints", source = "keyPoints")
    InterviewDetailDTO.AnswerDetailDTO toAnswerDetailDTO(
            InterviewAnswerEntity entity,
            List<String> keyPoints);

    /**
     * 批量转换（需要在 Service 层处理 JSON）
     */
    default List<InterviewDetailDTO.AnswerDetailDTO> toAnswerDetailDTOList(
            List<InterviewAnswerEntity> entities,
            Function<InterviewAnswerEntity, List<String>> keyPointsExtractor) {
        return entities.stream()
                .map(e -> toAnswerDetailDTO(e, keyPointsExtractor.apply(e)))
                .toList();
    }

    // ========== InterviewDetailDTO 映射 ==========

    /**
     * InterviewSessionEntity 转换为 InterviewDetailDTO
     * 注意：questions, strengths, improvements, referenceAnswers, answers 需要在 Service
     * 层处理
     */
    @Mapping(target = "status", expression = "java(session.getStatus().toString())")
    @Mapping(target = "evaluateStatus", expression = "java(session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null)")
    @Mapping(target = "evaluateError", source = "session.evaluateError")
    @Mapping(target = "questions", source = "questions")
    @Mapping(target = "strengths", source = "strengths")
    @Mapping(target = "improvements", source = "improvements")
    @Mapping(target = "referenceAnswers", source = "referenceAnswers")
    @Mapping(target = "abilityProfile", source = "abilityProfile")
    @Mapping(target = "adaptiveTrace", source = "adaptiveTrace")
    @Mapping(target = "answers", source = "answers")
    InterviewDetailDTO toDetailDTO(
            InterviewSessionEntity session,
            List<Object> questions,
            List<String> strengths,
            List<String> improvements,
            List<Object> referenceAnswers,
            AbilityProfileDTO abilityProfile,
            List<AdaptiveTraceDTO> adaptiveTrace,
            List<InterviewDetailDTO.AnswerDetailDTO> answers);

    // ========== InterviewSessionEntity 更新映射 ==========

    /**
     * 从 InterviewReportDTO 更新 InterviewSessionEntity
     * 注意：JSON 字段需要在 Service 层单独设置
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "sessionId", ignore = true)
    @Mapping(target = "resume", ignore = true)
    @Mapping(target = "totalQuestions", ignore = true)
    @Mapping(target = "currentQuestionIndex", ignore = true)
    @Mapping(target = "questionsJson", ignore = true)
    @Mapping(target = "strengthsJson", ignore = true)
    @Mapping(target = "improvementsJson", ignore = true)
    @Mapping(target = "referenceAnswersJson", ignore = true)
    @Mapping(target = "answers", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "completedAt", ignore = true)
    void updateSessionFromReport(InterviewReportDTO report, @MappingTarget InterviewSessionEntity session);

    // ========== 面试历史列表项映射 ==========

    /**
     * InterviewSessionEntity 转换为面试历史列表项 DTO
     */
    default InterviewHistoryItemDTO toInterviewHistoryItem(InterviewSessionEntity session) {
        return new InterviewHistoryItemDTO(
                session.getId(),
                session.getSessionId(),
                session.getTotalQuestions(),
                session.getStatus().toString(),
                session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null,
                session.getEvaluateError(),
                session.getOverallScore(),
                session.getCreatedAt(),
                session.getCompletedAt());
    }

    /**
     * 批量转换面试历史
     */
    default List<InterviewHistoryItemDTO> toInterviewHistoryList(List<InterviewSessionEntity> sessions) {
        return sessions.stream()
                .map(this::toInterviewHistoryItem)
                .toList();
    }

    // ========== 工具方法 ==========

    @Named("nullIndexToZero")
    default int nullIndexToZero(Integer value) {
        return value != null ? value : 0;
    }

    @Named("nullScoreToZero")
    default int nullScoreToZero(Integer value) {
        return value != null ? value : 0;
    }
}
