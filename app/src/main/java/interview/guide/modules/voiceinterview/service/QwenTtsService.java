/**
 * Qwen 实时文本转语音（TTS）服务
 *
 * 基于阿里云 DashScope qwen3-tts-flash-realtime 模型，通过 WebSocket 实现文本转语音。
 * 采用 commit 模式手动控制合成时机，每次合成建立临时 WebSocket 连接，
 * 收集所有音频分片后返回完整 PCM 数据。支持配置音色、语速、音量等参数。
 *
 * 联动关系：
 * - 被 VoiceInterviewWebSocketHandler 依赖（将 LLM 回复文本转为语音推送）
 * - 依赖 VoiceInterviewProperties（读取 qwen.tts 配置：模型、API Key、音色等）
 * - 使用 DashScope QwenTtsRealtime SDK（WebSocket 通信）
 *
 * 核心方法：
 * - synthesize(text)：将文本同步转为 PCM 音频数据，建立临时 WebSocket 连接并等待合成完成
 * - handleServerEvent()：处理服务端事件（session 创建、音频分片、错误等）
 *
 * 配置参数：
 * - model: qwen-tts-realtime
 * - voice: 音色（Cherry, Serena, Ethan 等）
 * - audio format: PCM, 24kHz 采样率
 * - mode: commit（用户控制合成时机）
 */
