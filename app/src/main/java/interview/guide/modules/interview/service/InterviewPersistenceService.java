/**
 * 面试持久化服务
 *
 * 面试模块的核心数据访问层，封装面试会话和答案的所有数据库操作，
 * 包括会话创建、状态更新、答案保存（upsert）、报告持久化、
 * 历史题目查询（支持按 resumeId + skillId 精确匹配或按 skillId 通用模式兜底）等。
 * 所有写操作使用 @Transactional 保证事务一致性。
 *
 * 联动关系：
 * - 被 InterviewSessionService、InterviewHistoryService、AnswerEvaluationService 调用
 * - 被 EvaluateStreamConsumer 调用查询答案和保存报告
 * - 被 InterviewController 直接调用删除会话和列表查询
 * - 依赖 InterviewSessionRepository 和 InterviewAnswerRepository
 * - 依赖 ResumeRepository 关联简历
 * - 被简历模块（ResumeService）间接调用删除关联面试记录
 */
package interview.guide.modules.interview.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 面试持久化服务
 * 面试会话和答案的持久化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPersistenceService {
    //对数据库的操作，即dao层的操作
    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    //对简历的数据库的操作，即dao数据访问层
    private final ResumeRepository resumeRepository;
    //json序列化工具
    private final ObjectMapper objectMapper;
    
    /**
     * 保存新的面试会话（支持可选简历）
     *
     * <p>由 {@code InterviewSessionService.createSession} 在 LLM 出题完成后调用。
     * 该方法序列化问题列表、可选关联简历并保存会话实体；
     * JSON 序列化失败时转换为 {@link BusinessException}。
     *
     * @return 保存后的会话实体
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              List<InterviewQuestionDTO> questions,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty) {
        try {
            // 创建会话实体
            InterviewSessionEntity session = new InterviewSessionEntity();
            session.setSessionId(sessionId);
            session.setTotalQuestions(totalQuestions);
            session.setCurrentQuestionIndex(0);
            session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
            session.setQuestionsJson(objectMapper.writeValueAsString(questions));
            session.setLlmProvider(llmProvider != null ? llmProvider : "default");
            session.setSkillId(skillId != null ? skillId : InterviewDefaults.SKILL_ID);
            session.setDifficulty(difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY);

            // 简历可选：有 resumeId 则关联简历
            if (resumeId != null) {
                Optional<ResumeEntity> resumeOpt = resumeRepository.findById(resumeId);
                resumeOpt.ifPresent(session::setResume);
            }

            InterviewSessionEntity saved = sessionRepository.save(session);
            log.info("面试会话已保存: sessionId={}, skillId={}, resumeId={}", sessionId, skillId, resumeId);

            return saved;
        } catch (JacksonException e) {
            log.error("序列化问题列表失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存会话失败");
        }
    }
    
    /**
     * 更新会话状态
     *
     * <p>由会话取题、提交答案、暂存和提前交卷流程调用。
     * 状态进入 COMPLETED 或 EVALUATED 时同时记录完成时间；
     * 会话不存在时保持幂等并直接返回。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSessionStatus(String sessionId, InterviewSessionEntity.SessionStatus status) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setStatus(status);
            if (status == InterviewSessionEntity.SessionStatus.COMPLETED ||
                status == InterviewSessionEntity.SessionStatus.EVALUATED) {
                session.setCompletedAt(LocalDateTime.now());
            }
            sessionRepository.save(session);
        }
    }

    /**
     * 更新评估状态
     *
     * <p>由会话结束流程、评估 Producer 和 Consumer 调用，
     * 用于记录最终评估任务的 PENDING、PROCESSING、COMPLETED 或失败状态。
     * 错误信息最多保存 500 个字符，会话不存在时直接返回。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            } else {
                session.setEvaluateError(null);
            }
            sessionRepository.save(session);
            log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
        }
    }

    /**
     * 更新自适应面试状态。
     *
     * <p>由 {@code AdaptiveInterviewService.persistResult} 在每轮即时评估后调用，
     * 同时保存能力画像和自适应决策轨迹 JSON。
     *
     * @throws BusinessException 会话不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateAdaptiveState(
        String sessionId,
        String abilityProfileJson,
        String adaptiveTraceJson
    ) {
        InterviewSessionEntity session = sessionRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        session.setAbilityProfileJson(abilityProfileJson);
        session.setAdaptiveTraceJson(adaptiveTraceJson);
        sessionRepository.save(session);
        log.debug("自适应面试状态已更新: sessionId={}", sessionId);
    }

    /**
     * 保存单轮即时评估分数和反馈，最终报告生成时仍可覆盖为完整评估结果
     *
     * <p>由 {@code AdaptiveInterviewService.persistResult} 调用。
     * 通过会话 ID 和题目索引定位答案；答案尚未写入时记录警告并返回，
     * 不创建缺失答案记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateAnswerEvaluation(
        String sessionId,
        int questionIndex,
        int score,
        String feedback
    ) {
        Optional<InterviewAnswerEntity> answerOpt =
            answerRepository.findBySession_SessionIdAndQuestionIndex(sessionId, questionIndex);
        if (answerOpt.isEmpty()) {
            log.warn("即时评估结果未写入，答案不存在: sessionId={}, questionIndex={}",
                sessionId, questionIndex);
            return;
        }
        InterviewAnswerEntity answer = answerOpt.get();
        answer.setScore(score);
        answer.setFeedback(feedback);
        answerRepository.save(answer);
        log.debug("即时评估结果已写入答案: sessionId={}, questionIndex={}, score={}",
            sessionId, questionIndex, score);
    }
    
    /**
     * 更新当前问题索引
     *
     * <p>由 {@code InterviewSessionService.submitAnswer} 在答案提交后调用，
     * 同时把持久化会话状态设置为 IN_PROGRESS。会话不存在时直接返回。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentQuestionIndex(String sessionId, int index) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setCurrentQuestionIndex(index);
            session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            sessionRepository.save(session);
        }
    }
    
    /**
     * 保存面试答案
     *
     * <p>由提交答案和暂存答案流程调用。
     * 根据“会话 + 题目索引”执行 upsert：已有答案则覆盖，
     * 不存在则创建并关联会话。
     *
     * @return 保存后的答案实体
     * @throws BusinessException 会话不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewAnswerEntity saveAnswer(String sessionId, int questionIndex,
                                            String question, String category,
                                            String userAnswer, int score, String feedback) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewAnswerEntity answer = answerRepository
            .findBySession_SessionIdAndQuestionIndex(sessionId, questionIndex)
            .orElseGet(() -> {
                InterviewAnswerEntity created = new InterviewAnswerEntity();
                created.setSession(sessionOpt.get());
                created.setQuestionIndex(questionIndex);
                return created;
            });

        answer.setQuestion(question);
        answer.setCategory(category);
        answer.setUserAnswer(userAnswer);
        answer.setScore(score);
        answer.setFeedback(feedback);

        InterviewAnswerEntity saved = answerRepository.save(answer);
        log.info("面试答案已保存: sessionId={}, questionIndex={}, score={}", 
                sessionId, questionIndex, score);
        
        return saved;
    }
    
    /**
     * 保存面试报告
     *
     * <p>由同步报告生成和异步评估 Consumer 调用。
     * 更新会话总评、优势、改进建议和参考答案，并按题目索引批量更新答案评分；
     * 对未回答题目创建占位答案记录，保证报告题目完整。
     * JSON 序列化失败时记录错误并结束，不向调用方抛出。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveReport(String sessionId, InterviewReportDTO report) {
        try {
            //interviewSessionEntity是面试会话实体类，包含了面试会话的所有信息
            Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
            if (sessionOpt.isEmpty()) {
                log.warn("会话不存在: {}", sessionId);
                return;
            }

            InterviewSessionEntity session = sessionOpt.get();
            session.setOverallScore(report.overallScore());
            session.setOverallFeedback(report.overallFeedback());
            session.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
            session.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
            session.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
            session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
            session.setCompletedAt(LocalDateTime.now());

            sessionRepository.save(session);

            // 查询已存在的答案，建立索引
            List<InterviewAnswerEntity> existingAnswers = answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
            java.util.Map<Integer, InterviewAnswerEntity> answerMap = existingAnswers.stream()
                .collect(java.util.stream.Collectors.toMap(
                    InterviewAnswerEntity::getQuestionIndex,
                    a -> a,
                    (a1, a2) -> a1
                ));

            // 建立参考答案索引
            java.util.Map<Integer, InterviewReportDTO.ReferenceAnswer> refAnswerMap = report.referenceAnswers().stream()
                .collect(java.util.stream.Collectors.toMap(
                    InterviewReportDTO.ReferenceAnswer::questionIndex,
                    r -> r,
                    (r1, r2) -> r1
                ));

            List<InterviewAnswerEntity> answersToSave = new java.util.ArrayList<>();

            // 遍历所有评估结果，更新或创建答案记录
            for (InterviewReportDTO.QuestionEvaluation eval : report.questionDetails()) {
                InterviewAnswerEntity answer = answerMap.get(eval.questionIndex());

                if (answer == null) {
                    // 未回答的题目，创建新记录
                    answer = new InterviewAnswerEntity();
                    answer.setSession(session);
                    answer.setQuestionIndex(eval.questionIndex());
                    answer.setQuestion(eval.question());
                    answer.setCategory(eval.category());
                    answer.setUserAnswer(null);  // 未回答
                    log.debug("为未回答的题目 {} 创建答案记录", eval.questionIndex());
                }

                // 更新评分和反馈
                answer.setScore(eval.score());
                answer.setFeedback(eval.feedback());

                // 设置参考答案和关键点
                InterviewReportDTO.ReferenceAnswer refAns = refAnswerMap.get(eval.questionIndex());
                if (refAns != null) {
                    answer.setReferenceAnswer(refAns.referenceAnswer());
                    if (refAns.keyPoints() != null && !refAns.keyPoints().isEmpty()) {
                        answer.setKeyPointsJson(objectMapper.writeValueAsString(refAns.keyPoints()));
                    }
                }

                answersToSave.add(answer);
            }

            answerRepository.saveAll(answersToSave);
            log.info("面试报告已保存: sessionId={}, score={}, 答案数={}",
                sessionId, report.overallScore(), answersToSave.size());

        } catch (JacksonException e) {
            log.error("序列化报告失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 根据会话ID获取会话
     *
     * <p>被会话恢复、报告生成、历史详情、自适应评估和异步评估共同调用，
     * 直接委托 {@code InterviewSessionRepository.findBySessionId}。
     */
    public Optional<InterviewSessionEntity> findBySessionId(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }
    
    /**
     * 获取简历的所有面试记录
     *
     * <p>供简历详情和历史记录查询使用，
     * 按创建时间倒序委托 Repository 查询。
     */
    public List<InterviewSessionEntity> findByResumeId(Long resumeId) {
        return sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
    }

    /**
     * 获取所有面试记录（按创建时间倒序）
     *
     * <p>调用入口：{@code InterviewController.listSessions}。
     * 直接委托 Repository 返回实体，Controller 随后负责转换 DTO。
     */
    public List<InterviewSessionEntity> findAll() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }
    
    /**
     * 删除简历的所有面试会话
     * 由于InterviewSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     *
     * <p>由简历删除流程调用；先查询关联会话，再批量删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionsByResumeId(Long resumeId) {
        List<InterviewSessionEntity> sessions = sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
        if (!sessions.isEmpty()) {
            sessionRepository.deleteAll(sessions);
            log.info("已删除 {} 个面试会话（包含所有答案）", sessions.size());
        }
    }
    
    /**
     * 删除单个面试会话
     * 由于InterviewSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     *
     * <p>调用入口：{@code InterviewController.deleteInterview}。
     * 先按业务 sessionId 查询实体，再依赖 JPA 级联删除答案。
     *
     * @throws BusinessException 会话不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionBySessionId(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            sessionRepository.delete(sessionOpt.get());
            log.info("已删除面试会话: sessionId={}", sessionId);
        } else {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }
    }
    
    /**
     * 查找未完成的面试会话（CREATED或IN_PROGRESS状态）
     *
     * <p>由 {@code InterviewSessionService.findUnfinishedSession} 调用，
     * 查询指定简历最新的 CREATED 或 IN_PROGRESS 会话。
     */
    public Optional<InterviewSessionEntity> findUnfinishedSession(Long resumeId) {
        List<InterviewSessionEntity.SessionStatus> unfinishedStatuses = List.of(
            InterviewSessionEntity.SessionStatus.CREATED,
            InterviewSessionEntity.SessionStatus.IN_PROGRESS
        );
        return sessionRepository.findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(resumeId, unfinishedStatuses);
    }
    
    /**
     * 根据会话ID查找所有答案
     *
     * <p>由缓存恢复、历史详情和最终评估流程调用，
     * 按题目索引升序返回，便于与问题列表对齐。
     */
    public List<InterviewAnswerEntity> findAnswersBySessionId(String sessionId) {
        return answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
    }

    private static final int MAX_HISTORICAL_QUESTIONS = 60;

    /**
     * 获取历史提问列表（结构化，按分类压缩用）。
     * 有 resumeId 时精确匹配 resumeId + skillId；无 resumeId 时按 skillId 查全部（通用模式兜底）。
     *
     * <p>由 {@code InterviewSessionService.createSession} 在生成新题前调用。
     * 该方法读取最近会话的问题 JSON、排除追问题、按题目文本去重，
     * 最多返回 {@value #MAX_HISTORICAL_QUESTIONS} 条主问题用于避免重复出题。
     */
    public List<HistoricalQuestion> getHistoricalQuestions(String skillId, Long resumeId) {
        List<InterviewSessionEntity> sessions;
        if (resumeId != null) {
            sessions = sessionRepository.findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(resumeId, skillId);
        } else {
            sessions = sessionRepository.findTop10BySkillIdOrderByCreatedAtDesc(skillId);
        }

        log.info("加载历史题目: skillId={}, resumeId={}, 查到 {} 个历史会话", skillId, resumeId, sessions.size());

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<HistoricalQuestion> result = sessions.stream()
            .map(InterviewSessionEntity::getQuestionsJson)
            .filter(json -> json != null && !json.isEmpty())
            .flatMap(json -> {
                try {
                    List<InterviewQuestionDTO> questions = objectMapper.readValue(json,
                        new TypeReference<List<InterviewQuestionDTO>>() {});
                    return questions.stream()
                        .filter(q -> !q.isFollowUp())
                        .map(q -> new HistoricalQuestion(q.question(), q.type(), q.topicSummary()));
                } catch (Exception e) {
                    log.error("解析历史问题JSON失败", e);
                    return java.util.stream.Stream.<HistoricalQuestion>empty();
                }
            })
            .filter(hq -> seen.add(hq.question()))
            .limit(MAX_HISTORICAL_QUESTIONS)
            .toList();

        log.info("历史题目加载完成: 去重后 {} 道主问题，按分类: {}", result.size(),
            result.stream().collect(java.util.stream.Collectors.groupingBy(
                hq -> hq.type() != null ? hq.type() : "GENERAL",
                java.util.stream.Collectors.counting())));

        return result;
    }
}
