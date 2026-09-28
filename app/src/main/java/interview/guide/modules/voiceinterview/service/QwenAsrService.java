/**
 * Qwen 实时语音识别（ASR）服务
 * 将用户说的语音转换成文字
 * 基于阿里云 DashScope qwen3-asr-flash-realtime 模型，通过 WebSocket 实现实时语音转文字。
 * 管理多会话并发识别，支持服务端 VAD 自动断句、部分识别结果实时推送、
 * 断线重连恢复及资源自动清理。每个面试会话对应一个独立的 ASR WebSocket 连接。
 *
 * 联动关系：
 * - 被 VoiceInterviewWebSocketHandler 依赖（启动/停止/重连 ASR、发送音频数据）
 * - 依赖 VoiceInterviewProperties（读取 qwen.asr 配置：模型、API Key、VAD 参数等）
 * - 使用 DashScope OmniRealtimeConversation SDK（WebSocket 通信）
 *
 * 核心方法：
 * - startTranscription()：启动识别会话，设置回调
 * - sendAudio()：发送音频数据到 ASR 服务
 * - stopTranscription()：停止识别并关闭会话
 * - handleServerEvent()：处理服务端事件（转写完成、实时字幕、错误等）
 *
 * 配置参数：
 * - model: qwen3-asr-flash-realtime
 * - audio format: PCM, 16kHz 采样率
 * - language: 中文 (zh)
 * - VAD: 启用服务端 VAD，400ms 静默时长自动断句
 */
package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Service
public class QwenAsrService {

    private String url;
    private String model;
    private String apiKey;
    private String language;
    private String format;
    private Integer sampleRate;
    private Boolean enableTurnDetection;
    private String turnDetectionType;
    private Float turnDetectionThreshold;
    private Integer turnDetectionSilenceDurationMs;

    /**
     * 创建 ASR 服务并复制当前 Qwen 识别配置。
     *
     * 由 Spring 构造 Bean；真正的 API Key 校验留到 {@link #init()}，便于测试通过 setter 替换配置。
     *
     * @param voiceInterviewProperties 集中的语音面试配置
     */
    public QwenAsrService(VoiceInterviewProperties voiceInterviewProperties) {
        applyAsrConfig(voiceInterviewProperties.getQwen().getAsr());
    }

