/**
 * 语音面试 WebSocket 处理器
 *
 * 语音面试的核心实时通信处理器，负责 WebSocket 连接的全生命周期管理。
 * 实现完整的语音面试管线：用户音频 → ASR 语音识别 → LLM 大模型生成回复 → TTS 语音合成 → AI 音频推送。
 * 支持流式 LLM 输出与句子级并发 TTS、STT 断线重连、AI 回声冷却、
 * 用户语音防抖合并、暂停超时检测、开场问题预缓存等功能。
 *
 * 联动关系：
 * - 被 WebSocketConfig 注册到 /ws/voice-interview/{sessionId} 端点
 * - 依赖 QwenAsrService（实时语音识别）
 * - 依赖 QwenTtsService（文本转语音）
 * - 依赖 DashscopeLlmService（大模型对话生成） 
 * - 依赖 VoiceInterviewService（会话状态管理、消息持久化）
 * - 依赖 VoiceInterviewProperties（读取流式推送、音频、开场问题等配置）
 * - 依赖 WebSocketControlMessage / WebSocketSubtitleMessage（消息反序列化/推送）
 */
package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.observability.VoiceMetricsRecorder;
import interview.guide.modules.voiceinterview.dto.WebSocketControlMessage;
import interview.guide.modules.voiceinterview.dto.WebSocketSubtitleMessage;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 语音面试 WebSocket 处理器
 *
 * 语音面试的核心实时通信处理器，负责 WebSocket 连接的全生命周期管理。
 * 实现完整的语音面试管线：用户音频 → ASR 语音识别 → LLM 大模型生成回复 → TTS 语音合成 → AI 音频推送。
 * 支持流式 LLM 输出与句子级并发 TTS、STT 断线重连、AI 回声冷却、
 * 用户语音防抖合并、暂停超时检测、开场问题预缓存等功能。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewWebSocketHandler extends TextWebSocketHandler implements DisposableBean {

    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;
    /** Qwen 实时语音识别（ASR）服务 */
    private final QwenAsrService sttService;
    /** Qwen 文本转语音（TTS）服务 */
    private final QwenTtsService ttsService;
    /** DashScope LLM 对话服务 */
    private final DashscopeLlmService llmService;
    /** 语音面试会话管理服务 */
    private final VoiceInterviewService interviewService;
    /** 语音面试配置属性 */
    private final VoiceInterviewProperties voiceInterviewProperties;
    /** 语音面试指标记录器 */
    private final VoiceMetricsRecorder voiceMetricsRecorder;

    /**
     * 合并多段 STT 定稿后再触发 LLM 的延迟调度（与 {@link VoiceInterviewProperties#getUserUtteranceDebounceMs()} 配合）
     */
    private final ScheduledExecutorService utteranceMergeScheduler = createUtteranceMergeScheduler();

    /**
     * LLM / TTS / JDBC 等阻塞工作全部跑在虚拟线程上，避免占满 utteranceMergeScheduler 的 2 个调度线程。
     */
    private final ExecutorService voicePipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** WebSocket 会话映射表，Key 为 sessionId */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** 会话状态映射表，Key 为 sessionId */
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();
    /** 开场问题音频缓存，Key 为开场问题文本 */
    private final Map<String, byte[]> openingAudioCache = new ConcurrentHashMap<>();

    /** 活动跟踪（用于暂停超时检测），Key 为 sessionId，Value 为最后活动时间戳 */
    private final Map<String, Long> lastActivityTime = new ConcurrentHashMap<>();
    /** 暂停警告时间阈值（4分30秒） */
    private static final long WARNING_TIME_MS = (long) (4.5 * 60 * 1000);
    /** 暂停超时时间阈值（5分钟） */
    private static final long PAUSE_TIMEOUT_MS = 5 * 60 * 1000;
    /** WebSocket 发送超时时间（10秒） */
    private static final int WS_SEND_TIME_LIMIT_MS = 10_000;
    /** WebSocket 发送缓冲区大小限制（512KB） */
    private static final int WS_SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
    /** AI 音频播放结束后的冷却期，防止扬声器尾音被麦克风拾取触发 STT */
    private static final long AI_SPEAK_COOLDOWN_MS = 800;
    /** 默认算法方向开场问题 */
    private static final String DEFAULT_OPENING_QUESTION_ALGORITHM =
        "你好，我是本场面试官。第一个问题：请你口述一道算法题，不写代码，只讲\u300C问题建模、数据结构选型、步骤、复杂度、边界处理\u300D。";
    /** 默认后端方向开场问题 */
    private static final String DEFAULT_OPENING_QUESTION_BACKEND =
        "你好，我是本场面试官。第一个问题：请用 1 分钟介绍一个你深度参与的项目，按三点回答：业务目标、你负责的核心模块、核心技术栈。说完我会立刻追问一个关键技术决策。";

    /**
     * 创建 STT 合并重试所用的轻量调度器。
     *
     * 在 Handler 实例初始化时调用；仅负责短延迟调度，LLM、TTS 和数据库等阻塞工作交由虚拟线程池。
     * 取消任务会立即从队列移除，应用关闭后也不会继续执行已排期任务。

     * @return 配置为两个守护线程的调度器
     */
    private static ScheduledExecutorService createUtteranceMergeScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "voice-utterance-merge");
            // 设置为守护线程，确保在应用关闭时自动关闭
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    /**
     * 应用启动后预热开场问题音频缓存。
     * 在虚拟线程中异步执行，将所有开场问题模板预合成为 WAV 音频。
     */
    @PostConstruct
    void warmupOpeningAudioCache() {
        voicePipelineExecutor.execute(() -> {
            try {
                VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
                if (opening == null) {
                    return;
                }
                LinkedHashSet<String> allTemplates = new LinkedHashSet<>();
                if (opening.getSkillQuestions() != null) {
                    allTemplates.addAll(opening.getSkillQuestions().values());
                }
                allTemplates.add(opening.getAlgorithmQuestion());
                allTemplates.add(opening.getBackendQuestion());
                for (String template : allTemplates) {
                    preloadOpeningAudio(template);
                }
                log.info("Opening audio cache warmed: {} entries", openingAudioCache.size());
            } catch (Exception e) {
                log.warn("Opening audio cache warmup skipped: {}", e.getMessage());
            }
        });
    }

    /**
     * 将一条开场问题预合成为 WAV 并写入内存缓存。
     *
     * 仅由 {@link #warmupOpeningAudioCache()} 遍历模板时调用；TTS 返回空音频时跳过缓存，避免后续
     * 把失败结果当作命中。
     *
     * @param text 开场问题文本
     */
    private void preloadOpeningAudio(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        byte[] wavAudio = synthesizeToWav(text);
        if (wavAudio.length > 0) {
            openingAudioCache.put(text, wavAudio);
        }
    }

    /**
     * 初始化一条语音面试 WebSocket 实时链路。
     *
     * 由 Spring WebSocket 在握手成功后调用。方法从 URI 提取业务会话 ID，包装并发安全的发送会话，
     * 初始化内存状态和活动时间，随后启动 ASR、发送欢迎消息并异步触发首次开场问题。
     *
     * @param session WebSocket 会话
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        //从会话参数中提取会话 ID
        String sessionId = extractSessionId(session);

        // 设置消息大小限制（1秒 PCM 16kHz 16bit ≈ 32KB 原始 ≈ 42KB Base64，256KB 提供缓冲）
        session.setTextMessageSizeLimit(256 * 1024);
        session.setBinaryMessageSizeLimit(256 * 1024);

        // 创建并发安全的 WebSocket 会话装饰器(防止并发写入冲突)
        /**
         * 创建并发安全的 WebSocket 会话装饰器，防止并发写入冲突。
         * 限制每个会话的发送时间间隔和缓冲区大小，避免资源耗尽。
         *
         * @param session 原始 WebSocket 会话
         * @param sendTimeLimitMs 最大发送时间间隔（毫秒）
         * @param sendBufferLimitBytes 最大发送缓冲区大小（字节）
         * @return 并发安全的 WebSocket 会话装饰器
         */
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
            session, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT_BYTES
        );
        
        //保存会话到映射表缓存
        sessions.put(sessionId, safeSession);
        sessionStates.put(sessionId, new SessionState());
        lastActivityTime.put(sessionId, System.currentTimeMillis());
        log.info("WebSocket connection established for session: {}", sessionId);

        try {
            // 启动 ASR 语音识别
            startDashScopeStt(sessionId, safeSession);

            // 发送欢迎消息
            sendMessage(safeSession, createWelcomeMessage());
            // 自动开场：面试官先说开场语并直接提出第一个问题（仅首次连接、无历史消息时触发）
            triggerOpeningQuestionIfNeeded(sessionId, safeSession);
        } catch (Exception e) {
            log.error("Error establishing WebSocket connection for session {}", sessionId, e);
            sendError(safeSession, "初始化语音识别失败: " + e.getMessage());
        }
    }

    /**
     * 构造连接建立后发送给前端的欢迎控制消息。
     *
     * 由 {@link #afterConnectionEstablished(WebSocketSession)} 调用，统一通过 {@link #toJson(Object)}
     * 序列化。

     * @return 欢迎控制消息 JSON
     */
    private String createWelcomeMessage() {
        return toJson(Map.of(
            "type", "control",
            "action", "welcome",
            "message", "连接成功，准备开始语音面试",
            "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 将服务端消息对象序列化为 WebSocket JSON 负载。
     *
     * 所有字幕、文本、音频和控制消息的发送辅助方法共同调用；序列化失败时记录堆栈并返回空对象，
     * 避免异常逃逸到 WebSocket IO 线程。

     * @param obj 待序列化的 DTO 或 Map
     * @return JSON 字符串，失败时返回 {@code {}}
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Error serializing JSON", e);
            return "{}";
        }
    }

    /**
     * 通过并发安全的 WebSocket 会话发送一条文本负载。
     *
     * 被所有具体消息类型的发送方法调用。发送前检查连接状态，异常仅记录，避免一次前端断连中止
     * 正在运行的语音管线。

     * @param session 目标 WebSocket 会话
     * @param message 已序列化的文本负载
     */
    private void sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                //发送文本消息到会话给前端
                session.sendMessage(new TextMessage(message));
                log.debug("Message sent to session: {}", message.substring(0, Math.min(100, message.length())));
            } else {
                log.warn("Session is closed, cannot send message");
            }
        } catch (Exception e) {
            log.error("Error sending message to session", e);
        }
    }

    /**
     * 触发开场问题（仅首次连接、无历史消息时触发）。
     * 在虚拟线程中异步执行，先发送文本再推送音频。
     *
     * @param sessionId 会话 ID
     * @param session WebSocket 会话
     */
    private void triggerOpeningQuestionIfNeeded(String sessionId, WebSocketSession session) {
        // 通过虚拟线程池异步执行，不阻塞 afterConnectionEstablished 返回和 ASR 启动
        voicePipelineExecutor.execute(() -> {
            try {
                // 前置检查1：WebSocket 连接是否仍在
                if (session == null || !session.isOpen()) {
                    return;
                }

                // 前置检查2：查询对话历史，有历史消息说明是重连/恢复场景，不重复发开场问题
                // 避免 AI 面试官"再次自我介绍"的尴尬体验
                List<String> history = getHistory(sessionId);
                if (history != null && !history.isEmpty()) {
                    return;
                }

                // 前置检查3：从数据库获取会话实体，需要 skillId 来选择开场问题模板
                VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
                if (sessionEntity == null) {
                    log.warn("Session entity not found when sending opening question: {}", sessionId);
                    return;
                }

                // 根据 skillId 走三级模板选择：技能专属 → 算法/后端方向 → 硬编码兜底
                String aiReply = buildOpeningQuestion(sessionEntity);
                if (aiReply == null || aiReply.isBlank()) {
                    return;
                }

                // 再次确认连接状态（TTS 合成可能耗时，中途可能断开）
                if (!session.isOpen()) {
                    return;
                }

                // 文本先行：WebSocket 文本帧瞬间到达，前端立即可见开场问题
                sendTextMessage(session, aiReply);
                // 保存 开场问题消息到数据库（userText 为 null，标记为系统主动发言）
                saveMessage(sessionId, null, aiReply);

                // 音频随后：优先从启动预热缓存取，未命中则实时 TTS 合成
                // 音频有延迟（合成 + 传输 + 解码），先显示文本确保用户不会对着空白界面等待
                byte[] wavAudio = getOpeningWavAudio(aiReply);
                if (wavAudio.length > 0 && session.isOpen()) {
                    sendAudio(session, wavAudio, aiReply);
                }

                log.info("Opening question sent for session {}", sessionId);
            } catch (Exception e) {
                // 开场问题失败不向用户报错——"尽力而为"设计
                // 用户仍可主动说话，ASR 正常识别，LLM 正常回复
                log.error("Failed to send opening question for session {}", sessionId, e);
            }
        });
    }

    /**
     * 获取开场问题 WAV 音频（优先从缓存获取，缓存未命中则实时合成）
     *
     * @param text 开场问题文本
     * @return WAV 格式音频数据
     */
    private byte[] getOpeningWavAudio(String text) {
        byte[] cached = openingAudioCache.get(text);
        if (cached != null && cached.length > 0) {
            return cached;
        }
        byte[] wav = synthesizeToWav(text);
        if (wav.length > 0) {
            openingAudioCache.put(text, wav);
        }
        return wav;
    }

    /**
     * 调用 TTS 合成 PCM，并封装为浏览器可播放的 WAV。
     *
     * 开场缓存和非流式回复路径共同调用；下游依次调用
     * {@link QwenTtsService#synthesize(String)} 与 {@link #convertPcmToWav(byte[])}。
     *
     * @param text 要合成的文本
     * @return WAV 格式音频数据
     */
    private byte[] synthesizeToWav(String text) {
        //调用tts服务将文本转换为pcm格式
        byte[] pcm = ttsService.synthesize(text);
        if (pcm == null || pcm.length == 0) {
            return new byte[0];
        }
        return convertPcmToWav(pcm);
    }

    /**
     * 根据会话的技能方向构建开场问题。
     * 优先使用技能专属问题，其次按算法/后端方向选择默认模板。
     *
     * @param sessionEntity 会话实体
     * @return 开场问题文本
     */
    private String buildOpeningQuestion(VoiceInterviewSessionEntity sessionEntity) {
        String skillId = sessionEntity.getSkillId() != null ? sessionEntity.getSkillId() : "";
        VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
        Map<String, String> skillQuestions = opening != null ? opening.getSkillQuestions() : null;
        if (skillQuestions != null) {
            String bySkill = skillQuestions.get(skillId);
            if (bySkill != null && !bySkill.isBlank()) {
                return bySkill;
            }
        }
        List<String> algorithmSkills = opening != null && opening.getAlgorithmSkills() != null
            ? opening.getAlgorithmSkills()
            : List.of();

        if (algorithmSkills.contains(skillId)) {
            String configured = opening != null ? opening.getAlgorithmQuestion() : null;
            return configured != null && !configured.isBlank()
                ? configured
                : DEFAULT_OPENING_QUESTION_ALGORITHM;
        }
        String configured = opening != null ? opening.getBackendQuestion() : null;
        return configured != null && !configured.isBlank()
            ? configured
            : DEFAULT_OPENING_QUESTION_BACKEND;
    }

    /**
     * 处理前端发送的文本消息——所有 WebSocket 文本帧的统一入口。
     * "文本"（ TextMessage ）指的是 WebSocket 协议层面上的消息帧类型
     *  ——文本帧（Text Frame），而不是"文字内容"，这里指语音消息
     * <p>
     * 此方法是 Handler 的核心消息分发器，所有从 WebSocket 传入的文本帧都经过此方法。
     * 根据消息的 {@code type} 字段分两条路径处理：
     * <ul>
     *   <li>{@code "audio"}：前端 Base64 编码的 PCM 音频数据 → 委托 {@link #handleUserAudio} 送入 ASR 识别</li>
     *   <li>{@code "control"}：前端控制指令 → 反序列化为 {@link WebSocketControlMessage} 后委托
     *       {@link #handleControl} 分发（submit 触发 LLM、end_interview 结束面试、start_phase 切换阶段）</li>
     * </ul>
     * <p>
     * 无论消息类型，每次消息到达时都会更新 {@code lastActivityTime} 时间戳，
     * 供 {@link #checkPauseTimeout()} 定时任务检测 5 分钟无活动自动暂停。
     * <p>
     * 超过 200KB 的消息会额外输出 warning 日志，用于排查音频采集参数异常
     * （正常情况下一段音频约 50KB，持续大消息说明前端采样率或编码参数配置不当）。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 从 WebSocket URI 提取面试会话 ID（路径格式：/ws/voice-interview/{sessionId}）
        String sessionId = extractSessionId(session);

        // 记录消息大小（KB），用于性能监控和异常排查
        int messageSize = message.getPayload().length();
        int messageSizeKB = messageSize / 1024;
        log.info("[WebSocket] Received message: sessionId={}, size={}KB ({} bytes), limit={}KB",
                sessionId, messageSizeKB, messageSize, session.getTextMessageSizeLimit() / 1024);

        // 大消息预警：正常 1-2 秒 PCM 音频 ≈ 32-64KB Base64 编码后
        // 超过 200KB 说明可能前端音频采集参数不正确，应在开发阶段发现并修正
        if (messageSizeKB > 200) {
            log.warn("[WebSocket] Large message detected: {}KB", messageSizeKB);
        }

        try {
            // 解析 JSON 消息体，提取 type 字段用于分发
            JsonNode msg = objectMapper.readTree(message.getPayload());
            String type = msg.get("type").asText();

            // 更新最后活动时间——每次消息到达都重置超时计时器
            // checkPauseTimeout() 定时任务（每 30 秒）检查此时间戳，
            // 超过 5 分钟无任何消息自动暂停会话
            lastActivityTime.put(sessionId, System.currentTimeMillis());

            switch (type) {
                // 音频消息：前端通过 WebSocket 推送的实时麦克风音频数据
                // 数据为 Base64 编码的 PCM 格式（16kHz 采样率、16bit、单声道）
                case "audio":
                    String audioData = msg.has("data") ? msg.get("data").asText() : null;
                    if (audioData != null && !audioData.isEmpty()) {
                        // 委托给音频处理方法：Base64 解码 → 回声冷却检查 →
                        // ASR 发送（含断线重连重试逻辑）
                        handleUserAudio(sessionId, audioData);
                    } else {
                        log.warn("Received audio message without data");
                    }
                    break;

                // 控制消息：前端的操作指令
                // 支持三种操作：submit（提交答案触发 LLM）、
                // end_interview（结束面试）、start_phase（切换面试阶段）
                case "control":
                    try {
                        // 将 JSON 反序列化为 WebSocketControlMessage 对象后分发处理
                        handleControl(sessionId,
                            objectMapper.treeToValue(msg, WebSocketControlMessage.class));
                    } catch (Exception e) {
                        log.error("Error handling control message for session {}", sessionId, e);
                        sendError(session, "控制消息处理失败: " + e.getMessage());
                    }
                    break;

                // 未识别的消息类型——记录日志但不断开会话
                default:
                    log.warn("Unknown message type: {} for session {}", type, sessionId);
            }

        } catch (Exception e) {
            // JSON 解析失败或消息处理过程中发生未预期的异常
            // 记录完整错误日志（含堆栈），并向前端推送错误提示
            log.error("Error handling message for session {}", sessionId, e);
            sendError(session, "消息处理失败: " + e.getMessage());
        }
    }

    /**
     * 在 Spring 销毁 Handler 时停止虚拟线程池和合并调度器。
     *
     * 由 {@link DisposableBean} 生命周期自动调用；先中断尚未完成的语音管线，再最多等待五秒，
     * 防止应用关闭时残留后台任务。
     */
    @Override
    public void destroy() {
        voicePipelineExecutor.shutdownNow();
        utteranceMergeScheduler.shutdownNow();
        try {
            if (!voicePipelineExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("voicePipelineExecutor did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 清理断开的 WebSocket 及其 ASR、内存和数据库状态。
     *
     * 由 Spring WebSocket 在正常关闭或异常断开后调用。方法中断当前 LLM/TTS 虚拟线程、停止 ASR、
     * 移除内存状态，并调用会话服务兜底结束仍处于进行中的会话。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = extractSessionId(session);
        try {
            sessions.remove(sessionId);
            SessionState removedState = sessionStates.remove(sessionId);
            if (removedState != null) {
                Thread t = removedState.getProcessingThread();
                if (t != null) {
                    t.interrupt();
                }
            }
            lastActivityTime.remove(sessionId);

            // 停止 ASR 语音识别
            sttService.stopTranscription(sessionId);
            log.info("WebSocket connection closed for session: {}, status: {}", sessionId, status);

            // WebSocket 异常断开时自动结束会话，防止状态永远停留在 IN_PROGRESS
            try {
                interviewService.endSessionIfInProgress(sessionId);
            } catch (Exception endEx) {
                log.warn("Failed to auto-end session {} after disconnect: {}", sessionId, endEx.getMessage());
            }
        } catch (Exception e) {
            log.error("Error cleaning up session {} after close", sessionId, e);
        }
    }

    /**
     * 记录底层 WebSocket 传输错误。
     *
     * 由 Spring WebSocket 调用；实际资源回收统一留给随后触发的
     * {@link #afterConnectionClosed(WebSocketSession, CloseStatus)}。
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}", extractSessionId(session), exception);
    }

    /**
     * 判断一次 ASR 发送失败是否可通过重建连接恢复。
     *
     * 仅由 {@link #handleUserAudio(String, String)} 的异常分支调用，认证或参数类错误不会重试。
     *
     * @param ex ASR 发送抛出的状态异常
     * @return 无活跃会话或追加音频失败时返回 {@code true}
     */
    private static boolean shouldRecoverAsrConnection(IllegalStateException ex) {
        String m = ex.getMessage();
        if (m == null) {
            return false;
        }  
        return m.contains("No active session") || m.contains("ASR append failed");
    }

    /**
     * 启动 DashScope ASR 语音识别，设置最终结果、部分结果和错误回调
     *
     * @param sessionId 会话 ID
     * @param session WebSocket 会话
     */
    private void startDashScopeStt(String sessionId, WebSocketSession session) {
        sttService.startTranscription(
                sessionId,
                text -> handleSttResult(sessionId, text, true),
                text -> handleSttResult(sessionId, text, false),
                error -> {
                    log.error("STT error for session {}", sessionId, error);
                    sendError(session, "语音识别失败: " + error.getMessage());
                }
        );
    }

    /**
     * 为仍然打开的前端会话重建 DashScope ASR 连接。
     *
     * 由 {@link #handleUserAudio(String, String)} 检测到可恢复发送异常后调用；重新注册的 final、
     * partial 和 error 回调与首次启动保持一致。

     * @param sessionId 业务会话 ID
     */
    private void restartDashScopeStt(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        sttService.restartTranscription(
                sessionId,
                text -> handleSttResult(sessionId, text, true),
                text -> handleSttResult(sessionId, text, false),
                error -> {
                    log.error("STT error for session {}", sessionId, error);
                    sendError(session, "语音识别失败: " + error.getMessage());
                }
        );
    }

    /**
     * 处理用户音频消息——语音面试核心管线的入口。
     * <p>
     * 将从 WebSocket 接收到的 Base64 编码 PCM 音频数据解码后发送到 DashScope ASR 进行实时语音识别。
     * 方法内部分三个阶段执行：
     * <ol>
     *   <li><b>AI 回声冷却检查</b>：AI 的 TTS 音频正在播放（{@code aiSpeaking=true}）
     *       或播放结束 800ms 冷却期内，直接丢弃麦克风输入，防止 AI 音频经扬声器→麦克风→ASR→LLM→TTS
     *       形成无限回声循环。</li>
     *   <li><b>Base64 解码</b>：将前端编码的音频字符串还原为 PCM 字节数组（16kHz、16bit、单声道）。</li>
     *   <li><b>ASR 发送与断线重连</b>：调用 {@link QwenAsrService#sendAudio} 发送到 DashScope，
     *       若 ASR WebSocket 连接已断开则触发重连并重试发送（最多 15 次，每次 80ms 间隔，总计约 1.2 秒窗口）。</li>
     * </ol>
     *
     * @param sessionId   面试会话 ID
     * @param base64Audio 前端通过 WebSocket 推送的 Base64 编码 PCM 音频数据
     */
    private void handleUserAudio(String sessionId, String base64Audio) {
        // 从 sessions Map 获取 WebSocket 会话，用于后续发送错误消息
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        // ===== 阶段一：AI 回声冷却检查 =====
        // 若 AI 的 TTS 音频正在通过浏览器扬声器播放（aiSpeaking=true），
        // 或刚播放完还在 800ms 冷却期内（aiSpeakEndAt 未过期），
        // 丢弃当前麦克风输入。
        //
        // 原因：TTS 音频 → 扬声器播放 → 同一设备麦克风拾取 → ASR 识别成文本 →
        // LLM 生成回复 → 再 TTS 播放 → 无限循环
        SessionState state = sessionStates.get(sessionId);
        if (state != null && state.isAiSpeakingOrCooldown()) {
            // 静默丢弃，不做任何处理——这是正常行为，不是异常
            return;
        }

        try {
            // ===== 阶段二：Base64 解码 =====
            // 前端将原始 PCM 字节数组 Base64 编码后通过 JSON 传输
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            log.debug("Received audio data for session {}, size: {} bytes",
                sessionId, audioData.length);

            try {
                // ===== 阶段三：发送到 DashScope ASR =====
                // sendAudio 内部会再次 Base64 编码后通过 OmniRealtimeConversation 发送
                sttService.sendAudio(sessionId, audioData);

            } catch (IllegalStateException ex) {
                // ASR 到 DashScope 的 WebSocket 连接已被服务端关闭
                // 先判断异常是否可恢复——仅 "No active session" 或 "ASR append failed" 触发重连
                if (shouldRecoverAsrConnection(ex)) {
                    log.warn("[Session: {}] ASR send failed ({}), restarting DashScope and retrying chunk",
                            sessionId, ex.getMessage() != null ? ex.getMessage() : "unknown");

                    // 3.1 停止旧连接 + 等待 200ms + 启动新连接
                    restartDashScopeStt(sessionId);

                    // 3.2 重试发送当前这段音频数据
                    // 最多 15 次，每次间隔 80ms，总计约 1.2 秒重试窗口
                    // 覆盖 ASR WebSocket 重新握手 + 配置下发的典型耗时
                    boolean sent = false;
                    for (int i = 0; i < 15; i++) {
                        try {
                            Thread.sleep(80);
                            sttService.sendAudio(sessionId, audioData);
                            sent = true;
                            break;  // 发送成功，退出重试循环
                        } catch (IllegalStateException retry) {
                            // 重试期间如果异常变成不可恢复类型（如认证失败），立即停止重试
                            if (!shouldRecoverAsrConnection(retry)) {
                                throw retry;
                            }
                            // 可恢复异常：继续下一次重试
                        }
                    }

                    // 15 次重试全部失败 → 通知用户刷新页面
                    if (!sent) {
                        log.error("[Session: {}] ASR still down after restart", sessionId);
                        sendError(session, "语音识别连接中断，请刷新页面后重试");
                    }
                } else {
                    // 不可恢复异常（认证失败、参数错误等）→ 直接向上抛出
                    throw ex;
                }
            }

        } catch (Exception e) {
            // 解码失败、ASR 不可恢复异常、或其他未预期的错误
            log.error("Error handling user audio for session {}", sessionId, e);
            // getErrorMessage 将阿里云特定错误（403/ACCESS_DENIED/timeout 等）
            // 转换为用户可读的中文提示
            String errorMessage = getErrorMessage(e);
            sendError(session, errorMessage);
        }
    }

    /**
     * 处理 ASR 语音识别结果。
     * 部分结果（isFinalSegment=false）直接推送实时字幕；
     * 最终结果（isFinalSegment=true）追加到合并缓冲区，等待用户提交后统一触发 LLM。
     *
     * @param sessionId 会话 ID
     * @param recognizedText 识别文本
     * @param isFinalSegment 是否为最终定稿片段
     */
    private void handleSttResult(String sessionId, String recognizedText, boolean isFinalSegment) {
        // 从内存 Map 中获取该会话的 WebSocket 连接和运行时状态
        WebSocketSession session = sessions.get(sessionId);
        SessionState state = sessionStates.get(sessionId);

        // 会话已断开或状态已被清理（如暂停超时），丢弃本次识别结果
        if (session == null || state == null) {
            log.warn("Session or state not found: {}", sessionId);
            return;
        }

        // 非最终结果（partial/delta）：用户还在说话，文本随时可能被修正
        // 只更新 STT 活动时间（用于暂停超时检测）并推送实时字幕，不参与 LLM 对话
        if (!isFinalSegment) {
            state.markSttActivity();
            // 推送实时字幕给前端
            sendSubtitle(session, recognizedText, false);
            return;
        }

        // 以下是最终结果（completed）的处理
        log.debug("STT final segment for session {}: {}", sessionId, recognizedText);
        // 统计最终结果接收次数
        incrementCounter("app.voice.interview.asr.final_segments", "status", "received");

        // ASR 的 VAD 可能在用户一句话中间就触发了（比如停顿一下），
        // 导致一句话被切成多个 completed 片段。这里把每段拼进 mergeBuffer 累积，
        // 等用户点"提交"按钮时一次性取出全部文本送给 LLM，而不是每段分别触发 LLM
        // 这样可以避免 LLM 被频繁触发，提高效率
        state.appendFinalSttSegment(recognizedText);
        // 推送拼接后的完整文本给前端做字幕展示
        sendSubtitle(session, state.getMergeBufferPreview(), false);
    }

    /**
     * 手动提交：取出 mergeBuffer 中累积的用户 ASR 识别文本并触发 LLM+TTS 管线。
     * <p>
     * 此方法在用户点击前端"提交"按钮时由 {@link #handleControl} 调用。
     * 内部执行以下步骤：
     * <ol>
     *   <li><b>CAS 并发锁</b>：使用 {@code AtomicBoolean.compareAndSet} 防止并发触发多个 LLM 管线，
     *       并发提交时排期 400ms 后重试而非丢弃请求。</li>
     *   <li><b>取出合并文本</b>：原子地取出 mergeBuffer 的全部累积文本并清空缓冲区。</li>
     *   <li><b>提交到虚拟线程池</b>：阻塞的 LLM+TTS 操作在虚拟线程中执行，
     *       不阻塞 WebSocket IO 线程，同时保存线程引用供断连时中断。</li>
     *   <li><b>finally 保证锁释放</b>：无论成功或异常，finally 块中必释放 CAS 锁和线程引用，
     *       防止会话陷入永久锁定状态。</li>
     * </ol>
     *
     * @param sessionId 面试会话 ID
     */
    private void flushMergedUtteranceToLlm(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        SessionState state = sessionStates.get(sessionId);
        // 会话已关闭或状态已清理 → 直接返回
        if (session == null || state == null || !session.isOpen()) {
            return;
        }

        // ===== 步骤1：CAS 并发锁 =====
        // compareAndSet(false, true)：如果当前值是 false，设为 true 并返回 true（获取锁成功）
        // 如果当前已经是 true（已有 LLM 在执行），返回 false（获取锁失败）
        //
        // 获取锁失败时不丢弃请求，而是排期 400ms 后重试——
        // 前一次 LLM 完成后会释放锁，排期的回调会被调度执行
        if (!state.isProcessing().compareAndSet(false, true)) {
            utteranceMergeScheduler.schedule(
                    () -> flushMergedUtteranceToLlm(sessionId),
                    400,
                    TimeUnit.MILLISECONDS);
            return;
        }

        // ===== 步骤2：取出合并缓冲区中的全部累积文本 =====
        // takeMergeBufferAndClear() 内部使用 getAndSet("") 原子操作，
        // 保证取出和清空在一步完成，不会丢失并发追加的文本
        long mergeStartAt = state.getMergeStartedAt();
        String userText = state.takeMergeBufferAndClear();

        // 文本为空 → 释放锁后直接返回
        if (userText == null || userText.trim().isEmpty()) {
            state.isProcessing().set(false);
            return;
        }

        // 记录合并等待耗时（从第一段 ASR 定稿到用户点击提交的时间差）
        long mergeWaitMs = Math.max(0, System.currentTimeMillis() - mergeStartAt);
        recordTimerMillis("app.voice.interview.asr.merge_wait", mergeWaitMs, "status", "success");

        // 保存累积文本到 State，供后续 triggerLlmResponse 使用
        state.setAccumulatedText(userText);
        log.info("Merged user utterance for session {}, triggering LLM (length {})",
            sessionId, userText.length());

        // ===== 步骤3：提交到虚拟线程池执行阻塞的 LLM+TTS 管线 =====
        // 关键设计：LLM API 调用和 TTS 合成都是阻塞 I/O 操作，可能耗时数秒到数十秒。
        // 如果在当前线程（WebSocket IO 线程）中直接执行，会阻塞其他会话的消息处理。
        // 提交到虚拟线程池后当前方法立即返回，IO 线程可继续处理其他消息。
        voicePipelineExecutor.execute(() -> {
            // 保存当前虚拟线程的引用——WebSocket 异常断开时，
            // afterConnectionClosed() 会取出此引用并调用 interrupt() 中断管线
            state.setProcessingThread(Thread.currentThread());
            try {
                // 触发 LLM 回复
                triggerLlmResponse(sessionId, session, state);
            } finally {
                // ===== 步骤4：finally 保证锁必释放 =====
                // 无论 triggerLlmResponse 成功返回、抛 BusinessException、还是任何未预期异常，
                // 这两个状态必须恢复——否则会话将永久锁定：
                // - isProcessing 永不释放 → 后续提交永远排期但永远不执行
                // - processingThread 残留 → 断连时可能对已结束的线程调用 interrupt（无害但多余）
                state.isProcessing().set(false);
                state.setProcessingThread(null);
            }
        });
    }



    /**
     * 触发 LLM 回复。
     * 启用流式模式时，使用句子级并发 TTS：每检测到一个完整句子就启动 TTS 合成，
     * TTS 与 LLM 生成并行执行，降低首音频延迟。
     * 禁用流式模式时，等待 LLM 完整回复后再进行 TTS 合成。
     *
     * @param sessionId 会话 ID
     * @param session WebSocket 会话
     * @param state 会话状态
     */
    private void triggerLlmResponse(String sessionId, WebSocketSession session, SessionState state) {
        // 记录 LLM 回复开始时间
        long turnStartNanos = System.nanoTime();
        // 标记会话为正在处理 LLM 回复
        state.aiSpeaking.set(true);
        try {
            // 检查会话是否已关闭
            if (!session.isOpen()) {
                log.warn("WebSocket session is closed, skipping LLM response for session {}", sessionId);
                return;
            }

            // 从会话状态中获取用户文本
            String userText = state.getAccumulatedText();
            if (userText == null || userText.trim().isEmpty()) {
                log.warn("Empty user text, skipping LLM response");
                return;
            }

            log.info("Getting LLM response for session {}, text: {}", sessionId, userText);

            // 从数据库中获取会话实体
            VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
            if (sessionEntity == null) {
                log.error("Session entity not found for session {}, cannot generate LLM response", sessionId);
                sendError(session, "会话不存在，请重新开始面试");
                return;
            }

            // 从数据库中获取会话历史记录
            List<String> conversationHistory = getHistory(sessionId);

            // 记录 LLM 回复开始时间
            long llmStartNanos = System.nanoTime();
            // 记录 LLM 回复第一个令牌时间
            AtomicLong firstTokenAtNanos = new AtomicLong(0);
            // 检查是否启用流式模式
            boolean streamEnabled = voiceInterviewProperties.isLlmStreamingEnabled();

            String aiReply;

            if (streamEnabled) {
                // 句子级并发 TTS：LLM 流式输出期间每检测到一个完整句子就启动 TTS
                Semaphore ttsSemaphore = new Semaphore(
                    Math.max(1, voiceInterviewProperties.getMaxConcurrentTtsPerSession()));
                List<CompletableFuture<byte[]>> ttsFutures = new ArrayList<>();
                
                // 调用 LLM 生成流式回复
                // 并在每个完整句子生成后启动 TTS 合成
                aiReply = llmService.chatStreamSentences(
                    userText,
                    partialText -> {
                        if (partialText == null || partialText.isBlank() || !session.isOpen()) {
                            return;
                        }
                        if (firstTokenAtNanos.compareAndSet(0L, System.nanoTime())) {
                            recordTimerSinceNanos(
                                "app.voice.interview.llm.first_token_latency",
                                llmStartNanos,
                                "status", "success"
                            );
                        }
                        sendTextMessage(session, partialText);
                    },
                    sentence -> {
                        if (sentence == null || sentence.isBlank()) {
                            return;
                        }
                        // 确保并发 TTS 数量不超过最大限制，信号量机制控制
                        // 避免 TTS 生成过快导致资源耗尽
                        // 例如：如果 maxConcurrentTtsPerSession = 3，且当前有 2 个 TTS 任务在运行，
                        // 则会阻塞当前句子的 TTS 任务，直到有 TTS 任务完成
                        ttsSemaphore.acquireUninterruptibly();
                        // 提交 TTS 任务到线程池
                        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                            try {
                                return ttsService.synthesize(sentence);
                            } finally {
                                ttsSemaphore.release();
                            }
                        }, voicePipelineExecutor);
                        // 收集 TTS 任务
                        // 用于后续按顺序收集 TTS 结果
                        ttsFutures.add(future);
                    },
                    sessionEntity,
                    conversationHistory
                );

                // 记录 LLM 回复持续时间
                recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                // 记录 LLM 回复调用次数
                incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "true");
                log.info("LLM response for session {}: '{}'", sessionId, aiReply);

                if (!session.isOpen()) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId);
                    return;
                }

                // 发送标题
                sendSubtitle(session, userText, true);
                // 发送回复
                sendTextMessage(session, aiReply);
                // 保存消息到数据库
                saveMessage(sessionId, userText, aiReply);

                // 按顺序收集所有 TTS 结果
                if (!ttsFutures.isEmpty()) {
                    // 记录 TTS 开始时间
                    long ttsStartNanos = System.nanoTime();
                    // 检查是否启用分块推送模式
                    boolean chunkedEnabled = voiceInterviewProperties.isChunkedAudioEnabled();

                    if (chunkedEnabled) {
                        // 分块推送模式：每句 TTS 完成后立即推送
                        for (int i = 0; i < ttsFutures.size(); i++) {
                            try {
                                byte[] pcm = ttsFutures.get(i).get();
                                if (pcm != null && pcm.length > 0 && session.isOpen()) {
                                    byte[] wavAudio = convertPcmToWav(pcm);
                                    sendAudioChunk(session, wavAudio, i, i == ttsFutures.size() - 1);
                                }
                            } catch (Exception e) {
                                log.warn("[Session: {}] TTS chunk {} failed: {}", sessionId, i, e.getMessage());
                            }
                        }
                        recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");
                    } else {
                        // 合并模式：收集所有 PCM 后合并为一个完整音频
                        List<byte[]> pcmChunks = new ArrayList<>();
                        int totalSize = 0;
                        for (CompletableFuture<byte[]> f : ttsFutures) {
                            try {
                                byte[] pcm = f.get();
                                if (pcm != null && pcm.length > 0) {
                                    pcmChunks.add(pcm);
                                    totalSize += pcm.length;
                                }
                            } catch (Exception e) {
                                log.warn("[Session: {}] TTS future failed for one sentence: {}", sessionId, e.getMessage());
                            }
                        }
                        recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                        if (!session.isOpen()) {
                            log.warn("WebSocket closed during TTS processing, discarding audio for session {}", sessionId);
                            return;
                        }

                        if (totalSize > 0) {
                            byte[] mergedPcm = new byte[totalSize];
                            int offset = 0;
                            for (byte[] chunk : pcmChunks) {
                                System.arraycopy(chunk, 0, mergedPcm, offset, chunk.length);
                                offset += chunk.length;
                            }

                            byte[] wavAudio = convertPcmToWav(mergedPcm);
                            log.info("[Session: {}] Sending merged audio - {} sentences, WAV size: {} bytes",
                                sessionId, pcmChunks.size(), wavAudio.length);
                                // 发送合并后的音频
                            sendAudio(session, wavAudio, aiReply);
                        } else {
                            log.error("[Session: {}] All TTS calls returned empty audio", sessionId);
                            incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                        }
                    }
                }
            } else {
                
                aiReply = llmService.chat(userText, sessionEntity, conversationHistory);
                recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "false");
                log.info("LLM response for session {}: '{}'", sessionId, aiReply);

                if (!session.isOpen()) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId);
                    return;
                }

                sendSubtitle(session, userText, true);
                sendTextMessage(session, aiReply);
                saveMessage(sessionId, userText, aiReply);

                long ttsStartNanos = System.nanoTime();
                log.info("[Session: {}] Starting TTS synthesis for text (length: {})",
                    sessionId, aiReply.length());
                byte[] aiAudio = ttsService.synthesize(aiReply);
                recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                if (!session.isOpen()) {
                    return;
                }

                if (aiAudio == null || aiAudio.length == 0) {
                    log.error("[Session: {}] TTS returned empty audio", sessionId);
                    incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                } else {
                    byte[] wavAudio = convertPcmToWav(aiAudio);
                    sendAudio(session, wavAudio, aiReply);
                }
            }

            state.setAccumulatedText("");
            // AI 说话结束，设置冷却期后解除封锁
            state.aiSpeaking.set(false);
            state.aiSpeakEndAt.set(System.currentTimeMillis() + AI_SPEAK_COOLDOWN_MS);
            recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "success");
            incrementCounter("app.voice.interview.turn.completed", "status", "success");

        } catch (Exception e) {
            log.error("Error triggering LLM response for session {}", sessionId, e);
            state.aiSpeaking.set(false);
            recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "failure");
            incrementCounter("app.voice.interview.turn.completed", "status", "failure");
            incrementCounter("app.voice.interview.errors", "stage", "turn");
            if (session.isOpen()) {
                sendError(session, "AI响应失败: " + e.getMessage());
            }
        }
    }

    /**
     * 将 ASR/TTS 管线异常转换为前端可理解的提示。
     *
     * 由音频入口的兜底异常分支调用，优先识别阿里云认证和连接超时，其余错误返回统一语音处理失败
     * 文本，避免将完整堆栈发送给客户端。

     * @param e 语音处理异常
     * @return 可直接发送给前端的中文提示
     */
    private String getErrorMessage(Exception e) {
        Throwable cause = e.getCause();

        // 检查阿里云特定错误
        if (cause != null) {
            String message = cause.getMessage();
            if (message != null) {
                if (message.contains("403") || message.contains("ACCESS_DENIED")) {
                    return "阿里云语音服务认证失败：AccessKey 无效或已过期。请在 .env 文件中配置正确的 ALIYUN_ACCESS_KEY";
                }
                if (message.contains("timeout") || message.contains("channel inactive")) {
                    return "阿里云语音服务连接超时。请检查网络连接或稍后重试";
                }
            }
        }

        // 默认错误消息
        return "语音处理失败：" + e.getMessage();
    }

    /**
     * 分发前端通过 WebSocket 发送的会话控制动作。
     *
     * 由 {@link #handleTextMessage(WebSocketSession, TextMessage)} 的 control 分支调用。submit 会把
     * 可选文本并入 ASR 缓冲并触发 LLM/TTS；end_interview 和 start_phase 分别委托会话服务结束会话
     * 或更新阶段。

     * @param sessionId 业务会话 ID
     * @param control 已反序列化的控制消息
     */
    private void handleControl(String sessionId, WebSocketControlMessage control) {
        log.info("Control message for session {}: action={}, phase={}",
                sessionId, control.getAction(), control.getPhase());

        switch (control.getAction()) {
            case "submit":
                // 文本提交：前端可能通过 data.text 传入用户输入的文本（非语音识别）
                if (control.getData() != null) {
                    Object textObj = control.getData().get("text");
                    if (textObj instanceof String text && !text.isBlank()) {
                        SessionState state = sessionStates.get(sessionId);
                        if (state != null) {
                            state.appendFinalSttSegment(text);
                        }
                    }
                }
                //拿着用户语音转写结果发给llm
                flushMergedUtteranceToLlm(sessionId);
                break;
            case "end_interview":
                interviewService.endSession(sessionId);
                break;
            case "start_phase":
                interviewService.startPhase(sessionId, control.getPhase());
                break;
        }
    }

    /**
     * 发送字幕消息到前端
     *
     * @param session WebSocket 会话
     * @param text 字幕文本
     * @param isFinal 是否为最终定稿
     */
    private void sendSubtitle(WebSocketSession session, String text, boolean isFinal) {
        WebSocketSubtitleMessage subtitle = WebSocketSubtitleMessage.builder()
                .type("subtitle")
                .text(text)
                .isFinal(isFinal)
                .build();
        sendMessage(session, toJson(subtitle));
    }

    /**
     * 将完整 WAV 和对应文本编码为 audio 消息发送给前端。
     *
     * 开场问题与非流式 TTS 路径调用；音频先转 Base64，再委托 {@link #sendMessage} 发送。

     * @param session 目标 WebSocket 会话
     * @param audio WAV 音频字节
     * @param text 音频对应的面试官文本
     */
    private void sendAudio(WebSocketSession session, byte[] audio, String text) {
        if (!session.isOpen()) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(audio);
        log.info("Sending audio to frontend - WAV size: {} bytes, Base64 length: {}",
                audio.length, base64Audio.length());
        sendMessage(session, toJson(Map.of(
                "type", "audio",
                "data", base64Audio,
                "text", text
        )));
    }

    /**
     * 发送一条面试官文本消息。
     *
     * 开场和 LLM 最终回复路径调用，供前端在音频到达前先展示内容。

     * @param session 目标 WebSocket 会话
     * @param text 面试官回复文本
     */
    private void sendTextMessage(WebSocketSession session, String text) {
        sendMessage(session, toJson(Map.of("type", "text", "content", text)));
    }

    /**
     * 将管线错误封装为统一 error 消息发送给前端。
     *
     * ASR、LLM、初始化和控制消息异常分支共同调用。

     * @param session 目标 WebSocket 会话
     * @param error 用户可读错误文本
     */
    private void sendError(WebSocketSession session, String error) {
        sendMessage(session, toJson(Map.of("type", "error", "message", error)));
    }

    /**
     * 发送分块音频到前端（流式 TTS 模式下每句完成后立即推送）
     *
     * @param session WebSocket 会话
     * @param wavAudio WAV 格式音频数据
     * @param index 分块索引
     * @param isLast 是否为最后一个分块
     */
    private void sendAudioChunk(WebSocketSession session, byte[] wavAudio, int index, boolean isLast) {
        if (!session.isOpen()) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(wavAudio);
        sendMessage(session, toJson(Map.of(
                "type", "audio_chunk",
                "data", base64Audio,
                "index", index,
                "isLast", isLast
        )));
        log.debug("[Session] Sent audio chunk index={}, isLast={}, size={} bytes", index, isLast, wavAudio.length);
    }

    /**
     * 记录从起始纳秒到当前时刻的管线耗时。
     *
     * LLM、TTS 和整轮处理路径调用，实际指标写入委托给 {@link VoiceMetricsRecorder}。

     * @param metricName 指标名称
     * @param startNanos {@link System#nanoTime()} 获取的起点
     * @param tags 偶数个标签键值
     */
    private void recordTimerSinceNanos(String metricName, long startNanos, String... tags) {
        voiceMetricsRecorder.recordTimerSinceNanos(metricName, startNanos, tags);
    }

    /**
     * 记录已经计算好的毫秒耗时。
     *
     * 当前用于 ASR 合并等待时间，实际指标写入委托给 {@link VoiceMetricsRecorder}。

     * @param metricName 指标名称
     * @param millis 耗时毫秒数
     * @param tags 偶数个标签键值
     */
    private void recordTimerMillis(String metricName, long millis, String... tags) {
        voiceMetricsRecorder.recordTimerMillis(metricName, millis, tags);
    }

    /**
     * 递增语音链路事件计数器。
     *
     * ASR 定稿、轮次完成和错误分支调用，统一委托 {@link VoiceMetricsRecorder}。

     * @param metricName 指标名称
     * @param tags 偶数个标签键值
     */
    private void incrementCounter(String metricName, String... tags) {
        voiceMetricsRecorder.incrementCounter(metricName, tags);
    }

    /**
     * 周期检查所有已连接会话的空闲时长。
     *
     * 由 Spring 每 30 秒调度。空闲超过 4 分 30 秒时发送一次警告，达到 5 分钟时调用
     * {@link #handlePauseTimeout(String)} 持久化暂停并关闭连接。
     */
    @Scheduled(fixedRate = 30000)
    public void checkPauseTimeout() {
        long now = System.currentTimeMillis();

        lastActivityTime.forEach((sessionId, lastTime) -> {
            long elapsed = now - lastTime;

            // 超过 4 分 30 秒发送警告
            if (elapsed > WARNING_TIME_MS && elapsed < PAUSE_TIMEOUT_MS) {
                sendPauseWarning(sessionId);
            }
            // 超过 5 分钟暂停会话
            else if (elapsed >= PAUSE_TIMEOUT_MS) {
                log.warn("Session {} inactive for {} minutes, pausing",
                    sessionId, PAUSE_TIMEOUT_MS / 60000);
                handlePauseTimeout(sessionId);
            }
        });
    }

    /**
     * 向仍在线的前端发送即将自动暂停的控制消息。
     *
     * 仅由 {@link #checkPauseTimeout()} 的预警窗口调用；连接已经关闭时静默跳过。

     * @param sessionId 业务会话 ID
     */
    private void sendPauseWarning(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            sendMessage(session, toJson(Map.of(
                "type", "control",
                "action", "pause_timeout_warning",
                "message", "会话将在30秒后暂停，请继续说话或点击继续",
                "timestamp", System.currentTimeMillis()
            )));
        }
    }

    /**
     * 将空闲超时会话持久化为暂停并释放实时资源。
     *
     * 由 {@link #checkPauseTimeout()} 调用。先通知前端，再通过会话服务更新数据库，最后关闭
     * WebSocket、停止 ASR 并清理内存映射；失败只记录日志，避免中断定时扫描其他会话。

     * @param sessionId 业务会话 ID
     */
    private void handlePauseTimeout(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);

        try {
            if (session != null && session.isOpen()) {
                sendMessage(session, toJson(Map.of(
                    "type", "control",
                    "action", "pause_timeout",
                    "message", "会话因超时已暂停,可在历史记录中恢复",
                    "timestamp", System.currentTimeMillis()
                )));
            }

            // 保存会话状态到数据库
            interviewService.pauseSession(sessionId, "timeout");

            // 关闭 WebSocket 连接
            if (session != null && session.isOpen()) {
                session.close(CloseStatus.GOING_AWAY);
            }

            // 清理 - 停止 ASR 会话防止资源泄漏
            sttService.stopTranscription(sessionId);
            sessions.remove(sessionId);
            sessionStates.remove(sessionId);
            lastActivityTime.remove(sessionId);

            log.info("Session {} paused due to timeout", sessionId);

        } catch (Exception e) {
            log.error("Error handling pause timeout for session {}", sessionId, e);
        }
    }

    /**
     * 从注册路径 {@code /ws/voice-interview/{sessionId}} 提取业务会话 ID。
     *
     * 连接建立、关闭、消息接收和错误回调共同调用。

     * @param session Spring WebSocket 会话
     * @return URI 最后一个路径段
     */
    private String extractSessionId(WebSocketSession session) {
        String path = session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * 加载有序消息并转换为 LLM 可读的角色对话历史。
     * 从数据库中查询会话历史消息
     *
     * 开场判重和每轮 LLM 提示词构建调用。方法委托会话服务查询实体，将用户和面试官文本拆成
     * 带角色前缀的字符串；查询失败时返回空列表，让实时链路继续运行。

     * @param sessionId 业务会话 ID
     * @return 按原始轮次排列的角色文本列表
     */
    private List<String> getHistory(String sessionId) {
        try {
            List<VoiceInterviewMessageEntity> messages = interviewService.getConversationHistory(sessionId);
            List<String> history = new ArrayList<>();

            for (VoiceInterviewMessageEntity msg : messages) {
                if (msg.getUserRecognizedText() != null && !msg.getUserRecognizedText().isEmpty()) {
                    history.add("候选人：" + msg.getUserRecognizedText());
                }
                if (msg.getAiGeneratedText() != null && !msg.getAiGeneratedText().isEmpty()) {
                    history.add("面试官：" + msg.getAiGeneratedText());
                }
            }

            log.debug("Loaded {} messages from history for session {}", history.size(), sessionId);
            return history;
        } catch (Exception e) {
            log.error("Error loading conversation history for session {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 解析字符串 ID 并通过会话服务读取会话实体。
     *
     * 开场问题和 LLM 管线调用；非法 ID 记录错误并返回 {@code null}，由调用方终止当前步骤。

     * @param sessionId 字符串形式的业务会话 ID
     * @return 会话实体，ID 非法或会话不存在时返回 {@code null}
     */
    private VoiceInterviewSessionEntity getSessionEntity(String sessionId) {
        try {
            Long sessionIdLong = Long.parseLong(sessionId);
            return interviewService.getSession(sessionIdLong);
        } catch (NumberFormatException e) {
            log.error("Invalid session ID format: {}", sessionId);
            return null;
        }
    }

    /**
     * 尽力保存一轮用户文本与 AI 回复。
     *
     * 开场问题和 LLM 完成路径调用，实际顺序号和阶段由会话服务填充；持久化失败只记录日志，
     * 不回滚已发送给前端的实时响应。

     * @param sessionId 业务会话 ID
     * @param userText ASR 用户文本，开场轮可为 {@code null}
     * @param aiText LLM 面试官回复
     */
    private void saveMessage(String sessionId, String userText, String aiText) {
        try {
            interviewService.saveMessage(sessionId, userText, aiText);
            log.debug("Message saved to database for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Error saving message for session {}", sessionId, e);
        }
    }

    /**
     * 将 PCM 音频数据转换为 WAV 格式。
     * 在 PCM 数据前添加 44 字节 WAV 头，便于浏览器播放。
     *
     * @param pcmData 原始 PCM 音频数据（24kHz, 16-bit, 单声道）
     * @return WAV 格式音频数据
     */
    private byte[] convertPcmToWav(byte[] pcmData) {
        // 使用 24000Hz 采样率（Qwen TTS Realtime API 输出）
        int sampleRate = 24000;
        int bitsPerSample = 16;
        int numChannels = 1;
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int fileSize = dataSize + 36;

        byte[] wavData = new byte[dataSize + 44];

        // 直接写入 WAV 头，避免流分配开销
        int pos = 0;

        // RIFF 头
        wavData[pos++] = 'R'; wavData[pos++] = 'I'; wavData[pos++] = 'F'; wavData[pos++] = 'F';
        writeIntLE(wavData, pos, fileSize); pos += 4;
        wavData[pos++] = 'W'; wavData[pos++] = 'A'; wavData[pos++] = 'V'; wavData[pos++] = 'E';

        // fmt 块
        wavData[pos++] = 'f'; wavData[pos++] = 'm'; wavData[pos++] = 't'; wavData[pos++] = ' ';
        writeIntLE(wavData, pos, 16); pos += 4; // 块大小
        writeShortLE(wavData, pos, (short) 1); pos += 2; // 音频格式（1 = PCM）
        writeShortLE(wavData, pos, (short) numChannels); pos += 2;
        writeIntLE(wavData, pos, sampleRate); pos += 4;
        writeIntLE(wavData, pos, byteRate); pos += 4;
        writeShortLE(wavData, pos, (short) blockAlign); pos += 2;
        writeShortLE(wavData, pos, (short) bitsPerSample); pos += 2;

        // data 块
        wavData[pos++] = 'd'; wavData[pos++] = 'a'; wavData[pos++] = 't'; wavData[pos++] = 'a';
        writeIntLE(wavData, pos, dataSize); pos += 4;

        // 复制 PCM 数据
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.length);

        return wavData;
    }

    /**
     * 将 32 位整数按小端序写入 WAV 头缓冲区。
     *
     * 仅由 {@link #convertPcmToWav(byte[])} 写 RIFF、采样率和数据长度字段时调用。

     * @param buf WAV 输出缓冲区
     * @param pos 写入起始位置
     * @param value 待写入整数
     */
    private static void writeIntLE(byte[] buf, int pos, int value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
        buf[pos + 2] = (byte) ((value >> 16) & 0xFF);
        buf[pos + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * 将 16 位短整数按小端序写入 WAV 头缓冲区。
     *
     * 仅由 {@link #convertPcmToWav(byte[])} 写声道数、位深和块对齐字段时调用。

     * @param buf WAV 输出缓冲区
     * @param pos 写入起始位置
     * @param value 待写入短整数
     */
    private static void writeShortLE(byte[] buf, int pos, short value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /**
     * 内部类，保存每个会话的运行时状态。
     * 包括用户累积文本、LLM 处理锁、AI 说话状态、STT 合并缓冲区等。
     */
    private static class SessionState {
        /** 累积的用户文本（当前轮次待送入 LLM 的文本） */
        private final AtomicReference<String> accumulatedText = new AtomicReference<>("");
        /** LLM 处理中标志，防止并发触发 */
        private final AtomicBoolean processing = new AtomicBoolean(false);
        /** AI 正在播放 TTS 音频，期间丢弃麦克风回声 */
        private final AtomicBoolean aiSpeaking = new AtomicBoolean(false);
        /** AI 音频播放结束后，额外等待这段时间再接受用户音频（ms），防止回声尾音 */
        private final AtomicLong aiSpeakEndAt = new AtomicLong(0);
        /** 多段 STT completed 拼接，防抖后再送 LLM */
        private final AtomicReference<String> mergeBuffer = new AtomicReference<>("");
        /** mergeBuffer 开始计时点，用于”最长等待补充”判定 */
        private final AtomicLong mergeStartedAt = new AtomicLong(0);
        /** 最近一次 STT 活动时间（partial/final） */
        private final AtomicLong lastSttActivityAt = new AtomicLong(System.currentTimeMillis());
        /** 当前正在执行 LLM+TTS 管线的虚拟线程，断连时可中断 */
        private volatile Thread processingThread = null;

        /** 追加 STT 最终定稿片段到合并缓冲区 */
        void appendFinalSttSegment(String segment) {
            String s = segment == null ? "" : segment.trim();
            if (s.isEmpty()) {
                return;
            }
            mergeBuffer.updateAndGet(prev -> {
                if (prev == null || prev.isEmpty()) {
                    mergeStartedAt.set(System.currentTimeMillis());
                    return s;
                }
                return prev + s;
            });
            markSttActivity();
        }

        /** 获取合并缓冲区预览文本（用于实时字幕） */
        String getMergeBufferPreview() {
            String s = mergeBuffer.get();
            return s == null ? "" : s;
        }

        /** 取出合并缓冲区内容并清空 */
        String takeMergeBufferAndClear() {
            mergeStartedAt.set(0);
            return mergeBuffer.getAndSet("");
        }

        /** 标记 STT 活动（更新最近活动时间） */
        void markSttActivity() {
            lastSttActivityAt.set(System.currentTimeMillis());
        }

        /** 获取合并开始时间（若未开始则返回当前时间） */
        long getMergeStartedAt() {
            long value = mergeStartedAt.get();
            return value > 0 ? value : System.currentTimeMillis();
        }

        /** 获取累积文本 */
        String getAccumulatedText() {
            return accumulatedText.get();
        }

        /** 设置累积文本 */
        void setAccumulatedText(String text) {
            accumulatedText.set(text);
        }

        /** 获取 LLM 处理中标志 */
        AtomicBoolean isProcessing() {
            return processing;
        }

        /** 设置当前处理线程（断连时可中断） */
        void setProcessingThread(Thread t) {
            this.processingThread = t;
        }

        /** 获取当前处理线程 */
        public Thread getProcessingThread() {
            return processingThread;
        }

        /** 判断 AI 是否正在说话或处于回声冷却期 */
        boolean isAiSpeakingOrCooldown() {
            if (aiSpeaking.get()) {
                return true;
            }
            // AI 播放结束后的冷却期（默认 800ms），防止扬声器尾音被录入
            return System.currentTimeMillis() < aiSpeakEndAt.get();
        }
    }
}