package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.google.gson.JsonObject;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class QwenTtsService {

    private String model;
    private String apiKey;
    private String voice;
    private String format;
    private Integer sampleRate;
    private String mode;
    private String languageType;
    private Float speechRate;
    private Integer volume;

    /**
     * 创建 TTS 服务并复制当前 Qwen 合成配置。
     *
     * 由 Spring 构造 Bean；API Key 在 {@link #init()} 中统一校验，便于测试覆盖运行参数。
     *
     * @param voiceInterviewProperties 集中的语音面试配置
     */
    public QwenTtsService(VoiceInterviewProperties voiceInterviewProperties) {
        applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
    }

    /**
     * 从最新属性对象重新加载后续合成请求使用的 TTS 配置。
     *
     * 由配置刷新逻辑或测试调用；每次合成本来就建立临时连接，因此无需迁移存量连接。
     *
     * @param voiceInterviewProperties 语音面试配置属性（包含 Qwen TTS 配置）
     */
    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
        log.info("QwenTtsService reloaded: model={}, voice={}", model, voice);
    }

    /**
     * 将配置对象复制为一次 TTS 会话所需的运行参数。
     *
     * 构造和 {@link #reload(VoiceInterviewProperties)} 共用，集中维护模型、音色、格式、语速和音量映射。
     *
     * @param tts TTS 配置对象，包含模型、API Key、音色、采样率等配置
     */
    private void applyTtsConfig(VoiceInterviewProperties.QwenTtsConfig tts) {
        this.model = tts.getModel();
        this.apiKey = tts.getApiKey();
        this.voice = tts.getVoice();
        this.format = tts.getFormat();
        this.sampleRate = tts.getSampleRate();
        this.mode = tts.getMode();
        this.languageType = tts.getLanguageType();
        this.speechRate = tts.getSpeechRate();
        this.volume = tts.getVolume();
    }

    /**
     * 在 Bean 初始化后验证建立 TTS 连接所需的 API Key。
     *
     * 由 Spring {@link PostConstruct} 生命周期调用；实际 DashScope WebSocket 连接在每次
     * {@link #synthesize(String)} 请求中临时建立并关闭。
     *
     * @throws IllegalStateException 若 apiKey 未配置
     */
    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API key must be configured before initializing QwenTtsService");
        }
        log.info("QwenTtsService initialized with model: {}, voice: {}, sampleRate: {}Hz",
                 model, voice, sampleRate);
    }

    /**
     * 将文本合成为语音音频。
     * <p>
     * 通过 DashScope WebSocket TTS API 同步将文本转为 PCM 音频数据。
     * 内部执行以下七个阶段：
     * <ol>
     *   <li><b>空文本检查</b>：null/空字符串/纯空白直接返回空数组，避免无效 WebSocket 连接</li>
     *   <li><b>构建连接参数</b>：用构造时注入的 model、apiKey 构建 QwenTtsRealtimeParam</li>
     *   <li><b>建立 WebSocket 连接</b>：创建 QwenTtsRealtime 实例并 connect()（阻塞）</li>
     *   <li><b>配置 TTS 参数</b>：updateSession 下发音色、语速、音量、输出格式、commit 模式</li>
     *   <li><b>发送文本并提交</b>：appendText(text) + commit() 触发服务端合成</li>
     *   <li><b>收集音频分片</b>：onEvent 回调中将 TTS 服务端推送的 Base64 音频分片解码后追加到 ByteArrayContainer</li>
     *   <li><b>返回完整音频</b>：CountDownLatch 解除阻塞 → 合并所有分片 → 返回 PCM 字节数组</li>
     * </ol>
     * <p>
     * 使用 CountDownLatch 将异步 WebSocket 响应转为同步等待，30 秒超时保护防止无限阻塞。
     * 所有异常均返回空数组（非抛异常），保证调用方不中断。
     *
     * @param text 要合成的文本（null、空字符串或纯空白返回空数组）
     * @return 采样率对应的 PCM 音频数据，合成失败返回空数组
     */
    public byte[] synthesize(String text) {
        // ===== 阶段一：空文本检查 =====
        // null、空字符串、纯空白文本无合成意义，直接返回空数组
        // 避免建立无意义的 WebSocket 连接
        if (text == null || text.trim().isEmpty()) {
            log.debug("Empty or null text provided, returning empty audio array");
            return new byte[0];
        }

        log.debug("Starting TTS synthesis for text: {} characters", text.length());

        // ===== 同步等待工具 =====
        // CountDownLatch：将异步 WebSocket 响应转为同步阻塞等待
        // 初始计数 1，onEvent 收到 audio.done 或 onClose 时 countDown() 解除阻塞
        CountDownLatch synthesisLatch = new CountDownLatch(1);

        // ByteArrayContainer：可动态增长的字节数组容器，收集 WebSocket 推送的多个音频分片
        // 等于 ByteArrayOutputStream 的简化版
        ByteArrayContainer audioContainer = new ByteArrayContainer();

        // AtomicReference<Throwable>：存储异步回调中发生的异常引用
        // 主线程在 CountDownLatch 解除后读取此引用，非 null 表示合成失败
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // AtomicReference<String>：存储 TTS 服务端返回的 responseId（用于日志追踪）
        AtomicReference<String> responseIdRef = new AtomicReference<>();

        try {
            // ===== 阶段二：构建连接参数 =====
            // model、apiKey 来自构造时通过 applyTtsConfig() 注入的成员变量
            QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                    .model(model)
                    .apikey(apiKey)
                    .build();

            // ===== 创建 WebSocket 事件回调 =====
            // onOpen：连接建立成功，无额外操作
            // onEvent：收到服务端推送 → 根据 type 分发处理
            //          - audio：提取 PCM 音频分片（Base64 解码）追加到 audioContainer
            //          - audio.done：合成完成 → countDown 解除主线程阻塞
            //          - error：记录错误 → countDown 解除阻塞
            // onClose：连接关闭 → countDown 解除阻塞（兜底，防止未收到 audio.done 时死锁）
            QwenTtsRealtimeCallback callback = new QwenTtsRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("TTS WebSocket connection established");
                }

                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(message, audioContainer, synthesisLatch, errorRef, responseIdRef);
                }

                @Override
                public void onClose(int code, String reason) {
                    log.debug("TTS WebSocket closed - code: {}, reason: {}", code, reason);
                    // 无论正常还是异常关闭，都要解除主线程阻塞
                    synthesisLatch.countDown();
                }
            };

            // ===== 阶段三：建立 WebSocket 连接 =====
            // QwenTtsRealtime 是 DashScope TTS SDK 的 WebSocket 客户端
            // connect() 方法是阻塞的——会等待 TCP 握手 + WebSocket 协议升级完成
            QwenTtsRealtime qwenTtsRealtime = new QwenTtsRealtime(param, callback);

            try {
                qwenTtsRealtime.connect();

                // ===== 阶段四：配置 TTS 会话参数 =====
                // voice：音色（如 "zhixiaobai" / "zhichu" 等 DashScope 预置音色）
                // responseFormat：音频输出格式（pcm / wav）
                // mode："commit" 模式——文本一次性提交，服务端完整合成后返回
                //        （对比：实时模式会逐字符合成，适合流式场景）
                // languageType：语言类型（Chinese / English）
                // speechRate：语速倍率（1.0 正常，0.5 慢速，2.0 快速）
                // volume：音量倍率（1.0 正常）
                QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
                        .voice(voice)
                        .responseFormat(getAudioFormat())
                        .mode(mode)
                        .languageType(languageType)
                        .speechRate(speechRate)
                        .volume(volume)
                        .build();

                // updateSession 将配置下发到 DashScope 服务端
                qwenTtsRealtime.updateSession(config);

                log.info("[TTS] Session configured with voice: {}, triggering synthesis for text (length: {})",
                         voice, text.length());

                // ===== 阶段五：发送文本并提交合成 =====
                // appendText(text)：将文本发送到服务端
                // commit()：告知服务端文本已全部发送，开始合成
                // 服务端合成完成后逐个音频分片推送，每个分片触发 onEvent("audio")
                qwenTtsRealtime.appendText(text);
                // 提交文本，触发服务端合成
                qwenTtsRealtime.commit();

                log.info("[TTS] Text sent to TTS service, waiting for audio response...");

                // ===== 阶段六：同步等待合成完成 =====
                // await 会阻塞当前线程（调用方为虚拟线程，阻塞不消耗 OS 线程）
                // 解除条件：onEvent 收到 audio.done / error / onClose 回调中的 countDown()
                // 30 秒超时——正常中文 TTS 合成 200 字约 5-10 秒，30 秒足够覆盖长文本
                boolean completed = synthesisLatch.await(30, TimeUnit.SECONDS);

                if (!completed) {
                    log.error("TTS synthesis timeout after 30 seconds");
                    return new byte[0];
                }

                // 检查异步回调中是否发生异常
                Throwable error = errorRef.get();
                if (error != null) {
                    log.error("TTS synthesis failed", error);
                    return new byte[0];
                }

                // ===== 阶段七：返回合并后的完整音频数据 =====
                // ByteArrayContainer 中已累积了所有 WebSocket 推送的音频分片
                byte[] audioData = audioContainer.toByteArray();
                log.info("[TTS] Synthesis completed successfully - {} bytes of audio data, responseId: {}",
                         audioData.length, responseIdRef.get());

                return audioData;

            } finally {
                // 无论成功、超时还是异常，确保 WebSocket 连接被关闭
                try {
                    qwenTtsRealtime.close();
                } catch (Exception e) {
                    log.error("Error closing TTS connection", e);
                }
            }

        } catch (InterruptedException e) {
            // await 被外部中断（如面试会话被强制结束、虚拟线程被 interrupt）
            // 必须恢复中断状态（遵循 Java 中断最佳实践）
            log.error("TTS synthesis interrupted", e);
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (Exception e) {
            // 其他未预期异常（网络错误、SDK 异常等）
            log.error("Failed to synthesize text", e);
            return new byte[0];
        }
    }

    /**
     * 返回与 WAV 封装逻辑匹配的 SDK 输出格式。
     *
     * 仅由 {@link #synthesize(String)} 构建会话配置时调用，当前固定为 24kHz、单声道、16 位 PCM。
     *
     * @return QwenTtsRealtimeAudioFormat 枚举值
     */
    private QwenTtsRealtimeAudioFormat getAudioFormat() {
        return QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;
    }

    /**
     * 记录 TTS Bean 销毁完成。
     *
     * 由 Spring {@link PreDestroy} 生命周期调用；每次合成的连接已在 finally 中关闭，因此这里无需
     * 维护或释放共享连接。
     */
    @PreDestroy
    public void destroy() {
        log.info("QwenTtsService destroyed successfully");
    }

    /**
     * 处理 DashScope TTS 服务端事件。
     *
     * 处理的事件类型：
     * - session.created: Session 创建成功
     * - session.updated: Session 配置已更新
     * - response.audio.delta: 收到音频分片
     * - response.done: 响应完成
     * - error: 发生错误
     *
     * @param message 服务端发送的 JSON 事件消息
     * @param audioContainer 音频分片收集容器
     * @param synthesisLatch 用于通知合成完成的 Latch
     * @param errorRef 错误追踪容器
     * @param responseIdRef 响应 ID 追踪容器
     */
    private void handleServerEvent(JsonObject message, ByteArrayContainer audioContainer,
                                    CountDownLatch synthesisLatch, AtomicReference<Throwable> errorRef,
                                    AtomicReference<String> responseIdRef) {
        try {
            // 从 JSON 消息中提取事件类型字段，DashScope TTS 所有事件都通过 type 区分
            String eventType = message.get("type").getAsString();

            // trace 级别输出完整消息体方便调试，debug 级别只输出事件类型减少日志量
            if (log.isTraceEnabled()) {
                log.trace("Received TTS event: {}, full message: {}", eventType, message);
            } else {
                log.debug("Received TTS event: {}", eventType);
            }

            switch (eventType) {
                // TTS 会话在服务端创建成功，提取 sessionId 用于日志追踪，不做业务处理
                case "session.created":
                    String sessionId = message.has("session") && message.get("session").isJsonObject()
                            ? message.get("session").getAsJsonObject().get("id").getAsString()
                            : "unknown";
                    log.debug("TTS session created: {}", sessionId);
                    break;

                // TTS 会话配置已更新（音色、语速、音量等生效），仅记录日志
                case "session.updated":
                    log.debug("TTS session configuration updated");
                    break;

                // 收到一个 PCM 音频分片（Base64 编码）
                // TTS 服务端不一定一次性返回全部音频，可能分多个 delta 推送
                // 每个 delta 解码后追加到 audioContainer，最终拼成完整音频
                case "response.audio.delta":
                    if (message.has("delta")) {
                        String audioBase64 = message.get("delta").getAsString();
                        if (audioBase64 != null && !audioBase64.isEmpty()) {
                            // Base64 解码还原为原始 PCM 字节
                            byte[] audioChunk = Base64.getDecoder().decode(audioBase64);
                            // 追加到可动态增长的容器，等待最后一个 delta 到达
                            audioContainer.append(audioChunk);
                            log.trace("Received audio chunk - {} bytes", audioChunk.length);
                        }
                    }
                    break;

                // 本次 TTS 合成完成（所有音频分片已推送完毕）
                // 调用 countDown() 解除主线程的 await() 阻塞，主线程随后从 audioContainer 取完整音频
                case "response.done":
                    String responseId = responseIdRef.get();
                    log.debug("TTS response completed - responseId: {}", responseId);
                    synthesisLatch.countDown();
                    break;

                // TTS 服务端报错（如文本过长、鉴权失败、音色不存在等）
                // 解析错误详情存入 errorRef，同时 countDown() 让主线程不再无限等待
                case "error":
                    if (message.has("error")) {
                        var errorElement = message.get("error");
                        String errorType = "unknown";
                        String errorCode = "unknown";
                        String errorMessage = "Unknown error";

                        // error 可能是 JSON 对象（标准错误格式），也可能直接是字符串
                        if (errorElement.isJsonObject()) {
                            JsonObject errorObj = errorElement.getAsJsonObject();
                            errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
                            errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
                            errorMessage = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";
                        } else {
                            errorMessage = errorElement.toString();
                        }

                        String fullErrorMessage = String.format("TTS Error [%s/%s]: %s", errorType, errorCode, errorMessage);
                        log.error("{}", fullErrorMessage);

                        // 将异常写入原子引用，主线程 await 返回后会检查该引用并处理
                        errorRef.set(new IllegalStateException(fullErrorMessage));
                        // 务必 countDown，否则主线程 await 30 秒超时才能返回
                        synthesisLatch.countDown();
                    }
                    break;

                // 未识别的事件类型，trace 级别静默忽略
                default:
                    log.trace("Unhandled TTS event type: {}", eventType);
            }

        } catch (Exception e) {
            // JSON 解析或 Base64 解码异常：记录错误、存入 errorRef、解除阻塞
            // 与 error case 同理，必须 countDown 防止主线程死等
            log.error("Error processing TTS server event", e);
            errorRef.set(e);
            synthesisLatch.countDown();
        }
    }

    /**
     * 收集同一次 TTS WebSocket 响应中的音频分片。
     *
     * {@link #handleServerEvent} 负责追加，等待线程在合成完成后读取；同步方法保证回调线程与等待
     * 线程之间的数据可见性。
     */
    private static class ByteArrayContainer {
        private final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        /**
         * 追加一块已解码的 PCM 数据。
         *
         * @param chunk 服务端返回的音频分片
         */
        public synchronized void append(byte[] chunk) {
            baos.write(chunk, 0, chunk.length);
        }

        /**
         * 合并并复制当前已收到的全部 PCM 分片。
         *
         * @return 完整 PCM 字节数组
         */
        public synchronized byte[] toByteArray() {
            return baos.toByteArray();
        }
    }

    /**
     * 设置 TTS 模型名称
     * @param model 模型名称，如 qwen-tts-realtime
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
     * 设置语音音色
     * @param voice 音色名称，如 Cherry, Serena, Ethan 等
     */
    public void setVoice(String voice) {
        this.voice = voice;
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
     * @param sampleRate 采样率，如 24000
     */
    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    /**
     * 设置 TTS 模式
     * @param mode 模式，commit 表示用户控制合成时机
     */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * 设置语言类型
     * @param languageType 语言类型，如 chinese（中文）
     */
    public void setLanguageType(String languageType) {
        this.languageType = languageType;
    }

    /**
     * 设置语速
     * @param speechRate 语速倍率，如 1.0 表示正常语速
     */
    public void setSpeechRate(Float speechRate) {
        this.speechRate = speechRate;
    }

    /**
     * 设置音量
     * @param volume 音量级别，数值越大音量越大
     */
    public void setVolume(Integer volume) {
        this.volume = volume;
    }
}