    /**
     * 从最新属性对象重新加载后续连接使用的 ASR 配置。
     *
     * 由配置刷新逻辑或测试调用；只更新成员变量，不重建已经存在的 WebSocket 识别会话。
     *
     * @param voiceInterviewProperties 语音面试配置属性（包含 Qwen ASR 配置）
     */
    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        applyAsrConfig(voiceInterviewProperties.getQwen().getAsr());
        log.info("QwenAsrService reloaded: model={}, url={}", model, url);
    }

    /**
     * 将配置对象复制为建立 DashScope 会话时使用的运行参数。
     *
     * 构造和 {@link #reload(VoiceInterviewProperties)} 共用，集中维护模型、音频格式与 VAD 参数映射。
     *
     * @param asr ASR 配置对象，包含模型、API Key、VAD 参数等配置
     */
    private void applyAsrConfig(VoiceInterviewProperties.AsrConfig asr) {
        this.url = asr.getUrl();
        this.model = asr.getModel();
        this.apiKey = asr.getApiKey();
        this.language = asr.getLanguage();
        this.format = asr.getFormat();
        this.sampleRate = asr.getSampleRate();
        this.enableTurnDetection = asr.isEnableTurnDetection();
        this.turnDetectionType = asr.getTurnDetectionType();
        this.turnDetectionThreshold = asr.getTurnDetectionThreshold();
        this.turnDetectionSilenceDurationMs = asr.getTurnDetectionSilenceDurationMs();
    }

    /**
     * 活跃的 ASR 会话映射表。
     * Key: session ID（用户提供的标识符）
     * Value: AsrSession（包含 OmniRealtimeConversation 实例和回调）
     * 即存服务端与ASR之间的会话连接
     */
    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();

    /**
     * 防止同一 interview sessionId 上并发 stop/start；
     * 并在重连时与 sessionLocks 配合使用。
     * 即会话的生命周期锁，确保只有一个会话的websocket连接在运行
     */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    /**
     * 获取同一业务会话执行启动、停止和重连时共用的互斥锁。
     *
     * ASR 生命周期方法调用，使用 {@code computeIfAbsent} 保证同一 sessionId 只创建一个锁对象。

     * @param sessionId 业务会话 ID
     * @return 该会话专用锁
     */
    private Object lockForSession(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    /**
     * 在 Bean 初始化后验证建立 ASR 连接所需的 API Key。
     *
     * 由 Spring {@link PostConstruct} 生命周期调用；此时不连接 DashScope，每个业务会话会在
     * {@link #startTranscription} 中单独建立连接。
     *
     * @throws IllegalStateException 若 apiKey 未配置
     */
    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API key must be configured before initializing QwenAsrService");
        }
        log.info("QwenAsrService initialized with model: {}, url: {}", model, url);
    }

    /**
     * 启动新的转写会话。
     *
     * 创建到 DashScope ASR 服务的 WebSocket 连接，并设置回调处理转写结果和错误。
     * 会话使用服务端 VAD（语音活动检测）自动检测句子边界。
     * 当检测到语音并转写时，onFinal 回调将被调用并传入转写文本。
     *
     * @param sessionId 会话唯一标识符
     * @param onFinal 句子/片段最终确定时的回调（{@code completed} 事件）
     * @param onError 发生错误时的回调
     * @throws IllegalStateException 若会话已存在或服务未初始化
     */
    public void startTranscription(String sessionId, Consumer<String> onFinal, Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, null, onError);
    }

    /** 
     * 与 {@link #startTranscription(String, Consumer, Consumer)} 相同，
     * 但额外支持转发部分转写结果（{@code conversation.item.input_audio_transcription.text}）
     * 用于实时字幕。由 WebSocket Handler 首次建立实时链路时调用，方法持有会话锁后委托核心启动
     * 实现，防止同一会话并发启动和停止。
     *
     * @param sessionId 业务会话 ID
     * @param onFinal 句子或片段最终确定时的回调
     * @param onPartial 部分转写回调，若不需要实时字幕可传 null
     * @param onError 连接或识别错误回调
     */
    public void startTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        // 每个面试会话一个锁对象，防止并发启动/停止/重连同一会话的 ASR
        // 锁对象通过 sessionLocks ConcurrentHashMap 的 computeIfAbsent 惰性创建
        synchronized (lockForSession(sessionId)) {
            // 在锁保护下委托给核心实现方法
            startTranscriptionLocked(sessionId, onFinal, onPartial, onError);
        }
    }

    /**
     * 停止旧连接并重新建立（用于 ASR WebSocket 被服务端关闭后恢复识别）。
     * <p>
     * 场景：DashScope ASR 的 WebSocket 连接可能因网络波动、服务端主动关闭等原因断开，
     * 此时 {@code sendAudio()} 会抛出 IllegalStateException，上层 WebSocket Handler
     * 捕获后调用本方法重建 ASR 连接。
     * <p>
     * 流程：
     * <ol>
     *   <li>持锁后先调用 stopTranscription 清理旧连接的会话和资源</li>
     *   <li>等待 200ms 让旧连接完全释放</li>
     *   <li>调用 startTranscriptionLocked 创建新连接</li>
     *   <li>轮询验证重连成功（最多 10 次，每次间隔 100ms，总计约 1 秒超时）</li>
     * </ol>
     *
     * @param sessionId 业务会话 ID
     * @param onFinal 最终转写回调
     * @param onPartial 部分转写回调，可为 {@code null}
     * @param onError 连接或识别错误回调
     */
    public void restartTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        // 同一把锁保证 stop + start 的原子性，防止并发重连导致多个连接并存
        synchronized (lockForSession(sessionId)) {
            log.info("[Session: {}] Restarting DashScope ASR (stop + start)", sessionId);

            // 第一步：停止旧连接，清理 sessions Map 中的旧条目和 DashScope WebSocket
            stopTranscription(sessionId);

            // 第二步：等待 200ms 让服务端彻底释放旧连接资源（TCP 四次挥手 + DashScope 内部清理）
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                // 被中断时恢复中断标记，让上层代码感知中断状态
                Thread.currentThread().interrupt();
            }

            // 第三步：启动新的 ASR 连接（内部在新的守护线程中异步建立）
            startTranscriptionLocked(sessionId, onFinal, onPartial, onError);

            // 第四步：轮询验证重连是否成功建立
            // 因为 connect() 在新的守护线程中异步执行，可能尚未完成，
            // 这里轮询检查 sessions Map 中是否已出现新的 AsrSession 条目
            // 最多检查 10 次，每次间隔 100ms，总计最长等待约 1 秒
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Thread.sleep(100);
                    AsrSession newSession = sessions.get(sessionId);
                    // Conversation 对象非 null 说明新连接已创建并注册到 Map
                    if (newSession != null && newSession.getConversation() != null) {
                        log.info("[Session: {}] ASR reconnection verified successfully", sessionId);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[Session: {}] ASR reconnection verification interrupted", sessionId);
                    return;
                }
            }

            // 1 秒后仍未检测到新连接，记录警告但不阻断流程
            // 后续 sendAudio 时会再次触发 IllegalStateException 进行重试
            log.warn("[Session: {}] ASR reconnection may not be fully ready after 1 second", sessionId);
        }
    }

    /**
     * 在持有 sessionLock 锁的前提下启动 ASR 转写会话（核心实现）。
     * <p>
     * 此方法执行以下完整流程：
     * <ol>
     *   <li><b>防重复检查</b>：同一 sessionId 不能已有活跃会话</li>
     *   <li><b>构建连接参数</b>：用构造时从配置文件注入的 model/url/apikey 构建 OmniRealtimeParam</li>
     *   <li><b>创建回调处理器</b>：匿名实现 OmniRealtimeCallback，处理 DashScope WebSocket 的
     *       onOpen（连接成功）、onEvent（转写结果/错误事件，转发到上层回调）、
     *       onClose（连接关闭，仅移除本次连接对应的会话避免误删重连后的新会话）</li>
     *   <li><b>创建 Conversation 实例</b>：用 param 和 callback 构造 OmniRealtimeConversation，
     *       并用 AtomicReference 保存引用供 onClose 回调中使用</li>
     *   <li><b>存入会话映射表</b>：在建立连接前先存入 sessions Map，
     *       确保 hasActiveSession() 返回 true（用于 checkPauseTimeout 等定时检测）</li>
     *   <li><b>异步建立 WebSocket 连接</b>：启动守护线程执行 connect()，
     *       连接成功后下发 updateSession() 配置转写参数和 VAD 语音活动检测策略</li>
     *   <li><b>失败清理</b>：连接建立失败时从 sessions Map 移除对应条目并回调 onError</li>
     * </ol>
     *
     * @param sessionId 面试会话唯一标识（与前端 WebSocket 的 sessionId 对应）
     * @param onFinal   ASR 最终定稿文本回调（VAD 检测到 400ms 静默后断句的结果）
     *                  文本追加到 Handler 的 mergeBuffer，等待用户手动提交后送入 LLM
     * @param onPartial ASR 实时部分识别结果回调（说话过程中的中间文本）
     *                  用于向前端推送实时字幕，提升用户体感
     * @param onError   ASR 连接或识别过程中发生错误时的回调
     *                  向 WebSocket 前端推送错误消息
     */
    private void startTranscriptionLocked(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        // 第一步：防重复校验，同一会话不允许重复启动 ASR
        if (sessions.containsKey(sessionId)) {
            throw new IllegalStateException("Session already exists: " + sessionId);
        }

        try {
            // 第二步：构建阿里云DashScope API 连接参数
            // 三个字段 model/url/apikey 在 QwenAsrService 构造时从
            // VoiceInterviewProperties.AsrConfig 读取并缓存到成员变量
            OmniRealtimeParam param = OmniRealtimeParam.builder()
                    .model(model)       // ASR 模型名，如 "qwen3-asr-flash-realtime"
                    .url(url)           // DashScope 实时转写 WebSocket 地址
                    .apikey(apiKey)     // 阿里云 API Key（来自 .env 配置）
                    .build();

            // AtomicReference 保存 OmniRealtimeConversation 引用，
            // 供 onClose 回调中比较对象引用以判断是否为本次连接对应的会话实例
            final AtomicReference<OmniRealtimeConversation> conversationRef = new AtomicReference<>();

            // 第三步：创建 DashScope WebSocket 事件回调处理器（匿名内部类）
            OmniRealtimeCallback callback = new OmniRealtimeCallback() {
                // WebSocket 连接成功打开时触发
                @Override
                public void onOpen() {
                    log.debug("[Session: {}] WebSocket connection established", sessionId);
                }

                // 收到 DashScope 服务端推送的 JSON 事件时触发
                // 事件类型包括：转写完成、部分结果、错误等，统一委托 handleServerEvent 分发
                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(sessionId, message, onFinal, onPartial, onError);
                }

                // WebSocket 连接关闭时触发
                // 关键设计：通过 conversationRef 比较来判断关闭的
                // 是否为"本次"连接。重连场景下旧连接的 onClose 回调可能
                // 延迟到达，如果不做引用比较会错误地移除新连接的会话条目，
                // 导致后续 ASR 音频发送失败（典型表现：「第三轮起无声」）
                @Override
                public void onClose(int code, String reason) {
                    OmniRealtimeConversation closed = conversationRef.get();
                    log.warn("[Session: {}] DashScope ASR WebSocket closed - code: {}, reason: {}",
                            sessionId, code, reason);
                    // 仅当 sessions Map 中当前条目引用的 Conversation 就是本次被关闭的那个实例时，
                    // 才移除该条目；否则说明已重连建立了新 Conversation，保留新条目不动
                    sessions.compute(sessionId, (id, existing) -> {
                        if (existing != null && closed != null && existing.getConversation() == closed) {
                            return null;
                        }
                        return existing;
                    });
                }
            };

            // 第四步：创建 OmniRealtimeConversation 实例（DashScope SDK 对象，内部管理 WebSocket）
            OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, callback);
            conversationRef.set(conversation);

            // 第五步：在建立连接之前先将会话存入 sessions Map
            // 这样 hasActiveSession() 立即返回 true，供 checkPauseTimeout 等定时检测使用
            sessions.put(sessionId, new AsrSession(conversation, onFinal, onPartial, onError));

            // 第六步：启动守护线程异步建立到 DashScope（即实时转写服务端） 的 WebSocket 连接
            // 异步设计确保 startTranscriptionLocked 快速返回，不阻塞 Handler 的 afterConnectionEstablished
            Thread connectionThread = new Thread(() -> {
                try {
                    // 6.1 建立 WebSocket 连接（阻塞当前线程，不阻塞调用方）
                    conversation.connect();

                    // 6.2 构建转写参数：语言、采样率、音频格式
                    // 这些值同样来自构造时缓存的成员变量
                    OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
                    transcriptionParam.setLanguage(language);               // 识别语言：zh（中文）
                    transcriptionParam.setInputSampleRate(sampleRate);      // 输入采样率：16000Hz
                    transcriptionParam.setInputAudioFormat(format);         // 音频格式：pcm

                    // 6.3 构建会话配置：指定只需文本输出 + VAD 语音活动检测策略
                    OmniRealtimeConfig config = OmniRealtimeConfig.builder()
                            .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))  // 仅需文本，无需音频输出
                            .enableTurnDetection(enableTurnDetection)                          // 启用服务端 VAD
                            .turnDetectionType(turnDetectionType)                              // VAD 类型：server_vad
                            .turnDetectionThreshold(turnDetectionThreshold)                    // VAD 灵敏度阈值
                            .turnDetectionSilenceDurationMs(turnDetectionSilenceDurationMs)    // 静默断句阈值：400ms
                            .transcriptionConfig(transcriptionParam)                           // 转写参数配置
                            .build();

                    // 6.4 将会话配置下发到 DashScope 服务端
                    // 此后用户发送的音频数据将按此配置进行实时转写
                    conversation.updateSession(config);

                    log.info("[Session: {}] Transcription session started successfully", sessionId);

                } catch (Exception e) {
                    // 连接建立失败时的清理逻辑：
                    // 用 compute 安全移除——仅移除引用与当前 conversation 一致的条目
                    log.error("[Session: {}] Failed to establish connection", sessionId, e);
                    sessions.compute(sessionId, (id, existing) -> {
                        if (existing != null && existing.getConversation() == conversation) {
                            return null;
                        }
                        return existing;
                    });
                    // 通知上层 WebSocket Handler 发送错误消息给前端
                    onError.accept(e);
                }
            }, "ASR-Connection-" + sessionId);
            // 设为守护线程，确保 JVM 退出时不会因该线程而阻塞进程关闭
            connectionThread.setDaemon(true);
            connectionThread.start();

        } catch (Exception e) {
            // 同步阶段（线程启动前）发生异常时的清理
            String errorMsg = "Failed to create transcription session: " + sessionId;
            log.error(errorMsg, e);
            sessions.remove(sessionId);
            onError.accept(new IllegalStateException(errorMsg, e));
            throw new IllegalStateException(errorMsg, e);
        }
    }

    /**
     * 将一块用户 PCM 音频追加到活跃的 DashScope ASR 会话。
     *
     * 音频数据应为 16kHz 采样率的 PCM 格式。
     * 数据在发送到 DashScope 服务前会先进行 Base64 编码。
     *
     * 启用服务端 VAD 后，服务会自动检测语音片段并在检测到静默时触发转写。
     *
     * @param sessionId 会话标识符
     * @param audioData 原始 PCM 音频字节
     * 由 WebSocket Handler 的音频入口高频调用。连接不存在或 SDK 追加失败时抛出统一状态异常，
     * 上层据此判断是否重建连接并重试当前音频块。
     *
     * @throws IllegalStateException 会话不存在或音频追加失败
     */
    public void sendAudio(String sessionId, byte[] audioData) {
        AsrSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("No active session found: " + sessionId);
        }

        try {
            // Convert audio data to Base64
            String audioBase64 = Base64.getEncoder().encodeToString(audioData);

            // Send to ASR service
            session.getConversation().appendAudio(audioBase64);

            log.trace("[Session: {}] Sent {} bytes of audio data", sessionId, audioData.length);

        } catch (Exception e) {
            log.error("[Session: {}] appendAudio failed (upstream may reconnect)", sessionId, e);
            // 抛出以便 WebSocket 层执行 restartTranscription；不在此重复 onError 避免用户先看到红条再恢复
            throw new IllegalStateException("ASR append failed: " + sessionId, e);
        }
    }

    /**
     * 原子移除并关闭指定业务会话的 ASR 连接。
     *
     * WebSocket 关闭、暂停超时、重连和 Bean 销毁都会调用。方法先从映射中移除会话和锁，再通知
     * DashScope 结束转写并关闭连接，重复停止是安全的。
     *
     * @param sessionId 会话标识符
     */
    public void stopTranscription(String sessionId) {
        synchronized (lockForSession(sessionId)) {
            AsrSession session = sessions.remove(sessionId);
            // Clean up the session lock to prevent memory leak
            sessionLocks.remove(sessionId);
            if (session == null) {
                log.warn("[Session: {}] Attempted to stop non-existent session", sessionId);
                return;
            }

            try {
                session.getConversation().endSession();
                log.info("[Session: {}] Transcription session stopped", sessionId);
            } catch (InterruptedException e) {
                log.error("[Session: {}] Thread interrupted while ending session", sessionId, e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[Session: {}] Error while ending session (may already be closed): {}", sessionId, e.getMessage());
            }

            try {
                session.getConversation().close();
            } catch (Exception e) {
                log.debug("[Session: {}] Connection already closed: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 检查指定 ID 是否已有已注册的 ASR 会话。
     *
     * 供上层健康检查和重连等待使用；这里只检查本地映射，不保证远端 WebSocket 仍然可写。
     *
     * @param sessionId 会话标识符
     * @return 若会话存在且活跃返回 true，否则返回 false
     */
    public boolean hasActiveSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * 在 Bean 销毁时停止全部 ASR 会话并释放远端连接。
     *
     * 由 Spring {@link PreDestroy} 生命周期调用，逐个复用 {@link #stopTranscription(String)}，单个
     * 会话清理失败不会阻止其余会话释放。
     */
    @PreDestroy
    public void destroy() {
        log.info("Destroying QwenAsrService with {} active sessions", sessions.size());

        // Stop all active sessions
        sessions.keySet().forEach(sessionId -> {
            try {
                stopTranscription(sessionId);
            } catch (Exception e) {
                log.error("[Session: {}] Error during cleanup", sessionId, e);
            }
        });

        sessions.clear();
        log.info("QwenAsrService destroyed successfully");
    }

    /**
     * 处理 DashScope ASR 服务端事件。
     *
     * 处理的事件类型：
     * - session.created: Session 创建成功
     * - session.updated: Session 配置已更新
     * - conversation.item.input_audio_transcription.completed: 最终转写结果
     * - conversation.item.input_audio_transcription.text / .delta: 部分转写（实时字幕）
     * - error: 发生错误
     *
     * @param sessionId 会话标识符
     * @param message 服务端发送的 JSON 事件消息
     * @param onFinal 最终片段文本的回调
     * @param onPartial 流式部分文本的回调（可选）
     * @param onError 错误回调
     */
    private void handleServerEvent(
            String sessionId,
            JsonObject message,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        try {
            // 从 JSON 消息中提取事件类型字段，所有 DashScope 事件都通过 type 区分
            String eventType = message.get("type").getAsString();

            log.trace("[Session: {}] Received event: {}", sessionId, eventType);

            switch (eventType) {
                // ASR 会话在服务端创建成功，仅记录日志，不做业务处理
                case "session.created":
                    log.debug("[Session: {}] Session created on server", sessionId);
                    break;

                // ASR 会话配置更新（如语言、采样率变更），仅记录日志
                case "session.updated":
                    log.debug("[Session: {}] Session configuration updated", sessionId);
                    break;

                // 一句话的最终识别结果（VAD 检测到静默后触发）
                // 提取识别文本、语种和情绪，通过 onFinal 回调传给 Handler 送入 LLM
                case "conversation.item.input_audio_transcription.completed":
                    JsonObject transcriptObj = message.getAsJsonObject();
                    // 最终的完整识别文本
                    String transcript = transcriptObj.get("transcript").getAsString();
                    // 语种（如 zh 中文、en 英文），字段可能不存在，兜底 unknown
                    String language = transcriptObj.has("language") ?
                            transcriptObj.get("language").getAsString() : "unknown";
                    // 情绪标签（如 neutral 中性、happy 高兴），字段可能不存在
                    String emotion = transcriptObj.has("emotion") ?
                            transcriptObj.get("emotion").getAsString() : "neutral";

                    log.debug("[Session: {}] Transcription completed - language: {}, emotion: {}, text: {}",
                            sessionId, language, emotion, transcript);

                    // 将最终文本传递给 Handler，Handler 会拼接后送 LLM 生成回复
                    onFinal.accept(transcript);
                    break;

                // 中间识别结果（用户还在说话，文本可能被后续结果修正）
                // text 是完整中间文本，delta 是增量更新，都通过 onPartial 推给前端做实时字幕
                case "conversation.item.input_audio_transcription.text":
                case "conversation.item.input_audio_transcription.delta":
                    dispatchPartialTranscript(sessionId, message, onPartial);
                    break;

                // ASR 服务端报错（如鉴权失败、音频格式不支持等）
                // 解析错误详情后通过 onError 回调通知 Handler，Handler 会向前端推送错误提示
                case "error":
                    JsonObject errorObj = message.getAsJsonObject("error");
                    String errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
                    String errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
                    String errorMessage = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";

                    String fullErrorMessage = String.format("ASR Error [%s/%s]: %s", errorType, errorCode, errorMessage);
                    log.error("[Session: {}] {}", sessionId, fullErrorMessage);

                    onError.accept(new IllegalStateException(fullErrorMessage));
                    break;

                // ASR 会话在服务端结束，仅记录日志
                case "session.finished":
                    log.debug("[Session: {}] Session finished on server", sessionId);
                    break;

                // 单句话转写失败（用户发音不清、噪音太大等），记日志但不中断整个会话
                case "conversation.item.input_audio_transcription.failed":
                    log.error("[Session: {}] ASR transcription failed (single utterance): {}", sessionId, message);
                    break;

                // 未识别的事件类型：转写相关的用 debug 级别方便排查，其他事件用 trace 静默忽略
                default:
                    if (eventType != null && eventType.contains("transcription")) {
                        log.debug("[Session: {}] Unhandled transcription-related event: {}", sessionId, message);
                    } else {
                        log.trace("[Session: {}] Unhandled event type: {}", sessionId, eventType);
                    }
            }

        } catch (Exception e) {
            // 解析 JSON 或回调执行异常时，记录错误并通过 onError 通知上层
            log.error("[Session: {}] Error processing server event", sessionId, e);
            onError.accept(e);
        }
    }

    /**
     * 从部分转写事件提取文本并转发给实时字幕回调。
     *
     * 由 {@link #handleServerEvent} 的 text/delta 分支调用；未注册回调或事件没有可见文本时静默
     * 跳过。VAD 只负责断句，字幕仍需要这里显式转发。

     * @param sessionId 业务会话 ID，用于日志关联
     * @param message DashScope 部分转写事件
     * @param onPartial 前端实时字幕回调，可为 {@code null}
     */
    private void dispatchPartialTranscript(
            String sessionId, JsonObject message, Consumer<String> onPartial) {
        if (onPartial == null) {
            log.trace("[Session: {}] Partial transcription received (no consumer)", sessionId);
            return;
        }
        String text = extractTranscriptPayload(message);
        if (text != null && !text.isBlank()) {
            onPartial.accept(text);
        } else {
            log.trace("[Session: {}] Partial ASR event without extractable text: {}", sessionId, message);
        }
    }

    /**
     * 从不同版本的 ASR JSON 事件结构中提取可显示文本。
     *
     * 对于 {@code conversation.item.input_audio_transcription.text}，
     * 官方预览为 {@code text}（确认前缀）+ {@code stash}（草稿后缀）；
     * 两者都可能为空；同时兼容 transcript、delta 和 item.transcript 等历史结构。由部分字幕分发和
     * 单元测试调用，不修改原始事件。

     * @param message DashScope ASR 事件
     * @return 可显示文本，事件不含文本时返回 {@code null}
     */
    static String extractTranscriptPayload(JsonObject message) {
        if (message.has("transcript") && !message.get("transcript").isJsonNull()) {
            JsonElement el = message.get("transcript");
            if (el.isJsonPrimitive()) {
                return el.getAsString();
            }
        }
        // Real-time partial: text + stash (see Alibaba qwen-asr-realtime server events doc)
        if (message.has("text") || message.has("stash")) {
            String prefix = "";
            String suffix = "";
            if (message.has("text") && !message.get("text").isJsonNull() && message.get("text").isJsonPrimitive()) {
                prefix = message.get("text").getAsString();
            }
            if (message.has("stash") && !message.get("stash").isJsonNull() && message.get("stash").isJsonPrimitive()) {
                suffix = message.get("stash").getAsString();
            }
            String combined = prefix + suffix;
            if (!combined.isBlank()) {
                return combined;
            }
        }
        if (message.has("delta")) {
            JsonElement d = message.get("delta");
            if (d.isJsonPrimitive()) {
                return d.getAsString();
            }
            if (d.isJsonObject()) {
                JsonObject o = d.getAsJsonObject();
                if (o.has("text") && !o.get("text").isJsonNull()) {
                    return o.get("text").getAsString();
                }
                if (o.has("transcript") && !o.get("transcript").isJsonNull()) {
                    return o.get("transcript").getAsString();
                }
            }
        }
        if (message.has("item") && message.get("item").isJsonObject()) {
            JsonObject item = message.getAsJsonObject("item");
            if (item.has("transcript") && !item.get("transcript").isJsonNull()) {
                return item.get("transcript").getAsString();
            }
        }
        return null;
    }

    /**
     * 内部类，用于保存会话数据。
     * 包含 OmniRealtimeConversation 实例和回调函数。
     */
    private static class AsrSession {
        private final OmniRealtimeConversation conversation;
        private final Consumer<String> onFinal;
        private final Consumer<String> onPartial;
        private final Consumer<Throwable> onError;

        /**
         * 构造 ASR 会话对象。
         *
         * @param conversation OmniRealtimeConversation 实例
         * @param onFinal 最终转写文本回调
         * @param onPartial 部分转写文本回调
         * @param onError 错误回调
         */
        AsrSession(
                OmniRealtimeConversation conversation,
                Consumer<String> onFinal,
                Consumer<String> onPartial,
                Consumer<Throwable> onError) {
            this.conversation = conversation;
            this.onFinal = onFinal;
            this.onPartial = onPartial;
            this.onError = onError;
        }

        /**
         * 返回发送音频和关闭连接所需的 SDK 会话。
         *
         * @return 当前 DashScope 实时会话
         */
        public OmniRealtimeConversation getConversation() {
            return conversation;
        }

        /**
         * 返回该会话注册的错误回调。
         *
         * @return 上层错误消费者
         */
        public Consumer<Throwable> getOnError() {
            return onError;
        }
    }

    /**
     * 设置 ASR 服务 URL
     * @param url DashScope ASR WebSocket URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * 设置 ASR 模型名称
     * @param model 模型名称，如 qwen3-asr-flash-realtime
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 设置 DashScope API Key
     * @param apiKey 阿里云 DashScope API 密钥
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 设置识别语言
     * @param language 语言代码，如 zh（中文）
     */
    public void setLanguage(String language) {
        this.language = language;
    }

    /**
     * 设置音频格式
     * @param format 音频格式，如 pcm
     */
    public void setFormat(String format) {
        this.format = format;
    }

    /**
     * 设置音频采样率
     * @param sampleRate 采样率，如 16000
     */
    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    /**
     * 设置是否启用轮流检测
     * @param enableTurnDetection 是否启用轮流检测
     */
    public void setEnableTurnDetection(Boolean enableTurnDetection) {
        this.enableTurnDetection = enableTurnDetection;
    }

    /**
     * 设置轮流检测类型
     * @param turnDetectionType 轮流检测类型，如 server_vad
     */
    public void setTurnDetectionType(String turnDetectionType) {
        this.turnDetectionType = turnDetectionType;
    }

    /**
     * 设置轮流检测阈值
     * @param turnDetectionThreshold 轮流检测阈值，数值越高越敏感
     */
    public void setTurnDetectionThreshold(Float turnDetectionThreshold) {
        this.turnDetectionThreshold = turnDetectionThreshold;
    }

    /**
     * 设置轮流检测静默时长
     * @param turnDetectionSilenceDurationMs 静默时长（毫秒），如 400ms 后断句
     */
    public void setTurnDetectionSilenceDurationMs(Integer turnDetectionSilenceDurationMs) {
        this.turnDetectionSilenceDurationMs = turnDetectionSilenceDurationMs;
    }
}
