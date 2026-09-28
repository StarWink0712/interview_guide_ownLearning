/**
 * 面试会话管理服务
 *
 * 面试模块的核心编排层，管理面试会话的完整生命周期：
 * 创建会话（含历史去重）、获取当前题目、提交/暂存答案、提前交卷、生成报告。
 * 使用 Redis 缓存（InterviewSessionCache）加速会话状态读写，缓存未命中时从数据库恢复。
 * 最后一题提交或提前交卷时通过 EvaluateStreamProducer 触发异步评估。
 *
 * 联动关系：
 * - 被 InterviewController 调用处理所有面试交互请求
 * - 依赖 InterviewQuestionService 生成面试题目
 * - 依赖 AnswerEvaluationService 同步生成报告
 * - 依赖 InterviewPersistenceService 持久化会话和答案
 * - 依赖 InterviewSessionCache（infrastructure.redis）做 Redis 缓存
 * - 依赖 EvaluateStreamProducer 发送异步评估任务
 * - 依赖 LlmProviderRegistry 获取 ChatClient
 */
package interview.guide.modules.interview.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.modules.interview.adaptive.AdaptiveInterviewService;
import interview.guide.modules.interview.adaptive.model.AdaptiveInterviewResultDTO;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 面试会话管理服务
 * 管理面试会话的生命周期，使用 Redis 缓存会话状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionService {

    //questionService是面试题目服务
    private final InterviewQuestionService questionService;
    //evaluationService是面试评估服务
    private final AnswerEvaluationService evaluationService;
    //persistenceService是面试持久化服务    
    private final InterviewPersistenceService persistenceService;
    //sessionCache是面试会话缓存,缓存在redis中
    private final InterviewSessionCache sessionCache;
    //objectMapper是对象映射器
    private final ObjectMapper objectMapper;
    //evaluateStreamProducer是评估流生产器,用于异步评估面试，即发送评估任务到消息队列中
    private final EvaluateStreamProducer evaluateStreamProducer;
    //llmProviderRegistry是LLM提供器注册器
    private final LlmProviderRegistry llmProviderRegistry;
    //adaptiveInterviewService是自适应面试增强服务
    private final AdaptiveInterviewService adaptiveInterviewService;

    /**
     * 创建或复用一个面试会话。
     *
     * <p>调用入口：{@code InterviewController.createSession}。
     * 未强制创建且简历存在未完成会话时，先调用
     * {@link #findUnfinishedSession(Long)} 返回旧会话。
     *
     * <p>新建路径会查询历史题目，调用
     * {@link InterviewQuestionService#generateQuestionsBySkill} 生成问题，
     * 随后写入 Redis 缓存并尝试持久化数据库。数据库写入失败会记录日志，
     * 但不影响已经创建的缓存会话返回。
     *
     * @param request 创建会话所需的简历、技能、难度和模型配置
     * @return 新创建或复用的会话 DTO
     */
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        // 如果指定了resumeId且未强制创建，检查是否有未完成的会话
        if (request.resumeId() != null && !Boolean.TRUE.equals(request.forceCreate())) {
            Optional<InterviewSessionDTO> unfinishedOpt = findUnfinishedSession(request.resumeId());
            if (unfinishedOpt.isPresent()) {
                log.info("检测到未完成的面试会话，返回现有会话: resumeId={}, sessionId={}",
                    request.resumeId(), unfinishedOpt.get().sessionId());
                return unfinishedOpt.get();
            }
        }
        //生成会话id
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        //获取面试方向，即前端选择的面试方向，skill
        String skillId = request.skillId() != null ? request.skillId() : InterviewDefaults.SKILL_ID;
        //获取面试难度
        String difficulty = request.difficulty() != null ? request.difficulty() : InterviewDefaults.DIFFICULTY;

        log.info("创建新面试会话: {}, skill: {}, difficulty: {}, questionCount: {}, resumeId: {}",
            sessionId, skillId, difficulty, request.questionCount(), request.resumeId());

        // 获取历史问题（通用模式按 skillId 查询，有简历时按 resumeId + skillId 精确匹配）
        List<HistoricalQuestion> historicalQuestions =
            persistenceService.getHistoricalQuestions(skillId, request.resumeId());

        // 基于 Skill 生成面试问题，再调用面试题目服务，颞部根据skill生成对应的面试问题
        //这个方法内部调用llm，用来生成问题，返回面试题目列表
        List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
            request.llmProvider(),
            skillId,
            difficulty,
            request.resumeText(),
            request.questionCount(),
            historicalQuestions,
            request.customCategories(),
            request.jdText()
        );

        // 保存到 Redis 缓存
        //缓存会话信息，包括会话id、简历文本、简历id、面试题目列表、当前问题索引、会话状态
        //以便后续获取会话信息
        sessionCache.saveSession(
            sessionId,
            request.resumeText() != null ? request.resumeText() : "",
            request.resumeId(),
            questions,
            0,
            SessionStatus.CREATED
        );

        // 保存到数据库
        try {
            persistenceService.saveSession(sessionId, request.resumeId(),
                questions.size(), questions, request.llmProvider(), skillId, difficulty);
        } catch (Exception e) {
            log.warn("保存面试会话到数据库失败: {}", e.getMessage());
        }
        //返回会话实体
        return new InterviewSessionDTO(
            //会话id
            sessionId,
            //简历文本
            request.resumeText() != null ? request.resumeText() : "",
            //面试题目数量
            questions.size(),
            0,
            //面试题目列表
            questions,
            //会话状态
            SessionStatus.CREATED
        );
    }

    /**
     * 获取会话信息（优先从缓存获取，缓存未命中则从数据库恢复）
     *
     * <p>调用入口：{@code InterviewController.getSession}。
     * 先查询 {@code InterviewSessionCache}，未命中时调用
     * {@link #restoreSessionFromDatabase(String)} 恢复并回填缓存。
     *
     * @param sessionId 会话业务 ID
     * @return 会话 DTO
     * @throws BusinessException 会话不存在或无法恢复时抛出
     */
    public InterviewSessionDTO getSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            return toDTO(cachedOpt.get());
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return toDTO(restoredSession);
    }

    /**
     * 查找并恢复未完成的面试会话
     *
     * <p>由 {@link #createSession(CreateInterviewRequest)} 和
     * {@link #findUnfinishedSessionOrThrow(Long)} 调用。
     * 先通过简历 ID 查询 Redis 索引，未命中时查询数据库并调用
     * {@link #restoreSessionFromEntity(InterviewSessionEntity)} 回填缓存。
     * 恢复异常会记录日志并降级为空结果。
     *
     * @param resumeId 简历 ID
     * @return 未完成会话；不存在或恢复失败时为空
     */
    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId) {
        try {
            // 1. 先从 Redis 缓存查找
            Optional<String> cachedSessionIdOpt = sessionCache.findUnfinishedSessionId(resumeId);
            if (cachedSessionIdOpt.isPresent()) {
                String sessionId = cachedSessionIdOpt.get();
                Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
                if (cachedOpt.isPresent()) {
                    log.debug("从 Redis 缓存找到未完成会话: resumeId={}, sessionId={}", resumeId, sessionId);
                    return Optional.of(toDTO(cachedOpt.get()));
                }
            }

            // 2. 缓存未命中，从数据库查找
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findUnfinishedSession(resumeId);
            if (entityOpt.isEmpty()) {
                return Optional.empty();
            }

            InterviewSessionEntity entity = entityOpt.get();
            CachedSession restoredSession = restoreSessionFromEntity(entity);
            if (restoredSession != null) {
                return Optional.of(toDTO(restoredSession));
            }
        } catch (Exception e) {
            log.error("恢复未完成会话失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * 查找并恢复未完成的面试会话，如果不存在则抛出异常
     *
     * <p>调用入口：{@code InterviewController.findUnfinishedSession}。
     * 主要调用 {@link #findUnfinishedSession(Long)}，
     * 将空 Optional 转换为面试会话不存在的业务异常。
     *
     * @param resumeId 简历 ID
     * @return 未完成的会话 DTO
     * @throws BusinessException 未找到未完成会话时抛出
     */
    public InterviewSessionDTO findUnfinishedSessionOrThrow(Long resumeId) {
        return findUnfinishedSession(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "未找到未完成的面试会话"));
    }

    /**
     * 从数据库恢复会话并缓存到 Redis
     *
     * <p>由 {@link #getSession(String)} 和 {@link #getOrRestoreSession(String)}
     * 在缓存未命中时调用。先通过持久化服务查询实体，
     * 再委托 {@link #restoreSessionFromEntity(InterviewSessionEntity)} 完成恢复。
     *
     * @param sessionId 会话业务 ID
     * @return 恢复后的缓存会话；不存在或恢复失败时为 {@code null}
     */
    private CachedSession restoreSessionFromDatabase(String sessionId) {
        try {
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
            return entityOpt.map(this::restoreSessionFromEntity).orElse(null);
        } catch (Exception e) {
            log.error("从数据库恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从实体恢复会话并缓存到 Redis
     *
     * <p>由数据库恢复和未完成会话查询调用。
     * 该方法反序列化题目、合并已保存答案、转换状态并重新建立 Redis 缓存。
     * 任一步失败都会记录日志并返回 {@code null}。
     *
     * @param entity 数据库会话实体
     * @return 写回 Redis 后重新读取的缓存会话；失败时为 {@code null}
     */
    private CachedSession restoreSessionFromEntity(InterviewSessionEntity entity) {
        try {
            // 解析问题列表
            List<InterviewQuestionDTO> questions = objectMapper.readValue(
                entity.getQuestionsJson(),
                new TypeReference<>() {}
            );

            // 恢复已保存的答案
            List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(entity.getSessionId());
            for (InterviewAnswerEntity answer : answers) {
                int index = answer.getQuestionIndex();
                if (index >= 0 && index < questions.size()) {
                    InterviewQuestionDTO question = questions.get(index);
                    questions.set(index, question.withAnswer(answer.getUserAnswer()));
                }
            }

            SessionStatus status = convertStatus(entity.getStatus());

            // 保存到 Redis 缓存
            sessionCache.saveSession(
                entity.getSessionId(),
                entity.getResume() != null ? entity.getResume().getResumeText() : "",
                entity.getResume() != null ? entity.getResume().getId() : null,
                questions,
                entity.getCurrentQuestionIndex(),
                status
            );

            log.info("从数据库恢复会话到 Redis: sessionId={}, currentIndex={}, status={}",
                entity.getSessionId(), entity.getCurrentQuestionIndex(), entity.getStatus());

            // 返回缓存的会话
            return sessionCache.getSession(entity.getSessionId()).orElse(null);
        } catch (Exception e) {
            log.error("恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 把持久化状态转换为缓存 DTO 使用的状态。
     *
     * <p>仅由 {@link #restoreSessionFromEntity(InterviewSessionEntity)} 调用，
     * 不产生任何状态写入。
     *
     * @param status JPA 实体状态
     * @return 对应的会话 DTO 状态
     */
    private SessionStatus convertStatus(InterviewSessionEntity.SessionStatus status) {
        return switch (status) {
            case CREATED -> SessionStatus.CREATED;
            case IN_PROGRESS -> SessionStatus.IN_PROGRESS;
            case COMPLETED -> SessionStatus.COMPLETED;
            case EVALUATED -> SessionStatus.EVALUATED;
        };
    }

    /**
     * 获取当前问题的响应（包含完成状态）
     *
     * <p>调用入口：{@code InterviewController.getCurrentQuestion}。
     * 主要调用 {@link #getCurrentQuestion(String)}；没有下一题时返回
     * {@code completed=true}，否则返回当前题目。
     *
     * @param sessionId 会话业务 ID
     * @return 适合 Controller 直接返回的题目或完成状态
     */
    public Map<String, Object> getCurrentQuestionResponse(String sessionId) {
        InterviewQuestionDTO question = getCurrentQuestion(sessionId);
        if (question == null) {
            return Map.of(
                "completed", true,
                "message", "所有问题已回答完毕"
            );
        }
        return Map.of(
            "completed", false,
            "question", question
        );
    }

    /**
     * 获取当前问题
     *
     * <p>由 {@link #getCurrentQuestionResponse(String)} 调用。
     * 通过 {@link #getOrRestoreSession(String)} 取得会话；首次取题时把状态
     * 从 CREATED 更新为 IN_PROGRESS，并尽力同步数据库。
     *
     * @param sessionId 会话业务 ID
     * @return 当前题目；题目已经全部完成时为 {@code null}
     * @throws BusinessException 会话不存在时抛出
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        if (session.getCurrentIndex() >= questions.size()) {
            return null; // 所有问题已回答完
        }

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            session.setStatus(SessionStatus.IN_PROGRESS);
            sessionCache.updateSessionStatus(sessionId, SessionStatus.IN_PROGRESS);

            // 同步到数据库
            try {
                persistenceService.updateSessionStatus(sessionId,
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            } catch (Exception e) {
                log.warn("更新会话状态失败: {}", e.getMessage());
            }
        }

        return questions.get(session.getCurrentIndex());
    }

    /**
     * 提交答案（并进入下一题）
     *
     * <p>调用入口：{@code InterviewController.submitAnswer}。
     * 校验索引后更新缓存题目和进度，尝试持久化答案与会话状态，
     * 并调用 {@code AdaptiveInterviewService.evaluateAndDecideSafely} 做单轮评估。
     * 最后一题完成后通过 {@code EvaluateStreamProducer} 投递最终评估任务。
     *
     * <p>数据库、自适应评估或入队阶段的异常当前会被记录并降级，
     * 已经更新的 Redis 会话进度不会回滚。
     *
     * @param request 会话 ID、题目索引和答案
     * @return 下一题、进度和可选的自适应评估结果
     * @throws BusinessException 会话不存在或题目索引非法时抛出
     */
    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        //从redis中获取会话
        CachedSession session = getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        // 移动到下一题
        int newIndex = index + 1;

        // 检查是否全部完成
        boolean hasNextQuestion = newIndex < questions.size();
        InterviewQuestionDTO nextQuestion = hasNextQuestion ? questions.get(newIndex) : null;

        SessionStatus newStatus = hasNextQuestion ? SessionStatus.IN_PROGRESS : SessionStatus.COMPLETED;

        // 更新 Redis 缓存
        sessionCache.updateQuestions(request.sessionId(), questions);
        // 更新当前题目索引
        sessionCache.updateCurrentIndex(request.sessionId(), newIndex);
        if (newStatus == SessionStatus.COMPLETED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.COMPLETED);
        }

        // 保存答案到数据库
        AdaptiveInterviewResultDTO adaptiveResult = null;
        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null  // 分数在报告生成时更新
            );
            persistenceService.updateCurrentQuestionIndex(request.sessionId(), newIndex);
            persistenceService.updateSessionStatus(request.sessionId(),
                newStatus == SessionStatus.COMPLETED
                    ? InterviewSessionEntity.SessionStatus.COMPLETED
                    : InterviewSessionEntity.SessionStatus.IN_PROGRESS);

            // 评估当前问题,每提交一个答案,就评估一次,并更新会话状态
            adaptiveResult = adaptiveInterviewService.evaluateAndDecideSafely(
                request.sessionId(),
                question,
                request.answer()
            );

            // 如果是最后一题，设置评估状态为 PENDING 并触发异步评估
            if (!hasNextQuestion) {
                persistenceService.updateEvaluateStatus(request.sessionId(), AsyncTaskStatus.PENDING, null);
                evaluateStreamProducer.sendEvaluateTask(request.sessionId());
                log.info("会话 {} 已完成所有问题，评估任务已入队", request.sessionId());
            }
        } catch (Exception e) {
            log.warn("保存答案到数据库失败: {}", e.getMessage());
        }

        log.info("会话 {} 提交答案: 问题{}, 剩余{}题",
            request.sessionId(), index, questions.size() - newIndex);

        return new SubmitAnswerResponse(
            hasNextQuestion,
            nextQuestion,
            newIndex,
            questions.size(),
            adaptiveResult != null ? adaptiveResult.turnEvaluation() : null,
            adaptiveResult != null ? adaptiveResult.adaptiveDecision() : null,
            adaptiveResult != null ? adaptiveResult.abilityProfile() : null
        );
    }

    /**
     * 暂存答案（不进入下一题）
     *
     * <p>调用入口：{@code InterviewController.saveAnswer}。
     * 更新 Redis 中的题目答案并尝试写入数据库，但不修改当前题目索引，
     * 也不调用自适应评估或最终评估 Producer。
     *
     * @param request 会话 ID、题目索引和暂存答案
     * @throws BusinessException 会话不存在或题目索引非法时抛出
     */
    public void saveAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        // 更新 Redis 缓存
        sessionCache.updateQuestions(request.sessionId(), questions);

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.IN_PROGRESS);
        }

        // 保存答案到数据库（不更新currentIndex）
        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null
            );
            persistenceService.updateSessionStatus(request.sessionId(),
                InterviewSessionEntity.SessionStatus.IN_PROGRESS);
        } catch (Exception e) {
            log.warn("暂存答案到数据库失败: {}", e.getMessage());
        }

        log.info("会话 {} 暂存答案: 问题{}", request.sessionId(), index);
    }

    /**
     * 提前交卷（触发异步评估）
     *
     * <p>调用入口：{@code InterviewController.completeInterview}。
     * 先把缓存和数据库会话更新为 COMPLETED，再将评估状态置为 PENDING，
     * 最后调用 {@code EvaluateStreamProducer.sendEvaluateTask} 投递任务。
     *
     * @param sessionId 会话业务 ID
     * @throws BusinessException 会话不存在或已经完成时抛出
     */
    public void completeInterview(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() == SessionStatus.COMPLETED || session.getStatus() == SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }

        // 更新 Redis 缓存
        sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);

        // 更新数据库状态
        try {
            persistenceService.updateSessionStatus(sessionId,
                InterviewSessionEntity.SessionStatus.COMPLETED);
            // 设置评估状态为 PENDING
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        } catch (Exception e) {
            log.warn("更新会话状态失败: {}", e.getMessage());
        }

        // 发送评估任务到 Redis Stream，传入的是会话id，代表当前会话
        evaluateStreamProducer.sendEvaluateTask(sessionId);

        log.info("会话 {} 提前交卷，评估任务已入队", sessionId);
    }

    /**
     * 获取或恢复会话（优先从缓存获取）
     *
     * <p>由取题、提交、暂存、提前交卷和报告生成共同调用。
     * 缓存命中时刷新 TTL；未命中时调用
     * {@link #restoreSessionFromDatabase(String)}，恢复失败则抛出业务异常。
     *
     * @param sessionId 会话业务 ID
     * @return 可读写的缓存会话
     * @throws BusinessException 会话不存在时抛出
     */
    private CachedSession getOrRestoreSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            // 刷新 TTL
            sessionCache.refreshSessionTTL(sessionId);
            return cachedOpt.get();
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return restoredSession;
    }

    /**
     * 生成评估报告
     *
     * <p>调用入口：{@code InterviewController.getReport}。
     * 校验会话完成状态后，根据数据库记录选择 LLM Provider，调用
     * {@code AnswerEvaluationService.evaluateInterview} 同步生成报告，
     * 再附加自适应分析、更新缓存状态并尝试持久化报告。
     *
     * @param sessionId 会话业务 ID
     * @return 完整面试报告
     * @throws BusinessException 会话不存在或尚未完成时抛出
     */
    public InterviewReportDTO generateReport(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() != SessionStatus.COMPLETED && session.getStatus() != SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告");
        }

        log.info("生成面试报告: {}", sessionId);

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        // 获取 LLM 客户端
        String provider = null;
        Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
        if (entityOpt.isPresent()) {
            provider = entityOpt.get().getLlmProvider();
        }
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

        InterviewReportDTO report = evaluationService.evaluateInterview(
            chatClient,
            sessionId,
            session.getResumeText(),
            questions
        );
        // 评估报告,并添加适应性分析
        report = adaptiveInterviewService.attachAdaptiveAnalysis(sessionId, report);

        // 更新 Redis 缓存状态
        sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);

        // 保存报告到数据库
        try {
            persistenceService.saveReport(sessionId, report);
        } catch (Exception e) {
            log.warn("保存报告到数据库失败: {}", e.getMessage());
        }

        return report;
    }

    /**
     * 将缓存会话转换为 DTO
     *
     * <p>由会话查询、未完成会话恢复和创建复用路径调用，
     * 负责反序列化缓存中的问题列表并隔离缓存内部结构。
     *
     * @param session Redis 缓存会话
     * @return 对外会话 DTO
     */
    private InterviewSessionDTO toDTO(CachedSession session) {
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        return new InterviewSessionDTO(
            session.getSessionId(),
            session.getResumeText(),
            questions.size(),
            session.getCurrentIndex(),
            questions,
            session.getStatus()
        );
    }
}
