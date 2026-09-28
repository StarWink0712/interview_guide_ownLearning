/**
 * Prompt 注入净化工具
 *
 * 仅用于 4 个严重风险的直接拼接点（裸拼接，无模板包裹）。
 * 模板插值点有 Layer 2 的系统提示词保护，不需要额外净化。
 *
 * 联动关系：
 * - 依赖 LlmProviderProperties 读取 app.ai.advisors.promptSanitizerEnabled 开关
 * - 被 VoiceInterviewPromptService、DashscopeLlmService、InterviewParseService、
 *   InterviewSkillService、InterviewQuestionService 调用，清洗用户输入文本
 */
package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Prompt 注入净化工具。
 * <p>
 * 仅用于 4 个严重风险的直接拼接点（裸拼接，无模板包裹）。
 * 模板插值点有 Layer 2 的系统提示词保护，不需要额外净化。
 */
@Component
public class PromptSanitizer {

    private static final Logger log = LoggerFactory.getLogger(PromptSanitizer.class);

    private final LlmProviderProperties properties;

    public PromptSanitizer(LlmProviderProperties properties) {
        this.properties = properties;
    }

    // 行首角色标记：只匹配行首，避免误杀 "Experience with system design"
    private static final Pattern ROLE_INJECTION_PATTERN = Pattern.compile(
        "(?im)^\\s*(system|user|assistant|human|ai|model)\\s*[:：].*"
    );

    // 注入短语：精确匹配，不单独匹配 "忽略" 或 "instruction" 等常见词
    private static final Pattern INJECTION_PHRASE_PATTERN = Pattern.compile(
        "(ignore\\s+(previous|above|all|your)\\s*(instructions|prompts|rules))" +
        "|(forget\\s+(everything|all\\s*(previous\\s*)?(instructions|rules|prompts)))" +
        "|(new\\s+instructions?:)" +
        "|忽略之前的指令" +
        "|忘记之前的指令" +
        "|忽略以上所有" +
        "|你不再是" +
        "|你的新角色是",
        Pattern.CASE_INSENSITIVE
    );

    // 分隔符伪造：匹配项目中 .st 模板使用的静态分隔符
    private static final Pattern DELIMITER_INJECTION_PATTERN = Pattern.compile(
        "---(?:简历|文档|问答)内容(?:开始|结束)---"
    );

    // XML 边界标签伪造：防止攻击者构造 <data-boundary...> 来提前关闭包裹
    private static final Pattern BOUNDARY_TAG_PATTERN = Pattern.compile(
        "</?data-boundary[^>]*>", Pattern.CASE_INSENSITIVE
    );

