/**
 * DashScope LLM 对话服务
 *
 * 封装语音面试场景下的大模型调用逻辑，支持同步调用（chat）和流式句子级调用
 * （chatStreamSentences）。流式模式下每检测到一个完整句子即回调 onSentence 触发并发 TTS，
 * 同时按配置间隔推送实时文本给前端。输出经过语音优化处理（去 Markdown、截断长回复）。
 * 通过 LlmProviderRegistry 支持多 Provider 路由。
 *
 * 联动关系：
 * - 被 VoiceInterviewWebSocketHandler 依赖（核心 LLM 调用入口）
 * - 依赖 LlmProviderRegistry（获取 ChatClient）
 * - 依赖 VoiceInterviewPromptService（生成系统提示词）
 * - 依赖 ResumeRepository（加载关联简历文本注入提示词）
 * - 依赖 VoiceInterviewProperties（读取 aiQuestionMaxChars、流式推送参数等）
 * - 依赖 PromptSanitizer（清洗用户输入防注入）
 */
package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * DashScope LLM 对话服务
 *
 * 负责语音面试场景下的 LLM 调用，支持同步和流式两种模式
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DashscopeLlmService {

    /** 用于检测句子终止的标点符号 */
    private static final String TERMINAL_PUNCTUATION = "。！？；!?;.";

    /** LLM Provider 注册表（获取 ChatClient） */
    private final LlmProviderRegistry llmProviderRegistry;
    /** 语音面试提示词服务 */
    private final VoiceInterviewPromptService promptService;
    /** 简历仓库 */
    private final ResumeRepository resumeRepository;
    /** 语音面试配置 */
    private final VoiceInterviewProperties voiceInterviewProperties;
    /** 提示词清洗器（防注入） */
    private final PromptSanitizer promptSanitizer;

    /**
     * 同步生成一轮适合语音播报的面试官回复。
     *
     * WebSocket Handler 在关闭流式模式时调用。方法构建提示词、按会话 Provider 获取
     * {@link ChatClient}、执行阻塞式调用，再通过 {@link #optimizeForVoice(String)} 清理和截断输出；
     * 外部服务异常会降级为可直接展示和播报的提示文本。
     *
     * @param userInput 用户输入文本
     * @param session 语音面试会话实体
     * @param conversationHistory 对话历史
     * @return LLM 生成的文本回复
     */
    public String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        try {
            // 构建提示词上下文
            PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);

            // 获取指定或默认的 LLM Provider
            String provider = session.getLlmProvider();
            log.info("[VoiceInterview] Session {} using LLM provider: {}", session.getId(), provider);

            ChatClient chatClient = llmProviderRegistry.getVoiceChatClient(provider);

            // 调用 LLM
            ChatClient.CallResponseSpec response = chatClient.prompt()
                .system(promptContext.systemPrompt())
                .user(promptContext.userPrompt())
                .call();

            // 获取回复并优化为语音友好格式
            String content = response.chatResponse().getResult().getOutput().getText();
            String optimized = optimizeForVoice(content);

            log.info("LLM response generated for session {}: {}", session.getId(),
                    optimized.substring(0, Math.min(100, optimized.length())));

            return optimized;

        } catch (Exception e) {
            log.error("LLM chat error for session {}: {}", session.getId(), e.getMessage(), e);
            return mapLlmErrorToUserMessage(e);
        }
    }

    /**
     * 以旧版 token 回调接口发起流式 LLM 调用。
     *
     * 兼容只需要实时文本、不需要句子级 TTS 的调用方，内部直接委托给
     * {@link #chatStreamSentences(String, Consumer, Consumer, VoiceInterviewSessionEntity, List)}。
     *
     * @param userInput 用户输入文本
     * @param onToken 实时文本推送回调
     * @param session 语音面试会话实体
     * @param conversationHistory 对话历史
     * @return LLM 完整回复文本
     */
    public String chatStream(String userInput, Consumer<String> onToken, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        return chatStreamSentences(userInput, onToken, null, session, conversationHistory);
    }

    /**
     * 流式生成回复，并分别回调实时全文与完整句子。
     *
     * WebSocket Handler 的流式分支调用：{@code onToken} 按时间和字符增量节流后更新前端文本，
     * {@code onSentence} 在检测到终止标点时触发调用方并发 TTS。方法本身不合成音频；流结束后会
     * 补发无终止标点的尾句，并返回语音优化后的完整回复用于持久化。
     *
     * @param userInput 用户输入文本
     * @param onToken 实时文本推送回调（控制推送频率）
     * @param onSentence 完整句子回调（由调用方在此回调中触发 TTS）
     * @param session 语音面试会话实体
     * @param conversationHistory 对话历史
     * @return LLM 完整回复文本
     */
    public String chatStreamSentences(String userInput,
                                      Consumer<String> onToken,
                                      Consumer<String> onSentence,
                                      VoiceInterviewSessionEntity session,
                                      List<String> conversationHistory) {
        try {
            // 构建提示词上下文
            PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);
            String provider = session.getLlmProvider();
            log.info("[VoiceInterview] Session {} using LLM provider (sentence stream): {}", session.getId(), provider);

            // 获取 ChatClient
            ChatClient chatClient = llmProviderRegistry.getVoiceChatClient(provider);
            StringBuilder raw = new StringBuilder();
            AtomicLong lastEmitNanos = new AtomicLong(System.nanoTime());
            AtomicInteger lastEmitLength = new AtomicInteger(0);
            AtomicInteger lastSentenceEnd = new AtomicInteger(0);

            // 读取配置参数
            int emitIntervalMs = Math.max(80, voiceInterviewProperties.getAiStreamPushIntervalMs());
            int minCharsDelta = Math.max(4, voiceInterviewProperties.getAiStreamMinCharsDelta());

            // 流式调用 LLM
            chatClient.prompt()
                .system(promptContext.systemPrompt())
                .user(promptContext.userPrompt())
                .stream()
                .content()
                .doOnNext(token -> {
                    if (token == null || token.isEmpty()) {
                        return;
                    }
                    raw.append(token);

                    // 检测句子边界，回调 onSentence 触发并发 TTS
                    if (onSentence != null && hasTerminalSince(token)) {
                        String normalized = normalizeRealtimeText(raw.toString());
                        int currentEnd = normalized.length();
                        if (currentEnd > lastSentenceEnd.get()) {
                            String sentence = normalized.substring(lastSentenceEnd.get()).trim();
                            if (!sentence.isEmpty()) {
                                onSentence.accept(sentence);
                            }
                            lastSentenceEnd.set(currentEnd);
                        }
                    }

                    // 实时文本推送（按配置的间隔和字符增量）
                    if (onToken == null) {
                        return;
                    }
                    long now = System.nanoTime();
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - lastEmitNanos.get());
                    int currentLength = raw.length();
                    boolean shouldEmit = elapsedMs >= emitIntervalMs && currentLength - lastEmitLength.get() >= minCharsDelta;
                    if (!shouldEmit) {
                        return;
                    }
                    String normalized = normalizeRealtimeText(raw.toString());
                    if (normalized.isBlank()) {
                        return;
                    }
                    onToken.accept(normalized);
                    lastEmitNanos.set(now);
                    lastEmitLength.set(normalized.length());
                })
                .blockLast();

            // 发送最后一段（可能不以终止标点结尾）
            if (onSentence != null) {
                String normalized = normalizeRealtimeText(raw.toString());
                if (normalized.length() > lastSentenceEnd.get()) {
                    String remaining = normalized.substring(lastSentenceEnd.get()).trim();
                    if (!remaining.isEmpty()) {
                        onSentence.accept(remaining);
                    }
                }
            }

            // 优化并推送最终文本
            String optimized = optimizeForVoice(raw.toString());
            if (onToken != null && !optimized.isBlank()) {
                onToken.accept(optimized);
            }

            log.info("LLM sentence stream response for session {}: {}", session.getId(),
                    optimized.substring(0, Math.min(100, optimized.length())));
            return optimized;
        } catch (Exception e) {
            log.error("LLM sentence stream error for session {}: {}", session.getId(), e.getMessage(), e);
            return mapLlmErrorToUserMessage(e);
        }
    }

    /**
     * 组装系统提示词与当前轮用户提示词。
     *
     * 被同步和流式入口共同调用。若会话关联简历则从 {@link ResumeRepository} 读取文本并交给提示词
     * 服务；对话历史和当前输入逐项清洗后拼入用户提示词，防止非可信文本越过边界。
     *
     * @param userInput 用户输入
     * @param session 会话实体
     * @param conversationHistory 对话历史
     * @return 包含系统提示词和用户提示词的上下文
     */
    private PromptContext buildPromptContext(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        // 加载简历文本（如果有）
        String resumeText = null;
        if (session.getResumeId() != null) {
            ResumeEntity resume = resumeRepository.findById(session.getResumeId()).orElse(null);
            if (resume != null) {
                resumeText = resume.getResumeText();
            }
        }

        // 生成系统提示词（包含技能配置和简历上下文）
        String systemPrompt = promptService.generateSystemPromptWithContext(session.getSkillId(), resumeText);

        // 构建用户提示词（包含对话历史和当前输入）
        StringBuilder promptBuilder = new StringBuilder();
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            promptBuilder.append("【之前的对话】\n");
            for (String message : conversationHistory) {
                promptBuilder.append(promptSanitizer.sanitize(message)).append("\n");
            }
            promptBuilder.append("\n【当前对话】\n");
        }
        promptBuilder.append("用户：").append(
            promptSanitizer.wrapWithDelimiters("input", promptSanitizer.sanitize(userInput)));

        return new PromptContext(systemPrompt, promptBuilder.toString());
    }

    /**
     * 将常见 Provider 异常映射为可展示、可播报的降级文本。
     *
     * 同步和流式调用的异常分支都会调用，避免把认证信息、网络栈或 SDK 细节直接发送给前端。
     *
     * @param e 异常
     * @return 用户友好的错误消息
     */
    private String mapLlmErrorToUserMessage(Exception e) {
        String errorMessage = e.getMessage();
        if (errorMessage != null) {
            if (errorMessage.contains("403") || errorMessage.contains("ACCESS_DENIED") ||
                    errorMessage.contains("Authentication")) {
                return "AI 服务认证失败，请检查 API Key 配置";
            } else if (errorMessage.contains("timeout") || errorMessage.contains("Timeout")) {
                return "AI 服务响应超时，请稍后重试";
            } else if (errorMessage.contains("429") || errorMessage.contains("rate limit") ||
                       errorMessage.contains("quota")) {
                return "AI 服务调用频率超限，请稍后重试";
            } else if (errorMessage.contains("connection") || errorMessage.contains("network")) {
                return "AI 服务网络连接失败，请检查网络";
            }
        }
        return "抱歉，AI 服务暂时不可用，请稍后重试";
    }

    /**
     * 将模型原始回复压缩为适合实时语音播报的文本。
     *
     * 调用 {@link #normalizeRealtimeText(String)} 去除 Markdown 和多余空白；超过配置上限时优先在
     * 后半段终止标点处截断，否则用省略号硬截断。空结果降级为“请继续”。
     *
     * @param content 原始内容
     * @return 优化后的内容
     */
    private String optimizeForVoice(String content) {
        // 先标准化
        String normalized = normalizeRealtimeText(content);
        if (normalized.isBlank()) {
            return "请继续。";
        }

        // 超长回复截断到句子边界
        int maxChars = Math.max(80, voiceInterviewProperties.getAiQuestionMaxChars());
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        // 向前寻找最后一个终止标点
        String truncated = normalized.substring(0, maxChars);
        int lastTerminal = -1;
        for (int i = truncated.length() - 1; i >= 0; i--) {
            if (TERMINAL_PUNCTUATION.indexOf(truncated.charAt(i)) >= 0) {
                lastTerminal = i;
                break;
            }
        }
        if (lastTerminal >= maxChars / 2) {
            return truncated.substring(0, lastTerminal + 1);
        }

        return truncated + "…";
    }

    /**
     * 去除实时输出中的 Markdown 标记、列表前缀和重复空白。
     *
     * 流式回调和最终语音优化共同调用，确保前端逐步展示与最终保存文本格式一致。
     *
     * @param content 原始内容
     * @return 标准化后的内容
     */
    private String normalizeRealtimeText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content
            .replace("**", "")
            .replace("```", "")
            .replace("`", "")
            .replaceAll("(?m)^\\s*[-*+]\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /**
     * 判断当前流式 token 是否出现句子终止标点。
     *
     * 由 {@link #chatStreamSentences} 在每个 token 到达时调用；返回 true 后，调用方根据已累计文本
     * 截取尚未发送给 TTS 的新句子。
     *
     * @param token LLM 返回的 token
     * @return 是否包含终止标点
     */
    private boolean hasTerminalSince(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (TERMINAL_PUNCTUATION.indexOf(token.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提示词上下文记录（系统提示词 + 用户提示词）
     */
    private record PromptContext(String systemPrompt, String userPrompt) {}
}
