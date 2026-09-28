/**
 * 语音面试核心业务服务
 *
 * 语音面试模块的核心服务层，负责会话全生命周期管理：创建、结束、暂停、恢复、删除；
 * 面试阶段切换与状态追踪；对话消息持久化与历史查询；Redis 缓存加速会话读取；
 * 异步评估任务触发。WebSocket 异常断开时自动结束会话防止状态泄漏。
 *
 * 联动关系：
 * - 被 VoiceInterviewController 依赖（REST API 的业务委托）
 * - 被 VoiceInterviewWebSocketHandler 依赖（WebSocket 中的会话操作和消息保存）
 * - 被 VoiceEvaluateStreamConsumer/Producer 依赖（更新评估状态）
 * - 依赖 VoiceInterviewSessionRepository / MessageRepository / EvaluationRepository
 * - 依赖 VoiceEvaluateStreamProducer（结束会话时触发异步评估）
 * - 依赖 RedissonClient（会话 Redis 缓存）
 * - 依赖 VoiceInterviewProperties（阶段时长配置）
 * - 依赖 LlmProviderRegistry（获取默认 Provider）
 */
package interview.guide.modules.voiceinterview.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import interview.guide.modules.voiceinterview.dto.SessionMetaDTO;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 语音面试核心业务服务
 *
 * 提供语音面试会话的完整生命周期管理功能
 * 包括：会话创建、状态管理、消息持久化、Redis 缓存、阶段控制等
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewService {

    /**
     * 语音面试会话数据仓库
     * 用于会话的 CRUD 操作
     */
    private final VoiceInterviewSessionRepository sessionRepository;

    /**
     * 语音面试消息数据仓库
     * 用于对话消息的持久化和查询
     */
    private final VoiceInterviewMessageRepository messageRepository;

    /**
     * 语音面试评估数据仓库
     * 用于评估结果的查询
     */
    private final VoiceInterviewEvaluationRepository evaluationRepository;

    /**
     * Redis 客户端
     * 用于会话缓存加速读取
     */
    private final RedissonClient redissonClient;

    /**
     * 语音面试配置属性
     * 用于读取阶段时长等配置
     */
    private final VoiceInterviewProperties properties;

    /**
     * 语音面试评估任务生产者
     * 用于发送评估任务到 Redis Stream
     */
    private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;

    /** Redis 会话缓存 Key 前缀 */
    private static final String SESSION_CACHE_KEY_PREFIX = "voice:interview:session:";
    /** 缓存有效期（小时） */
    private static final int CACHE_TTL_HOURS = 1;
    /** 默认用户 ID */
    private static final String DEFAULT_USER_ID = "default";

    /**
     * 根据前端配置创建并缓存新的语音面试会话。
     *
     * 由 REST 创建会话接口调用。方法补齐技能、难度和 Provider 默认值，通过
     * {@link #determineFirstPhase(CreateSessionRequest)} 选择首个启用阶段，随后写入数据库和 Redis，
     * 最终返回建立 WebSocket 连接所需的会话 DTO。
     * 
     * @param request 创建会话请求（包含技能方向、难度、阶段配置等）
     * @return 会话响应 DTO（包含会话 ID 和 WebSocket URL）
     */
    @Transactional
    public SessionResponseDTO createSession(CreateSessionRequest request) {
        // 处理默认值，skillid是面试模板id，比如"java-backend", "bytedance-backend"
        String effectiveSkillId = request.getSkillId() != null ? request.getSkillId() : InterviewDefaults.SKILL_ID;
        // 处理默认值，llmProvider是llm模型提供者，比如"openai", "qwen-plus"
        String effectiveLlmProvider = (request.getLlmProvider() != null && !request.getLlmProvider().isBlank())
            ? request.getLlmProvider()
            : null;

        // 创建会话实体
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                .userId(DEFAULT_USER_ID)
                //作为面试角色类型
                .roleType(effectiveSkillId)
                .skillId(effectiveSkillId)
                .difficulty(request.getDifficulty() != null ? request.getDifficulty() : InterviewDefaults.DIFFICULTY)
                .customJdText(request.getCustomJdText())
                .resumeId(request.getResumeId())
                .introEnabled(request.getIntroEnabled())
                .techEnabled(request.getTechEnabled())
                .projectEnabled(request.getProjectEnabled())
                .hrEnabled(request.getHrEnabled())
                .llmProvider(effectiveLlmProvider)
                .plannedDuration(request.getPlannedDuration())
                .currentPhase(determineFirstPhase(request))
                .build();

        // 保存会话
        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        // 缓存会话，确保后续快速访问  ，缓存到Redis
        cacheSession(saved);

        log.info("Created voice interview session: {} with template: {}, phase: {}",
                saved.getId(), effectiveSkillId, saved.getCurrentPhase());

        //构建会话响应DTO，包含会话ID和WebSocket URL
        return buildSessionResponse(saved);
    }

    /**
     * 在 WebSocket 断开后兜底结束仍在进行的会话。
     *
     * 由 {@code VoiceInterviewWebSocketHandler.afterConnectionClosed} 调用。仅处理
     * {@link VoiceInterviewSessionStatus#IN_PROGRESS}，因此不会覆盖正常结束或暂停状态；该兜底只
     * 收口会话状态，不重复投递评估任务。
     *
     * @param sessionId 会话 ID（字符串格式）
     */
    @Transactional
    public void endSessionIfInProgress(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong).orElse(null);
        if (session == null || session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            return;
        }
        log.info("Auto-ending IN_PROGRESS session {} after WebSocket disconnect", sessionId);
        endSession(session);
    }

    /**
     * 正常结束会话并投递异步评估任务。
     *
     * REST 结束接口和 WebSocket 的 {@code end_interview} 控制消息都会调用。方法先通过
     * {@link #endSession(VoiceInterviewSessionEntity)} 持久化结束状态，再调用
     * {@link VoiceEvaluateStreamProducer#sendEvaluateTask(String)} 创建任务并发送到 Redis Stream。
     *
     * @param sessionId 会话 ID（字符串格式）
     */
    @Transactional
    public void endSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        endSession(session);
        voiceEvaluateStreamProducer.sendEvaluateTask(sessionId);
    }

    /**
     * 收口会话生命周期字段并使缓存失效。
     *
     * 供正常结束和断连兜底复用；负责计算实际时长、标记评估待处理并保存实体，但不负责投递消息，
     * 以便不同入口自行决定是否触发评估。
     *
     * @param session 会话实体
     */
    private void endSession(VoiceInterviewSessionEntity session) {
        session.setEndTime(LocalDateTime.now());
        session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
        session.setStatus(VoiceInterviewSessionStatus.COMPLETED);
        session.setActualDuration((int) Duration.between(session.getStartTime(), LocalDateTime.now()).toSeconds());
        session.setEvaluateStatus(AsyncTaskStatus.PENDING);

        sessionRepository.save(session);
        // 从缓存中移除会话，确保后续查询获取最新状态
        invalidateSessionCache(session.getId());

        log.info("Ended voice interview session: {}, duration: {} seconds, evaluation triggered",
                session.getId(), session.getActualDuration());
    }

    /**
     * 将字符串会话 ID 解析后委托给缓存版查询。
     *
     * WebSocket 链路使用字符串 ID，因此通过该重载统一复用 {@link #getSession(Long)}。
     *
     * @param sessionId 会话 ID（字符串格式，会转换为 Long）
     * @return 会话实体或 null
     */
    public VoiceInterviewSessionEntity getSession(String sessionId) {
        return getSession(parseSessionId(sessionId));
    }

    /**
     * 按 ID 查询会话，优先读取 Redis，未命中时回源数据库。
     *
     * 被 Controller、WebSocket Handler 和评估链路共同调用。数据库回源结果当前不自动回填缓存，
     * 避免把可能已经变化的状态再次延长缓存生命周期。
     *
     * @param sessionId 会话 ID（Long 格式）
     * @return 会话实体或 null
     */
    public VoiceInterviewSessionEntity getSession(Long sessionId) {
        if (sessionId == null) {
            return null;
        }

        // 先查缓存
        String cacheKey = getSessionCacheKey(sessionId);
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        VoiceInterviewSessionEntity cached = bucket.get();

        if (cached != null) {
            log.debug("Session {} found in cache", sessionId);
            return cached;
        }

        // 缓存未命中，查数据库
        return sessionRepository.findById(sessionId).orElse(null);
    }

    /**
     * 更新会话的当前面试阶段（仅更新数据库字段，不触发出题）。
     *
     * 用户在面试中切换阶段（如从 INTRO 到 TECH）时，前端通过 WebSocket 发送
     * start_phase 控制消息，Handler 调用此方法更新 currentPhase。
     * 真正的阶段切换行为（AI 出对应阶段的第一题）由 triggerLlmResponse 在
     * 读取 currentPhase 时自动处理。
     *
     * @param sessionId 会话 ID（字符串格式）
     * @param phaseStr  阶段名称（INTRO, TECH, PROJECT, HR）
     */
    @Transactional
    public void startPhase(String sessionId, String phaseStr) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("Cannot start phase - session not found: {}", sessionId);
            return;
        }

        try {
            VoiceInterviewSessionEntity.InterviewPhase newPhase =
                    VoiceInterviewSessionEntity.InterviewPhase.valueOf(phaseStr.toUpperCase());

            VoiceInterviewSessionEntity.InterviewPhase oldPhase = session.getCurrentPhase();
            session.setCurrentPhase(newPhase);
            sessionRepository.save(session);
            cacheSession(session);

            log.info("Session {} transitioned from phase {} to {}", sessionId, oldPhase, newPhase);

        } catch (IllegalArgumentException e) {
            log.error("Invalid phase string: {}", phaseStr, e);
        }
    }

    /**
     * 获取实时出题所使用的当前面试阶段。
     *
     * WebSocket LLM 管线在组织下一轮提示词或判断阶段切换时调用，内部复用缓存版会话查询。
     *
     * @param sessionId 会话 ID（字符串格式）
     * @return 当前阶段或 null
     */
    public VoiceInterviewSessionEntity.InterviewPhase getCurrentPhase(String sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);
        return session != null ? session.getCurrentPhase() : null;
    }

    /**
     * 将一轮 ASR 用户文本和 LLM 回复持久化为对话消息。
     *
     * 由 WebSocket Handler 在开场问题和每轮 LLM/TTS 完成后调用。方法读取当前阶段，使用
     * {@link #getNextSequenceNum(Long)} 生成展示顺序，再保存到消息 Repository。
     *
     * @param sessionId 会话 ID（字符串格式）
     * @param userText 用户说的话（ASR 识别结果）
     * @param aiText AI 的回复
     */
    @Transactional
    public void saveMessage(String sessionId, String userText, String aiText) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("Cannot save message - session not found: {}", sessionId);
            return;
        }

        //会话的一个消息，而不是整个会话
        VoiceInterviewMessageEntity message = VoiceInterviewMessageEntity.builder()
                .sessionId(sessionIdLong)
                .messageType("DIALOGUE")
                .phase(session.getCurrentPhase())
                .userRecognizedText(userText)
                .aiGeneratedText(aiText)
                .sequenceNum(getNextSequenceNum(sessionIdLong))
                .build();

        messageRepository.save(message);
        log.debug("Saved message for session: {}, phase: {}, sequence: {}",
                sessionId, session.getCurrentPhase(), message.getSequenceNum());
    }

    /**
     * 查询供内部 LLM 和评估链路使用的有序对话实体。
     *
     * WebSocket Handler 使用实体构建上下文；前端接口应调用
     * {@link #getConversationHistoryDTO(String)}，禁止直接暴露此方法返回的实体。
     * @param sessionId 会话 ID（字符串格式）
     * @return 消息实体列表（按序号排序）
     */
    public List<VoiceInterviewMessageEntity> getConversationHistory(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        return messageRepository.findBySessionIdOrderBySequenceNumAsc(sessionIdLong);
    }

    /**
     * 查询并转换前端可展示的对话历史。
     *
     * 由消息历史 REST 接口调用，先复用 {@link #getConversationHistory(String)} 的有序查询，再逐条
     * 映射阶段、识别文本、AI 回复和序号。
     *
     * @param sessionId 会话 ID（字符串格式）
     * @return 消息 DTO 列表
     */
    public List<VoiceInterviewMessageDTO> getConversationHistoryDTO(String sessionId) {
        return getConversationHistory(sessionId).stream()
            .map(msg -> VoiceInterviewMessageDTO.builder()
                .id(msg.getId())
                .sessionId(msg.getSessionId())
                .messageType(msg.getMessageType())
                .phase(msg.getPhase() != null ? msg.getPhase().name() : null)
                .userRecognizedText(msg.getUserRecognizedText())
                .aiGeneratedText(msg.getAiGeneratedText())
                .timestamp(msg.getTimestamp())
                .sequenceNum(msg.getSequenceNum())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * 校验并暂停进行中的面试会话。
     *
     * REST 暂停接口调用。方法直接查库以取得最新状态，写入暂停时间后清除 Redis 缓存；状态不合法
     * 时抛出 {@link BusinessException}，避免把已结束会话重新置为暂停。
     *
     * @param sessionId 会话 ID
     * @param reason 暂停原因（user_initiated 或 timeout）
     */
    @Transactional
    public void pauseSession(String sessionId, String reason) {
        Long sessionIdLong = parseSessionId(sessionId);

        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));

        if (session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "会话状态为 " + session.getStatus() + "，无法暂停"
            );
        }

        session.setStatus(VoiceInterviewSessionStatus.PAUSED);
        session.setPausedAt(LocalDateTime.now());

        sessionRepository.save(session);
        //会话暂停就删除缓存中的会话，确保后续查询获取最新状态
        invalidateSessionCache(sessionIdLong);

        log.info("Session {} paused, reason: {}", sessionId, reason);
    }

    /**
     * 校验并恢复已暂停的面试会话。
     *
     * REST 恢复接口调用。方法更新状态和恢复时间，保存后重新缓存会话，并返回前端重连 WebSocket
     * 所需的 DTO。
     *
     * @param sessionId 会话 ID
     * @return 会话响应 DTO
     */
    @Transactional
    public SessionResponseDTO resumeSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);

        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));

        if (session.getStatus() != VoiceInterviewSessionStatus.PAUSED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "会话状态为 " + session.getStatus() + "，无法恢复"
            );
        }

        session.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
        session.setResumedAt(LocalDateTime.now());

        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        //会话恢复就缓存会话，确保后续查询获取最新状态
        cacheSession(saved);

        log.info("Session {} resumed with {} messages in conversation history",
            sessionId, messageRepository.countBySessionId(sessionIdLong));

        return buildSessionResponse(saved);
    }

    /**
     * 获取用户的所有会话
     *
     * @param userId 用户 ID（可选，为空则返回所有会话）
     * @param status 状态过滤（可选）
     * @return 会话元信息列表
     */
    public List<SessionMetaDTO> getAllSessions(String userId, String status) {
        userId = userId != null ? userId : DEFAULT_USER_ID;

        List<VoiceInterviewSessionEntity> sessions;
        if (status != null && !status.isEmpty()) {
            VoiceInterviewSessionStatus statusEnum =
                VoiceInterviewSessionStatus.valueOf(status.toUpperCase());
            sessions = sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, statusEnum);
        } else {
            sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        }

        return sessions.stream()
            .map(session -> SessionMetaDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .status(session.getStatus().name())
                .currentPhase(session.getCurrentPhase().name())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .actualDuration(session.getActualDuration())
                .messageCount(messageRepository.countBySessionId(session.getId()))
                .evaluateStatus(session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null)
                .evaluateError(session.getEvaluateError())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * 查询会话并转换为 REST 响应 DTO。
     *
     * 由会话详情接口调用，复用缓存查询和 {@link #buildSessionResponse}；会话不存在时返回
     * {@code null}，由控制器转换为统一错误响应。
     *
     * @param sessionId 会话 ID
     * @return 会话响应 DTO 或 null
     */
    public SessionResponseDTO getSessionDTO(Long sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);

        if (session == null) {
            return null;
        }

        return buildSessionResponse(session);
    }

    /**
     * 检查是否应该转换到下一个阶段
     *
     * 基于时长和问题数量判断是否应该自动切换阶段
     *
     * @param session 当前会话
     * @param phaseStartTime 阶段开始时间
     * @param questionCount 当前阶段问题数量
     * @return 是否应该转换
     */
    public boolean shouldTransitionToNextPhase(VoiceInterviewSessionEntity session,
                                                LocalDateTime phaseStartTime,
                                                int questionCount) {
        VoiceInterviewSessionEntity.InterviewPhase currentPhase = session.getCurrentPhase();
        if (currentPhase == null || currentPhase == VoiceInterviewSessionEntity.InterviewPhase.COMPLETED) {
            return false;
        }

        // 计算当前阶段持续时间
        // 即位时间-开始时间=当前阶段持续时间，duration是表示时间间隔的类，java8引入的，不可变对象线程安全。
        Duration phaseDuration = Duration.between(phaseStartTime, LocalDateTime.now());
        // 获取当前阶段的配置
        VoiceInterviewProperties.DurationConfig config = getPhaseConfig(currentPhase);

        // 规则1：达到最大时长（强制转换）
        if (phaseDuration.toMinutes() >= config.getMaxDuration()) {
            log.info("Phase {} reached max duration {} minutes, forcing transition",
                    currentPhase, config.getMaxDuration());
            return true;
        }

        // 规则2：达到最大问题数量（建议转换）
        if (questionCount >= config.getMaxQuestions()) {
            log.info("Phase {} reached max questions {}, suggesting transition",
                    currentPhase, config.getMaxQuestions());
            return true;
        }

        // 规则3：达到建议时长且问题数量达标（建议转换）
        if (phaseDuration.toMinutes() >= config.getSuggestedDuration()
                && questionCount >= config.getMinQuestions()) {
            log.info("Phase {} reached suggested duration {} with {} questions, suggesting transition",
                    currentPhase, config.getSuggestedDuration(), questionCount);
            return true;
        }

        return false;
    }

    /**
     * 获取下一阶段的面试阶段，跳过用户禁用的阶段。
     * <p>
     * 根据会话的四个阶段开关（introEnabled / techEnabled / projectEnabled / hrEnabled），
     * 从当前阶段向后查找第一个启用的阶段。若当前阶段之后所有阶段均被禁用，返回 COMPLETED。
     * <p>
     * 调用链路：triggerLlmResponse → shouldTransitionToNextPhase → getNextPhase
     * （LLM 回复完当前问题后，检查阶段题目数是否达标，达标则用此方法确定下一阶段并自动切换）
     *
     * @param session 当前会话
     * @return 下一个启用的阶段，或 COMPLETED（所有阶段均已结束）
     */
    public VoiceInterviewSessionEntity.InterviewPhase getNextPhase(VoiceInterviewSessionEntity session) {
        VoiceInterviewSessionEntity.InterviewPhase current = session.getCurrentPhase();

        // 当前阶段为 null（极端情况，如创建时未设置）→ 退回到第一个启用的阶段
        if (current == null) {
            return getFirstEnabledPhase(session);
        }

        // 用 switch 表达式根据当前阶段计算下一阶段：
        // 每个 case 中按阶段顺序（TECH → PROJECT → HR）依次检查启用开关，
        // 找到第一个启用的阶段即返回，全部禁用则返回 COMPLETED
        return switch (current) {
            // INTRO 结束后 → 按 TECH → PROJECT → HR 顺序找第一个启用的阶段
            case INTRO -> session.getTechEnabled()
                    ? VoiceInterviewSessionEntity.InterviewPhase.TECH
                    : session.getProjectEnabled()
                            ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT
                            : session.getHrEnabled()
                                    ? VoiceInterviewSessionEntity.InterviewPhase.HR
                                    : VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;

            // TECH 结束后 → 按 PROJECT → HR 顺序查找
            case TECH -> session.getProjectEnabled()
                    ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT
                    : session.getHrEnabled()
                            ? VoiceInterviewSessionEntity.InterviewPhase.HR
                            : VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;

            // PROJECT 结束后 → 只剩 HR，检查是否启用
            case PROJECT -> session.getHrEnabled()
                    ? VoiceInterviewSessionEntity.InterviewPhase.HR
                    : VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;

            // HR 已结束或已处于 COMPLETED → 不再有下一阶段
            case HR, COMPLETED -> VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
        };
    }

    /**
     * 根据启用的阶段确定第一个阶段
     * 创建会话时被调用，根据用户启用的阶段，确定第一个启用的阶段作为会话的初始阶段。
     * 若所有阶段均被禁用，返回 COMPLETED 表示会话立即结束
     * @param request 创建会话请求
     * @return 第一个阶段
     */
    private VoiceInterviewSessionEntity.InterviewPhase determineFirstPhase(CreateSessionRequest request) {
        if (request.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (request.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (request.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (request.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    /**
     * 获取第一个启用的阶段
     * 
     * @param session 会话实体
     * @return 第一个启用的阶段
     */
    private VoiceInterviewSessionEntity.InterviewPhase getFirstEnabledPhase(VoiceInterviewSessionEntity session) {
        if (session.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (session.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (session.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (session.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    /**
     * 构建会话响应 DTO
     *
     * @param session 会话实体
     * @return 会话响应 DTO
     */
    private SessionResponseDTO buildSessionResponse(VoiceInterviewSessionEntity session) {
        return SessionResponseDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .currentPhase(session.getCurrentPhase().name())
                .status(session.getStatus().name())
                .startTime(session.getStartTime())
                .plannedDuration(session.getPlannedDuration())
                .webSocketUrl(String.format("ws://localhost:8080/ws/voice-interview/%d", session.getId()))
                .build();
    }

    /**
     * 获取阶段配置
     *
     * @param phase 阶段
     * @return 阶段时长配置
     */
    private VoiceInterviewProperties.DurationConfig getPhaseConfig(VoiceInterviewSessionEntity.InterviewPhase phase) {
        return switch (phase) {
            // 开场介绍阶段配置
            //case INTRO -> properties.getPhaseConfig().getIntro();
            case INTRO -> properties.getPhase().getIntro();
            // 技术面阶段配置
            case TECH -> properties.getPhase().getTech();
            // 项目面阶段配置
            case PROJECT -> properties.getPhase().getProject();
            // HR面阶段配置
            case HR -> properties.getPhase().getHr();
            default -> new VoiceInterviewProperties.DurationConfig(0, 0, 0, 0, 0);
        };
    }

    /**
     * 获取下一条消息的序号
     *
     * @param sessionId 会话 ID
     * @return 下一序号
     */
    private int getNextSequenceNum(Long sessionId) {
        return (int) messageRepository.countBySessionId(sessionId) + 1;
    }

    /**
     * 更新会话上的异步评估状态和错误摘要。
     *
     * Producer 在入队失败时、Consumer 在处理各阶段以及手动触发入口都会调用。该方法采取尽力更新
     * 策略：会话已删除时跳过，数据库写入异常只记录日志，避免掩盖消息消费主异常。
     *
     * @param sessionId 会话 ID
     * @param status 评估状态
     * @param error 错误信息
     */
    public void updateEvaluateStatus(Long sessionId, AsyncTaskStatus status, String error) {
        try {
            sessionRepository.findById(sessionId).ifPresent(session -> {
                session.setEvaluateStatus(status);
                session.setEvaluateError(error);
                sessionRepository.save(session);
                log.debug("Evaluation status updated: sessionId={}, status={}", sessionId, status);
            });
        } catch (Exception e) {
            log.error("Failed to update evaluation status: sessionId={}, status={}, error={}",
                    sessionId, status, e.getMessage(), e);
        }
    }

    /**
     * 将会话标记为待评估并交给 Producer 异步入队。
     *
     * 由手动评估 REST 接口调用；Producer 负责创建或复用任务，并在当前事务提交后发送 Redis
     * Stream 消息。
     *
     * @param sessionId 会话 ID
     */
    @Transactional
    public void triggerEvaluation(Long sessionId) {
        updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        // 触发异步评估任务，将会话ID发送到消息队列，即用redisstream消息队列。
        voiceEvaluateStreamProducer.sendEvaluateTask(sessionId.toString());
    }

    /**
     * 删除会话及其评估、消息关联数据。
     *
     * 由会话删除接口调用。先校验主会话存在，再按评估、消息、会话顺序删除，避免残留业务数据；
     * 会话不存在时抛出领域业务异常。
     *
     * @param sessionId 会话 ID
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }
        evaluationRepository.findBySessionId(sessionId).ifPresent(evaluationRepository::delete);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
        log.info("Deleted voice interview session: {}", sessionId);
    }

    /**
     * 将会话缓存到 Redis
     *
     * @param session 会话实体
     */
    private void cacheSession(VoiceInterviewSessionEntity session) {
        String cacheKey = getSessionCacheKey(session.getId());
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.set(session, Duration.ofHours(CACHE_TTL_HOURS));
        log.debug("Cached session: {}", session.getId());
    }

    /**
     * 清除会话 Redis 缓存
     *
     * @param sessionId 会话 ID
     */
    private void invalidateSessionCache(Long sessionId) {
        String cacheKey = getSessionCacheKey(sessionId);
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.delete();
        log.debug("Invalidated cache for session: {}", sessionId);
    }

    /**
     * 生成 Redis 缓存 Key
     *
     * @param sessionId 会话 ID
     * @return 缓存 Key
     */
    private String getSessionCacheKey(Long sessionId) {
        return SESSION_CACHE_KEY_PREFIX + sessionId;
    }

    /**
     * 解析会话 ID（字符串转 Long）
     *
     * @param sessionId 会话 ID 字符串
     * @return Long 类型的会话 ID 或 null
     */
    private Long parseSessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            log.error("Invalid session ID format: {}", sessionId, e);
            return null;
        }
    }
}