    /**
     * 清洗用户文本，替换危险模式为中性占位符。
     *
     * <p>清洗策略（四层防御）：
     * <ol>
     *   <li><b>角色注入检测</b>：匹配行首的 "System:"、"User:" 等角色标记，
     *       防止攻击者通过换行注入伪装的系统指令</li>
     *   <li><b>注入短语检测</b>：匹配 "ignore previous instructions"、
     *       "忽略之前的指令" 等已知注入短语</li>
     *   <li><b>分隔符伪造检测</b>：匹配 "---简历内容开始---" 等模板分隔符，
     *       防止攻击者伪造数据边界</li>
     *   <li><b>XML 边界标签检测</b>：匹配 &lt;data-boundary...&gt; 等标签，
     *       防止攻击者提前关闭数据包裹标签</li>
     * </ol>
     *
     * <p>四层检测相互独立，每一层检测到问题都会替换对应内容。
     * 但不阻断请求，只是用中性占位符替换危险内容。
     *
     * <p>受 {@code app.ai.advisors.promptSanitizerEnabled} 配置控制。
     * 配置关闭时直接返回原文本，不做任何处理。
     *
     * @param text 用户输入的原始文本
     * @return 净化后的安全文本，不会改变原始字符串的引用
     */
    public String sanitize(String text) {
        // 空值保护：null 或空白字符串直接返回，不做处理
        if (text == null || text.isBlank()) {
            return text;
        }
        // 配置开关检查：功能关闭时跳过所有净化逻辑
        if (!isSanitizerEnabled()) {
            return text;
        }

        boolean injected = false;  // 是否检测到注入尝试（用于日志告警）
        String result = text;

        // 第 1 层：角色注入检测
        // 匹配如 "System: do something"、"user：..." 等行首角色标记
        var roleMatcher = ROLE_INJECTION_PATTERN.matcher(result);
        if (roleMatcher.find()) {
            injected = true;
            result = roleMatcher.replaceAll("[filtered-role-marker]");  // 替换为中性占位符
        }
        // 第 2 层：注入短语检测
        // 匹配 "ignore instructions"、"忽略之前的指令" 等已知注入短语
        var phraseMatcher = INJECTION_PHRASE_PATTERN.matcher(result);
        if (phraseMatcher.find()) {
            injected = true;
            result = phraseMatcher.replaceAll("[filtered]");
        }
        // 第 3 层：分隔符伪造检测
        // 匹配项目中 .st 模板使用的静态分隔符（如 "---简历内容开始---"）
        var delimMatcher = DELIMITER_INJECTION_PATTERN.matcher(result);
        if (delimMatcher.find()) {
            result = delimMatcher.replaceAll("[filtered-delimiter]");
        }
        // 第 4 层：XML 边界标签检测
        // 匹配 <data-boundary...> 等标签，防止提前关闭包裹
        var tagMatcher = BOUNDARY_TAG_PATTERN.matcher(result);
        if (tagMatcher.find()) {
            result = tagMatcher.replaceAll("[filtered-boundary-tag]");
        }

        // 日志审计：记录检测到的注入尝试（不阻断请求，仅留痕）
        if (injected) {
            log.warn("检测到潜在 Prompt 注入尝试，文本长度: {}", text.length());
        }
        return result;
    }

    /**
     * 用不可预测的分隔符包裹用户文本，防止 Prompt 注入攻击者伪造分隔符逃逸。
     *
     * <p>工作原理：
     * <ol>
     *   <li>生成 8 位随机 UUID 片段作为分隔符的唯一标识</li>
     *   <li>拼接标签（label）构成完整分隔符名称</li>
     *   <li>分别在文本前后添加开放标签和闭合标签</li>
     * </ol>
     *
     * <p>格式：{@code <data-boundary-{uuid片段}-{label}> ... </data-boundary-{uuid片段}-{label}>}
     *
     * <p>安全原理：攻击者无法预知运行时的 UUID 片段，
     * 因此无法在用户输入中预先构造匹配的闭合标签来逃逸数据边界。
     * 这是比静态分隔符（如 "---"）更安全的防注入手段。
     *
     * @param label 标签名称（如 "resume"、"user_input"），用于标识数据段用途
     * @param text 需要被包裹的用户数据文本
     * @return 被安全标签包裹的文本
     */
    public String wrapWithDelimiters(String label, String text) {
        // 生成 8 位随机 UUID 片段，确保运行时不可预测
        String id = UUID.randomUUID().toString().substring(0, 8);
        // 构造非对称的开放/闭合标签，LLM 识别为数据边界
        String openTag = "<data-boundary-" + id + "-" + label + ">";
        String closeTag = "</data-boundary-" + id + "-" + label + ">";
        // 将用户文本包裹在安全标签内
        return openTag + "\n" + text + "\n" + closeTag;
    }

    /**
     * 检测注入尝试（仅日志告警，不阻断）。
     */
    public boolean detectInjectionAttempt(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return ROLE_INJECTION_PATTERN.matcher(text).find()
            || INJECTION_PHRASE_PATTERN.matcher(text).find();
    }

    private boolean isSanitizerEnabled() {
        return properties.getAdvisors() == null
            || properties.getAdvisors().isPromptSanitizerEnabled();
    }
}
